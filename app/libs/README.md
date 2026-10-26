# JieLi Bluetooth OTA dependency

- SDK: `jl_bt_ota_V1.11.0_11015-release.aar`
- Source: https://github.com/Jieli-Tech/Android-JL_OTA/tree/master/libs
- Downloaded 2026-10-26 from the official Jieli-Tech repository.
- SHA-256: `6f8dec58c53c33dc9b1189d6aa1ecc4a0fe6a43ecf44bb4c79bbee723e0d2550`
- License: Apache-2.0, see `JIELI_OTA_LICENSE.txt`.

The AAR is an upstream build dependency, not a generated application APK.
Its ARM64 native library LOAD segments use 16 KiB alignment. OTA uses a
dedicated BLE RCSP connection; the existing CTRL2/SPP client is not reused.
The vendor SDK performs device authentication, UFW handling and loader
rediscovery. Native ABI and real OTA interoperability still require device
testing. MCU and NTN firmware updates are outside this dependency's scope.
