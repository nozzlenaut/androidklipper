#include "wifi_credentials.h"

#include <stdint.h>
#include <string.h>

#include "hardware/flash.h"
#include "pico/flash.h"
#include "pico/platform.h"

#define AK_MAX_SSID_LEN 32
#define AK_MAX_PASSWORD_LEN 63
#define AK_MAX_CREDENTIALS 20

#ifndef PICO_FLASH_BANK_TOTAL_SIZE
#define PICO_FLASH_BANK_TOTAL_SIZE (FLASH_SECTOR_SIZE * 2u)
#endif

#ifndef PICO_FLASH_BANK_STORAGE_OFFSET
#if PICO_RP2350 && PICO_RP2350_A2_SUPPORTED
#define AK_FLASH_TARGET_OFFSET \
    (PICO_FLASH_SIZE_BYTES - FLASH_SECTOR_SIZE - \
     PICO_FLASH_BANK_TOTAL_SIZE - FLASH_SECTOR_SIZE)
#else
#define AK_FLASH_TARGET_OFFSET \
    (PICO_FLASH_SIZE_BYTES - PICO_FLASH_BANK_TOTAL_SIZE - FLASH_SECTOR_SIZE)
#endif
#endif

static const uint8_t *const ak_flash_credentials =
    (const uint8_t *)(XIP_BASE + AK_FLASH_TARGET_OFFSET);

static size_t copy_field(
    const uint8_t *src,
    size_t src_len,
    size_t *position,
    char *dst,
    size_t dst_len) {
    if (!src || !position || !dst || dst_len == 0 || *position >= src_len) {
        return 0;
    }

    size_t out = 0;
    while (*position < src_len && src[*position] != 0) {
        if (out + 1 < dst_len) {
            dst[out++] = (char)src[*position];
        }
        (*position)++;
    }

    if (*position < src_len && src[*position] == 0) {
        (*position)++;
    }
    dst[out] = '\0';
    return out;
}

bool ak_wifi_credentials_read_last(
    char *ssid, size_t ssid_len,
    char *password, size_t password_len) {
    if (!ssid || !password || ssid_len == 0 || password_len == 0) {
        return false;
    }

    ssid[0] = '\0';
    password[0] = '\0';

    // Erased flash or an uninitialised provisioning sector.
    if (ak_flash_credentials[0] == 0xff && ak_flash_credentials[1] == 0xff) {
        return false;
    }

    uint8_t count = ak_flash_credentials[1];
    if (count == 0 || count > AK_MAX_CREDENTIALS) {
        return false;
    }

    size_t position = 2;
    char current_ssid[AK_MAX_SSID_LEN + 1];
    char current_password[AK_MAX_PASSWORD_LEN + 1];

    for (uint8_t i = 0; i < count; i++) {
        if (position >= FLASH_SECTOR_SIZE) return false;

        current_ssid[0] = '\0';
        current_password[0] = '\0';

        copy_field(
            ak_flash_credentials, FLASH_SECTOR_SIZE, &position,
            current_ssid, sizeof(current_ssid));
        copy_field(
            ak_flash_credentials, FLASH_SECTOR_SIZE, &position,
            current_password, sizeof(current_password));

        if (current_ssid[0] == '\0') {
            continue;
        }

        // Provisioning appends new networks, so the last valid entry is the
        // network the user most recently selected.
        if (i == count - 1) {
            strncpy(ssid, current_ssid, ssid_len - 1);
            ssid[ssid_len - 1] = '\0';
            strncpy(password, current_password, password_len - 1);
            password[password_len - 1] = '\0';
            return true;
        }
    }

    return false;
}
