#!/usr/bin/env python3
"""Use a GitHub Actions encrypted secret; never publish a keystore or password."""
import base64, json, os, pathlib, re, subprocess, sys, tempfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
EXPECTED_CERT = '6f629548392328aaaa3b2f36e33ddff028277bd1270ed0000152d24fd4f3f13f'

def main():
    raw = os.environ.pop('X96_SIGNING_BUNDLE', '')
    if not raw:
        raise SystemExit('X96_SIGNING_BUNDLE is not configured; signed build requires the original key.')
    config = json.loads(raw)
    alias = config['alias']
    if not isinstance(alias, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', alias):
        raise SystemExit('Invalid signing alias')
    password = config['password']
    if not isinstance(password, str) or not password:
        raise SystemExit('Signing password is missing')
    env = os.environ.copy()
    env['X96_KEYSTORE_PASSWORD'] = password
    with tempfile.TemporaryDirectory(prefix='x96-signing-', dir=os.environ.get('RUNNER_TEMP')) as private:
        key = pathlib.Path(private) / 'release.p12'
        key.write_bytes(base64.b64decode(config['keystore_base64'], validate=True))
        key.chmod(0o600)
        subprocess.run([sys.executable, str(ROOT/'tools/build.py'), '--keystore', str(key), '--alias', alias], cwd=ROOT, env=env, check=True)
        apks = [p for p in (ROOT/'build').glob('X96-local-*.apk') if not p.name.endswith('-unsigned.apk')]
        if len(apks) != 1:
            raise SystemExit('Expected one signed APK')
        result = subprocess.run([str(ROOT/'.toolcache/android-15/apksigner'), 'verify', '--print-certs', str(apks[0])], capture_output=True, text=True, check=True)
        cert = re.search(r'certificate SHA-256 digest: ([0-9a-fA-F]+)', result.stdout)
        if not cert or cert.group(1).lower() != EXPECTED_CERT:
            apks[0].unlink()
            raise SystemExit('Signer differs from X96 Local 0.1.0/0.2.0; APK was removed.')
        summary = os.environ.get('GITHUB_STEP_SUMMARY')
        if summary:
            with open(summary, 'a') as s:
                s.write('## Installable update built\n\nTests passed. APK signature matches the original X96 Local certificate.\n')
        print('Signed APK verified against the original X96 certificate.')

if __name__ == '__main__':
    main()
