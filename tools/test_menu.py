#!/usr/bin/env python3
"""Executa MenuController real com arvore de foco/Android simulados.
Nao testa layout ou distribuicao de eventos do Horizon OS.
"""
import argparse, os, subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--java',default='java')
p.add_argument('--compiler-classpath',required=True)
p.add_argument('--stdlib',required=True)
p.add_argument('--work-dir',required=True,type=Path)
p.add_argument('--source',type=Path,default=root/'app/src/main/java/io/github/gblandro/p5m/MenuController.kt')
a=p.parse_args()
a.work_dir.mkdir(parents=True,exist_ok=True)
jar=a.work_dir.resolve()/'menu-tests.jar'
subprocess.run([a.java,'-cp',a.compiler_classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-nowarn','-no-stdlib','-no-reflect','-classpath',a.stdlib,'-d',str(jar),str(a.source),*map(str,sorted((root/'tools/menu').glob('*.kt')))],check=True)
subprocess.run([a.java,'-cp',os.pathsep.join([str(jar),a.stdlib]),'tests.RegressionKt'],check=True)
