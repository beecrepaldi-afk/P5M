#!/usr/bin/env python3
"""Testa o ciclo de StreamSession/ViewModel reais com JNI e fila Android simulados.
Nao testa o console, o driver de video ou o ciclo de vida do Quest.
"""
import argparse, os, subprocess, tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--java', default='java')
p.add_argument('--compiler-classpath', required=True)
p.add_argument('--stdlib', required=True)
p.add_argument('--work-dir', required=True, type=Path)
a = p.parse_args()
work = a.work_dir.resolve()
work.mkdir(parents=True, exist_ok=True)
sub = root/'external/chiaki-ng'
with tempfile.TemporaryDirectory(prefix='p5m-session-test-') as directory:
    tree = Path(directory)/'tree'
    subprocess.run(['git','worktree','add','--detach',str(tree),'HEAD'],cwd=sub,check=True,capture_output=True)
    try:
        for patch in sorted((root/'patches').glob('*.patch')):
            subprocess.run(['git','apply',str(patch)],cwd=tree,check=True,capture_output=True)
        for name in ['session/StreamSession.kt','stream/StreamViewModel.kt']:
            source = tree/'android/app/src/main/java/com/metallic/chiaki'/name
            (work/source.name).write_text(source.read_text(encoding='utf-8'),encoding='utf-8')
    finally:
        subprocess.run(['git','worktree','remove','--force',str(tree)],cwd=sub,check=True,capture_output=True)

