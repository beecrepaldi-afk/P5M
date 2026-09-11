#!/usr/bin/env python3
"""Compila StreamReturn real com doubles pequenos do Android."""
import argparse, os, subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--java', default='java')
p.add_argument('--compiler-classpath', required=True)
p.add_argument('--stdlib', required=True)
p.add_argument('--work-dir', required=True, type=Path)
a = p.parse_args()
a.work_dir.mkdir(parents=True, exist_ok=True)
jar = a.work_dir.resolve() / 'stream-return-tests.jar'
subprocess.run([a.java, '-cp', a.compiler_classpath,
	'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-nowarn', '-no-stdlib',
	'-no-reflect', '-classpath', a.stdlib, '-d', str(jar),
	str(root / 'app/src/main/java/io/github/gblandro/p5m/StreamReturn.kt'),
	*map(str, sorted((root / 'tools/return').glob('*.kt')))], check=True)
subprocess.run([a.java, '-cp', os.pathsep.join([str(jar), a.stdlib]),
	'tests.RegressionKt'], check=True)
