import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('claude_monitor', Path(__file__).with_name('claude-monitor.py'))
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)


class MonitorTest(unittest.TestCase):
    def test_invocation_settings_cannot_change_billing_after_auth_preflight(self):
        for arguments in (['--settings', 'api.json'], ['--settings=api.json'],
                          ['--setting-sources=project'], ['--bare']):
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                monitor.validate_claude_arguments(arguments)
        monitor.validate_claude_arguments(['--resume', '--model', 'sonnet'])

    def test_launch_environment_does_not_switch_subscription_to_api_or_capture_content(self):
        original = {'ANTHROPIC_API_KEY': 'private', 'ANTHROPIC_AUTH_TOKEN': 'private',
                    'ANTHROPIC_BASE_URL': 'http://old', 'OTEL_LOG_RAW_API_BODIES': '1',
                    'OTEL_EXPORTER_OTLP_HEADERS': 'Authorization=private', 'PATH': '/bin'}
        child = monitor.monitor_environment(original, 't' * 32, 'http://localhost:8080/telemetry/v1/logs')
        self.assertTrue(all(name not in child for name in monitor.AUTH_VARIABLES))
        self.assertEqual('none', child['OTEL_METRICS_EXPORTER'])
        self.assertEqual('0', child['OTEL_LOG_RAW_API_BODIES'])
        self.assertNotIn('OTEL_EXPORTER_OTLP_HEADERS', child)
        self.assertEqual('private', original['ANTHROPIC_API_KEY'])

    def test_remote_plaintext_and_credentials_in_url_are_rejected(self):
        for endpoint in ('http://example.com/telemetry/v1/logs', 'https://secret@example.com/logs',
                         'https://example.com/logs?token=secret', 'file:///tmp/logs'):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                monitor.monitor_environment({}, 't' * 32, endpoint)

    def test_admin_key_cannot_be_mistaken_for_collector_key(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'admin.env'
            path.write_text('GW_ADMIN_TOKEN=' + 'a' * 64 + '\n')
            with self.assertRaises(ValueError):
                monitor.collector_token({}, path)
            path.write_text(path.read_text() + 'GW_TELEMETRY_TOKEN=' + 't' * 64 + '\n')
            self.assertEqual('t' * 64, monitor.collector_token({}, path))

    def test_api_auth_and_logged_out_status_stop_before_any_model_call(self):
        for status in ({'loggedIn': True, 'authMethod': 'api_key', 'apiProvider': 'firstParty', 'subscriptionType': 'max'},
                       {'loggedIn': False, 'authMethod': 'claude.ai', 'subscriptionType': 'max'},
                       {'loggedIn': True, 'authMethod': 'claude.ai', 'apiProvider': 'bedrock', 'subscriptionType': 'max'}):
            status['email'] = 'private@example.com'
            with patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, json.dumps(status), '')) as run:
                with self.assertRaises(ValueError) as caught:
                    monitor.verify_subscription('claude', {})
                self.assertNotIn('private@example.com', str(caught.exception))
                self.assertEqual(['claude', 'auth', 'status', '--json'], run.call_args.args[0])

    def test_subscription_status_is_accepted_without_revealing_identity(self):
        status = {'loggedIn': True, 'authMethod': 'claude.ai', 'apiProvider': 'firstParty', 'subscriptionType': 'max'}
        with patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, json.dumps(status), '')):
            monitor.verify_subscription('claude', {})

    def test_collector_redirect_does_not_forward_ingestion_credentials(self):
        seen = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                seen.append(self.path)
                self.send_response(307)
                self.send_header('Location', '/credential-leak')
                self.end_headers()
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with self.assertRaisesRegex(ValueError, '307'):
                monitor.verify_collector('http://127.0.0.1:%d/logs' % server.server_port, 't' * 32)
            self.assertEqual(['/logs'], seen)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__':
    unittest.main()
