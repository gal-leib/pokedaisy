# PokeDaisy for iPad (proof of concept): a Swift app over mGBA and the C companion the 3DS and Linux ports share.
# See README.md. Two ways in:
#
#   iOS (on a Mac):  cmake -S ios -B build/ios -G Xcode -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphonesimulator
#                    (ios/build.sh does this, builds and runs it in the iPad simulator)
#   anywhere else:   cmake -S ios -B build/ios-host && cmake --build build/ios-host
#                    builds the C side alone and pd_ios_smoke, which runs a ROM headless through it (a check that
#                    needs no Mac).
cmake_minimum_required(VERSION 3.20)
if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    project(PokeDaisy C Swift)
else()
    project(PokeDaisy C)
endif()
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
get_filename_component(REPO "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)

if(NOT EXISTS "${REPO}/third_party/mgba/CMakeLists.txt")
    message(FATAL_ERROR "mGBA submodule missing - run: git submodule update --init third_party/mgba")
endif()

# mGBA as a static library with the GBA and Game Boy cores and nothing else, as the 3DS simulator builds it.
foreach(off BUILD_SHARED BUILD_GL BUILD_GLES2 BUILD_GLES3 BUILD_PERF BUILD_TEST BUILD_SUITE BUILD_LIBRETRO BUILD_PYTHON
            BUILD_QT BUILD_SDL BUILD_CINEMA BUILD_EXAMPLE BUILD_UPDATER
            USE_DEBUGGERS USE_GDB_STUB USE_EDITLINE USE_FFMPEG USE_DISCORD_RPC USE_LZMA USE_LUA USE_ELF USE_SQLITE3
            USE_LIBZIP USE_MINIZIP USE_PNG USE_ZLIB USE_EPOXY USE_FREETYPE BUILD_LTO ENABLE_SCRIPTING)
    set(${off} OFF CACHE BOOL "" FORCE)
endforeach()
foreach(on BUILD_STATIC DISABLE_FRONTENDS DISABLE_DEPS M_CORE_GBA M_CORE_GB SKIP_INSTALL_ALL)
    set(${on} ON CACHE BOOL "" FORCE)
endforeach()
set(SKIP_LIBRARY OFF CACHE BOOL "" FORCE)
add_subdirectory("${REPO}/third_party/mgba" mgba EXCLUDE_FROM_ALL)

# The companion (3ds/core), the host logic every screen-of-its-own host shares (3ds/host), the app's own C it builds on
# (EXPAND, the calls into the game, cheats, FREE FLIGHT, the Game Boy's sound) and the Linux port's shared pieces (HUD,
# QUICK MENU, FLY, the field items): the same list as 3ds/desktop/sim, without armGBA.
file(GLOB CORE_SOURCES "${REPO}/3ds/core/*.c")
file(GLOB HOST_SOURCES "${REPO}/3ds/host/*.c")
set(APP_C "${REPO}/app/src/main/cpp")
add_library(pokedaisy_native STATIC
    native/pd_ios.c native/pd_armgba_none.c ${HOST_SOURCES} ${CORE_SOURCES}
    "${APP_C}/pk_host.c" "${APP_C}/pk_call.c" "${APP_C}/pk_cheats.c" "${APP_C}/pk_gb_field.c" "${APP_C}/pk_gb_audio.c"
    "${APP_C}/pk_wide.c" "${APP_C}/pk_wide_gb.c" "${APP_C}/pk_flight.c"
    "${REPO}/linux/fly.c" "${REPO}/linux/flash.c" "${REPO}/linux/flight.c" "${REPO}/linux/tm_teach.c"
    "${REPO}/linux/field_items.c" "${REPO}/linux/gb_field.c" "${REPO}/linux/wild.c" "${REPO}/linux/hud.c"
    "${REPO}/linux/quick_menu.c")
target_include_directories(pokedaisy_native PRIVATE "${REPO}/third_party/mgba/include" "${REPO}/third_party/mgba/src"
    "${CMAKE_CURRENT_BINARY_DIR}/mgba/include" "${REPO}/3ds/core" "${REPO}/linux" "${APP_C}")
target_include_directories(pokedaisy_native PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/native")
target_compile_definitions(pokedaisy_native PRIVATE M_CORE_GBA M_CORE_GB BUILD_STATIC PK_WIDE_CACHE)
set_property(TARGET pokedaisy_native PROPERTY C_STANDARD 11)
target_compile_options(pokedaisy_native PRIVATE -O2 -Wno-unused-parameter)
target_link_libraries(pokedaisy_native PUBLIC mgba)

if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    file(GLOB SWIFT_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/App/*.swift")
    add_executable(PokeDaisy MACOSX_BUNDLE ${SWIFT_SOURCES})
    target_link_libraries(PokeDaisy PRIVATE pokedaisy_native)
    set_target_properties(PokeDaisy PROPERTIES
        MACOSX_BUNDLE_INFO_PLIST "${CMAKE_CURRENT_SOURCE_DIR}/App/Info.plist"
        XCODE_ATTRIBUTE_PRODUCT_BUNDLE_IDENTIFIER "com.pokedaisy.ipad"
        XCODE_ATTRIBUTE_SWIFT_OBJC_BRIDGING_HEADER "${CMAKE_CURRENT_SOURCE_DIR}/App/PokeDaisy-Bridging-Header.h"
        XCODE_ATTRIBUTE_SWIFT_VERSION "5.0"
        XCODE_ATTRIBUTE_IPHONEOS_DEPLOYMENT_TARGET "16.0"
        XCODE_ATTRIBUTE_TARGETED_DEVICE_FAMILY "2"
        XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED "NO"
        XCODE_ATTRIBUTE_CODE_SIGN_IDENTITY ""
        XCODE_ATTRIBUTE_HEADER_SEARCH_PATHS "${CMAKE_CURRENT_SOURCE_DIR}/native"
        XCODE_ATTRIBUTE_ENABLE_BITCODE "NO")
    target_link_libraries(PokeDaisy PRIVATE "-framework UIKit" "-framework Metal" "-framework MetalKit"
        "-framework AVFoundation" "-framework GameController")
else()
    add_executable(pd_ios_smoke smoke/pd_ios_smoke.c)
    target_link_libraries(pd_ios_smoke PRIVATE pokedaisy_native m)
    find_package(Threads)
    if(Threads_FOUND)
        target_link_libraries(pd_ios_smoke PRIVATE Threads::Threads)
    endif()
endif()
