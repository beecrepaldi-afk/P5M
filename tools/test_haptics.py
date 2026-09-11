#!/usr/bin/env python3
"""Compila e executa DualSenseHaptics real com doubles de Android/JNI/Bluetooth.

Requer JDK e jars do compilador Kotlin (classpath separado por ; no Windows).
Nao testa o radio nem o firmware. --source permite reproduzir a regressao
contra uma copia anterior do arquivo, sem mexer no checkout nem no index.
"""
import argparse
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--java', default='java')
p.add_argument('--compiler-classpath', required=True)
p.add_argument('--stdlib', required=True)
p.add_argument('--work-dir', required=True, type=Path)
p.add_argument('--source', type=Path, default=root / 'app/src/main/java/io/github/gblandro/p5m/DualSenseHaptics.kt')
a = p.parse_args()
a.work_dir.mkdir(parents=True, exist_ok=True)
jar = a.work_dir.resolve() / 'haptics-tests.jar'
subprocess.run([a.java, '-cp', a.compiler_classpath,
                'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-classpath', a.stdlib,
                '-d', str(jar), str(a.source),
                str(root / 'app/src/main/java/io/github/gblandro/p5m/DualSenseOutputSequence.kt'),
                str(root / 'app/src/main/java/io/github/gblandro/p5m/TimingHistogram.kt'),
                *map(str, sorted((root / 'tools/haptics').glob('*.kt')))], check=True)
subprocess.run([a.java, '-cp', os.pathsep.join([str(jar), a.stdlib]),
                'io.github.gblandro.p5m.RegressionKt'], check=True)
