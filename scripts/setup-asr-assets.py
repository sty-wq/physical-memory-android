#!/usr/bin/env python3
"""Fetch pinned upstream artifacts, verify SHA-256, and extract only the two runtime model files."""
import hashlib, pathlib, shutil, tarfile, tempfile, urllib.request
root = pathlib.Path(__file__).resolve().parent.parent
artifacts = [
    ('https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar',
     'c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8', 'sherpa-onnx-1.13.7.aar'),
    ('https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01.tar.bz2',
     'b3b309f7ce4a737195fcc6963ea19b0653a7d3401580af5ae0d3e284cbb71f0b', 'model.tar.bz2'),
]
with tempfile.TemporaryDirectory(prefix='physical-memory-asr-') as temp:
    temp = pathlib.Path(temp)
    for url, expected, name in artifacts:
        target = temp / name
        urllib.request.urlretrieve(url, target)
        actual = hashlib.sha256(target.read_bytes()).hexdigest()
        if actual != expected: raise ValueError(f'Checksum mismatch: {name}: {actual}')
    (root / 'app/libs').mkdir(parents=True,exist_ok=True)
    shutil.copy2(temp / artifacts[0][2], root / 'app/libs' / artifacts[0][2])
    assets = root / 'app/src/main/assets/asr'
    assets.mkdir(parents=True, exist_ok=True)
    with tarfile.open(temp / 'model.tar.bz2') as archive:
        for member in archive.getmembers():
            name = member.name.rsplit('/', 1)[-1]
            if member.isfile() and name in {'model.int8.onnx', 'tokens.txt'}:
                (assets / name).write_bytes(archive.extractfile(member).read())
print('Pinned Sherpa AAR and runtime model installed.')
