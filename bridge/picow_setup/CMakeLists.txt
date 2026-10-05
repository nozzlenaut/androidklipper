cmake_minimum_required(VERSION 3.13)

set(PICO_BOARD pico_w CACHE STRING "Board type")

if (NOT DEFINED PICO_SDK_PATH)
  if (DEFINED ENV{PICO_SDK_PATH})
    set(PICO_SDK_PATH $ENV{PICO_SDK_PATH})
  else()
    message(FATAL_ERROR "Set PICO_SDK_PATH to a Pico SDK 2.x checkout")
  endif()
endif()

include(${PICO_SDK_PATH}/external/pico_sdk_import.cmake)

project(androidklipper_setup_bench C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

pico_sdk_init()

add_executable(androidklipper_setup_bench
  AP_provisioning.c
  dhcpserver/dhcpserver.c
  dnsserver/dnsserver.c
)

target_include_directories(androidklipper_setup_bench PRIVATE
  ${CMAKE_CURRENT_LIST_DIR}
  ${CMAKE_CURRENT_LIST_DIR}/dhcpserver
  ${CMAKE_CURRENT_LIST_DIR}/dnsserver
  ${PICO_LWIP_CONTRIB_PATH}/apps/httpd
)

target_link_libraries(androidklipper_setup_bench PRIVATE
  pico_cyw43_arch_lwip_threadsafe_background
  pico_lwip_http
  pico_lwip_iperf
  androidklipper_setup_content
  pico_stdlib
  pico_unique_id
  hardware_flash
)

pico_enable_stdio_usb(androidklipper_setup_bench 1)
pico_enable_stdio_uart(androidklipper_setup_bench 0)

pico_add_extra_outputs(androidklipper_setup_bench)

pico_add_library(androidklipper_setup_content NOFLAG)
pico_set_lwip_httpd_content(androidklipper_setup_content INTERFACE
  ${CMAKE_CURRENT_LIST_DIR}/content/index.shtml
  ${CMAKE_CURRENT_LIST_DIR}/content/404.html
  ${CMAKE_CURRENT_LIST_DIR}/content/404.response
)
