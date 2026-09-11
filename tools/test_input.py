#!/usr/bin/env python3
"""Executa StreamInput apos todos os patches, com eventos Android simulados.
Usa ControllerState real. Nao simula o radio, o compositor ou o runtime Quest.
"""
import argparse, difflib, os, re, subprocess, tempfile
from pathlib import Path
root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--java', default='java')
p.add_argument('--compiler-classpath', required=True)
p.add_argument('--stdlib', required=True)
p.add_argument('--work-dir', required=True, type=Path)
a = p.parse_args()
a.work_dir.mkdir(parents=True, exist_ok=True)
work = a.work_dir.resolve()
sub = root/'external/chiaki-ng'
with tempfile.TemporaryDirectory(prefix='p5m-input-test-') as directory:
    tree = Path(directory)/'tree'
    subprocess.run(['git','worktree','add','--detach',str(tree),'HEAD'],cwd=sub,check=True,capture_output=True)
    try:
        for patch in sorted((root/'patches').glob('*.patch')):
            subprocess.run(['git','apply',str(patch)],cwd=tree,check=True,capture_output=True)
        kotlin = tree/'android/app/src/main/java/com/metallic/chiaki'
        source = (kotlin/'session/StreamInput.kt').read_text(encoding='utf-8')
        (work/'StreamInput.kt').write_text(source,encoding='utf-8')
        lib = (kotlin/'lib/Chiaki.kt').read_text(encoding='utf-8')
        state = lib[lib.index('private fun maxAbs'):lib.index('class QuitReason')]
        (work/'ControllerState.kt').write_text('package com.metallic.chiaki.lib\nimport kotlin.math.abs\n'+state,encoding='utf-8')
    finally:
        subprocess.run(['git','worktree','remove','--force',str(tree)],cwd=sub,check=True,capture_output=True)
# Constantes simbolicas, sem reproduzir a traducao para bits do PS5.
keys = sorted(set(re.findall(r'KeyEvent\.(KEYCODE_\w+)',source)))
axes = sorted(set(re.findall(r'MotionEvent\.(AXIS_\w+)',source)))
view = '''package android.view
class InputDevice(val id: Int, val name: String, val sources: Int) {
    fun supportsSource(source: Int) = sources and source == source
    companion object { const val SOURCE_GAMEPAD=0x401; const val SOURCE_JOYSTICK=0x1000010; const val SOURCE_CLASS_JOYSTICK=0x10 }
}
class WindowManager { val defaultDisplay = Display() }
class Display { val rotation = 0 }
object Surface { const val ROTATION_90 = 1 }
class KeyEvent(val action: Int, val keyCode: Int) { companion object {
    const val ACTION_DOWN=0; const val ACTION_UP=1
'''+ '\n'.join(f'const val {key}={i+100}' for i,key in enumerate(keys)) + '''
} }
class MotionEvent(val deviceId: Int, val axes: Map<Int, Float>) {
    val source=InputDevice.SOURCE_JOYSTICK
    val historySize=0
    fun getAxisValue(id: Int) = axes[id] ?: 0f
    fun getHistoricalAxisValue(id: Int, index: Int) = getAxisValue(id)
    companion object {
'''+ '\n'.join(f'const val {axis}={i}' for i,axis in enumerate(axes)) + '\n} }'
(work/'View.kt').write_text(view,encoding='utf-8')
jar = work/'input-tests.jar'
sources = [root/'app/src/main/java/io/github/gblandro/p5m/GamepadMonitor.kt', work/'StreamInput.kt', work/'ControllerState.kt', work/'View.kt', *sorted((root/'tools/input').glob('*.kt'))]
subprocess.run([a.java,'-cp',a.compiler_classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-nowarn','-no-stdlib','-no-reflect','-classpath',a.stdlib,'-d',str(jar),*map(str,sources)],check=True)
subprocess.run([a.java,'-cp',os.pathsep.join([str(jar),a.stdlib]),'io.github.gblandro.p5m.RegressionKt'],check=True)
