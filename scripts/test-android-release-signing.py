#!/usr/bin/env python3
"""执行 Android 构建步骤的真实签名入口，验证默认构建和显式发布的凭据边界。"""
from __future__ import annotations

import base64
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/release-android.yml'


def signing_script() -> str:
    """在资源下载前截取构建步骤，不触发编译或网络请求。"""
    step = WORKFLOW.read_text(encoding='utf-8').split('      - name: Build native Android host\n', 1)[1]
    return textwrap.dedent(step.split('        run: |\n', 1)[1].split('          mkdir -p target/resources', 1)[0])


def publish_default(entry: str) -> str:
    text = WORKFLOW.read_text(encoding='utf-8').split('  ' + entry + ':', 1)[1]
    text = text.split('\npermissions:', 1)[0].split('\n  workflow_call:', 1)[0]
    return re.search(r'^      publish:.*default: (true|false)', text, re.MULTILINE).group(1)


class AndroidReleaseSigningTests(unittest.TestCase):
    def setUp(self):
        if shutil.which('bash') is None:
            self.skipTest('bash is not installed')
        self.directory = tempfile.TemporaryDirectory(prefix='android-release-signing-')
        self.addCleanup(self.directory.cleanup)
        self.work = Path(self.directory.name)

    def run_signing(self, publish: str, key: str = '', password: str = '') -> subprocess.CompletedProcess:
        env = dict(os.environ, PUBLISH=publish, RELEASE_KEYSTORE_BASE64=key,
                   MSIME_ANDROID_RELEASE_KEYSTORE_PASSWORD=password, RUNNER_TEMP=str(self.work))
        env.pop('MSIME_ANDROID_RELEASE_KEYSTORE', None)
        # 追加观察点区分开发签名与发布签名，同时检查解码结果和退出清理。
        observe = '\nif [[ -n "${MSIME_ANDROID_RELEASE_KEYSTORE:-}" ]]; then\n' \
                  '  cmp "$MSIME_ANDROID_RELEASE_KEYSTORE" "$RUNNER_TEMP/expected.p12"\n' \
                  '  echo RELEASE_SIGNING_SELECTED\nelse\n  echo DEVELOPMENT_SIGNING_SELECTED\nfi\n'
        result = subprocess.run(['bash', '-e', '-c', signing_script() + observe],
                                env=env, capture_output=True, text=True)
        self.assertFalse((self.work / 'msime-release.p12').exists(), '退出后必须清理临时密钥')
        for value in (key, password):
            if value:
                self.assertNotIn(value, result.stdout + result.stderr)
        return result

    def test_default_dispatch_build_needs_no_release_credentials(self):
        publish = publish_default('workflow_dispatch')
        self.assertEqual(publish, 'false')
        result = self.run_signing(publish)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('DEVELOPMENT_SIGNING_SELECTED', result.stdout)

    def test_reusable_build_needs_no_release_credentials(self):
        publish = publish_default('workflow_call')
        self.assertEqual(publish, 'false')
        result = self.run_signing(publish)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('DEVELOPMENT_SIGNING_SELECTED', result.stdout)

    def test_explicit_publish_rejects_missing_or_partial_credentials(self):
        for key, password in [('', ''), ('synthetic-key', ''), ('', 'synthetic-password')]:
            with self.subTest(key_present=bool(key), password_present=bool(password)):
                result = self.run_signing('true', key, password)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('ANDROID_RELEASE_KEYSTORE_BASE64', result.stderr)
                self.assertIn('ANDROID_RELEASE_KEYSTORE_PASSWORD', result.stderr)
                self.assertNotIn('SIGNING_SELECTED', result.stdout)

    def test_explicit_publish_decodes_and_removes_synthetic_key(self):
        payload = b'synthetic Android release signing fixture\n'
        (self.work / 'expected.p12').write_bytes(payload)
        result = self.run_signing('true', base64.b64encode(payload).decode(), 'synthetic-password')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('RELEASE_SIGNING_SELECTED', result.stdout)

    def test_explicit_publish_rejects_invalid_base64(self):
        result = self.run_signing('true', '!!!!', 'synthetic-password')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('SIGNING_SELECTED', result.stdout)


if __name__ == '__main__':
    unittest.main()
