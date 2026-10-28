import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('signing',Path(__file__).parents[1]/'setup_signing.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class SigningSafetyTests(unittest.TestCase):
    def test_public_upload_requires_explicit_permission_and_preserves_key(self):
        for permitted in [False,True]:
            with tempfile.TemporaryDirectory() as folder:
                root=Path(folder);directory=self.pair(root);calls=[]
                original=(directory/'release.p12').read_bytes()
                def response(args,**kwargs):
                    calls.append((args,kwargs));return SimpleNamespace(stdout=b'false')
                argv=['setup_signing.py','--upload','--repo','owner/public']+(['--allow-public'] if permitted else [])
                with patch.object(module,'ROOT',root),patch('sys.argv',argv),patch.object(module,'run',side_effect=response),contextlib.redirect_stdout(io.StringIO()):
                    if permitted:module.main()
                    else:
                        with self.assertRaises(SystemExit):module.main()
                self.assertEqual(original,(directory/'release.p12').read_bytes())
                secrets=[c for c in calls if c[0][:3]==['gh','secret','set']]
                self.assertEqual(4 if permitted else 0,len(secrets))
                self.assertTrue(all('input' in kwargs for args,kwargs in secrets))

    def pair(self,root):
        directory=root/'.signing';directory.mkdir()
        (directory/'release.p12').write_bytes(b'fixture-private-key')
        (directory/'signing.properties').write_text('storeFile=.signing/release.p12\nstorePassword=fixture-password\nkeyAlias=fixture-alias\nkeyPassword=fixture-password\n')
        return directory
    def test_existing_key_is_never_regenerated(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);directory=self.pair(root)
            before=(directory/'release.p12').read_bytes()
            with patch.object(module,'ROOT',root),patch('sys.argv',['setup_signing.py']),patch.object(module,'run',side_effect=AssertionError('must not invoke keytool')),contextlib.redirect_stdout(io.StringIO()):
                module.main()
            self.assertEqual(before,(directory/'release.p12').read_bytes())
    def test_partial_signing_files_are_not_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);directory=root/'.signing';directory.mkdir();(directory/'release.p12').write_bytes(b'preserve-me')
            with patch.object(module,'ROOT',root),patch('sys.argv',['setup_signing.py']),self.assertRaises(SystemExit):module.main()
            self.assertEqual(b'preserve-me',(directory/'release.p12').read_bytes())
    def test_published_repo_cannot_generate_replacement_key(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            def response(args,**kwargs):return SimpleNamespace(stdout=b'1' if args[-1]=='length' else b'true')
            with patch.object(module,'ROOT',root),patch('sys.argv',['setup_signing.py','--upload','--repo','owner/private']),patch.object(module,'run',side_effect=response),self.assertRaises(SystemExit):module.main()
            self.assertFalse((root/'.signing/release.p12').exists())
    def test_secret_values_use_stdin_and_never_console_or_arguments(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.pair(root);calls=[];output=io.StringIO()
            def response(args,**kwargs):calls.append((args,kwargs));return SimpleNamespace(stdout=b'true')
            with patch.object(module,'ROOT',root),patch('sys.argv',['setup_signing.py','--upload','--repo','owner/private']),patch.object(module,'run',side_effect=response),contextlib.redirect_stdout(output):module.main()
            secrets=[entry for entry in calls if entry[0][:3]==['gh','secret','set']]
            self.assertEqual(4,len(secrets))
            self.assertFalse('fixture-password' in output.getvalue())
            for args,kwargs in secrets:
                self.assertIn('input',kwargs)
                self.assertNotIn('fixture-password',' '.join(args))
                self.assertNotIn('fixture-private-key',' '.join(args))
