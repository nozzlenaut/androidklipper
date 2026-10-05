#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>

#include "pico/stdlib.h"
#include "pico/cyw43_arch.h"
#include "pico/unique_id.h"

#include "lwip/tcp.h"
#include "lwip/udp.h"
#include "lwip/pbuf.h"

#include "tusb.h"
#include "bridge_config.h"

#define LANGUAGE_ID          0x0409
#define RING_SIZE            8192
#define USB_IO_CHUNK         512
#define NET_IO_CHUNK         1024
#define DISCOVERY_BUF_SIZE   1024
#define INVALID_CDC_INDEX    0xff

typedef struct {
    uint8_t data[RING_SIZE];
    size_t head;
    size_t tail;
    size_t count;
} ring_t;

typedef struct {
    bool usb_mounted;
    bool faulted;
    bool disconnect_requested;
    uint8_t cdc_idx;
    uint8_t daddr;
    uint8_t itf_num;
    char serial[64];
    uint16_t port;

    struct tcp_pcb *listener;
    struct tcp_pcb *client;

    ring_t usb_to_net;
    ring_t net_to_usb;

    uint64_t usb_rx_bytes;
    uint64_t usb_tx_bytes;
    uint64_t net_rx_bytes;
    uint64_t net_tx_bytes;
    uint32_t connects;
    uint32_t disconnects;
    uint32_t overflows;
} mcu_slot_t;

typedef struct {
    bool pending;
    uint8_t cdc_idx;
    uint8_t daddr;
} mount_event_t;

static mcu_slot_t g_slots[AK_MAX_MCUS];
static mount_event_t g_mounts[CFG_TUH_CDC];
static tusb_desc_device_t g_device_desc[CFG_TUH_DEVICE_MAX + 1];
static bool g_device_desc_valid[CFG_TUH_DEVICE_MAX + 1];

static struct udp_pcb *g_discovery_udp;
static char g_bridge_id[PICO_UNIQUE_BOARD_ID_SIZE_BYTES * 2 + 1];

CFG_TUH_MEM_SECTION static uint16_t g_serial_desc[64];

static size_t ring_free(const ring_t *r) {
    return RING_SIZE - r->count;
}

static void ring_clear(ring_t *r) {
    r->head = r->tail = r->count = 0;
}

static bool ring_write(ring_t *r, const uint8_t *src, size_t len) {
    if (len > ring_free(r)) return false;

    size_t first = len;
    const size_t until_end = RING_SIZE - r->head;
    if (first > until_end) first = until_end;

    memcpy(&r->data[r->head], src, first);
    if (len > first) memcpy(&r->data[0], src + first, len - first);

    r->head = (r->head + len) % RING_SIZE;
    r->count += len;
    return true;
}

static size_t ring_peek(const ring_t *r, uint8_t *dst, size_t max_len) {
    size_t len = r->count < max_len ? r->count : max_len;
    size_t first = len;
    const size_t until_end = RING_SIZE - r->tail;
    if (first > until_end) first = until_end;

    memcpy(dst, &r->data[r->tail], first);
    if (len > first) memcpy(dst + first, &r->data[0], len - first);
    return len;
}

static void ring_drop(ring_t *r, size_t len) {
    if (len > r->count) len = r->count;
    r->tail = (r->tail + len) % RING_SIZE;
    r->count -= len;
}

static int slot_index(const mcu_slot_t *slot) {
    return (int)(slot - g_slots);
}

static void request_disconnect(mcu_slot_t *slot, const char *reason) {
    if (!slot->disconnect_requested) {
        printf("MCU[%d] transport disconnect requested: %s\n", slot_index(slot), reason);
    }
    slot->disconnect_requested = true;
}

static void abort_client(mcu_slot_t *slot) {
    if (!slot->client) return;

    struct tcp_pcb *pcb = slot->client;
    slot->client = NULL;

    tcp_arg(pcb, NULL);
    tcp_recv(pcb, NULL);
    tcp_sent(pcb, NULL);
    tcp_poll(pcb, NULL, 0);
    tcp_err(pcb, NULL);
    tcp_abort(pcb);

    slot->disconnects++;
    ring_clear(&slot->usb_to_net);
    ring_clear(&slot->net_to_usb);
}

static err_t client_sent(void *arg, struct tcp_pcb *pcb, u16_t len) {
    (void)pcb;
    (void)len;
    mcu_slot_t *slot = (mcu_slot_t *)arg;
    if (!slot) return ERR_OK;
    return ERR_OK;
}

