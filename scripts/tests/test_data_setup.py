import base64
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
import io
from urllib.error import HTTPError
from unittest.mock import patch

SCRIPTS = Path(__file__).parents[1]
sys.path.insert(0, str(SCRIPTS))
import finish_data_setup as setup
import prepare_school_repository as publisher


class DataSetupTests(unittest.TestCase):
    def setUp(self):
        self.progress_patch=patch.object(setup,'progress')
        self.progress_patch.start()
        self.addCleanup(self.progress_patch.stop)

    def test_auth_and_quota_errors_are_distinct_without_echoing_upstream_message(self):
        for code,replace_key in [('ERROR-290',True),('ERROR-337',False),('INFO-300',True)]:
            with patch.object(setup,'download',return_value={'RESULT':{'CODE':code,'MESSAGE':'opaque-test-key'}}):
                with self.assertRaises(setup.KeyCheckFailure) as failure:
                    setup.check_key('opaque-test-key')
                self.assertEqual(replace_key,failure.exception.replace_key)
                self.assertNotIn('opaque-test-key',str(failure.exception))

    def test_transient_failure_retries_same_key_without_another_paste(self):
        error=setup.KeyCheckFailure('HTTP_500','NEIS HTTP 500')
        with patch.object(setup.getpass,'getpass',return_value='opaque-test-key') as prompt, patch.object(setup,'check_key',side_effect=[error,None]) as check, patch.object(setup,'configure') as configure, patch('builtins.input',return_value=''), patch('sys.stdout',new_callable=io.StringIO):
            setup.secrets()
        prompt.assert_called_once()
        self.assertEqual([('opaque-test-key',),('opaque-test-key',)],[call.args for call in check.call_args_list])
        configure.assert_called_once_with(publisher.REPO,'opaque-test-key')

    def test_key_check_accepts_one_known_school_without_nationwide_count(self):
        response={'schoolInfo':[{'head':[{'list_total_count':1},{'RESULT':{'CODE':'INFO-000'}}]}, {'row':[{'ATPT_OFCDC_SC_CODE':'B10','SD_SCHUL_CODE':'7010057'}]}]}
        with patch.object(setup.getpass,'getpass',side_effect=['opaque-test-key',EOFError]), patch.object(setup,'download',return_value=response) as fetch, patch.object(setup,'configure') as configure, patch('sys.stdout',new_callable=io.StringIO):
            setup.secrets()
        configure.assert_called_once_with(publisher.REPO,'opaque-test-key')
        self.assertIn('ATPT_OFCDC_SC_CODE=B10', fetch.call_args[0][0])
        self.assertIn('SD_SCHUL_CODE=7010057', fetch.call_args[0][0])

    def test_http_500_is_not_reported_as_bad_key_or_echoed(self):
        error=HTTPError('https://open.neis.go.kr/hub/schoolInfo?KEY=opaque-test-key',500,'opaque-test-key',None,None)
        with patch.object(setup.getpass,'getpass',side_effect=['opaque-test-key',EOFError]), patch.object(setup,'download',side_effect=error), patch.object(setup,'configure') as configure, patch('builtins.input',side_effect=EOFError), patch('sys.stdout',new_callable=io.StringIO) as output:
            with self.assertRaises(EOFError):
                setup.secrets()
        configure.assert_not_called()
        self.assertIn('HTTP 500',output.getvalue())
        self.assertNotIn('opaque-test-key',output.getvalue())
        self.assertNotIn('NEIS 인증 확인 실패입니다',output.getvalue())

    def test_approval_uses_ordinary_browser_without_automation(self):
        from types import SimpleNamespace
        url = 'https://dash.cloudflare.com/oauth2/auth?state=test-state'
        with patch.object(setup, 'quiet', return_value=SimpleNamespace(returncode=0)) as run:
            setup.open_approval(url)
        run.assert_called_once_with(['xdg-open', url], timeout=20)

    def test_public_package_contains_only_allowlisted_school_sources(self):
        files = publisher.package()
        self.assertEqual(set(publisher.FILES) | {'.github/workflows/school-directory.yml', 'README.md', '.gitignore'}, set(files))
        self.assertFalse(any(name.startswith(('app/', 'backend/', '.signing/', 'conversation/')) for name in files))
        workflow = files['.github/workflows/school-directory.yml']
        self.assertIn("vars.DIRECTORY_READY == 'true'", workflow)
        self.assertNotIn('SCHOOL_DIRECTORY_EXTERNAL', workflow)
        self.assertIn('secrets.NEIS_API_KEY', workflow)

    def test_unrecognized_remote_readme_is_never_modified(self):
        def api(path, method='GET', **kwargs):
            self.assertEqual('GET', method)
            if path.endswith('/contents/README.md'):
                return {'content':base64.b64encode(b'User-owned repository').decode()}
            return {'private':False, 'permissions':{'admin':True}}
        with patch.object(publisher, 'api', side_effect=api), self.assertRaisesRegex(ValueError, 'marker missing'):
            publisher.publish(publisher.REPO, publisher.package())

    def test_wrong_repository_is_rejected_before_api_call(self):
        with patch.object(publisher, 'api') as api, self.assertRaises(ValueError):
            publisher.publish('Mangom72/hannun-meal', publisher.package())
        api.assert_not_called()

    def test_deploy_url_is_from_expected_worker_only(self):
        self.assertEqual('https://hannun-meal-api.example.workers.dev', setup.worker_url([
            {'type':'deploy', 'worker_name':'hannun-meal-api', 'targets':['hannun-meal-api.example.workers.dev']}
        ]))
        for target in ['https://hannun-meal-api.example.workers.dev.evil.test', 'http://hannun-meal-api.example.workers.dev', 'https://other.example.workers.dev']:
            with self.assertRaises(RuntimeError):
                setup.worker_url([{'type':'deploy','worker_name':'hannun-meal-api','targets':[target]}])
        with self.assertRaises(RuntimeError):
            setup.worker_url([{'type':'deploy','worker_name':'other','targets':['hannun-meal-api.example.workers.dev']}])

    def test_new_endpoints_preserve_previous_local_configuration(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            out = root/'artifacts'
            out.mkdir()
            target = root/'backend.properties'
            target.write_text('previous-valid-settings\n')
            with patch.object(setup, 'ROOT', root), patch.object(setup, 'OUT', out):
                setup.save_endpoints('https://hannun-meal-api.example.workers.dev')
                self.assertEqual('previous-valid-settings\n', next(out.iterdir()).read_text())
                self.assertIn(setup.PAGES, target.read_text())
                self.assertNotIn('NEIS_API_KEY', target.read_text())
                setup.save_endpoints('https://hannun-meal-api.example.workers.dev')
                self.assertEqual(1, len(list(out.iterdir())))

    def test_no_hidden_key_input_without_real_terminal(self):
        with patch.object(setup.sys.stdin, 'isatty', return_value=False), patch.object(setup, 'authorize') as auth:
            with self.assertRaisesRegex(RuntimeError, '터미널'):
                setup.main()
            auth.assert_not_called()
