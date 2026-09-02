#!/usr/bin/env python3
"""Download the official release, verify its published SHA-256, extract safely."""
import hashlib, json, pathlib, tarfile, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODEL = 'sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25'
SHA256 = '393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96'
URL = f'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/{MODEL}.tar.bz2'
models = ROOT / 'models'
models.mkdir(exist_ok=True)
archive = models / (MODEL + '.tar.bz2')
def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024*1024), b''): digest.update(chunk)
    return digest.hexdigest()
if not archive.exists():
    temporary = archive.with_suffix('.download')
    print('Downloading', URL, flush=True)
    with urllib.request.urlopen(URL, timeout=60) as source, temporary.open('wb') as dest:
        while chunk := source.read(1024*1024): dest.write(chunk)
    temporary.replace(archive)
actual = sha(archive)
if actual != SHA256: raise SystemExit(f'Archive checksum mismatch: {actual}; no extraction performed')
print('Official archive SHA-256 verified:', actual, flush=True)
with tarfile.open(archive) as source:
    for member in source:
        relative = pathlib.PurePosixPath(member.name)
        if relative.is_absolute() or '..' in relative.parts or relative.parts[0] != MODEL:
            raise ValueError(f'Unexpected archive path: {member.name}')
        if member.isdir(): (models / member.name).mkdir(parents=True, exist_ok=True)
        elif member.isfile():
            target = models / member.name
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.extractfile(member) as src, target.open('wb') as dest:
                while chunk := src.read(1024*1024): dest.write(chunk)
        else: raise ValueError(f'Unexpected archive entry type: {member.name}')
folder = models / MODEL
entries = [{'path': str(p.relative_to(folder)), 'bytes': p.stat().st_size, 'sha256': sha(p)}
           for p in sorted(folder.rglob('*')) if p.is_file()]
manifest = {'model': MODEL, 'source': URL, 'archiveBytes': archive.stat().st_size,
            'archiveSha256': SHA256, 'files': entries, 'totalBytes': sum(x['bytes'] for x in entries)}
(models / 'qwen3-manifest.json').write_text(json.dumps(manifest, indent=2))
print(json.dumps(manifest, indent=2), flush=True)