static err_t client_poll(void *arg, struct tcp_pcb *pcb) {
    (void)pcb;
    mcu_slot_t *slot = (mcu_slot_t *)arg;
    if (!slot) return ERR_OK;
    if (!slot->usb_mounted || slot->faulted) {
        request_disconnect(slot, !slot->usb_mounted ? "USB MCU unavailable" : "transport fault");
    }
    return ERR_OK;
}

static void client_error(void *arg, err_t err) {
    mcu_slot_t *slot = (mcu_slot_t *)arg;
    if (!slot) return;

    printf("MCU[%d] TCP error %d\n", slot_index(slot), (int)err);
    slot->client = NULL;
    slot->disconnects++;
    ring_clear(&slot->usb_to_net);
    ring_clear(&slot->net_to_usb);
}

static err_t client_receive(void *arg, struct tcp_pcb *pcb, struct pbuf *p, err_t err) {
    mcu_slot_t *slot = (mcu_slot_t *)arg;
    if (!slot) {
        if (p) pbuf_free(p);
        return ERR_ARG;
    }

    if (err != ERR_OK) {
        if (p) pbuf_free(p);
        request_disconnect(slot, "TCP receive error");
        return err;
    }

    if (!p) {
        slot->client = NULL;
        slot->disconnects++;
        ring_clear(&slot->usb_to_net);
        ring_clear(&slot->net_to_usb);
        tcp_arg(pcb, NULL);
        tcp_recv(pcb, NULL);
        tcp_sent(pcb, NULL);
        tcp_poll(pcb, NULL, 0);
        tcp_err(pcb, NULL);
        tcp_close(pcb);
        printf("MCU[%d] Android client closed\n", slot_index(slot));
        return ERR_OK;
    }

    if (p->tot_len > ring_free(&slot->net_to_usb)) {
        slot->overflows++;
        slot->faulted = true;
        printf("MCU[%d] NET->USB buffer overflow (%u bytes)\n",
               slot_index(slot), (unsigned)p->tot_len);
        pbuf_free(p);
        tcp_abort(pcb);
        slot->client = NULL;
        return ERR_ABRT;
    }

    uint16_t offset = 0;
    uint8_t temp[NET_IO_CHUNK];
    while (offset < p->tot_len) {
        const uint16_t want =
            (uint16_t)((p->tot_len - offset) > sizeof(temp) ? sizeof(temp) : (p->tot_len - offset));
        const uint16_t got = pbuf_copy_partial(p, temp, want, offset);
        if (!got || !ring_write(&slot->net_to_usb, temp, got)) {
            slot->overflows++;
            slot->faulted = true;
            pbuf_free(p);
            tcp_abort(pcb);
            slot->client = NULL;
            return ERR_ABRT;
        }
        offset += got;
        slot->net_rx_bytes += got;
    }

    tcp_recved(pcb, p->tot_len);
    pbuf_free(p);
    return ERR_OK;
}

static err_t accept_client(void *arg, struct tcp_pcb *client, err_t err) {
    mcu_slot_t *slot = (mcu_slot_t *)arg;
    if (!slot || err != ERR_OK || !client) return ERR_VAL;

    if (slot->client) {
        printf("MCU[%d] replacing previous Android client\n", slot_index(slot));
        abort_client(slot);
    }

    slot->client = client;
    slot->connects++;
    slot->faulted = false;
    slot->disconnect_requested = false;
    ring_clear(&slot->usb_to_net);
    ring_clear(&slot->net_to_usb);

    tcp_arg(client, slot);
    tcp_recv(client, client_receive);
    tcp_sent(client, client_sent);
    tcp_poll(client, client_poll, 2);
    tcp_err(client, client_error);
    tcp_nagle_disable(client);

#if LWIP_TCP_KEEPALIVE
    client->so_options |= SOF_KEEPALIVE;
    client->keep_idle = 5000;
    client->keep_intvl = 2000;
    client->keep_cnt = 3;
#endif

    printf("MCU[%d] Android client connected on TCP %u (%s)\n",
           slot_index(slot), slot->port,
           slot->usb_mounted ? slot->serial : "waiting for MCU");

    if (!slot->usb_mounted) {
        request_disconnect(slot, "client connected before MCU");
    }

    return ERR_OK;
}

