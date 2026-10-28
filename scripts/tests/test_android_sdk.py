import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT=Path(__file__).parents[1]/'ensure_android_sdk.sh'

class AndroidSdkTests(unittest.TestCase):
    def test_installed_runner_packages_do_not_require_sdkmanager_on_path(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            (root/'platforms/android-36').mkdir(parents=True)
            (root/'platforms/android-36/android.jar').touch()
            signer=root/'build-tools/35.0.0/apksigner';signer.parent.mkdir(parents=True)
            signer.write_text('#!/bin/sh\nexit 0\n');signer.chmod(0o755)
            result=subprocess.run(['bash',str(SCRIPT)],env=dict(os.environ,ANDROID_HOME=folder,GITHUB_ENV=str(root/'runner-env')),capture_output=True,text=True)
            self.assertEqual(0,result.returncode)
            self.assertIn('Using installed Android 36',result.stdout)
    def test_missing_packages_use_absolute_installed_tools_not_path(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);manager=root/'cmdline-tools/12.0/bin/sdkmanager'
            manager.parent.mkdir(parents=True);manager.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$SDK_CAPTURE"\n');manager.chmod(0o755)
            capture=root/'args'
            result=subprocess.run(['bash',str(SCRIPT)],env=dict(os.environ,ANDROID_HOME=folder,SDK_CAPTURE=str(capture),GITHUB_ENV=str(root/'runner-env')),capture_output=True,text=True)
            self.assertEqual(0,result.returncode)
            self.assertEqual(['--sdk_root='+folder,'platforms;android-36','build-tools;35.0.0'],capture.read_text().splitlines())
    def test_missing_sdk_configuration_is_explicit(self):
        env=dict(os.environ);env.pop('ANDROID_HOME',None);env.pop('ANDROID_SDK_ROOT',None)
        result=subprocess.run(['bash',str(SCRIPT)],env=env,capture_output=True,text=True)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('Configure ANDROID_HOME',result.stderr)
