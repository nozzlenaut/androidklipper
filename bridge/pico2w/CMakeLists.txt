cmake_minimum_required(VERSION 3.13)

if (NOT DEFINED PICO_BOARD)
  set(PICO_BOARD pico_w CACHE STRING "Board type")
endif()

if (NOT DEFINED PICO_SDK_PATH)
  if (DEFINED ENV{PICO_SDK_PATH})
    set(PICO_SDK_PATH $ENV{PICO_SDK_PATH})
  else()
    message(FATAL_ERROR "Set PICO_SDK_PATH to a Pico SDK 2.x checkout")
  endif()
endif()

include(${PICO_SDK_PATH}/external/pico_sdk_import.cmake)

project(androidklipper_bridge C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

pico_sdk_init()

option(AK_USB_DIAGNOSTIC "Keep USB in device/stdio mode and skip host init" OFF)

add_executable(androidklipper_bridge
  src/main.c
  wifi_credentials.c
  ../picow_setup/dhcpserver/dhcpserver.c
)

target_include_directories(androidklipper_bridge PRIVATE
  ${CMAKE_CURRENT_LIST_DIR}
  ${CMAKE_CURRENT_LIST_DIR}/../picow_setup/dhcpserver
)

target_link_libraries(androidklipper_bridge
  pico_stdlib
  pico_unique_id
  pico_cyw43_arch_lwip_threadsafe_background
  tinyusb_host
  tinyusb_board
)

if (AK_USB_DIAGNOSTIC)
  target_compile_definitions(androidklipper_bridge PRIVATE AK_USB_DIAGNOSTIC=1)
  pico_enable_stdio_usb(androidklipper_bridge 1)
  pico_enable_stdio_uart(androidklipper_bridge 0)
else()
  # Production bridge: the only USB controller is used as host.
  pico_enable_stdio_usb(androidklipper_bridge 0)
  pico_enable_stdio_uart(androidklipper_bridge 1)
endif()

pico_add_extra_outputs(androidklipper_bridge)
