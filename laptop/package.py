"""Package source only: no private keys, recordings or Python environment."""
import argparse
from pathlib import Path
import zipfile

FILES = ['studio.py', 'mapper.py', 'index.html', 'requirements.txt', 'Start Studio.cmd', 'Start-Studio.ps1', 'README.md']
if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name in FILES:
            archive.write(Path(__file__).parent / name, 'Ceiling-Scout-Studio/' + name)
