#pragma once

// Prototype-only Wi-Fi configuration.
//
// Keep real credentials out of git. Change these locally before building.
// Phase two will provision Wi-Fi from AndroidKlipper instead of compiling it in.
#define AK_WIFI_SSID     "CHANGE_ME"
#define AK_WIFI_PASSWORD "CHANGE_ME"

#define AK_DISCOVERY_PORT 7130
#define AK_MCU_BASE_PORT  7131
#define AK_MAX_MCUS       3

#define AK_KLIPPER_VID 0x1d50
#define AK_KLIPPER_PID 0x614e

#define AK_FIRMWARE_VERSION "0.1.0-poc"
