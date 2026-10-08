"""Offline regression checks for log accounting and personal-key startup."""
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parent


class UsageTests(unittest.TestCase):
    def aggregate(self, records):
        result = subprocess.run(
            [sys.executable, str(SCRIPTS / 'usage.py'), '-', '--by', 'key', '--json'],
            input='\n'.join(json.dumps(r) for r in records),
            text=True, capture_output=True, check=True)
        return {row['key']: row for row in json.loads(result.stdout)}

    def test_mixed_content_events_do_not_inflate_legacy_or_typed_access_usage(self):
        rows = self.aggregate([
            {'event': 'gateway.content', 'phase': 'request', 'requestId': 'legacy',
             'keyId': 'key-a', 'text': 'prompt'},
            {'event': 'gateway.content', 'phase': 'response', 'requestId': 'legacy',
             'keyId': 'key-a', 'status': 200, 'text': 'reply'},
            {'requestId': 'legacy', 'keyId': 'key-a', 'status': 200,
             'promptTokens': 2, 'completionTokens': 3, 'costUsd': .1},
            {'event': 'gateway.access', 'requestId': 'typed', 'keyId': 'key-a',
             'status': 200, 'promptTokens': 4, 'completionTokens': 5, 'costUsd': .2},
            {'event': 'gateway.diagnostic', 'requestId': 'legacy', 'keyId': 'key-a',
             'costUsd': 99, 'promptTokens': 999},
        ])
        self.assertEqual(set(rows), {'key-a'})
        row = rows['key-a']
        self.assertEqual(row['requests'], 2)
        self.assertEqual(row['total_tokens'], 14)
        self.assertEqual(row['cost_usd'], .3)
        self.assertEqual(row['priced_requests'], 2)

    def test_groups_by_observed_key_not_client_identity(self):
        rows = self.aggregate([
            {'requestId': '1', 'keyId': 'key-a', 'tenant': 'spoofed', 'costUsd': .1},
            {'requestId': '2', 'keyId': 'key-a', 'costUsd': .2},
            {'requestId': '3', 'keyId': 'key-b', 'costUsd': .4},
        ])
        self.assertEqual(set(rows), {'key-a', 'key-b'})
        self.assertEqual(rows['key-a']['requests'], 2)
        self.assertEqual(rows['key-a']['cost_usd'], .3)
        self.assertEqual(rows['key-b']['cost_usd'], .4)

    def test_historical_logs_and_cache_only_unknown_prices(self):
        records = [{'requestId': str(i), field: 10} for i, field in enumerate([
            'promptTokens', 'completionTokens', 'cacheWriteTokens',
            'cacheWrite1hTokens', 'cacheReadTokens'])]
        records += [{'requestId': 'error', 'status': 401},
                    {'requestId': 'free', 'cacheReadTokens': 20, 'costUsd': 0}]
        row = self.aggregate(records)['(unknown)']
        self.assertEqual(row['unpriced_requests'], 5)
        self.assertEqual(row['total_tokens'], 70)
        self.assertEqual(row['errors'], 1)

    def test_provider_outcome_traffic_filter_and_gzip(self):
        records = [
            {'requestId': '1', 'provider': 'openai', 'traffic': 'live',
             'status': 200, 'path': '/openai/v1/responses', 'usageAvailability': 'missing'},
            {'requestId': '2', 'provider': 'anthropic', 'traffic': 'test', 'costUsd': 5},
            {'requestId': '3', 'provider': 'anthropic', 'traffic': 'demo', 'costUsd': 7},
            {'requestId': '4', 'costUsd': 10},
        ]
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'access.log.gz'
            with gzip.open(archive, 'wt', encoding='utf-8') as stream:
                stream.write('\n'.join(json.dumps(r) for r in records))
            result = subprocess.run(
                [sys.executable, str(SCRIPTS / 'usage.py'), str(archive),
                 '--traffic', 'live', '--by', 'provider,outcome', '--json'],
                text=True, capture_output=True, check=True)
        rows = json.loads(result.stdout)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['provider'], 'openai')
        self.assertEqual(rows[0]['outcome'], 'success')
        self.assertEqual(rows[0]['requests'], 1)
        self.assertEqual(rows[0]['missing_usage_requests'], 1)
        self.assertEqual(rows[0]['priced_requests'], 0)
        self.assertFalse(rows[0]['cost_complete'])

    def test_unknown_cost_table_never_prints_free_generation(self):
        records = [
            {'requestId': '1', 'status': 200, 'path': '/v1/messages'},
            {'requestId': '2', 'status': 200, 'path': '/v1/models'},
            {'requestId': '3', 'status': 401, 'path': '/v1/messages'},
        ]
        result = subprocess.run(
            [sys.executable, str(SCRIPTS / 'usage.py'), '-', '--by', 'outcome'],
            input='\n'.join(json.dumps(r) for r in records), text=True,
            capture_output=True, check=True)
        self.assertIn('?', result.stdout)
        self.assertNotIn('0.000000', result.stdout)
        row = self.aggregate(records)['(unknown)']
        self.assertEqual(row['missing_usage_requests'], 1)
        self.assertFalse(row['cost_complete'])

    def test_explicit_usage_availability_includes_cancelled_generation(self):
        rows = self.aggregate([
            {'requestId': '1', 'outcome': 'cancelled', 'usageAvailability': 'missing'},
            {'requestId': '2', 'status': 200, 'usageAvailability': 'not_applicable',
             'generation': False, 'promptTokens': 100},
            {'requestId': '3', 'status': 200, 'usageAvailability': 'reported',
             'promptTokens': 0, 'completionTokens': 0, 'costUsd': 0},
        ])
        row = rows['(unknown)']
        self.assertEqual(row['missing_usage_requests'], 1)
        self.assertEqual(row['priced_requests'], 1)
        self.assertEqual(row['cancelled'], 1)
        self.assertEqual(row['unpriced_requests'], 0)


    def test_partial_usage_keeps_observed_cost_but_marks_total_incomplete(self):
        records = [
            {'requestId': 'partial', 'keyId': 'key-a', 'status': 200,
             'outcome': 'cancelled', 'usageAvailability': 'partial',
             'promptTokens': 100, 'completionTokens': 5, 'costUsd': .25},
            {'requestId': 'done', 'keyId': 'key-a', 'status': 200,
             'usageAvailability': 'reported', 'promptTokens': 50,
             'completionTokens': 10, 'costUsd': .1},
        ]
        row = self.aggregate(records)['key-a']
        self.assertEqual(row['cost_usd'], .35)
        self.assertEqual(row['total_tokens'], 165)
        self.assertEqual(row['partial_usage_requests'], 1)
        self.assertEqual(row['missing_usage_requests'], 0)
        self.assertFalse(row['cost_complete'])
        result = subprocess.run(
            [sys.executable, str(SCRIPTS / 'usage.py'), '-', '--by', 'key'],
            input='\n'.join(json.dumps(r) for r in records), text=True,
            capture_output=True, check=True)
        self.assertIn('0.350000*', result.stdout)



