#pragma once
#include <stdbool.h>
#include <stddef.h>

// Read the most recently saved SSID/password from the same flash sector used
// by the Pico W setup/provisioning firmware.
bool ak_wifi_credentials_read_last(
    char *ssid, size_t ssid_len,
    char *password, size_t password_len);
