#!/usr/bin/env python3
"""Run official Claude Code with subscription authentication and usage-only OTLP export."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
AUTH_VARIABLES = ('ANTHROPIC_API_KEY', 'ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_BASE_URL')


def validate_claude_arguments(arguments):
    # These flags alter authentication/configuration after the status preflight.
    # Configure persistent settings first so status and the actual session agree.
    for argument in arguments:
        if argument.split('=', 1)[0] in ('--settings', '--setting-sources', '--bare'):
            raise ValueError('구독 모니터 실행기에서는 --settings, --setting-sources, --bare를 사용할 수 없습니다. 저장된 설정을 정리한 뒤 --check로 확인하세요.')


def collector_token(environ, path):
    token = environ.get('GW_TELEMETRY_TOKEN', '')
    if not token and path.is_file():
        for line in path.read_text().splitlines():
            name, separator, value = line.partition('=')
            if name == 'GW_TELEMETRY_TOKEN' and separator:
                token = value
                break
    if len(token) < 32 or len(token) > 4096 or any(c.isspace() for c in token):
        raise ValueError('GW_TELEMETRY_TOKEN이 필요합니다. admin.sh로 발급하거나 수집 전용 키를 설정하세요.')
    return token


def monitor_environment(environ, token, endpoint):
    url = urllib.parse.urlsplit(endpoint)
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError('수집 주소는 인증 정보·쿼리가 없는 HTTP(S) URL이어야 합니다.')
    if url.scheme == 'http' and url.hostname not in ('localhost', '127.0.0.1', '::1'):
        raise ValueError('원격 수집 주소는 HTTPS를 사용하세요.')
    child = environ.copy()
    for name in AUTH_VARIABLES:
        child.pop(name, None)
    # General headers are merged by OTel; do not attach unrelated collector credentials.
    child.pop('OTEL_EXPORTER_OTLP_HEADERS', None)
    child.update({
        'CLAUDE_CODE_ENABLE_TELEMETRY': '1',
        'OTEL_LOGS_EXPORTER': 'otlp',
        'OTEL_METRICS_EXPORTER': 'none',
        'OTEL_TRACES_EXPORTER': 'none',
        'OTEL_EXPORTER_OTLP_LOGS_PROTOCOL': 'http/json',
        'OTEL_EXPORTER_OTLP_LOGS_ENDPOINT': endpoint,
        'OTEL_EXPORTER_OTLP_LOGS_HEADERS': 'Authorization=Bearer ' + token,
        'OTEL_EXPORTER_OTLP_LOGS_COMPRESSION': 'none',
        'OTEL_LOG_USER_PROMPTS': '0',
        'OTEL_LOG_ASSISTANT_RESPONSES': '0',
        'OTEL_LOG_TOOL_DETAILS': '0',
        'OTEL_LOG_RAW_API_BODIES': '0',
    })
    return child


def verify_subscription(executable, child):
    result = subprocess.run([executable, 'auth', 'status', '--json'], env=child,
                            capture_output=True, text=True, timeout=20)
    try:
        status = json.loads(result.stdout)
    except (ValueError, TypeError):
        raise ValueError('Claude Code 인증 상태를 확인할 수 없습니다. CLI를 업데이트하고 claude auth status로 확인하세요.') from None
    if (result.returncode or not status.get('loggedIn') or status.get('authMethod') != 'claude.ai'
            or status.get('apiProvider') != 'firstParty' or not status.get('subscriptionType')):
        raise ValueError('구독 로그인이 확인되지 않았습니다. Claude Code에서 구독 계정으로 로그인하고 설정 파일의 API 키·apiKeyHelper·공급자 설정을 확인하세요.')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def verify_collector(endpoint, token):
    request = urllib.request.Request(endpoint, data=b'{"resourceLogs":[]}',
        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=15) as response:
            if response.status != 200:
                raise ValueError('수집기가 요청을 수락하지 않았습니다.')
    except urllib.error.HTTPError as error:
        raise ValueError('수집기 연결 실패 (HTTP ' + str(error.code) + '). 주소·수집 키·DB 상태를 확인하세요.') from None
    except (urllib.error.URLError, TimeoutError):
        raise ValueError('수집기에 연결할 수 없습니다. 어드민 서버와 주소를 확인하세요.') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='인증·수집 연결만 확인 (모델 호출 없음)')
    parser.add_argument('--endpoint', default=os.environ.get('GW_TELEMETRY_ENDPOINT', 'http://127.0.0.1:8080/telemetry/v1/logs'))
    parser.add_argument('--token-file', type=Path, default=ROOT / 'var/admin.env')
    args, claude_args = parser.parse_known_args()
    executable = shutil.which('claude')
    if not executable:
        parser.exit(1, 'Claude Code CLI가 필요합니다.\n')
    try:
        validate_claude_arguments(claude_args)
        token = collector_token(os.environ, args.token_file)
        child = monitor_environment(os.environ, token, args.endpoint)
        verify_subscription(executable, child)
        verify_collector(args.endpoint, token)
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        # Do not print child environment, response bodies, or authentication JSON.
        message = str(error) if isinstance(error, ValueError) else '사전 확인을 완료할 수 없습니다. CLI·파일·연결 상태를 확인하세요.'
        parser.exit(1, message + '\n')
    print('구독 로그인·수집 연결 확인 완료. 어드민에서 Claude Code 소스를 선택하세요.', flush=True)
    print('사용량만 전송합니다. 환산 비용은 구독 청구액이나 남은 한도가 아닙니다.', flush=True)
    if not args.check:
        os.execve(executable, [executable, *claude_args], child)


if __name__ == '__main__':
    main()
