cmake_minimum_required(VERSION 3.20)

project(
    KiwiCrypto
    VERSION 1.0.0
    DESCRIPTION "桃桃音乐 AES-256-GCM-SIV 压缩加密模块"
    LANGUAGES CXX
)

include(CTest)
include(GNUInstallDirs)
include(CMakePackageConfigHelpers)

option(KIWI_CRYPTO_WARNINGS_AS_ERRORS "把编译警告视为错误" OFF)
option(KIWI_CRYPTO_SIDE_CHANNEL_TESTS "运行时序侧信道烟雾测试" ON)

find_package(OpenSSL 3.6 REQUIRED COMPONENTS Crypto)
find_package(zstd 1.5 CONFIG REQUIRED)

add_library(
    kiwi_crypto
    src/kiwi_cipher.cpp
    src/replay_guard.cpp
)
add_library(taotao::kiwi_crypto ALIAS kiwi_crypto)

target_compile_features(kiwi_crypto PUBLIC cxx_std_17)
target_include_directories(
    kiwi_crypto
    PUBLIC
        "$<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>"
        "$<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}>"
)
target_link_libraries(kiwi_crypto PUBLIC OpenSSL::Crypto zstd::libzstd)

set_target_properties(
    kiwi_crypto
    PROPERTIES
        CXX_EXTENSIONS OFF
        WINDOWS_EXPORT_ALL_SYMBOLS ON
)

if(MSVC)
    target_compile_options(kiwi_crypto PRIVATE /W4 /utf-8 /GS /sdl /guard:cf)
    if(BUILD_SHARED_LIBS)
        target_link_options(kiwi_crypto PRIVATE /guard:cf /DYNAMICBASE /NXCOMPAT)
    endif()
    if(KIWI_CRYPTO_WARNINGS_AS_ERRORS)
        target_compile_options(kiwi_crypto PRIVATE /WX)
    endif()
else()
    target_compile_options(
        kiwi_crypto
        PRIVATE
            -Wall
            -Wextra
            -Wpedantic
            -Wconversion
            -Wsign-conversion
            -fstack-protector-strong
            "$<$<CONFIG:Release>:-D_FORTIFY_SOURCE=2>"
    )
    if(MINGW AND BUILD_SHARED_LIBS)
        target_link_options(
            kiwi_crypto
            PRIVATE
                -Wl,--dynamicbase
                -Wl,--nxcompat
                -Wl,--high-entropy-va
        )
    endif()
    if(KIWI_CRYPTO_WARNINGS_AS_ERRORS)
        target_compile_options(kiwi_crypto PRIVATE -Werror)
    endif()
endif()

if(BUILD_TESTING)
    add_executable(kiwi_crypto_test tests/kiwi_cipher_test.cpp)
    target_link_libraries(kiwi_crypto_test PRIVATE taotao::kiwi_crypto)
    set_target_properties(kiwi_crypto_test PROPERTIES CXX_EXTENSIONS OFF)
    if(MSVC)
        target_compile_options(kiwi_crypto_test PRIVATE /GS /guard:cf)
        target_link_options(kiwi_crypto_test PRIVATE /guard:cf /DYNAMICBASE /NXCOMPAT)
    elseif(MINGW)
        target_compile_options(kiwi_crypto_test PRIVATE -fstack-protector-strong)
        target_link_options(
            kiwi_crypto_test
            PRIVATE
                -Wl,--dynamicbase
                -Wl,--nxcompat
                -Wl,--high-entropy-va
        )
    endif()
    add_test(NAME kiwi_crypto_test COMMAND kiwi_crypto_test)

    add_executable(kiwi_crypto_performance_test tests/performance_test.cpp)
    target_link_libraries(
        kiwi_crypto_performance_test
        PRIVATE taotao::kiwi_crypto
    )
    set_target_properties(
        kiwi_crypto_performance_test
        PROPERTIES CXX_EXTENSIONS OFF
    )
    add_test(
        NAME kiwi_crypto_performance_test
        COMMAND kiwi_crypto_performance_test
    )
    set_tests_properties(
        kiwi_crypto_performance_test
        PROPERTIES
            LABELS performance
            TIMEOUT 60
    )
    if(MSVC)
        target_compile_options(
            kiwi_crypto_performance_test
            PRIVATE /GS /guard:cf
        )
        target_link_options(
            kiwi_crypto_performance_test
            PRIVATE /guard:cf /DYNAMICBASE /NXCOMPAT
        )
    elseif(MINGW)
        target_compile_options(
            kiwi_crypto_performance_test
            PRIVATE -fstack-protector-strong
        )
        target_link_options(
            kiwi_crypto_performance_test
            PRIVATE
                -Wl,--dynamicbase
                -Wl,--nxcompat
                -Wl,--high-entropy-va
        )
    endif()

    if(KIWI_CRYPTO_SIDE_CHANNEL_TESTS)
        add_executable(
            kiwi_crypto_side_channel_test
            tests/side_channel_timing_test.cpp
        )
        target_link_libraries(
            kiwi_crypto_side_channel_test
            PRIVATE taotao::kiwi_crypto
        )
        set_target_properties(
            kiwi_crypto_side_channel_test
            PROPERTIES CXX_EXTENSIONS OFF
        )
        if(MSVC)
            target_compile_options(
                kiwi_crypto_side_channel_test
                PRIVATE /GS /guard:cf
            )
            target_link_options(
                kiwi_crypto_side_channel_test
                PRIVATE /guard:cf /DYNAMICBASE /NXCOMPAT
            )
        elseif(MINGW)
            target_compile_options(
                kiwi_crypto_side_channel_test
                PRIVATE -fstack-protector-strong
            )
            target_link_options(
                kiwi_crypto_side_channel_test
                PRIVATE
                    -Wl,--dynamicbase
                    -Wl,--nxcompat
                    -Wl,--high-entropy-va
            )
        endif()
        add_test(
            NAME kiwi_crypto_side_channel_test
            COMMAND kiwi_crypto_side_channel_test
        )
        set_tests_properties(
            kiwi_crypto_side_channel_test
            PROPERTIES
                LABELS security
                TIMEOUT 60
        )
    endif()
endif()

install(
    TARGETS kiwi_crypto
    EXPORT KiwiCryptoTargets
    ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}"
    LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}"
    RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}"
    INCLUDES DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}"
)
install(DIRECTORY include/ DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}")

configure_package_config_file(
    cmake/KiwiCryptoConfig.cmake.in
    "${CMAKE_CURRENT_BINARY_DIR}/KiwiCryptoConfig.cmake"
    INSTALL_DESTINATION "${CMAKE_INSTALL_LIBDIR}/cmake/KiwiCrypto"
)
write_basic_package_version_file(
    "${CMAKE_CURRENT_BINARY_DIR}/KiwiCryptoConfigVersion.cmake"
    VERSION "${PROJECT_VERSION}"
    COMPATIBILITY SameMajorVersion
)
install(
    EXPORT KiwiCryptoTargets
    FILE KiwiCryptoTargets.cmake
    NAMESPACE taotao::
    DESTINATION "${CMAKE_INSTALL_LIBDIR}/cmake/KiwiCrypto"
)
install(
    FILES
        "${CMAKE_CURRENT_BINARY_DIR}/KiwiCryptoConfig.cmake"
        "${CMAKE_CURRENT_BINARY_DIR}/KiwiCryptoConfigVersion.cmake"
    DESTINATION "${CMAKE_INSTALL_LIBDIR}/cmake/KiwiCrypto"
)
