#!/usr/bin/env python3
"""Download the pinned official Q8 model; verifies bytes before using or deploying it."""
import argparse,hashlib,json,subprocess,urllib.request
from pathlib import Path
MODEL='Qwen3-1.7B-Q8_0.gguf'
SHA='061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a'
REV='90862c4b9d2787eaed51d12237eafdfe7c5f6077'
URL=f'https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/{REV}/{MODEL}'
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for block in iter(lambda:f.read(8*1024*1024),b''):h.update(block)
 return h.hexdigest()
p=argparse.ArgumentParser();p.add_argument('--directory',type=Path,required=True);p.add_argument('--serial');a=p.parse_args()
a.directory.mkdir(parents=True,exist_ok=True);target=a.directory/MODEL
if not target.exists() or digest(target)!=SHA:
 part=target.with_suffix('.partial')
 with urllib.request.urlopen(URL,timeout=90) as source,part.open('wb') as out:
  while chunk:=source.read(8*1024*1024):out.write(chunk)
 if digest(part)!=SHA:raise SystemExit('Model checksum mismatch')
 part.replace(target)
print(json.dumps({'model':MODEL,'revision':REV,'sha256':SHA,'bytes':target.stat().st_size}))
if a.serial:
 adb=['adb','-s',a.serial]
 subprocess.run(adb+['shell','run-as','dev.local.physicalmemory','mkdir','-p','files/nlu_models'],check=True)
 # Write as the app UID. Do not use adb-created external scoped storage on Xiaomi.
 with target.open('rb') as source:
  subprocess.run(adb+['shell',f'run-as dev.local.physicalmemory sh -c "cat > files/nlu_models/{MODEL}.partial"'],stdin=source,check=True)
 result=subprocess.check_output(adb+['shell','run-as','dev.local.physicalmemory','sha256sum',f'files/nlu_models/{MODEL}.partial'],text=True)
 if result.split()[0]!=SHA:raise SystemExit('Device checksum mismatch')
 subprocess.run(adb+['shell','run-as','dev.local.physicalmemory','mv',f'files/nlu_models/{MODEL}.partial',f'files/nlu_models/{MODEL}'],check=True)
 print('Verified and deployed as app UID to private storage.')
