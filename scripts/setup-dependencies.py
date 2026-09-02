#!/usr/bin/env python3
"""Restore pinned build dependencies without checking large binaries into Git."""
import argparse
import hashlib
import json
import shutil
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def fetch(url, expected, name, cache):
    target = cache / name
    if target.exists() and digest(target) == expected:
        return target
    partial = target.with_suffix(target.suffix + '.partial')
    with urllib.request.urlopen(url, timeout=90) as source, partial.open('wb') as out:
        shutil.copyfileobj(source, out)
    if digest(partial) != expected:
        partial.unlink()
        raise RuntimeError(f'Checksum mismatch: {name}')
    partial.replace(target)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, default=ROOT / '.gradle' / 'dependency-downloads')
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    artifacts = json.loads((ROOT / 'third-party/downloads.json').read_text())
    aar = next(a for a in artifacts if a['file'].endswith('.aar'))
    installed = ROOT / 'app/libs' / aar['file']
    if not installed.exists() or digest(installed) != aar['sha256']:
        archive = fetch(aar['url'], aar['sha256'], aar['file'], args.cache)
        installed.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(archive, installed)
    print(f"Verified {aar['file']}")

    llama = json.loads((ROOT / 'third-party/llama-version.json').read_text())
    revision = llama['commit']
    archive = fetch(f"{llama['repository']}/archive/{revision}.tar.gz",
                    llama['archive_sha256'], f'llama-{revision}.tar.gz', args.cache)
    destination = ROOT / 'third-party/llama.cpp'
    with tempfile.TemporaryDirectory(prefix='llama-restore-', dir=ROOT / 'third-party') as temp:
        extracted = Path(temp)
        with tarfile.open(archive) as tar:
            prefix = 'llama.cpp-' + revision
            for member in tar.getmembers():
                path = Path(member.name)
                if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] != prefix:
                    raise RuntimeError('Unexpected archive path')
                if member.isdir():
                    continue
                if not member.isfile():
                    raise RuntimeError(f'Unsupported archive entry: {member.name}')
                target = extracted.joinpath(*path.parts[1:])
                target.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(member) as source, target.open('wb') as output:
                    shutil.copyfileobj(source, output)
                target.chmod(member.mode & 0o777)
        if destination.exists():
            differences = [str(p.relative_to(extracted)) for p in extracted.rglob('*') if p.is_file()
                           and (not (destination / p.relative_to(extracted)).is_file()
                                or digest(p) != digest(destination / p.relative_to(extracted)))]
            if differences:
                raise RuntimeError('Existing llama.cpp differs from the pinned archive; preserve your edits before replacing it: '
                                   + ', '.join(differences[:5]))
        else:
            shutil.move(str(extracted), str(destination))
    print(f'Verified llama.cpp {revision}')
    print('Build dependencies ready. Qwen ASR/NLU models are deployed separately; see README.md.')


if __name__ == '__main__':
    main()
