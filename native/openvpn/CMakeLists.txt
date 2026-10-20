cmake_minimum_required(VERSION 3.22)
project(MolidoOpenVpn LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
foreach(required MOLIDO_SOURCES MOLIDO_PREFIX MOLIDO_OPENVPN_COMMIT)
    if(NOT DEFINED ${required})
        message(FATAL_ERROR "Missing ${required}")
    endif()
endforeach()
set(core "${MOLIDO_SOURCES}/openvpn")
add_library(molido_lz4 STATIC
    "${MOLIDO_SOURCES}/lz4/lib/lz4.c"
    "${MOLIDO_SOURCES}/lz4/lib/lz4hc.c")
target_include_directories(molido_lz4 PUBLIC "${MOLIDO_SOURCES}/lz4/lib")
add_library(molido_openvpn SHARED bridge.cpp
    "${core}/client/ovpncli.cpp"
    "${core}/openvpn/crypto/data_epoch.cpp")
target_compile_definitions(molido_openvpn PRIVATE
    USE_OPENSSL USE_ASIO ASIO_STANDALONE HAVE_LZ4 FMT_HEADER_ONLY
    MOLIDO_OPENVPN_COMMIT="${MOLIDO_OPENVPN_COMMIT}")
target_include_directories(molido_openvpn PRIVATE
    "${core}" "${MOLIDO_SOURCES}/asio/asio/include"
    "${MOLIDO_SOURCES}/fmt/include" "${MOLIDO_PREFIX}/include")
target_link_libraries(molido_openvpn PRIVATE
    "${MOLIDO_PREFIX}/lib/libssl.a"
    "${MOLIDO_PREFIX}/lib/libcrypto.a" molido_lz4 log dl)
target_compile_options(molido_openvpn PRIVATE -fvisibility=hidden)
target_link_options(molido_openvpn PRIVATE
    "-Wl,--no-undefined" "-Wl,-z,max-page-size=16384"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
