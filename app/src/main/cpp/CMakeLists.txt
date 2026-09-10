cmake_minimum_required(VERSION 3.22.1)
project(androidklipper LANGUAGES C CXX)

add_library(androidklipper_pty SHARED pty_bridge.cpp)
target_compile_features(androidklipper_pty PRIVATE cxx_std_17)

set(KLIPPER_CHELPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/klipper/klippy/chelper")

if(EXISTS "${KLIPPER_CHELPER_DIR}/pyhelper.c")
    add_library(klipper_c_helper SHARED
        ${KLIPPER_CHELPER_DIR}/pyhelper.c
        ${KLIPPER_CHELPER_DIR}/serialqueue.c
        ${KLIPPER_CHELPER_DIR}/stepcompress.c
        ${KLIPPER_CHELPER_DIR}/steppersync.c
        ${KLIPPER_CHELPER_DIR}/itersolve.c
        ${KLIPPER_CHELPER_DIR}/trapq.c
        ${KLIPPER_CHELPER_DIR}/pollreactor.c
        ${KLIPPER_CHELPER_DIR}/msgblock.c
        ${KLIPPER_CHELPER_DIR}/trdispatch.c
        ${KLIPPER_CHELPER_DIR}/kin_cartesian.c
        ${KLIPPER_CHELPER_DIR}/kin_corexy.c
        ${KLIPPER_CHELPER_DIR}/kin_corexz.c
        ${KLIPPER_CHELPER_DIR}/kin_delta.c
        ${KLIPPER_CHELPER_DIR}/kin_deltesian.c
        ${KLIPPER_CHELPER_DIR}/kin_polar.c
        ${KLIPPER_CHELPER_DIR}/kin_rotary_delta.c
        ${KLIPPER_CHELPER_DIR}/kin_winch.c
        ${KLIPPER_CHELPER_DIR}/kin_extruder.c
        ${KLIPPER_CHELPER_DIR}/kin_shaper.c
        ${KLIPPER_CHELPER_DIR}/kin_idex.c
        ${KLIPPER_CHELPER_DIR}/kin_generic.c
    )
    target_include_directories(klipper_c_helper PRIVATE ${KLIPPER_CHELPER_DIR})
    target_compile_options(klipper_c_helper PRIVATE -O2 -fPIC)
    # Klipper's helper uses libm functions. pthread/dl APIs are provided by
    # Android's bionic libc, but math is kept explicit so NDK link behavior is
    # deterministic across ARMv7 and ARM64.
    target_link_libraries(klipper_c_helper PRIVATE m)
    set_target_properties(klipper_c_helper PROPERTIES OUTPUT_NAME "klipper_c_helper")
else()
    message(FATAL_ERROR "Pinned Klipper source missing. Run scripts/vendor-klipper.sh before building.")
endif()