class LauncherTests(unittest.TestCase):
    def test_rejects_server_owned_key_without_echoing_secret(self):
        for variable in ('GW_UPSTREAM_API_KEY', 'GATEWAY_UPSTREAM_API_KEY'):
            env = dict(os.environ, GW_UPSTREAM_API_KEY='', GATEWAY_UPSTREAM_API_KEY='')
            env[variable] = 'fake-server-secret-for-test'
            result = subprocess.run(['bash', str(SCRIPTS / 'claude.sh')],
                                    env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('unset', result.stderr)
            self.assertNotIn(env[variable], result.stdout + result.stderr)

    def test_launcher_forces_personal_mode_without_loading_home_key(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            shutil.copy(SCRIPTS / 'claude.sh', root / 'scripts/claude.sh')
            (root / 'gradlew').write_text('#!/bin/sh\nexit 0\n')
            (root / 'gradlew').chmod(0o700)
            (root / 'java').write_text('#!/bin/sh\nprintf "ARG:%s\\n" "$@"\n')
            (root / 'java').chmod(0o700)
            (root / '.config/anthropic').mkdir(parents=True)
            (root / '.config/anthropic/env').write_text('exit 92\n')
            env = dict(os.environ, HOME=str(root), PATH=str(root) + ':' + os.environ['PATH'],
                       GW_UPSTREAM_API_KEY='', GATEWAY_UPSTREAM_API_KEY='')
            result = subprocess.run(['bash', str(root / 'scripts/claude.sh')],
                                    env=env, capture_output=True, text=True, check=True)
            self.assertIn('ARG:--gateway.upstream-api-key=\n', result.stdout)
            self.assertIn('ARG:--gateway.upstream-base-url=https://api.anthropic.com/v1', result.stdout)
            self.assertNotIn('/v1/chat/completions', result.stdout)

    def test_gateway_launcher_forwards_profile_and_preserves_personal_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            shutil.copy(SCRIPTS / 'gateway.sh', root / 'scripts/gateway.sh')
            for name, body in {'gradlew': 'exit 0', 'java': 'printf "ARG:%s\\n" "$@"'}.items():
                executable = root / name
                executable.write_text('#!/bin/sh\n' + body + '\n')
                executable.chmod(0o700)
            env = dict(os.environ, PATH=str(root) + ':' + os.environ['PATH'],
                       GW_UPSTREAM_API_KEY='', GATEWAY_UPSTREAM_API_KEY='')
            result = subprocess.run(['bash', str(root / 'scripts/gateway.sh'),
                                     '--spring.profiles.active=prod'],
                                    env=env, capture_output=True, text=True, check=True)
            self.assertIn('ARG:--spring.profiles.active=prod', result.stdout)
            self.assertIn('ARG:--gateway.upstream-api-key=', result.stdout)
            self.assertIn('/anthropic', result.stdout)
            self.assertIn('/openai/v1', result.stdout)
            rejected = subprocess.run(['bash', str(root / 'scripts/gateway.sh'),
                                       '--gateway.upstream-api-key=fake-test-key'],
                                      env=env, capture_output=True, text=True)
            self.assertNotEqual(rejected.returncode, 0)
            self.assertNotIn('fake-test-key', rejected.stdout + rejected.stderr)



if __name__ == '__main__':
    unittest.main()
