"""Real CLI, local HTTP collector, and filesystem contract tests (no model calls)."""
import concurrent.futures
import http.server
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

SCRIPT = Path(__file__).with_name('codex-response-monitor.py')
THREAD = '12345678-1234-4234-8234-123456789abc'
TURN = 'abcdef12-1234-4234-8234-123456789abc'
spec = importlib.util.spec_from_file_location('codex_response_monitor', SCRIPT)
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


class AdapterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.home = self.root / 'codex'
        self.home.mkdir(mode=0o700)
        self.spool = self.root / 'spool'
        self.sidecar = self.root / 'adapter.json'
        self.forwarded = self.root / 'forwarded.json'
        self.notifier = self.root / 'notifier.py'
        self.notifier.write_text('import json,sys\nfrom pathlib import Path\nPath(sys.argv[1]).write_text(json.dumps(sys.argv[2:]))\n')
        self.settings = {'version': 1, 'original_notify': [sys.executable, str(self.notifier), str(self.forwarded), 'literal $(touch nope); &'], 'spool_dir': str(self.spool), 'network_timeout_seconds': 0.2, 'forward_timeout_seconds': 0.2}
        self.env = {**os.environ, 'CODEX_HOME': str(self.home)}
        self.env.pop('CODEX_RESPONSE_MONITOR_ACTIVE', None)
        self.requests = []
        self.status = 200
        self.body = b'{}'
        self.redirect = None
        self.delay = 0
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                owner.requests.append((self.path, dict(self.headers), json.loads(body)))
                self.send_response(owner.status)
                if owner.redirect:
                    self.send_header('Location', owner.redirect)
                self.end_headers()
                time.sleep(owner.delay)
                try:
                    self.wfile.write(owner.body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.endpoint = f'http://127.0.0.1:{self.server.server_port}/telemetry/v1/logs'
        self.configure()

    def configure(self, endpoint=None, headers=None):
        config = '[otel.exporter.otlp-http]\nendpoint = ' + json.dumps(endpoint or self.endpoint) + '\nprotocol = "json"\n[otel.exporter.otlp-http.headers]\n'
        for key, value in (headers or {'Authorization': 'Bearer collector-secret'}).items():
            config += json.dumps(key) + ' = ' + json.dumps(value) + '\n'
        (self.home / 'config.toml').write_text(config)
        self.sidecar.write_text(json.dumps(self.settings))
        self.sidecar.chmod(0o600)

    def payload(self, **changes):
        value = {'type': 'agent-turn-complete', 'thread-id': THREAD, 'turn-id': TURN, 'cwd': '/workspace', 'client': 'codex_exec', 'input-messages': ['First question', 'Follow-up 🙂'], 'last-assistant-message': 'Final response <script>literal</script>'}
        value.update(changes)
        return json.dumps(value, ensure_ascii=False, indent=2)

    def run_adapter(self, payload=None, flush=False, env=None):
        argv = [sys.executable, str(SCRIPT), '--config', str(self.sidecar)]
        argv += ['--flush'] if flush else [payload or self.payload()]
        return subprocess.run(argv, env=env or self.env, capture_output=True, text=True, timeout=10)

    def records(self):
        return [json.loads(path.read_text()) for path in sorted(self.spool.glob('*.json'))]

    def assert_forwarded(self, payload):
        self.assertEqual(json.loads(self.forwarded.read_text()), ['literal $(touch nope); &', payload])

    def test_real_notify_shape_emits_joined_prompt_response_and_ids(self):
        raw = self.payload()
        result = self.run_adapter(raw)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_forwarded(raw)
        self.assertEqual(len(self.requests), 1)
        path, headers, batch = self.requests[0]
        self.assertEqual(path, '/telemetry/v1/logs')
        self.assertEqual(headers['Authorization'], 'Bearer collector-secret')
        self.assertEqual(headers['X-Llm-Gateway-Codex-Question-Source'], 'notify')
        record = batch['resourceLogs'][0]['scopeLogs'][0]['logRecords'][0]
        attrs = {v['key']: v['value']['stringValue'] for v in record['attributes']}
        self.assertEqual(attrs['event.name'], 'codex.turn_response')
        self.assertEqual(attrs['conversation.id'], THREAD)
        self.assertEqual(attrs['turn.id'], TURN)
        self.assertEqual(attrs['prompt'], 'First question\n\nFollow-up 🙂')
        self.assertEqual(attrs['response'], 'Final response <script>literal</script>')
        self.assertIn('event.timestamp', attrs)
        self.assertNotIn('model', attrs)
        self.assertEqual(self.records(), [])

    def test_empty_and_null_response_remain_uncollected_empty_string(self):
        for response in ('', None):
            result = self.run_adapter(self.payload(**{'last-assistant-message': response}))
            self.assertEqual(result.returncode, 0)
        self.assertEqual(len(self.requests), 2)
        for _, _, batch in self.requests:
            attrs = batch['resourceLogs'][0]['scopeLogs'][0]['logRecords'][0]['attributes']
            self.assertEqual(next(a['value']['stringValue'] for a in attrs if a['key'] == 'response'), '')

    def test_invalid_payloads_forward_verbatim_without_export(self):
        cases = [{'thread-id': ''}, {'turn-id': 'not-a-uuid'}, {'type': 'other'}, {'input-messages': 'question'}, {'input-messages': [123]}, {'last-assistant-message': {'text': 'bad'}}, {'input-messages': []}]
        for changes in cases:
            raw = self.payload(**changes)
            self.assertEqual(self.run_adapter(raw).returncode, 0)
            self.assert_forwarded(raw)
        raw = '{unparsed private question'
        self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assert_forwarded(raw)
        self.assertEqual(self.requests, [])

    def test_notifier_failure_does_not_prevent_export(self):
        self.settings['original_notify'] = [sys.executable, '-c', 'raise SystemExit(7)']
        self.configure()
        self.assertEqual(self.run_adapter().returncode, 0)
        self.assertEqual(len(self.requests), 1)

    def test_notifier_timeout_is_bounded_and_export_succeeds(self):
        self.settings['original_notify'] = [sys.executable, '-c', 'import time; time.sleep(10)']
        self.configure()
        start = time.monotonic()
        self.assertEqual(self.run_adapter().returncode, 0)
        self.assertLess(time.monotonic() - start, 2)
        self.assertEqual(len(self.requests), 1)

    def test_recursive_notify_command_does_not_recurse_or_double_export(self):
        self.settings['original_notify'] = [sys.executable, str(SCRIPT), '--config', str(self.sidecar)]
        self.configure()
        self.assertEqual(self.run_adapter().returncode, 0)
        self.assertEqual(len(self.requests), 1)

    def test_notifier_literal_adapter_filename_argument_is_preserved(self):
        self.settings['original_notify'].append(str(SCRIPT))
        self.configure()
        raw = self.payload()
        self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assertTrue(self.forwarded.exists())
        self.assertEqual(json.loads(self.forwarded.read_text()), ['literal $(touch nope); &', str(SCRIPT), raw])

    def test_notifier_empty_argument_is_preserved(self):
        self.settings['original_notify'].append('')
        self.configure()
        raw = self.payload()
        self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assertTrue(self.forwarded.exists())
        self.assertEqual(json.loads(self.forwarded.read_text()), ['literal $(touch nope); &', '', raw])

    def test_unreachable_collector_preserves_event_for_later_flush(self):
        self.configure(endpoint='http://127.0.0.1:1/telemetry/v1/logs')
        raw = self.payload()
        self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assert_forwarded(raw)
        self.assertEqual(self.records()[0]['state'], 'pending')
        self.assertEqual(self.requests, [])
        self.configure()
        self.assertEqual(self.run_adapter(flush=True).returncode, 0)
        self.assertEqual(self.records(), [])

    def test_recursion_environment_stops_nested_invocation(self):
        self.assertEqual(self.run_adapter(env={**self.env, 'CODEX_RESPONSE_MONITOR_ACTIVE': '1'}).returncode, 0)
        self.assertEqual(self.requests, [])
        self.assertFalse(self.forwarded.exists())

    def test_remote_http_and_url_credentials_are_rejected_but_notify_survives(self):
        for url in ('http://collector.example/telemetry/v1/logs', 'https://user:password@collector.example/logs', 'file:///tmp/logs', self.endpoint + '?key=secret', self.endpoint + '#secret'):
            self.configure(endpoint=url)
            raw = self.payload()
            self.assertEqual(self.run_adapter(raw).returncode, 0)
            self.assert_forwarded(raw)
        self.assertEqual(self.requests, [])

    def test_offline_queue_is_private_token_free_and_flush_retries_with_current_headers(self):
        self.status = 503
        self.assertEqual(self.run_adapter().returncode, 0)
        self.assertEqual(len(self.records()), 1)
        self.assertEqual(self.spool.stat().st_mode & 0o777, 0o700)
        for path in self.spool.iterdir():
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertNotIn('collector-secret', path.read_text())
        self.status = 200
        self.configure(headers={'Authorization': 'Bearer rotated-secret'})
        self.assertEqual(self.run_adapter(flush=True).returncode, 0)
        self.assertEqual(self.records(), [])
        self.assertEqual(self.requests[-1][1]['Authorization'], 'Bearer rotated-secret')

    def test_partial_success_is_retained_and_not_automatically_retried(self):
        self.body = b'{"partialSuccess":{"rejectedLogRecords":"1","errorMessage":"private server details"}}'
        result = self.run_adapter()
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.records()[0]['state'], 'partial_success')
        self.assertNotIn('private server details', result.stderr + json.dumps(self.records()))
        self.assertEqual(self.run_adapter(flush=True).returncode, 0)
        self.assertEqual(len(self.requests), 1)

    def test_partial_success_zero_rejections_with_warning_is_retained(self):
        self.body = b'{"partialSuccess":{"rejectedLogRecords":"0","errorMessage":"warning"}}'
        self.assertEqual(self.run_adapter().returncode, 0)
        self.assertEqual(self.records()[0]['state'], 'partial_success')

    def test_empty_partial_success_is_a_full_ack(self):
        self.body = b'{"partialSuccess":{}}'
        self.assertEqual(self.run_adapter().returncode, 0)
        self.assertEqual(self.records(), [])

    def test_permanent_error_and_redirect_remain_inspectable_without_retry(self):
        for status in (401, 400, 302):
            self.status = status
            self.redirect = 'http://collector.invalid/private'
            raw = self.payload(**{'turn-id': f'{status:08d}-1234-4234-8234-123456789abc'})
            self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assertEqual(len(self.requests), 3)
        self.assertEqual([r['state'] for r in self.records()], ['permanent_error'] * 3)
        self.run_adapter(flush=True)
        self.assertEqual(len(self.requests), 3)

    def test_malformed_ack_preserves_queue(self):
        for body in (b'', b'not json', b'[]', b'{"partialSuccess":"bad"}'):
            self.body = body
            self.assertEqual(self.run_adapter().returncode, 0)
            self.assertEqual(len(self.records()), 1)
        self.body = b'{}'
        self.run_adapter(flush=True)
        self.assertEqual(self.records(), [])

    def test_capacity_does_not_evict_existing_events_or_break_notify(self):
        self.settings['max_events'] = 1
        self.configure()
        self.status = 503
        self.assertEqual(self.run_adapter().returncode, 0)
        original = self.records()[0]
        raw = self.payload(**{'turn-id': '11111111-1234-4234-8234-123456789abc'})
        self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assert_forwarded(raw)
        self.assertEqual(len(self.records()), 1)
        self.assertEqual(self.records()[0]['payload'], original['payload'])

    def test_full_queue_still_flushes_prior_event_on_next_notification(self):
        self.settings['max_events'] = 1
        self.configure()
        self.status = 503
        self.run_adapter()
        self.status = 200
        raw = self.payload(**{'turn-id': '11111111-1234-4234-8234-123456789abc'})
        self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assert_forwarded(raw)
        self.assertEqual(self.records(), [])
        self.assertEqual(len(self.requests), 3)

    def test_corrupt_record_does_not_block_later_valid_event(self):
        self.status = 503
        self.run_adapter()
        next(self.spool.glob('*.json')).write_text('{broken private record')
        self.status = 200
        raw = self.payload(**{'turn-id': '11111111-1234-4234-8234-123456789abc'})
        self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assertEqual(len(self.requests), 2)
        self.assertEqual(len(list(self.spool.glob('*.json'))), 1)

    def test_slow_collector_cannot_delay_forwarding_beyond_budget(self):
        self.delay = 1.5
        raw = self.payload()
        start = time.monotonic()
        self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assertLess(time.monotonic() - start, 1.2)
        self.assert_forwarded(raw)
        self.assertEqual(len(self.records()), 1)

    def test_header_override_and_no_original_notifier(self):
        self.settings['original_notify'] = []
        self.configure(headers={'Authorization': 'Bearer collector-secret', 'x-llm-gateway-codex-question-source': 'native'})
        self.assertEqual(self.run_adapter().returncode, 0)
        self.assertEqual(self.requests[0][1]['X-Llm-Gateway-Codex-Question-Source'], 'notify')
        self.assertFalse(self.forwarded.exists())

    def test_retryable_http_statuses_preserve_pending_event(self):
        for status in (408, 429, 500, 503):
            self.status = status
            self.assertEqual(self.run_adapter().returncode, 0)
            self.assertEqual(self.records()[0]['state'], 'pending')

    def test_world_readable_sidecar_cannot_execute_notifier(self):
        self.sidecar.chmod(0o644)
        result = self.run_adapter()
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.requests, [])
        self.assertFalse(self.forwarded.exists())
        self.assertNotIn('collector-secret', result.stderr)

    def test_missing_response_is_rejected_and_original_payload_is_preserved(self):
        value = json.loads(self.payload())
        del value['last-assistant-message']
        raw = json.dumps(value)
        self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assert_forwarded(raw)
        self.assertEqual(self.requests, [])

    def test_malformed_partial_success_error_message_is_not_treated_as_partial_ack(self):
        for message in (123, None, True, [], {}):
            with self.subTest(message=message):
                self.body = json.dumps({'partialSuccess': {'rejectedLogRecords': '0', 'errorMessage': message}}).encode()
                self.assertEqual(self.run_adapter().returncode, 0)
                self.assertEqual(self.records()[0]['state'], 'pending')

    def test_invalid_rejection_count_keeps_payload_pending(self):
        for rejected in ('0_0', ' 0', '0 ', '+0', '-0', '٠', '', '-1',
                         '9223372036854775808', 9223372036854775808, 0.0, True, None):
            with self.subTest(rejected=rejected):
                self.body = json.dumps({'partialSuccess': {'rejectedLogRecords': rejected}}).encode()
                raw = self.payload()
                self.assertEqual(self.run_adapter(raw).returncode, 0)
                self.assert_forwarded(raw)
                self.assertEqual(len(self.records()), 1)
                self.assertEqual(self.records()[0]['state'], 'pending')
            for path in self.spool.glob('*.json'):
                path.unlink()
        self.body = b'{}'
        self.run_adapter(flush=True)
        self.assertEqual(self.records(), [])

    def test_valid_int64_rejection_counts_and_zero_ack(self):
        for rejected in ('9223372036854775807', 9223372036854775807):
            with self.subTest(rejected=rejected):
                self.body = json.dumps({'partialSuccess': {'rejectedLogRecords': rejected}}).encode()
                self.run_adapter()
                self.assertEqual(self.records()[0]['state'], 'partial_success')
                next(self.spool.glob('*.json')).unlink()
        for rejected in ('0', 0):
            self.body = json.dumps({'partialSuccess': {'rejectedLogRecords': rejected}}).encode()
            self.run_adapter()
            self.assertEqual(self.records(), [])

    def spool_bytes(self):
        return sum(p.lstat().st_size for p in self.spool.iterdir())

    def measure_write_peak(self):
        peaks = []
        replace = os.replace

        def measured_replace(source, destination):
            peaks.append(self.spool_bytes())
            return replace(source, destination)

        return peaks, mock.patch.object(adapter.os, 'replace', side_effect=measured_replace)

    def test_admission_reserves_atomic_terminal_rewrite_space(self):
        self.settings['max_bytes'] = 2000
        spool = adapter.Spool(self.settings)
        filename, payload = adapter.event(self.payload(**{'last-assistant-message': 'x' * 650}))
        self.assertFalse(spool.enqueue(filename, payload))
        self.assertEqual(self.records(), [])
        self.assertLessEqual(self.spool_bytes(), 2000)

    def test_terminal_rewrites_keep_peak_disk_usage_within_bound(self):
        self.settings['max_bytes'] = 3200
        spool = adapter.Spool(self.settings)
        for state in ('partial_success', 'permanent_error'):
            with self.subTest(state=state):
                filename, payload = adapter.event(self.payload(**{'last-assistant-message': 'x' * 650}))
                peaks, measurement = self.measure_write_peak()
                with measurement:
                    self.assertTrue(spool.enqueue(filename, payload))
                    self.body = b'{"partialSuccess":{"rejectedLogRecords":"1"}}'
                    self.status = 200 if state == 'partial_success' else 400
                    self.assertEqual(spool.flush(self.endpoint, {}, 1, 20), 1)
                self.assertEqual(self.records()[0]['state'], state)
                self.assertEqual(self.records()[0]['payload'], payload)
                self.assertTrue(peaks)
                self.assertLessEqual(max(peaks), 3200)
                next(self.spool.glob('*.json')).unlink()

    def test_stale_temporary_files_prevent_unbounded_terminal_rewrites(self):
        self.settings['max_bytes'] = 3200
        spool = adapter.Spool(self.settings)
        filename, payload = adapter.event(self.payload(**{'last-assistant-message': 'x' * 650}))
        self.assertTrue(spool.enqueue(filename, payload))
        stale = self.spool / '.record-interrupted'
        stale.write_bytes(b'x' * 1000)
        stale.chmod(0o600)
        before = self.spool_bytes()
        self.body = b'{"partialSuccess":{"rejectedLogRecords":"1"}}'
        peaks, measurement = self.measure_write_peak()
        with measurement:
            for _ in range(3):
                spool.flush(self.endpoint, {}, 1, 20)
                self.assertEqual(self.records()[0]['state'], 'pending')
                self.assertEqual(self.records()[0]['payload'], payload)
                self.assertEqual(self.spool_bytes(), before)
        self.assertEqual(peaks, [])
        self.assertEqual(stale.read_bytes(), b'x' * 1000)

    def test_existing_near_capacity_record_stays_pending_without_rewrite_space(self):
        self.settings['max_bytes'] = 2000
        spool = adapter.Spool(self.settings)
        filename, payload = adapter.event(self.payload(**{'last-assistant-message': 'x' * 650}))
        path = self.spool / filename
        path.write_text(json.dumps({'version': 1, 'state': 'pending', 'payload': payload},
                                   ensure_ascii=False, separators=(',', ':')))
        path.chmod(0o600)
        original = path.read_bytes()
        self.body = b'{"partialSuccess":{"rejectedLogRecords":"1"}}'
        peaks, measurement = self.measure_write_peak()
        with measurement:
            spool.flush(self.endpoint, {}, 1, 20)
        self.assertLessEqual(max(peaks, default=self.spool_bytes()), 2000)
        self.assertEqual(path.read_bytes(), original)

    def test_record_read_consumes_flush_network_budget(self):
        spool = adapter.Spool(self.settings)
        filename, payload = adapter.event(self.payload())
        self.assertTrue(spool.enqueue(filename, payload))
        read = adapter.private_read

        def delayed_read(path, *args):
            result = read(path, *args)
            time.sleep(0.08)
            return result

        with mock.patch.object(adapter, 'private_read', side_effect=delayed_read):
            self.assertEqual(spool.flush(self.endpoint, {}, 0.04, 20), 0)
        self.assertEqual(self.requests, [])
        self.assertEqual(self.records()[0]['state'], 'pending')

    def test_invalid_cli_never_echoes_payload(self):
        result = subprocess.run([sys.executable, str(SCRIPT), '--config', str(self.sidecar), self.payload(), 'private-payload-extra'], env=self.env, capture_output=True, text=True)
        self.assertNotIn('private-payload-extra', result.stderr)

    def test_byte_capacity_rejects_large_event(self):
        self.settings['max_bytes'] = 100
        self.configure()
        raw = self.payload()
        self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assert_forwarded(raw)
        self.assertEqual(self.records(), [])
        self.assertEqual(self.requests, [])

    def test_concurrent_notifications_keep_all_events_and_deduplicate_same_turn(self):
        self.status = 503
        payloads = [self.payload(**{'turn-id': f'{i:08d}-1234-4234-8234-123456789abc'}) for i in range(8)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(self.run_adapter, payloads))
        self.assertTrue(all(result.returncode == 0 for result in results))
        self.assertEqual(len(self.records()), 8)
        self.run_adapter(payloads[0])
        self.assertEqual(len(self.records()), 8)

    def test_invalid_collector_config_does_not_break_notifier_or_lose_queue(self):
        self.status = 503
        self.run_adapter()
        (self.home / 'config.toml').write_text('invalid toml = [')
        raw = self.payload(**{'turn-id': '11111111-1234-4234-8234-123456789abc'})
        self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assert_forwarded(raw)
        self.assertEqual(len(self.records()), 2)

    def test_unsafe_spool_symlink_is_rejected_without_touching_target(self):
        target = self.root / 'target'
        target.mkdir(mode=0o700)
        self.spool.symlink_to(target, target_is_directory=True)
        raw = self.payload()
        self.assertEqual(self.run_adapter(raw).returncode, 0)
        self.assert_forwarded(raw)
        self.assertEqual(list(target.iterdir()), [])


if __name__ == '__main__':
    unittest.main()
