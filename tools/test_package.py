"""Exercise the source packager without accessing signing keys or the Android SDK."""
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location('pilot_package', Path(__file__).with_name('package.py'))
package = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(package)


class PackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / 'project'
        self.root.mkdir()
        self.apk = self.base / 'signed-input.apk'
        self.apk.write_bytes(b'already-signed APK fixture; signing is verified separately')
        self.root_patch = patch.object(package, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def write(self, name, data=b'fixture'):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def invoke(self, output=None):
        args = ['--apk', str(self.apk)]
        if output is not None:
            args += ['--out', str(output)]
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            package.main(args)
        return json.loads(stdout.getvalue())

    def snapshot(self):
        return {p.relative_to(self.base).as_posix(): p.read_bytes()
                for p in self.base.rglob('*') if p.is_file()}

    def assert_rejected_without_writes(self, output, message):
        before = self.snapshot()
        stderr = io.StringIO()
        with redirect_stderr(stderr), redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                package.main(['--apk', str(self.apk), '--out', str(output)])
        self.assertEqual(2, error.exception.code)
        self.assertIn(message, stderr.getvalue())
        self.assertEqual(before, self.snapshot())

    def test_rejects_project_root_without_writes(self):
        self.write('README.md', b'Existing source must remain intact')
        self.assert_rejected_without_writes(self.root, 'project root or its ancestor')

    def test_rejects_ancestor_without_writes(self):
        self.write('README.md')
        self.assert_rejected_without_writes(self.base, 'project root or its ancestor')

    def test_rejects_empty_source_before_creating_output(self):
        self.write('.local/signing/password.txt', b'not source')
        self.write('app/build/cache.bin', b'not source')
        output = self.root / 'new-output'
        self.assert_rejected_without_writes(output, 'No distributable source files')
        self.assertFalse(output.exists())

    def test_archive_is_deterministic_and_manifest_matches(self):
        expected = {'README.md': b'Pilot source\n', 'gradlew': b'#!/bin/sh\n',
                    'engine/src/main/java/Example.java': b'class Example {}\n'}
        for name, content in expected.items():
            self.write(name, content)
        first_report = self.invoke()
        output = self.root / 'dist'
        archive = output / 'CoreFilterPilot-source.zip'
        first_bytes = archive.read_bytes()
        self.assertEqual(first_report, self.invoke())
        self.assertEqual(first_bytes, archive.read_bytes())
        self.assertEqual(len(expected), first_report['sourceEntries'])
        with zipfile.ZipFile(archive) as result:
            self.assertIsNone(result.testzip())
            self.assertEqual({'corefilter-pilot/' + name for name in expected}, set(result.namelist()))
            for name, content in expected.items():
                self.assertEqual(content, result.read('corefilter-pilot/' + name))
            self.assertTrue((result.getinfo('corefilter-pilot/gradlew').external_attr >> 16) & 0o111)
        manifest = json.loads((output / 'artifacts.json').read_text(encoding='utf-8'))
        self.assertEqual(first_report['artifacts'], manifest)
        checksums = []
        for name, record in manifest.items():
            contents = (output / name).read_bytes()
            digest = hashlib.sha256(contents).hexdigest()
            self.assertEqual(len(contents), record['bytes'])
            self.assertEqual(digest, record['sha256'])
            checksums.append(f'{digest}  {name}\n')
        self.assertEqual(''.join(checksums), (output / 'SHA256SUMS.txt').read_text(encoding='utf-8'))
        self.assertEqual(self.apk.read_bytes(), (output / 'CoreFilterPilot-1.0.0-rc1.apk').read_bytes())

    def test_custom_output_and_private_build_files_are_excluded(self):
        self.write('README.md', b'Public source')
        private = ['.git/config', '.gradle/cache.bin', '.local/signing/pass.txt', '.toolchain/sdk/tool',
                   'app/build/output.bin', 'dist/old-artifact.txt', '__pycache__/cached.pyc',
                   '.idea/options.xml', 'local.properties', '.env', '.env.production',
                   'release-password.txt', 'release.jks', 'release.keystore',
                   'release.p12', 'release.pfx', 'release.key', 'build.log', 'old.apk']
        for name in private:
            self.write(name, b'PRIVATE OR GENERATED')
        output = self.root / 'custom release output'
        self.write('custom release output/not-source.txt', b'PREVIOUS OUTPUT')
        self.invoke(output)
        with zipfile.ZipFile(output / 'CoreFilterPilot-source.zip') as result:
            self.assertEqual(['corefilter-pilot/README.md'], result.namelist())
            self.assertEqual(b'Public source', result.read(result.namelist()[0]))


if __name__ == '__main__':
    unittest.main()
