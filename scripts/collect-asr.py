#!/usr/bin/env python3
"""Read-only export from one explicitly selected Android device. Source env.sh first."""
import argparse, json, pathlib, subprocess

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--out', required=True, type=pathlib.Path)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=True)
base = ['adb', '-s', a.serial]
def adb(*args):
    r = subprocess.run(base + list(args), capture_output=True, text=True)
    return {'exit_code': r.returncode, 'stdout': r.stdout.strip(), 'stderr': r.stderr.strip()}
properties = {}
for key in ['ro.product.manufacturer', 'ro.product.model', 'ro.build.version.release',
            'ro.build.version.sdk', 'ro.product.cpu.abilist', 'ro.build.fingerprint']:
    properties[key] = adb('shell', 'getprop', key)
probe = {'serial': a.serial, 'properties': properties,
         'ram': adb('shell', 'cat', '/proc/meminfo'),
         'recognition_services': adb('shell', 'cmd', 'package', 'query-services', '--brief', '-a', 'android.speech.RecognitionService'),
         'default_provider': adb('shell', 'settings', 'get', 'secure', 'voice_recognition_service'),
         'app_memory': adb('shell', 'dumpsys', 'meminfo', 'dev.local.physicalmemory'),
         'app_package': adb('shell', 'dumpsys', 'package', 'dev.local.physicalmemory')}
(a.out / 'device-probe.json').write_text(json.dumps(probe, ensure_ascii=False, indent=2))
events = adb('exec-out', 'run-as', 'dev.local.physicalmemory', 'cat', 'files/asr/events.jsonl')
(a.out / 'events-export-status.json').write_text(json.dumps(events if events['exit_code'] else {'exit_code': 0}, ensure_ascii=False))
(a.out / 'events.jsonl').write_text(events['stdout'] + ('\n' if events['stdout'] else ''))
log = adb('logcat', '-d', '-s', 'PhysicalMemoryASR:I', 'AndroidRuntime:E')
(a.out / 'asr-logcat.txt').write_text(log['stdout'])
print(json.dumps({'serial': a.serial, 'out': str(a.out.resolve()), 'events_exported': events['exit_code'] == 0}, ensure_ascii=False))
