# Host build of the Clairix LoRa core and its tests:
#   cmake -S lora/core -B build/lora-core && cmake --build build/lora-core && ctest --test-dir build/lora-core
# The boards build the same sources through PlatformIO (library.json, lora/firmware).
cmake_minimum_required(VERSION 3.16)
project(clairix_lora_core C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(clx STATIC
    src/bytes.cpp src/code.cpp src/crypto.cpp src/region.cpp src/net.cpp src/lite.cpp
    src/bundle.cpp src/router.cpp src/link.cpp src/vault.cpp src/handheld.cpp src/bridge.cpp src/nmea.cpp
    src/monocypher/monocypher.c src/monocypher/monocypher-ed25519.c)
target_include_directories(clx PUBLIC include PRIVATE src)
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(clx PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-Wall -Wextra -Werror>)
endif()

enable_testing()
add_executable(clx_tests tests/main.cpp tests/host_random.cpp)
target_link_libraries(clx_tests clx)
target_include_directories(clx_tests PRIVATE src)
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(clx_tests PRIVATE -Wall -Wextra -Werror)
endif()
add_test(NAME clx_tests COMMAND clx_tests)
