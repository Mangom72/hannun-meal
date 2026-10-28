import importlib.util
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('bump',Path(__file__).parents[1]/'bump_version.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
notes_spec=importlib.util.spec_from_file_location('manifest',Path(__file__).parents[1]/'create_update_manifest.py')
manifest=importlib.util.module_from_spec(notes_spec);notes_spec.loader.exec_module(manifest)

class ReleaseNotesTests(unittest.TestCase):
    def test_only_current_version_notes_reach_the_app(self):
        text='# 1.2.1\n\nPublic updates.\n\n# 한눈급식 1.1.0\n\nObsolete private token instructions.\n'
        self.assertEqual('Public updates.',manifest.release_notes(text,'1.2.1'))
    def test_missing_version_entry_is_not_silently_replaced_with_old_notes(self):
        with self.assertRaises(ValueError):manifest.release_notes('# 1.1.0\nOld notes.','1.2.1')

class VersionTests(unittest.TestCase):
    def test_increases_code_and_preserves_repository(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'version.properties'
            p.write_text('VERSION_CODE=2\nVERSION_NAME=1.1.0\nUPDATE_REPOSITORY=owner/name\n')
            module.bump(p,'1.1.1')
            self.assertIn('VERSION_CODE=3',p.read_text())
            self.assertIn('UPDATE_REPOSITORY=owner/name',p.read_text())
    def test_rejects_downgrade_and_malformed_versions_without_modification(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'version.properties'
            original='VERSION_CODE=2\nVERSION_NAME=1.1.0\n'
            for version in ['1.1.0','1.0.9','v1.2.0','01.2.0','1.2.0\nmalicious','latest']:
                p.write_text(original)
                with self.assertRaises(ValueError):module.bump(p,version)
                self.assertEqual(original,p.read_text())
    def test_rejects_android_code_overflow(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'version.properties'
            p.write_text('VERSION_CODE=2147483647\nVERSION_NAME=1.1.0\n')
            with self.assertRaises(ValueError):module.bump(p,'1.2.0')
