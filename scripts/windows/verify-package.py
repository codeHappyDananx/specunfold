#!/usr/bin/env python3
"""Verify the native distribution without Java or Node.js on PATH."""
import argparse
import json
import os
from pathlib import Path
import queue
import subprocess
import tempfile
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app-dir', type=Path, required=True)
    args = parser.parse_args()
    app = args.app_dir.resolve()
    server = app / 'SpecUnfold-server.exe'
    assert server.is_file(), server
    build = Path(__file__).resolve().parents[2] / '.build/windows/verification'
    build.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='安装验证 ', dir=build) as temporary:
        temporary = Path(temporary)
        workspace = temporary / '中文项目 with spaces'
        workspace.mkdir()
        (workspace / 'AGENTS.md').write_text('# 本项目原有规则\n', encoding='utf-8')
        env = dict(os.environ)
        env['PATH'] = str(Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32')
        for key in ('JAVA_HOME', 'JDK_HOME', 'NODE_PATH', 'SPECUNFOLD_WORKSPACE_ROOT',
                    'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
            env.pop(key, None)
        env['TEMP'] = env['TMP'] = str(temporary)
        install = subprocess.run([str(server), 'install', '--workspace', str(workspace),
                                  '--profile', 'example'], env=env, cwd=temporary,
                                 capture_output=True, encoding='utf-8', timeout=30)
        assert install.returncode == 0, install.stdout + install.stderr
        assert (workspace / 'AGENTS.md').read_text(encoding='utf-8').startswith('# 本项目原有规则\n')
        assert (workspace / '.agents/harness/manifest.json').is_file()
        (workspace / 'delivery.txt').write_text('验证内置脚本运行环境。\n', encoding='utf-8')
        with (temporary / 'server.stderr').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([str(server)], cwd=workspace, env=env,
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=log, encoding='utf-8')
            lines = queue.Queue()

            def read_stdout():
                for line in process.stdout:
                    lines.put(line)
                lines.put(None)

            reader = threading.Thread(target=read_stdout, daemon=True)
            reader.start()
            sequence = 0

            def rpc(method, params):
                nonlocal sequence
                sequence += 1
                process.stdin.write(json.dumps({'jsonrpc': '2.0', 'id': sequence,
                                                'method': method, 'params': params}) + '\n')
                process.stdin.flush()
                line = lines.get(timeout=30)
                assert line is not None, 'Native server exited without replying'
                result = json.loads(line)
                assert result.get('id') == sequence and 'error' not in result, result
                return result['result']

            def call(action, **arguments):
                result = rpc('tools/call', {'name': 'workflow_ops', 'arguments': {
                    'action': action, 'workspaceRoot': str(workspace), 'profile': 'example', **arguments}})
                assert not result.get('isError'), result
                assert result['structuredContent']['success'], result
                return result['structuredContent']['result']

            try:
                initialized = rpc('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {},
                                                 'clientInfo': {'name': 'package-verification', 'version': '1'}})
                assert initialized['serverInfo']['name'] == 'specunfold'
                assert [tool['name'] for tool in rpc('tools/list', {})['tools']] == ['workflow_ops']
                assert call('context', intent='package verification')['status'] == 'IDLE'
                assert not (workspace / '.specunfold').exists(), 'Ordinary context must not create workflow state'
                assert call('validate')['status'] == 'VALID'
                script = call('run_profile_script', scriptId='check_artifact', input={'artifactPath': 'delivery.txt'})
                assert script['ok'] and script['exists'] and script['bytes'] > 0, script
                process.stdin.close()
                assert process.wait(timeout=15) == 0
                reader.join(timeout=5)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=10)
                if not process.stdin.closed:
                    process.stdin.close()
                process.stdout.close()
                reader.join(timeout=5)
        print(json.dumps({'passed': True, 'appDirectory': str(app),
                          'checks': ['no system Java or Node.js', 'Unicode and spaces in project path',
                                     'project instruction preservation', 'native stdio handshake',
                                     'stateless context', 'profile validation', 'bundled Node.js script']},
                         ensure_ascii=False))


if __name__ == '__main__':
    main()