static bool start_tcp_listener(mcu_slot_t *slot) {
    struct tcp_pcb *pcb = tcp_new_ip_type(IPADDR_TYPE_V4);
    if (!pcb) return false;

    if (tcp_bind(pcb, IP_ANY_TYPE, slot->port) != ERR_OK) {
        tcp_close(pcb);
        return false;
    }

    pcb = tcp_listen_with_backlog(pcb, 1);
    if (!pcb) return false;

    slot->listener = pcb;
    tcp_arg(pcb, slot);
    tcp_accept(pcb, accept_client);
    return true;
}

static void pump_net_to_usb(mcu_slot_t *slot) {
    if (!slot->usb_mounted || slot->faulted || !slot->net_to_usb.count) return;
    if (!tuh_cdc_mounted(slot->cdc_idx)) return;

    const uint32_t available = tuh_cdc_write_available(slot->cdc_idx);
    if (!available) return;

    uint8_t temp[USB_IO_CHUNK];
    size_t want = slot->net_to_usb.count;
    if (want > available) want = available;
    if (want > sizeof(temp)) want = sizeof(temp);

    const size_t peeked = ring_peek(&slot->net_to_usb, temp, want);
    if (!peeked) return;

    const uint32_t written = tuh_cdc_write(slot->cdc_idx, temp, (uint32_t)peeked);
    if (written) {
        ring_drop(&slot->net_to_usb, written);
        slot->usb_tx_bytes += written;
        tuh_cdc_write_flush(slot->cdc_idx);
    }
}

static void pump_usb_to_net(mcu_slot_t *slot) {
    if (!slot->client || slot->faulted || !slot->usb_to_net.count) return;

    const u16_t snd = tcp_sndbuf(slot->client);
    if (!snd) return;

    uint8_t temp[NET_IO_CHUNK];
    size_t want = slot->usb_to_net.count;
    if (want > snd) want = snd;
    if (want > sizeof(temp)) want = sizeof(temp);

    const size_t peeked = ring_peek(&slot->usb_to_net, temp, want);
    if (!peeked) return;

    const err_t err = tcp_write(slot->client, temp, (u16_t)peeked, TCP_WRITE_FLAG_COPY);
    if (err == ERR_OK) {
        ring_drop(&slot->usb_to_net, peeked);
        slot->net_tx_bytes += peeked;
        tcp_output(slot->client);
    } else if (err != ERR_MEM) {
        request_disconnect(slot, "TCP write failed");
    }
}

static void pump_transport(void) {
    for (int i = 0; i < AK_MAX_MCUS; i++) {
        mcu_slot_t *slot = &g_slots[i];

        if (slot->disconnect_requested) {
            slot->disconnect_requested = false;
            abort_client(slot);
        }

        pump_net_to_usb(slot);
        pump_usb_to_net(slot);
    }
}

static void utf16_descriptor_to_ascii(const uint16_t *desc, char *out, size_t out_len) {
    if (!out_len) return;
    out[0] = '\0';

    const uint8_t byte_len = (uint8_t)(desc[0] & 0xffu);
    if (byte_len < 2) return;

    const size_t chars = (byte_len - 2u) / 2u;
    size_t used = 0;
    for (size_t i = 0; i < chars && used + 1 < out_len; i++) {
        const uint16_t c = desc[i + 1];
        out[used++] = (c >= 0x20 && c <= 0x7e) ? (char)c : '_';
    }
    out[used] = '\0';
}

static bool read_serial(uint8_t daddr, char *serial, size_t serial_len) {
    memset(g_serial_desc, 0, sizeof(g_serial_desc));
    const tusb_xfer_result_t result =
        tuh_descriptor_get_serial_string_sync(
            daddr, LANGUAGE_ID, g_serial_desc, sizeof(g_serial_desc));

    if (result != XFER_RESULT_SUCCESS) return false;
    utf16_descriptor_to_ascii(g_serial_desc, serial, serial_len);
    return serial[0] != '\0';
}

static mcu_slot_t *find_slot_for_serial(const char *serial) {
    for (int i = 0; i < AK_MAX_MCUS; i++) {
        if (g_slots[i].serial[0] && strcmp(g_slots[i].serial, serial) == 0) {
            return &g_slots[i];
        }
    }

    for (int i = 0; i < AK_MAX_MCUS; i++) {
        if (!g_slots[i].serial[0]) return &g_slots[i];
    }

    // If all historical slots are occupied, recycle a currently-unmounted slot.
    for (int i = 0; i < AK_MAX_MCUS; i++) {
        if (!g_slots[i].usb_mounted) return &g_slots[i];
    }

    return NULL;
}

