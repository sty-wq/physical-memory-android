#!/usr/bin/env python3
"""Export one explicitly selected debug device's local ASR telemetry and opt-in WAVs."""
import argparse, json, pathlib, re, subprocess
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args()
a.out.mkdir(parents=True,exist_ok=True);audio=a.out/'audio';audio.mkdir(exist_ok=True)
adb=['adb','-s',a.serial]
def read(*args):return subprocess.check_output(adb+list(args))
metadata={'serial':a.serial}
for key in ['ro.product.manufacturer','ro.product.model','ro.product.cpu.abi','ro.build.version.release','ro.build.version.sdk']:
    metadata[key]=read('shell','getprop',key).decode().strip()
(a.out/'device.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2))
(a.out/'events.jsonl').write_bytes(read('exec-out','run-as','dev.local.physicalmemory','cat','files/asr/events.jsonl'))
listing=subprocess.run(adb+['shell','run-as','dev.local.physicalmemory','ls','files/asr_debug'],capture_output=True,text=True)
names=[n for n in listing.stdout.splitlines() if re.fullmatch(r'[A-Za-z0-9_-]+\.wav',n)]
for name in names:(audio/name).write_bytes(read('exec-out','run-as','dev.local.physicalmemory','cat','files/asr_debug/'+name))
for name in ['qwen3-native-probe.json', 'qwen3-native-after-release.json', 'qwen3-replay.json', 'qwen3-microphone-probe.json']:
    exists=subprocess.run(adb+['shell','run-as','dev.local.physicalmemory','test','-f','files/'+name],capture_output=True)
    if exists.returncode == 0:
        data=read('exec-out','run-as','dev.local.physicalmemory','cat','files/'+name)
        json.loads(data) # Reject failed reads instead of saving shell error text as benchmark data.
        (a.out/name).write_bytes(data)
print('Device:',metadata['ro.product.model'],'; exported WAVs:',len(names),'; output:',a.out)
