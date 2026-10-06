#pragma once

// All-MCU bridge test network.
// The Pico W owns a dedicated 2.4 GHz AP so Android can stay on a normal
// charger while the Pico owns USB host duty for the printer hub.
#define AK_AP_SSID     "AndroidKlipper-Bridge"
#define AK_AP_PASSWORD "androidklipper"

#define AK_DISCOVERY_PORT 7130
#define AK_MCU_BASE_PORT  7131
#define AK_MAX_MCUS       3

#define AK_KLIPPER_VID 0x1d50
#define AK_KLIPPER_PID 0x614e

#define AK_FIRMWARE_VERSION "0.2.3-rssi"