static void process_mount_event(const mount_event_t *event) {
    tuh_itf_info_t info;
    if (!tuh_cdc_itf_get_info(event->cdc_idx, &info)) return;

    const uint8_t daddr = info.daddr;
    tusb_desc_device_t desc;

    if (daddr < (CFG_TUH_DEVICE_MAX + 1) && g_device_desc_valid[daddr]) {
        desc = g_device_desc[daddr];
    } else {
        if (tuh_descriptor_get_device_sync(daddr, &desc, sizeof(desc)) != XFER_RESULT_SUCCESS) {
            printf("CDC[%u] unable to read device descriptor\n", event->cdc_idx);
            return;
        }
    }

    if (desc.idVendor != AK_KLIPPER_VID || desc.idProduct != AK_KLIPPER_PID) {
        printf("Ignoring CDC[%u] %04x:%04x (not Klipper native USB)\n",
               event->cdc_idx, desc.idVendor, desc.idProduct);
        return;
    }

    char serial[64];
    if (!read_serial(daddr, serial, sizeof(serial))) {
        snprintf(serial, sizeof(serial), "unstable-%04x-%04x-%u-%u",
                 desc.idVendor, desc.idProduct, daddr, info.desc.bInterfaceNumber);
        printf("WARNING: Klipper MCU has no readable USB serial; using %s\n", serial);
    }

    mcu_slot_t *slot = find_slot_for_serial(serial);
    if (!slot) {
        printf("No free AndroidKlipper MCU slot for %s\n", serial);
        return;
    }

    if (slot->usb_mounted && slot->cdc_idx != event->cdc_idx) {
        printf("Duplicate MCU serial %s ignored\n", serial);
        return;
    }

    snprintf(slot->serial, sizeof(slot->serial), "%s", serial);
    slot->cdc_idx = event->cdc_idx;
    slot->daddr = daddr;
    slot->itf_num = info.desc.bInterfaceNumber;
    slot->usb_mounted = true;
    slot->faulted = false;
    slot->disconnect_requested = false;
    ring_clear(&slot->usb_to_net);
    ring_clear(&slot->net_to_usb);

    printf("MCU[%d] mounted: %s daddr=%u cdc=%u TCP=%u\n",
           slot_index(slot), slot->serial, slot->daddr, slot->cdc_idx, slot->port);
}

static void process_pending_mounts(void) {
    for (int i = 0; i < CFG_TUH_CDC; i++) {
        if (!g_mounts[i].pending) continue;

        mount_event_t event = g_mounts[i];
        g_mounts[i].pending = false;
        process_mount_event(&event);
    }
}

void tuh_enum_descriptor_device_cb(uint8_t daddr, const tusb_desc_device_t *desc_device) {
    if (daddr <= CFG_TUH_DEVICE_MAX) {
        g_device_desc[daddr] = *desc_device;
        g_device_desc_valid[daddr] = true;
    }
}

void tuh_cdc_mount_cb(uint8_t idx) {
    tuh_itf_info_t info;
    if (!tuh_cdc_itf_get_info(idx, &info)) return;

    for (int i = 0; i < CFG_TUH_CDC; i++) {
        if (!g_mounts[i].pending) {
            g_mounts[i].pending = true;
            g_mounts[i].cdc_idx = idx;
            g_mounts[i].daddr = info.daddr;
            return;
        }
    }

    printf("CDC mount event queue full\n");
}

void tuh_cdc_umount_cb(uint8_t idx) {
    for (int i = 0; i < AK_MAX_MCUS; i++) {
        mcu_slot_t *slot = &g_slots[i];
        if (slot->usb_mounted && slot->cdc_idx == idx) {
            printf("MCU[%d] unmounted: %s\n", i, slot->serial);
            slot->usb_mounted = false;
            slot->cdc_idx = INVALID_CDC_INDEX;
            request_disconnect(slot, "USB MCU unmounted");
            return;
        }
    }
}

