"""Bundle reviewed source and the small real-frame regression corpus; exclude SDKs and caches."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib

root=Path(__file__).resolve().parents[1]
root_files=['.gitignore','.gitattributes','build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','README.md','INSTALL.txt','app/build.gradle']
files=[root/name for name in root_files]
for folder in ['app/src','gradle','tools','tests','docs','.github']:
    files.extend(p for p in (root/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
files.extend(p for p in (root/'laptop').rglob('*') if p.is_file() and not any(x in p.parts for x in ['.venv','.studio-deps','__pycache__','data']))
fixtures=['clip2_56.5.png','clip1_81.5.png','clip2_14.0.png','clip2_49.0.png','clip2_55.png','clip2_58.png','clip1_83.png','clip2_60.png','clip2_0.png','video_metadata.json']
files.extend(root/'analysis'/name for name in fixtures)
files.extend(root/'analysis'/'v2'/('field_'+str(t)+'.png') for t in [0,10,25,40,130,170])
v3_fixtures=['farmer_56.00.png','farmer_58.00.png','farmer_64.00.png','farmer_15.00.png','farmer_168.00.png',
             'manual_5.50.png','manual_5.75.png','manual_6.00.png','manual_6.25.png','manual_6.50.png',
             'manual_8.75.png','manual_9.00.png','manual_9.25.png']
files.extend(root/'analysis'/'v3'/name for name in v3_fixtures)
destination=root/'dist'/'Ceiling-Scout-source.zip'
destination.parent.mkdir(exist_ok=True)
with ZipFile(destination,'w',ZIP_DEFLATED,compresslevel=9) as z:
    for file in sorted(set(files)):
        z.write(file,file.relative_to(root).as_posix())
with ZipFile(destination) as z:
    assert z.testzip() is None
    for file in sorted(set(files)):
        assert z.read(file.relative_to(root).as_posix())==file.read_bytes()
digest=hashlib.sha256(destination.read_bytes()).hexdigest()
destination.with_suffix('.zip.sha256').write_text(digest+'  '+destination.name+'\n')
print(f'Verified {len(set(files))} source/fixture files: {destination.name}, {destination.stat().st_size} bytes, SHA256 {digest}')
