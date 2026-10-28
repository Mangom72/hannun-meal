import importlib.util
from pathlib import Path
import unittest
from types import SimpleNamespace
spec=importlib.util.spec_from_file_location('data_secrets',Path(__file__).parents[1]/'configure_data_secrets.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class DataSecretsTests(unittest.TestCase):
    def test_same_key_to_two_services_only_through_stdin(self):
        calls=[]
        def run(args,**kwargs):calls.append((args,kwargs));return SimpleNamespace(returncode=0)
        module.configure('owner/repo','one-issued-test-key',run)
        self.assertEqual(2,len(calls))
        self.assertTrue(all(c[1]['input']==b'one-issued-test-key' for c in calls))
        self.assertTrue(all('one-issued-test-key' not in str(c[0]) for c in calls))
        self.assertTrue(all(c[1]['capture_output'] for c in calls))
    def test_partial_failure_is_reported_without_tool_output_or_key(self):
        calls=[]
        def run(args,**kwargs):calls.append(args);return SimpleNamespace(returncode=len(calls)-1,stdout=b'secret',stderr=b'secret')
        with self.assertRaisesRegex(RuntimeError,'Actions was updated') as result:module.configure('owner/repo','one-issued-test-key',run)
        self.assertNotIn('one-issued-test-key',str(result.exception))
    def test_invalid_destination_fails_before_transmitting(self):
        with self.assertRaises(ValueError):module.configure('owner/repo/other','key',lambda *a,**k:self.fail('unexpected command'))
