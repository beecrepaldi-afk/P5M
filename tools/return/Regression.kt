package tests

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.AppTask
import android.app.ActivityManager.RecentTaskInfo
import android.content.ComponentName
import android.content.Intent
import io.github.gblandro.p5m.StreamReturn
import io.github.gblandro.p5m.Trace

const val MENU = "com.metallic.chiaki.main.MainActivity"
const val LAUNCHER = "io.github.gblandro.p5m.LauncherActivity"
const val PSN = "io.github.gblandro.p5m.PsnRemoteActivity"
const val VR = "com.metallic.chiaki.stream.VrStreamActivity"
const val WINDOW = "com.metallic.chiaki.stream.StreamActivity"
const val DIAGNOSTIC = "io.github.gblandro.p5m.DiagnosticActivity"

fun c(name: String) = ComponentName("io.github.gblandro.p5m", name)
fun task(id: Int, base: String, top: String, n: Int = 1, refuse: Boolean = false) =
	AppTask(RecentTaskInfo(id, c(base), c(top), n), refuse)
fun from(name: String) = Intent().putExtra(StreamReturn.EXTRA_ACTIVITY, name)
fun stream(taskId: Int, vararg tasks: AppTask) = Activity().apply {
	this.taskId = taskId
	manager = ActivityManager(tasks.toList())
}

fun main()
{
	// Imersivo com o menu em outra tarefa: ela vem para a frente, e o diário
	// lista todas, a do stream marcada.
	val menu = task(10, LAUNCHER, MENU, n = 2)
	val vr = stream(20, task(20, VR, VR), menu)
	StreamReturn.activate(vr, from(MENU))
	check(vr.started.isEmpty())
	check(menu.movedToFront == 1)
	check(Trace.lines.last() == "Returning control to MainActivity: moved task 10 to front; " +
		"tasks: #20 base VrStreamActivity top VrStreamActivity n=1 (stream), " +
		"#10 base LauncherActivity top MainActivity n=2") { Trace.lines.last() }

	// Com mais de uma tarefa nossa, vence a que tem a tela gravada no topo; a
	// do diagnóstico não é tela de retorno e nunca é escolhida.
	val diagnostic = task(11, DIAGNOSTIC, DIAGNOSTIC)
	val psn = task(12, LAUNCHER, PSN)
	val menu2 = task(13, LAUNCHER, MENU)
	val vr2 = stream(20, diagnostic, psn, menu2)
	StreamReturn.activate(vr2, from(MENU))
	check(vr2.started.isEmpty())
	check(menu2.movedToFront == 1 && psn.movedToFront == 0 && diagnostic.movedToFront == 0)

	// Sem a tela gravada no topo de nenhuma, serve a tarefa que começa no
	// lançador: o menu pode estar embaixo de outra tela nossa.
	val launcherOnly = task(14, LAUNCHER, PSN)
	val vr3 = stream(20, diagnostic, launcherOnly)
	StreamReturn.activate(vr3, from(MENU))
	check(vr3.started.isEmpty() && launcherOnly.movedToFront == 1)

	// O caso da dev.177: só a tarefa do stream, com o menu dentro dela ou
	// escondido. Nada é lançado, e o diário mostra onde o stream estava.
	val alone = stream(30, task(30, LAUNCHER, VR, n = 3))
	StreamReturn.activate(alone, from(MENU))
	check(alone.started.isEmpty())
	check(Trace.lines.last() == "Returning control to MainActivity: no other P5M task, leaving it to " +
		"the panel below; tasks: #30 base LauncherActivity top VrStreamActivity n=3 (stream)") { Trace.lines.last() }

	// Lista vazia também é resposta, e diferente da de cima.
	val empty = stream(30)
	StreamReturn.activate(empty, from(MENU))
	check(empty.started.isEmpty() && Trace.lines.last().endsWith("tasks: none listed"))

	// A tarefa sumiu entre a listagem e o moveToFront: ainda sem lançar.
	val gone = task(15, LAUNCHER, MENU, refuse = true)
	val vr4 = stream(20, gone)
	StreamReturn.activate(vr4, from(MENU))
	check(vr4.started.isEmpty())
	check(Trace.lines.last().contains("task 15 refused to move"))

	// Sem ActivityManager, também não lança.
	val bare = Activity()
	StreamReturn.activate(bare, from(LAUNCHER))
	check(bare.started.isEmpty() && Trace.lines.last().contains("ActivityManager unavailable"))

	// Com a tarefa gravada, ela vem pelo número, mesmo invisível em appTasks.
	val recorded = stream(40, task(40, VR, VR))
	StreamReturn.activate(recorded, from(MENU).putExtra(StreamReturn.EXTRA_TASK, 6324))
	check(recorded.started.isEmpty() && recorded.manager!!.movedTasks == listOf(6324))
	check(Trace.lines.last() == "Returning control to MainActivity: moved recorded task 6324 to front")

	// Recusada, cai na busca por appTasks e diz por quê.
	val denied = Activity().apply { taskId = 40; manager = ActivityManager(listOf(task(10, LAUNCHER, MENU)), true) }
	StreamReturn.activate(denied, from(MENU).putExtra(StreamReturn.EXTRA_TASK, 6324))
	check(Trace.lines.dropLast(1).last().contains("recorded task 6324 refused to move (SecurityException"))
	check(denied.manager!!.appTasks.single().movedToFront == 1)

	// Origem ausente ou de fora do P5M não reativa nada.
	val guarded = stream(20, task(10, LAUNCHER, MENU))
	StreamReturn.activate(guarded, from("other.app.UntrustedActivity"))
	StreamReturn.activate(guarded, Intent())
	StreamReturn.activate(guarded, null)
	check(guarded.started.isEmpty())
	check(guarded.manager!!.appTasks.single().movedToFront == 0)
	println("PASS: stream return never launches, moves an existing P5M task and lists every task")
}
