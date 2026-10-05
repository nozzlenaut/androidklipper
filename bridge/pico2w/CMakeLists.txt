cmake_minimum_required(VERSION 3.13)

set(PICO_BOARD pico2_w CACHE STRING "Board type")

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

add_executable(androidklipper_bridge
  src/main.c
)

target_include_directories(androidklipper_bridge PRIVATE
  ${CMAKE_CURRENT_LIST_DIR}
)

target_link_libraries(androidklipper_bridge
  pico_stdlib
  pico_unique_id
  pico_cyw43_arch_lwip_poll
  tinyusb_host
  tinyusb_board
)

# The only USB controller is being used as a host. Diagnostics go to UART.
pico_enable_stdio_usb(androidklipper_bridge 0)
pico_enable_stdio_uart(androidklipper_bridge 1)

pico_add_extra_outputs(androidklipper_bridge)
