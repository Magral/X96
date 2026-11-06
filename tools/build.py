#!/usr/bin/env python3
"""Linux build without Gradle: Java 17+ runtime, Python 3, pinned official SDK/compiler.
No downloaded code becomes an app runtime dependency. No signing secrets in this project.
"""
import argparse, hashlib, os, pathlib, shutil, stat, subprocess, urllib.request, zipfile, xml.etree.ElementTree as ET
ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot().attrib['{http://schemas.android.com/apk/res/android}versionName']
TOOLS = [
    ('platform-35_r02.zip', 'https://dl.google.com/android/repository/platform-35_r02.zip', '0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0'),
    ('build-tools_r35_linux.zip', 'https://dl.google.com/android/repository/build-tools_r35_linux.zip', 'bd3a4966912eb8b30ed0d00b0cda6b6543b949d5ffe00bea54c04c81e1561d88'),
    ('ecj.jar', 'https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.40.0/ecj-3.40.0.jar', '05cc22a24e7982970f63a405fc6c820bc80b806f27f3c5a6236fc475f8f7152b'),
]
def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT)
def tools(cache):
    cache.mkdir(parents=True, exist_ok=True)
    for name, url, digest in TOOLS:
        f = cache / name
        if not f.exists():
            print('Downloading build tool:', name, flush=True)
            urllib.request.urlretrieve(url, f)
        if hashlib.sha256(f.read_bytes()).hexdigest() != digest:
            raise SystemExit('Build tool checksum mismatch: ' + name)
        if name.endswith('.zip'):
            with zipfile.ZipFile(f) as z:
                for entry in z.infolist():
                    p = (cache / entry.filename).resolve()
                    if cache.resolve() not in p.parents:
                        raise SystemExit('Invalid SDK archive path')
                z.extractall(cache)
    sdk = cache / 'android-35' / 'android.jar'
    bt = cache / 'android-15'
    for name in ['aapt2', 'd8', 'zipalign', 'apksigner']:
        (bt / name).chmod((bt / name).stat().st_mode | stat.S_IXUSR)
    return sdk, bt
def main():
    p = argparse.ArgumentParser()
    p.add_argument('--tool-cache', type=pathlib.Path, default=ROOT / '.toolcache')
    p.add_argument('--keystore', type=pathlib.Path)
    p.add_argument('--alias', default='x96local')
    p.add_argument('--test-only', action='store_true')
    args = p.parse_args()
    cache = args.tool_cache.resolve()
    sdk, bt = tools(cache)
    out = ROOT / 'build'
    if out.exists(): shutil.rmtree(out)
    out.mkdir()
    src = ROOT / 'app/src/main/java/ru/x96/local'
    tests = out / 'test-classes'
    tests.mkdir()
    core = ['Policy', 'AdbProtocol', 'AdbClient', 'TvShell', 'RecoveryStore', 'TvRepository', 'Option', 'ProjectivyTools', 'ApkInstall', 'DeviceHealth', 'AppOptimization']
    run('java', '-jar', cache/'ecj.jar', '-1.8', '-proc:none', '-warn:none', '-d', tests,
        *[src/(n+'.java') for n in core], *sorted((ROOT/'tests').glob('*.java')))
    run('java', '-cp', tests, 'ru.x96.local.CoreTests')
    run('java', '-cp', tests, 'ru.x96.local.RepositoryTests')
    run('java', '-cp', tests, 'ru.x96.local.ProjectivyTests')
    run('java', '-cp', tests, 'ru.x96.local.InstallTests')
    run('java', '-cp', tests, 'ru.x96.local.HealthTests')
    run('java', '-cp', tests, 'ru.x96.local.OptimizationTests')
    if args.test_only: return
    classes, dex = out/'classes', out/'dex'
    classes.mkdir(); dex.mkdir()
    run('java', '-jar', cache/'ecj.jar', '-1.8', '-proc:none', '-warn:none', '-bootclasspath', sdk,
        '-classpath', bt/'core-lambda-stubs.jar', '-d', classes, *sorted(src.glob('*.java')))
    jar = out/'classes.jar'
    with zipfile.ZipFile(jar, 'w') as z:
        for f in sorted(classes.rglob('*.class')): z.write(f, f.relative_to(classes))
    run(bt/'d8', '--lib', sdk, '--min-api', '26', '--output', dex, jar)
    run(bt/'aapt2', 'compile', '--dir', ROOT/'app/src/main/res', '-o', out/'resources.zip')
    run(bt/'aapt2', 'link', '-I', sdk, '--manifest', ROOT/'app/src/main/AndroidManifest.xml',
        '-A', ROOT/'app/src/main/assets', '-o', out/'base.apk', out/'resources.zip')
    with zipfile.ZipFile(out/'base.apk', 'a') as z: z.write(dex/'classes.dex', 'classes.dex')
    aligned = out/f'X96-local-{VERSION}-unsigned.apk'
    run(bt/'zipalign', '-f', '-p', '4', out/'base.apk', aligned)
    result = aligned
    if args.keystore:
        if not os.environ.get('X96_KEYSTORE_PASSWORD'): raise SystemExit('Set X96_KEYSTORE_PASSWORD to sign')
        result = out/f'X96-local-{VERSION}.apk'
        run(bt/'apksigner', 'sign', '--ks', args.keystore.resolve(), '--ks-key-alias', args.alias,
            '--ks-pass', 'env:X96_KEYSTORE_PASSWORD', '--key-pass', 'env:X96_KEYSTORE_PASSWORD', '--out', result, aligned)
        run(bt/'apksigner', 'verify', '--verbose', result)
    print('APK:', result)
    print('SHA256:', hashlib.sha256(result.read_bytes()).hexdigest())
if __name__ == '__main__': main()
