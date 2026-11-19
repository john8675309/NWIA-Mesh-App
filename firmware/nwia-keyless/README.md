# NWIA keyless Wio Tracker L1 modem

This is the hardware-tested Android pilot's firmware overlay for the standard
Wio Tracker L1 / L1 Pro (not the 1W or e-ink models). It transports opaque native
MeshCore packets over encrypted/bonded BLE NUS. It has no mesh identity or channel
keys and does not expose stock companion commands.

Build against NWIA MeshCore commit `a153b2a5ee9656e27ff96cc2454b4d13ce8ce784`:

```sh
git clone https://github.com/john8675309/NWIA-MeshCore-MQTT.git meshcore-radio
git -C meshcore-radio checkout a153b2a5ee9656e27ff96cc2454b4d13ce8ce784
python3 firmware/nwia-keyless/build.py --meshcore meshcore-radio --uf2
```

PlatformIO must be installed; use `--pio /path/to/pio` if it is not on PATH.
The script writes only ignored `.pio` overlays/output in that checkout. It does
not flash or erase a device. Output is under `.pio/build/WioTrackerL1_nwia_keyless/`.
Radio settings are 910.525 MHz / 62.5 kHz / SF7 / CR5. Attach the correct antenna
before transmitting and choose settings appropriate to your deployment.

Back up any existing firmware/settings before replacement. Old Meshtastic or
MeshCore settings must be cleared using the matching bootloader/flash layout
before commissioning a device as keyless. Installing this overlay alone does not
prove old stored keys were erased. Do not use an arbitrary full-chip erase on an
nRF52840: preserve its bootloader and matching SoftDevice.

The OLED shows the per-boot BLE pairing PIN. Pair through Android settings, then
select the companion in NWIA controls. A PIN remaining on the OLED after pairing
is expected. USB `status` reports transport state and `keys=none`; USB `pin`
shows the pairing PIN locally. Never paste a PIN or message key into an issue.
