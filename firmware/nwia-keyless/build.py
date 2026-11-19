#!/usr/bin/env python3
"""Compile against the existing MeshCore checkout without editing tracked files.
No flashing. Build overlays and outputs live under its ignored .pio directory.
"""
import argparse, pathlib, subprocess, shutil
p=argparse.ArgumentParser();p.add_argument('--meshcore',type=pathlib.Path,required=True);p.add_argument('--pio',default='pio');p.add_argument('--uf2',action='store_true');a=p.parse_args()
root=a.meshcore.resolve();here=pathlib.Path(__file__).resolve().parent
if not (root/'nwia/BridgeCore.h').exists():p.error('NWIA MeshCore v0.2 checkout required')
source=(root/'platformio.ini').read_text()
source+='''\n[env:WioTrackerL1_nwia_keyless]\nextends = WioTrackerL1\nplatform = nordicnrf52@11.0.0\nbuild_src_filter = ${nrf52_base.build_src_filter}\n  +<../variants/wio-tracker-l1>\n  +<helpers/sensors/EnvironmentSensorManager.cpp>\n  +<'''+'../.pio/nwia-keyless-src/'+'''>\nbuild_flags = ${nrf52_base.build_flags}\n  -I variants/wio-tracker-l1\n  -D WIO_TRACKER_L1\n  -D USE_SX1262\n  -D RADIO_CLASS=CustomSX1262\n  -D WRAPPER_CLASS=CustomSX1262Wrapper\n  -D LORA_TX_POWER=22\n  -D SX126X_CURRENT_LIMIT=140\n  -D SX126X_RX_BOOSTED_GAIN=1\n  -D PIN_OLED_RESET=-1\n  -I '''+str(root/'nwia')+'''\n  -D LORA_FREQ=910.525\n  -D LORA_SF=7\nbuild_unflags =\n  ${sensor_base.build_flags}\n  -D LORA_FREQ=869.618\n  -D LORA_SF=8\nlib_deps =\n  SPI\n  Wire\n  https://github.com/oltaco/CustomLFS#0.2.3\n  stevemarple/MicroNMEA @ 2.0.6\n  adafruit/Adafruit SH110X @ 2.1.13\n'''
(root/'.pio').mkdir(exist_ok=True)
shutil.copytree(here/'src',root/'.pio/nwia-keyless-src',dirs_exist_ok=True)
config=root/'.pio/nwia-keyless.ini';config.write_text(source)
subprocess.run([a.pio,'run','-d',str(root),'-c',str(config),'-e','WioTrackerL1_nwia_keyless']+(['-t','create_uf2'] if a.uf2 else []),check=True)
