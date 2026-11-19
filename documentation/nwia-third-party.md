# NWIA native components

The upstream MeshCore Open MIT license remains in the repository root. The
MeshCore-derived protocol/firmware notice is preserved in
`firmware/nwia-keyless/LICENSE`. The Android phone-native cryptography imports
the pilot's unchanged vendor sources under `android/app/src/main/cpp/vendor`:
orlp Ed25519 retains its license file, and rweather Crypto retains its full MIT
copyright/license headers. The ref10 verifier is not linked; native advert
verification uses rweather Ed25519. AES/HMAC use Android JCE with the native
MeshCore format and short MAC limitations.

Paho Java MQTT 1.2.5 uses EPL-2.0 / EDL-1.0:
https://github.com/eclipse-paho/paho.mqtt.java/tree/v1.2.5

The companion build uses NWIA MeshCore commit
`a153b2a5ee9656e27ff96cc2454b4d13ce8ce784`, preserving its vendor lock records and
upstream platform/framework, RadioLib, Crypto, CustomLFS, MicroNMEA and Adafruit
licenses. No Android SDK, private signing key, service-account key, MQTT password
or deployment Firebase configuration is included in source.

Background sync uses AndroidX WorkManager (Apache-2.0). The server dependencies
are installed separately using `server/requirements.txt`; the tested deployment
versions are recorded in `server/requirements.lock`. No server SDK or sender
credential is bundled into the mobile APK.
