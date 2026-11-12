#!/usr/bin/env python3
"""Compile native Kotlin sources/tests from cached dependencies; optionally execute real JNI.
No Gradle invocation, network download, bundled weights, or Android execution claim.
Run from project root: python3 app/src/test/java/com/nyx/ai/nativeinference/host_verify.py
Optional: --library-dir /tmp/nyx-native-host --model /tmp/.../model.gguf
"""
import argparse
from pathlib import Path
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--library-dir', type=Path)
p.add_argument('--model', type=Path)
p.add_argument('--output', type=Path, default=Path('/tmp/nyx-native-kotlin'))
a = p.parse_args()
root = Path(__file__).resolve().parents[8]
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'

def jar(name):
    matches = list(cache.rglob(name))
    if not matches:
        raise SystemExit('Missing cached dependency: ' + name)
    return matches[0]

stdlib = jar('kotlin-stdlib-2.0.21.jar')
compiler = [jar('kotlin-compiler-embeddable-2.0.21.jar'), stdlib,
            jar('kotlin-script-runtime-2.0.21.jar'), jar('kotlin-reflect-1.6.10.jar'),
            jar('trove4j-1.0.20200330.jar'), jar('annotations-13.0.jar'),
            jar('kotlinx-coroutines-core-jvm-1.9.0.jar')]
deps = [stdlib, jar('kotlinx-coroutines-core-jvm-1.9.0.jar'), jar('annotation-jvm-1.8.1.jar'),
        jar('junit-4.13.2.jar'), jar('hamcrest-core-1.3.jar'), jar('json-20240303.jar'),
        jar('annotations-13.0.jar'), Path.home() / 'Android/Sdk/platforms/android-35/android.jar',
        root / 'app/build/tmp/kotlin-classes/debug']
if not deps[-1].is_dir():
    raise SystemExit('Build existing app contracts once first; this runner only compiles native-owned sources')
sources = sorted((root / 'app/src/main/java/com/nyx/ai/nativeinference').glob('*.kt'))
sources += sorted(Path(__file__).parent.glob('*.kt'))
a.output.mkdir(parents=True, exist_ok=True)
subprocess.run(['java', '-cp', ':'.join(map(str, compiler)),
                'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                '-jvm-target', '17', '-classpath', ':'.join(map(str, deps)), '-d', str(a.output),
                *map(str, sources)], check=True, cwd=root)
cp = ':'.join(map(str, [a.output, *deps]))
subprocess.run(['java', '-cp', cp, 'org.junit.runner.JUnitCore',
                'com.nyx.ai.nativeinference.GgufMetadataParserTest',
                'com.nyx.ai.nativeinference.NativeTokenSinkTest'], check=True, cwd=root)
if bool(a.library_dir) != bool(a.model):
    raise SystemExit('Supply both --library-dir and --model for actual inference')
if a.model:
    subprocess.run(['java', '-Djava.library.path=' + str(a.library_dir.resolve()), '-cp', cp,
                    'com.nyx.ai.nativeinference.NativeHostJniSmoke', str(a.model.resolve())], check=True, cwd=root)
else:
    print('REAL JNI INFERENCE NOT EXECUTED: no external model/library arguments (not an inference pass)')
