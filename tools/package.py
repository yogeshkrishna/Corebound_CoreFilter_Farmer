"""Create a source ZIP and checksum manifest without local credentials or build caches."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent
EXCLUDE = {'.git', '.gradle', '.local', 'build', 'dist', '__pycache__', '.idea'}

def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, required=True, help='Already signed release APK')
    parser.add_argument('--out', type=Path, default=ROOT/'dist')
    args = parser.parse_args(argv)
    if not args.apk.is_file(): parser.error('The signed release APK does not exist')
    root = ROOT.resolve()
    output = args.out.resolve()
    if output == root or output in root.parents:
        parser.error('The output directory must not be the project root or its ancestor')
    source = output/'CoreFilterPilot-source.zip'
    entries = []
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if not path.is_file() or any(p in EXCLUDE for p in relative.parts): continue
        if path.name == 'local.properties' or path.name.startswith('.env') or path.name.endswith('password.txt') or path.suffix.lower() in {'.jks', '.keystore', '.p12', '.pfx', '.key', '.log', '.apk'}: continue
        if output in path.resolve().parents: continue
        entries.append((path, relative))
    if not entries:
        parser.error('No distributable source files were found')
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path, relative in entries:
            info = zipfile.ZipInfo(str(Path('corefilter-pilot')/relative).replace('\\','/'), (2026,1,1,0,0,0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o755 if relative.name == 'gradlew' else 0o644) << 16
            archive.writestr(info, path.read_bytes())
    with zipfile.ZipFile(source) as archive:
        if archive.testzip() is not None: raise RuntimeError('Source archive failed its CRC check')
        for path, relative in entries:
            name = str(Path('corefilter-pilot')/relative).replace('\\','/')
            if archive.read(name) != path.read_bytes(): raise RuntimeError(f'Archive mismatch: {relative}')
    apk = output/'CoreFilterPilot-1.0.0-rc1.apk'
    if args.apk.resolve() != apk.resolve(): shutil.copyfile(args.apk, apk)
    manifest = {p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in [source,apk]}
    (output/'SHA256SUMS.txt').write_text(''.join(f'{v["sha256"]}  {k}\n' for k,v in manifest.items()),encoding='utf-8')
    (output/'artifacts.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    print(json.dumps({'sourceEntries':len(entries),'artifacts':manifest},indent=2))

if __name__=='__main__': main()
