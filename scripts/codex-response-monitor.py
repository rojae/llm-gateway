#!/usr/bin/env python3
"""Forward Codex notify unchanged and spool completed turns for Gateway OTLP.

Requires Python 3.11+. --config points to an owned 0600 JSON sidecar:
{"version":1,"original_notify":["/path/to/notifier","literal argument"],
 "spool_dir":"/absolute/private/spool"}. Optional limits: max_events=100,
max_bytes=10485760, network_timeout_seconds=2, forward_timeout_seconds=2,
flush_limit=20. Use --flush without a payload to retry pending records.
Collector endpoint/headers come only from active CODEX_HOME/config.toml.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import ipaddress
import json
import math
import os
from pathlib import Path
import queue
import stat
import subprocess
import sys
import tempfile
import threading
import time
import tomllib
import urllib.error
import urllib.parse
import urllib.request
import uuid

MAX_RECORD_BYTES = 4 * 1024 * 1024
ACTIVE = 'CODEX_RESPONSE_MONITOR_ACTIVE'
CAPTURE_HEADER = 'X-LLM-Gateway-Codex-Question-Source'


def private_read(path, limit=MAX_RECORD_BYTES):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as handle:
        info = os.fstat(handle.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > limit):
            raise ValueError('unsafe private file')
        return handle.read(limit + 1)


def settings(path):
    value = json.loads(private_read(path, 65536))
    if not isinstance(value, dict) or value.get('version') != 1:
        raise ValueError('invalid sidecar')
    command = value.get('original_notify')
    if (not isinstance(command, list) or any(not isinstance(v, str) or '\x00' in v for v in command)
            or command and not command[0]):
        raise ValueError('invalid notifier')
    return value


def number(config, name, default, maximum, integer=False):
    value = config.get(name, default)
    kind = int if integer else (int, float)
    if isinstance(value, bool) or not isinstance(value, kind) or not math.isfinite(value) or not 0 < value <= maximum:
        raise ValueError('invalid bound')
    return value


def event(payload):
    value = json.loads(payload)
    if not isinstance(value, dict) or value.get('type') != 'agent-turn-complete':
        return None
    for key in ('thread-id', 'turn-id'):
        identifier = value.get(key)
        if not isinstance(identifier, str) or len(identifier) != 36 or not uuid.UUID(identifier).int:
            raise ValueError('invalid identifier')
    inputs = value.get('input-messages')
    response = value.get('last-assistant-message')
    if not isinstance(inputs, list) or not inputs or any(not isinstance(v, str) for v in inputs):
        raise ValueError('invalid prompt')
    if response is not None and not isinstance(response, str):
        raise ValueError('invalid response')
    if 'last-assistant-message' not in value:
        raise ValueError('missing response')
    now = time.time_ns()
    attributes = {
        'event.name': 'codex.turn_response',
        'conversation.id': value['thread-id'],
        'turn.id': value['turn-id'],
        'prompt': '\n\n'.join(inputs),
        'response': response or '',
        'event.timestamp': datetime.fromtimestamp(now / 1e9, timezone.utc).isoformat().replace('+00:00', 'Z'),
    }
    record = {'timeUnixNano': str(now), 'attributes': [
        {'key': key, 'value': {'stringValue': text}} for key, text in attributes.items()
    ]}
    batch = {'resourceLogs': [{
        'resource': {'attributes': [{'key': 'service.name', 'value': {'stringValue': 'codex'}}]},
        'scopeLogs': [{'scope': {'name': 'llm-gateway.codex-notify', 'version': '1'}, 'logRecords': [record]}],
    }]}
    identity = (value['thread-id'].lower() + '\n' + value['turn-id'].lower()).encode()
    return hashlib.sha256(identity).hexdigest() + '.json', batch


def collector(home):
    with (home / 'config.toml').open('rb') as handle:
        value = tomllib.load(handle)['otel']['exporter']['otlp-http']
    endpoint = value['endpoint']
    if not isinstance(endpoint, str) or any(c.isspace() for c in endpoint):
        raise ValueError('invalid collector')
    url = urllib.parse.urlsplit(endpoint)
    if (url.scheme not in ('http', 'https') or not url.hostname or url.username is not None
            or url.password is not None or url.query or url.fragment or not url.path):
        raise ValueError('invalid collector')
    _ = url.port
    if url.scheme == 'http' and url.hostname != 'localhost':
        try:
            loopback = ipaddress.ip_address(url.hostname).is_loopback
        except ValueError:
            loopback = False
        if not loopback:
            raise ValueError('unsafe collector')
    if value.get('protocol', 'json') != 'json':
        raise ValueError('unsupported collector protocol')
    headers = value.get('headers', {})
    if not isinstance(headers, dict):
        raise ValueError('invalid headers')
    filtered = {}
    for key, text in headers.items():
        if (not isinstance(key, str) or not isinstance(text, str) or not key
                or any(c in key + text for c in '\r\n\x00')
                or key.lower() in ('host', 'content-length', 'transfer-encoding', 'connection')):
            raise ValueError('invalid header')
        if key.lower() not in (CAPTURE_HEADER.lower(), 'content-type'):
            filtered[key] = text
    filtered.update({'Content-Type': 'application/json', CAPTURE_HEADER: 'notify'})
    return endpoint, filtered


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def deliver(endpoint, headers, payload, timeout):
    request = urllib.request.Request(endpoint, data=json.dumps(payload, ensure_ascii=False).encode('utf-8'), headers=headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=timeout) as response:
            if response.status != 200:
                return 'pending'
            raw = response.read(65537)
            if len(raw) > 65536:
                return 'pending'
        ack = json.loads(raw)
        if not isinstance(ack, dict) or set(ack) - {'partialSuccess'}:
            return 'pending'
        partial = ack.get('partialSuccess', {})
        if not isinstance(partial, dict):
            return 'pending'
        rejected = partial.get('rejectedLogRecords', '0')
        if isinstance(rejected, bool) or not isinstance(rejected, (str, int)):
            return 'pending'
        if isinstance(rejected, str) and (not rejected or any(c not in '0123456789' for c in rejected)):
            return 'pending'
        rejected = int(rejected)
        if not 0 <= rejected <= 9223372036854775807 or set(partial) - {'rejectedLogRecords', 'errorMessage'}:
            return 'pending'
        if not isinstance(partial.get('errorMessage', ''), str):
            return 'pending'
        if rejected or partial.get('errorMessage'):
            return 'partial_success'
        return 'acked'
    except urllib.error.HTTPError as error:
        error.close()
        return 'pending' if error.code >= 500 or error.code in (408, 429) else 'permanent_error'
    except (OSError, ValueError, urllib.error.URLError):
        return 'pending'


def bounded_deliver(endpoint, headers, payload, timeout):
    # A daemon plus join bounds the whole exchange, even a slow trickling response.
    result = queue.Queue(maxsize=1)

    def send():
        try:
            result.put(deliver(endpoint, headers, payload, timeout))
        except Exception:
            result.put('pending')

    worker = threading.Thread(target=send, daemon=True)
    worker.start()
    worker.join(timeout)
    return result.get_nowait() if not worker.is_alive() else 'pending'


class Spool:
    def __init__(self, config):
        self.path = Path(config['spool_dir'])
        if not self.path.is_absolute():
            raise ValueError('spool must be absolute')
        try:
            self.path.mkdir(mode=0o700, parents=False)
        except FileExistsError:
            pass
        info = self.path.lstat()
        if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o700):
            raise ValueError('unsafe spool')
        self.max_events = number(config, 'max_events', 100, 1000, integer=True)
        self.max_bytes = number(config, 'max_bytes', 10 * 1024 * 1024, 100 * 1024 * 1024, integer=True)

    @contextmanager
    def lock(self, name, wait=0.5):
        fd = os.open(self.path / name, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
                raise ValueError('unsafe lock')
            deadline = time.monotonic() + wait
            while True:
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError('spool busy') from None
                    time.sleep(0.01)
            yield
        finally:
            os.close(fd)

    def files(self):
        return sorted(self.path.glob('*.json'), key=lambda p: p.lstat().st_mtime_ns)

    def disk_bytes(self):
        return sum(p.lstat().st_size for p in self.path.iterdir() if p.name not in ('.queue.lock', '.flush.lock'))

    def write(self, path, value):
        encoded = json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        if len(encoded) > MAX_RECORD_BYTES:
            raise ValueError('event too large')
        # Called under the queue lock: replacements temporarily retain both copies.
        # Stale files from interrupted writes also consume this capacity.
        if self.disk_bytes() + len(encoded) > self.max_bytes:
            return False
        fd, temporary = tempfile.mkstemp(prefix='.record-', dir=self.path)
        try:
            with os.fdopen(fd, 'wb') as handle:
                os.fchmod(handle.fileno(), 0o600)
                handle.write(encoded)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
            directory = os.open(self.path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        return True

    def enqueue(self, filename, payload):
        value = {'version': 1, 'state': 'pending', 'payload': payload}
        encoded = json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        with self.lock('.queue.lock'):
            path = self.path / filename
            if path.exists():
                private_read(path)
                return True
            files = self.files()
            size = self.disk_bytes()
            # Reserve each record's terminal-state growth and one full temporary
            # replacement of the largest record; writes are serialized by the lock.
            largest = max([len(encoded), *(p.lstat().st_size for p in files)])
            required = size + len(encoded) + 16 * (len(files) + 1) + largest + 16
            if len(files) >= self.max_events or required > self.max_bytes:
                return False
            return self.write(path, value)

    def flush(self, endpoint, headers, timeout, limit):
        # Export serialization does not block short enqueue operations.
        with self.lock('.flush.lock', wait=0):
            deadline = time.monotonic() + timeout
            with self.lock('.queue.lock', wait=min(0.5, max(0, deadline - time.monotonic()))):
                files = self.files()
            attempted = 0
            for path in files:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or attempted >= limit:
                    break
                try:
                    with self.lock('.queue.lock', wait=min(0.5, remaining)):
                        value = json.loads(private_read(path))
                    if not isinstance(value, dict) or value.get('version') != 1 or not isinstance(value.get('payload'), dict):
                        continue
                except (OSError, ValueError):
                    # Preserve corrupt/unsafe files for inspection; do not starve later events.
                    continue
                if value.get('state') != 'pending':
                    continue
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                attempted += 1
                state = bounded_deliver(endpoint, headers, value['payload'], remaining)
                # Allow the usual bounded lock wait to persist the final result,
                # even when delivery consumed the remaining network budget.
                with self.lock('.queue.lock'):
                    if state == 'acked':
                        path.unlink()
                    elif state != 'pending':
                        value['state'] = state
                        self.write(path, value)
                if state == 'pending':
                    break
            return attempted


def forward(config, payload):
    command = config['original_notify']
    if not command:
        return
    timeout = number(config, 'forward_timeout_seconds', 2, 5)
    subprocess.run([*command, payload], env={**os.environ, ACTIVE: '1'},
                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                   timeout=timeout, check=False, shell=False)


class PrivateArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(0, 'Codex response monitor: invalid invocation.\n')


def main():
    if os.environ.get(ACTIVE) == '1':
        return 0
    home = Path(os.environ.get('CODEX_HOME', str(Path.home() / '.codex')))
    parser = PrivateArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=home / 'response-monitor.json')
    parser.add_argument('--flush', action='store_true')
    parser.add_argument('payload', nargs='?')
    args = parser.parse_args()
    config = None
    try:
        config = settings(args.config)
        spool = Spool(config)
        item = None
        queued = True
        if args.payload is not None:
            try:
                item = event(args.payload)
                if item is not None:
                    queued = spool.enqueue(*item)
            except Exception:
                print('Codex response monitor: event unavailable.', file=sys.stderr)
        endpoint, headers = collector(home)
        timeout = number(config, 'network_timeout_seconds', 2, 5)
        limit = number(config, 'flush_limit', 20, 100, integer=True)
        deadline = time.monotonic() + timeout
        attempted = spool.flush(endpoint, headers, timeout, limit)
        if item is not None and not queued:
            # A recovered collector can drain a full queue and accept this new turn.
            if not spool.enqueue(*item):
                print('Codex response monitor: queue capacity reached.', file=sys.stderr)
            elif attempted < limit and time.monotonic() < deadline:
                spool.flush(endpoint, headers, deadline - time.monotonic(), limit - attempted)
    except Exception:
        # Never interpolate exceptions, collector replies, command arguments, or payload.
        print('Codex response monitor: collection deferred or unavailable.', file=sys.stderr)
    finally:
        if config is not None and args.payload is not None:
            try:
                forward(config, args.payload)
            except Exception:
                print('Codex response monitor: original notifier unavailable.', file=sys.stderr)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