void tuh_cdc_rx_cb(uint8_t idx) {
    mcu_slot_t *slot = NULL;
    for (int i = 0; i < AK_MAX_MCUS; i++) {
        if (g_slots[i].usb_mounted && g_slots[i].cdc_idx == idx) {
            slot = &g_slots[i];
            break;
        }
    }
    if (!slot) {
        uint8_t discard[USB_IO_CHUNK];
        while (tuh_cdc_read_available(idx)) {
            tuh_cdc_read(idx, discard, sizeof(discard));
        }
        return;
    }

    uint8_t temp[USB_IO_CHUNK];
    while (tuh_cdc_read_available(idx)) {
        const uint32_t got = tuh_cdc_read(idx, temp, sizeof(temp));
        if (!got) break;

        if (!ring_write(&slot->usb_to_net, temp, got)) {
            slot->overflows++;
            slot->faulted = true;
            request_disconnect(slot, "USB->NET buffer overflow");
            printf("MCU[%d] USB->NET buffer overflow (%u bytes)\n",
                   slot_index(slot), (unsigned)got);
            return;
        }
        slot->usb_rx_bytes += got;
    }
}

void tuh_cdc_tx_complete_cb(uint8_t idx) {
    (void)idx;
}

static size_t append_json(char *buf, size_t cap, size_t used, const char *text) {
    if (used >= cap) return used;
    const size_t left = cap - used;
    const int n = snprintf(buf + used, left, "%s", text);
    if (n < 0) return used;
    const size_t wrote = (size_t)n;
    return used + (wrote < left ? wrote : left - 1);
}

static void send_discovery_reply(const ip_addr_t *addr, u16_t port) {
    char response[DISCOVERY_BUF_SIZE];
    size_t used = 0;

    used += (size_t)snprintf(
        response + used, sizeof(response) - used,
        "{\"type\":\"androidklipper-bridge\",\"protocol\":1,"
        "\"bridge_id\":\"%s\",\"firmware\":\"%s\",\"mcus\":[",
        g_bridge_id, AK_FIRMWARE_VERSION);

    bool first = true;
    int count = 0;

    for (int i = 0; i < AK_MAX_MCUS; i++) {
        mcu_slot_t *slot = &g_slots[i];
        if (!slot->usb_mounted || !slot->serial[0]) continue;

        char entry[160];
        snprintf(entry, sizeof(entry),
                 "%s{\"serial\":\"%s\",\"port\":%u}",
                 first ? "" : ",", slot->serial, slot->port);
        used = append_json(response, sizeof(response), used, entry);
        first = false;
        count++;
    }

    char tail[64];
    snprintf(tail, sizeof(tail), "],\"mcu_count\":%d}", count);
    used = append_json(response, sizeof(response), used, tail);

    struct pbuf *reply = pbuf_alloc(PBUF_TRANSPORT, (u16_t)used, PBUF_RAM);
    if (!reply) return;
    pbuf_take(reply, response, (u16_t)used);
    udp_sendto(g_discovery_udp, reply, addr, port);
    pbuf_free(reply);
}

static void discovery_receive(
    void *arg,
    struct udp_pcb *pcb,
    struct pbuf *p,
    const ip_addr_t *addr,
    u16_t port
) {
    (void)arg;
    (void)pcb;

    if (!p) return;

    char request[32] = {0};
    const u16_t n = pbuf_copy_partial(
        p, request,
        (u16_t)((p->tot_len < sizeof(request) - 1) ? p->tot_len : sizeof(request) - 1),
        0);
    request[n] = '\0';

    if (strncmp(request, "AKDISCOVER/1", 12) == 0) {
        send_discovery_reply(addr, port);
    }

    pbuf_free(p);
}

static bool start_discovery(void) {
    g_discovery_udp = udp_new_ip_type(IPADDR_TYPE_V4);
    if (!g_discovery_udp) return false;

    if (udp_bind(g_discovery_udp, IP_ANY_TYPE, AK_DISCOVERY_PORT) != ERR_OK) {
        udp_remove(g_discovery_udp);
        g_discovery_udp = NULL;
        return false;
    }

    udp_recv(g_discovery_udp, discovery_receive, NULL);
    return true;
}

static bool start_network_services(void) {
    for (int i = 0; i < AK_MAX_MCUS; i++) {
        g_slots[i].port = (uint16_t)(AK_MCU_BASE_PORT + i);
        g_slots[i].cdc_idx = INVALID_CDC_INDEX;

        if (!start_tcp_listener(&g_slots[i])) {
            printf("Unable to listen on TCP %u\n", g_slots[i].port);
            return false;
        }
    }

    if (!start_discovery()) {
        printf("Unable to listen on UDP %u\n", AK_DISCOVERY_PORT);
        return false;
    }

    return true;
}

