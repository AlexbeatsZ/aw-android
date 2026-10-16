"""Reuse the unchanged arm64 backend from the matching upstream v0.14.3 APK."""
import argparse
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path, help='Official aw-android-arm64-v8a.apk from upstream tag v0.14.3')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
with zipfile.ZipFile(args.apk) as archive:
    for name in ['libaw_server.so', 'libaw_sync.so']:
        data = archive.read('lib/arm64-v8a/' + name)
        if not data.startswith(b'\x7fELF'):
            raise ValueError('Missing ELF backend: ' + name)
        target = root / 'mobile/src/main/jniLibs/arm64-v8a' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        print('Prepared', target.relative_to(root))
