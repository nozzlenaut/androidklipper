#ifndef _TUSB_CONFIG_H_
#define _TUSB_CONFIG_H_

#ifdef __cplusplus
extern "C" {
#endif

#ifndef CFG_TUSB_OS
#define CFG_TUSB_OS                 OPT_OS_NONE
#endif
#define CFG_TUSB_DEBUG              0

#define CFG_TUH_ENABLED             1
#define CFG_TUD_ENABLED             0

// Two hubs (external powered hub + printer-side hub) plus up to three Klipper MCUs.
#define CFG_TUH_HUB                 2
#define CFG_TUH_DEVICE_MAX          5

// Leave enough CDC slots for the three target MCUs plus one diagnostic extra.
#define CFG_TUH_CDC                 4
#define CFG_TUH_HID                 0
#define CFG_TUH_MSC                 0
#define CFG_TUH_MIDI                0
#define CFG_TUH_VENDOR              0

#define CFG_TUH_ENUMERATION_BUFSIZE 512
#define CFG_TUH_CDC_RX_BUFSIZE      512
#define CFG_TUH_CDC_TX_BUFSIZE      512
#define CFG_TUH_CDC_RX_EPSIZE       64
#define CFG_TUH_CDC_TX_EPSIZE       64

// Klipper native USB does not require us to assert DTR/RTS.
#define CFG_TUH_CDC_LINE_CONTROL_ON_ENUM 0

#ifndef BOARD_TUH_RHPORT
#define BOARD_TUH_RHPORT 0
#endif

#define CFG_TUSB_RHPORT0_MODE       (OPT_MODE_HOST | OPT_MODE_FULL_SPEED)

#ifdef __cplusplus
}
#endif

#endif