fixtures = {
'Context.kt': 'package android.content\nopen class Context',
'App.kt': 'package android.app\nclass Application: android.content.Context()',
'Log.kt': 'package android.util\nobject Log { fun i(t:String,m:String)=0; fun w(t:String,m:String)=0 }',
'Graphics.kt': 'package android.graphics\nclass SurfaceTexture',
'Os.kt': '''package android.os
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
    fun post(block: () -> Unit) { queue.add(block) }
    companion object {
        private val queue = java.util.ArrayDeque<() -> Unit>()
        fun drain() { while(queue.isNotEmpty()) queue.removeFirst()() }
    }
}''',
'Lifecycle.kt': '''package androidx.lifecycle
open class LiveData<T>(initial:T) { open var value:T=initial }
class MutableLiveData<T>(initial:T): LiveData<T>(initial) {
    fun postValue(v:T) { android.os.Handler(android.os.Looper.getMainLooper()).post { value=v } }
}
open class ViewModel { open fun onCleared() {} }
''',
'View.kt': '''package android.view
class Surface(texture:android.graphics.SurfaceTexture?)
class SurfaceView { val holder=SurfaceHolder() }
class SurfaceHolder {
    val surface=Surface(null)
    fun addCallback(c:Callback) {}
    interface Callback { fun surfaceCreated(h:SurfaceHolder); fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,z:Int); fun surfaceDestroyed(h:SurfaceHolder) }
}
class TextureView {
    var surfaceTextureListener:SurfaceTextureListener?=null
    fun setSurfaceTexture(s:android.graphics.SurfaceTexture) {}
    interface SurfaceTextureListener {
        fun onSurfaceTextureAvailable(s:android.graphics.SurfaceTexture,w:Int,h:Int)
        fun onSurfaceTextureDestroyed(s:android.graphics.SurfaceTexture):Boolean
        fun onSurfaceTextureSizeChanged(s:android.graphics.SurfaceTexture,w:Int,h:Int)
        fun onSurfaceTextureUpdated(s:android.graphics.SurfaceTexture)
    }
}''',
'Common.kt': '''package com.metallic.chiaki.common
class LogManager(context:android.content.Context) {
    class Entry { val file=java.io.File("simulated.log") }
    fun createNewFile()=Entry()
}
class Preferences(context:android.content.Context) {
    val logVerbose=false
    var onScreenControlsEnabled=false
    var touchpadOnlyEnabled=false
}''',
'Input.kt': '''package com.metallic.chiaki.session
class StreamInput(c:android.content.Context,p:com.metallic.chiaki.common.Preferences) {
    var controllerStateChangedCallback:((Int)->Unit)?=null
}''',
'Lib.kt': '''package com.metallic.chiaki.lib
class ConnectInfo(val duid:String="", val ps5:Boolean=true)
class ErrorCode(val isSuccess:Boolean=true)
class CreateError(val errorCode:ErrorCode):Exception()
class QuitReason
sealed class Event
object ConnectedEvent:Event()
class QuitEvent(val reason:QuitReason=QuitReason(),val reasonString:String?=null):Event()
class LoginPinRequestEvent(val pinIncorrect:Boolean):Event()
class RumbleEvent(val left:UByte,val right:UByte):Event()
class Session(c:ConnectInfo,l:String,v:Boolean) {
    var eventCallback:((Event)->Unit)?=null
    var stops=0; var disposes=0; var starts=0
    fun start() { starts++ }
    fun stop() { stops++; eventCallback?.invoke(QuitEvent()) }
    fun dispose() { check(stops>0); disposes++ }
    fun cancelPsn() {}
    fun connectPsn(d:String,p:Boolean)=ErrorCode()
    fun setControllerState(s:Int) { check(disposes==0) }
    fun setSurface(s:android.view.Surface?) { check(disposes==0) }
    fun setLoginPin(pin:String) {}
}''',
'Regression.kt': '''package tests
import android.os.Handler
import com.metallic.chiaki.lib.*
import com.metallic.chiaki.session.*
import com.metallic.chiaki.stream.StreamViewModel
fun main() {
    val vm=StreamViewModel(android.app.Application(),ConnectInfo())
    val stream=vm.session
    stream.resume()
    val first=stream.session!!
    val oldCallback=first.eventCallback!!
    first.eventCallback!!(ConnectedEvent)
    Handler.drain()
    check(stream.state.value === StreamStateConnected)
    // Evento ja enfileirado e callback que ainda estava em voo ao encerrar.
    oldCallback(QuitEvent())
    stream.shutdown()
    check(first.stops==1 && first.disposes==1)
    check(stream.session==null)
    stream.resume()
    val second=stream.session!!
    oldCallback(LoginPinRequestEvent(true))
    oldCallback(RumbleEvent(255U,255U))
    Handler.drain()
    check(stream.state.value === StreamStateConnecting)
    check(stream.rumbleState.value.left.toInt()==0)
    second.eventCallback!!(ConnectedEvent)
    Handler.drain()
    check(stream.state.value === StreamStateConnected)
    stream.resume()
    check(stream.session===second && second.starts==1)
    second.eventCallback!!(QuitEvent(reasonString="current failure"))
    Handler.drain()
    check((stream.state.value as StreamStateQuit).reasonString=="current failure")
    println("PASS: current events delivered, old queued/in-flight events rejected")
    // onCleared deve liberar a sessao real, mesmo sem pause anterior.
    vm.onCleared()
    Handler.drain()
    check(second.stops==1 && second.disposes==1 && stream.session==null)
    check(stream.state.value === StreamStateIdle)
    vm.onCleared()
    check(second.disposes==1)
    println("PASS: ViewModel cleanup stops/disposes the owned session exactly once")
    stream.resume()
    val third=stream.session!!
    stream.pause()
    Handler.drain()
    check(third.stops==1 && third.disposes==1)
    check(stream.state.value === StreamStateIdle)
    println("PASS: pause cannot publish its stop event over idle state")
}'''
}
for name, content in fixtures.items():
    (work/name).write_text(content,encoding='utf-8')
sources=[work/'StreamSession.kt',work/'StreamViewModel.kt',*[work/name for name in fixtures]]
jar=work/'session-tests.jar'
subprocess.run([a.java,'-cp',a.compiler_classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-nowarn','-no-stdlib','-no-reflect','-classpath',a.stdlib,'-d',str(jar),*map(str,sources)],check=True)
subprocess.run([a.java,'-cp',os.pathsep.join([str(jar),a.stdlib]),'tests.RegressionKt'],check=True)