static bool connect_wifi(void) {
    if (strcmp(AK_WIFI_SSID, "CHANGE_ME") == 0) {
        printf("Set AK_WIFI_SSID / AK_WIFI_PASSWORD in bridge_config.h first.\n");
        return false;
    }

    cyw43_arch_enable_sta_mode();

    printf("Connecting Wi-Fi to %s...\n", AK_WIFI_SSID);
    const int rc = cyw43_arch_wifi_connect_timeout_ms(
        AK_WIFI_SSID,
        AK_WIFI_PASSWORD,
        CYW43_AUTH_WPA2_AES_PSK,
        30000);

    if (rc) {
        printf("Wi-Fi connect failed: %d\n", rc);
        return false;
    }

    printf("Wi-Fi connected. AndroidKlipper discovery UDP=%u, MCU TCP=%u-%u\n",
           AK_DISCOVERY_PORT,
           AK_MCU_BASE_PORT,
           AK_MCU_BASE_PORT + AK_MAX_MCUS - 1);
    return true;
}

static void init_usb_host(void) {
    tusb_rhport_init_t host_init = {
        .role = TUSB_ROLE_HOST,
        .speed = TUSB_SPEED_AUTO,
    };

    if (!tusb_init(BOARD_TUH_RHPORT, &host_init)) {
        printf("TinyUSB host init failed\n");
        while (true) tight_loop_contents();
    }

    printf("TinyUSB host ready on root port %d\n", BOARD_TUH_RHPORT);
}

static void print_stats_periodically(void) {
    static absolute_time_t next;
    if (is_nil_time(next)) next = make_timeout_time_ms(5000);
    if (!time_reached(next)) return;
    next = make_timeout_time_ms(5000);

    for (int i = 0; i < AK_MAX_MCUS; i++) {
        const mcu_slot_t *s = &g_slots[i];
        if (!s->serial[0] && !s->usb_mounted && !s->client) continue;

        printf(
            "MCU[%d] serial=%s usb=%d net=%d "
            "usbRx=%llu usbTx=%llu netRx=%llu netTx=%llu "
            "qU2N=%u qN2U=%u connects=%lu disconnects=%lu overflow=%lu\n",
            i,
            s->serial[0] ? s->serial : "-",
            s->usb_mounted ? 1 : 0,
            s->client ? 1 : 0,
            (unsigned long long)s->usb_rx_bytes,
            (unsigned long long)s->usb_tx_bytes,
            (unsigned long long)s->net_rx_bytes,
            (unsigned long long)s->net_tx_bytes,
            (unsigned)s->usb_to_net.count,
            (unsigned)s->net_to_usb.count,
            (unsigned long)s->connects,
            (unsigned long)s->disconnects,
            (unsigned long)s->overflows);
    }
}

int main(void) {
    stdio_init_all();
    sleep_ms(1000);

    pico_get_unique_board_id_string(g_bridge_id, sizeof(g_bridge_id));

    printf("\nAndroidKlipper Pico 2 W Bridge %s\n", AK_FIRMWARE_VERSION);
    printf("Bridge ID: %s\n", g_bridge_id);

    if (cyw43_arch_init()) {
        printf("CYW43 init failed\n");
        return 1;
    }

    while (!connect_wifi()) {
        printf("Retrying Wi-Fi in 5 seconds...\n");
        sleep_ms(5000);
    }

    cyw43_arch_lwip_begin();
    const bool network_ok = start_network_services();
    cyw43_arch_lwip_end();

    if (!network_ok) {
        printf("Network service startup failed\n");
        return 2;
    }

    init_usb_host();

    while (true) {
        // TinyUSB must be serviced frequently; Klipper traffic is latency-sensitive.
        tuh_task();

        // Descriptor reads use TinyUSB synchronous helpers, so do them outside
        // the TinyUSB callback itself.
        process_pending_mounts();

        // Poll lwIP/CYW43 frequently and then move bytes in both directions.
        cyw43_arch_poll();

        cyw43_arch_lwip_begin();
        pump_transport();
        cyw43_arch_lwip_end();

        print_stats_periodically();
        tight_loop_contents();
    }
}
