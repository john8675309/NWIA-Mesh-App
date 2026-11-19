cmake_minimum_required(VERSION 3.22.1)
project(nwia_crypto LANGUAGES C CXX)
add_library(nwia_crypto SHARED NativeCrypto.cpp
 vendor/ed25519/fe.c vendor/ed25519/ge.c vendor/ed25519/sc.c
 vendor/ed25519/sha512.c vendor/ed25519/keypair.c vendor/ed25519/key_exchange.c vendor/ed25519/sign.c
 vendor/Crypto/Ed25519.cpp vendor/Crypto/Curve25519.cpp vendor/Crypto/BigNumberUtil.cpp
 vendor/Crypto/SHA512.cpp vendor/Crypto/Hash.cpp vendor/Crypto/Crypto.cpp)
target_include_directories(nwia_crypto PRIVATE vendor/ed25519 vendor/Crypto)
target_compile_definitions(nwia_crypto PRIVATE ED25519_NO_SEED=1)
target_compile_features(nwia_crypto PRIVATE cxx_std_17)
target_compile_options(nwia_crypto PRIVATE -fstack-protector-strong -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(nwia_crypto PRIVATE -Wl,-z,relro,-z,now -Wl,--gc-sections)
