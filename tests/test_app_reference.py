import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReferenceTests(unittest.TestCase):
    def git(self, directory, *args):
        return subprocess.check_output(['git', '-C', str(directory), '-c', 'user.name=Test',
            '-c', 'user.email=test@example.invalid', *args], text=True).strip()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.project = Path(self.tmp.name) / 'app'
        self.framework = self.project / 'vendor/uni-frame'
        self.framework.mkdir(parents=True)
        self.git(self.project, 'init', '-q')
        self.git(self.framework, 'init', '-q')
        (self.framework / 'version').write_text('1')
        self.git(self.framework, 'add', 'version')
        self.git(self.framework, 'commit', '-qm', 'initial')
        self.pin = self.git(self.framework, 'rev-parse', 'HEAD')
        self.git(self.project, 'update-index', '--add', '--cacheinfo', f'160000,{self.pin},vendor/uni-frame')
        (self.project / 'example').mkdir()
        (self.project / 'example/pubspec.yaml').write_text('name: example\n')
        (self.project / 'uni.project.json').write_text(json.dumps({'projectId': 'example', 'flutterDirectory': 'example'}))
        spec = importlib.util.spec_from_file_location('app_entry', ROOT / 'tools/app.py')
        self.app = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.app)
        self.app.FRAMEWORK = self.framework

    def test_accepts_pinned_framework_and_nested_flutter_app(self):
        result = self.app.inspect(self.project)
        self.assertEqual(result['frameworkCommit'], self.pin)
        self.assertEqual(result['flutterDirectory'], str(self.project / 'example'))

    def test_rejects_unpinned_framework_upgrade(self):
        (self.framework / 'version').write_text('2')
        self.git(self.framework, 'commit', '-qam', 'upgrade')
        with self.assertRaisesRegex(ValueError, 'pinned'):
            self.app.inspect(self.project)

    def test_rejects_dirty_framework_code(self):
        (self.framework / 'version').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'uncommitted'):
            self.app.inspect(self.project)

    def test_rejects_flutter_directory_outside_app(self):
        (self.project / 'uni.project.json').write_text(json.dumps({'flutterDirectory': '..'}))
        with self.assertRaisesRegex(ValueError, 'outside'):
            self.app.inspect(self.project)


if __name__ == '__main__':
    unittest.main()
