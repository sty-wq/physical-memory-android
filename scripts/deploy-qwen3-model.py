#!/usr/bin/env python3
"""Deploy and verify official model files on one explicitly selected debug device."""
import argparse, json, pathlib, shlex, subprocess, tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--storage', choices=['internal', 'external'], default='internal')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
manifest = json.loads((root/'models/qwen3-manifest.json').read_text())
model = manifest['model']
source = root/'models'/model
package = 'dev.local.physicalmemory'
target = (f'files/asr_models/{model}' if args.storage == 'internal' else
          f'/sdcard/Android/data/{package}/files/asr_models/{model}')
adb = ['adb', '-s', args.serial]
def run(*parts): return subprocess.run(adb+list(parts), check=True, capture_output=True, text=True).stdout.strip()
def app_run(*parts):
    command = shlex.join(['run-as', package] + list(parts))
    return run('shell', command)
def put_file(local, remote):
    # No world-readable chmod or broad storage permission; the app UID owns and verifies each file.
    command = shlex.join(['run-as', package, 'sh', '-c', 'cat > '+shlex.quote(remote)])
    with open(local, 'rb') as data:
        subprocess.run(adb+['shell', '-T', command], stdin=data, check=True, stdout=subprocess.DEVNULL)
print('Target:',args.serial,run('shell','getprop','ro.product.manufacturer'),run('shell','getprop','ro.product.model'),flush=True)
print('Storage:', args.storage, target, flush=True)
run('shell','am','force-stop',package)
app_run('mkdir','-p',target)
app_run('rm','-f',target+'/.verified')
for entry in manifest['files']:
    relative = pathlib.PurePosixPath(entry['path'])
    if relative.is_absolute() or '..' in relative.parts: raise ValueError('Unsafe manifest path')
    path = target+'/'+entry['path']
    app_run('mkdir','-p',str(pathlib.PurePosixPath(path).parent))
    put_file(source/entry['path'],path)
    actual = app_run('sha256sum',path).split()[0]
    if actual != entry['sha256']: raise RuntimeError('Device checksum mismatch: '+entry['path'])
    print('App UID verified:',entry['path'],flush=True)
with tempfile.NamedTemporaryFile(mode='w',prefix='qwen3-verified-',delete=True) as marker:
    marker.write(manifest['archiveSha256']+'\n');marker.flush()
    put_file(marker.name,target+'/.verified')
run('shell','sync') # adb push verifies guest cache; flush before any emulator shutdown/restart.
print('All deployed files verified. Model bytes:',manifest['totalBytes'],flush=True)
print(app_run('du','-sk',target),flush=True)
