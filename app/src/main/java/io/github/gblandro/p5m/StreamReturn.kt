// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent

/**
 * Devolve ao primeiro plano a tela 2D que abriu o stream.
 *
 * **Nunca inicia activity.** A dev.176 iniciava a tela gravada com
 * `REORDER_TO_FRONT | SINGLE_TOP` e a dev.177, quando não achava tarefa, com
 * `NEW_TASK | CLEAR_TOP | SINGLE_TOP`; as duas abriram um segundo painel do app
 * ao encerrar a sessão. No Horizon, lançar a partir da tela imersiva cria painel
 * novo em vez de achar o existente, e o diário da dev.177 mostra que quase nunca
 * havia tarefa para trazer (`no other P5M task among 0`).
 *
 * Então só `moveToFront()`, que não tem como duplicar. Sem tarefa, sobra o que a
 * dev.175 fazia: encerrar e deixar o painel de baixo assumir. O custo conhecido
 * é o foco do controle às vezes não voltar para ele.
 */
object StreamReturn
{
	const val EXTRA_ACTIVITY = "io.github.gblandro.p5m.RETURN_ACTIVITY"
	const val EXTRA_TASK = "io.github.gblandro.p5m.RETURN_TASK"

	private val permitidas = setOf(
		"com.metallic.chiaki.main.MainActivity",
		LauncherActivity::class.java.name,
		PsnRemoteActivity::class.java.name)

	fun activate(stream: Activity, source: Intent?)
	{
		val classe = source?.getStringExtra(EXTRA_ACTIVITY)
		if(classe == null || classe !in permitidas)
			return
		val nome = classe.substringAfterLast('.')

		val manager = stream.getSystemService(ActivityManager::class.java)
		if(manager == null)
		{
			Trace.log(stream, "Returning control to $nome: ActivityManager unavailable, leaving it to the panel below")
			return
		}

		// Primeiro pelo numero gravado na abertura. No diario da dev.178 o
		// imersivo so via a propria tarefa em appTasks: a do menu existia
		// (#6324, lancador e menu) e era invisivel ali, entao nao havia o que
		// trazer, e o menu ficava sem o foco do controle.
		val gravada = source?.getIntExtra(EXTRA_TASK, -1) ?: -1
		if(gravada != -1 && gravada != stream.taskId)
		{
			try
			{
				manager.moveTaskToFront(gravada, 0)
				Trace.log(stream, "Returning control to $nome: moved recorded task $gravada to front")
				return
			}
			catch(e: RuntimeException)
			{
				Trace.log(stream, "Returning control to $nome: recorded task $gravada refused to move " +
						"(${e.javaClass.simpleName}: ${e.message}); trying the app tasks")
			}
		}

		val tarefas = try
		{
			manager.appTasks.mapNotNull { t -> runCatching { t to t.taskInfo }.getOrNull() }
		}
		catch(e: RuntimeException)
		{
			Trace.log(stream, "Returning control to $nome: could not list the app tasks " +
					"(${e.javaClass.simpleName}: ${e.message}), leaving it to the panel below")
			return
		}

		// Todas as tarefas vão para o diário, a do stream inclusive. A dev.177
		// contava só as outras, e "among 0" não separava "o sistema não mostra as
		// tarefas dos painéis" de "o menu mora na mesma tarefa do stream".
		val inventario = if(tarefas.isEmpty()) "none listed" else tarefas.joinToString(", ") { (_, i) ->
			"#${i.taskId} base ${i.baseActivity?.shortClassName} top ${i.topActivity?.shortClassName} " +
					"n=${i.numActivities}" + (if(i.taskId == stream.taskId) " (stream)" else "")
		}

		// Entre as outras, vale primeiro a que tem a tela gravada no topo, depois
		// a que a tem na base, e por último qualquer uma que comece numa tela 2D
		// nossa: o lançador costuma ser a base e o menu fica por cima dele.
		val outras = tarefas.filter { it.second.taskId != stream.taskId }
		val escolhida = outras.firstOrNull { it.second.topActivity?.className == classe }
				?: outras.firstOrNull { it.second.baseActivity?.className == classe }
				?: outras.firstOrNull { it.second.baseActivity?.className in permitidas }
		if(escolhida == null)
		{
			Trace.log(stream, "Returning control to $nome: no other P5M task, leaving it to the " +
					"panel below; tasks: $inventario")
			return
		}

		val (tarefa, info) = escolhida
		try
		{
			tarefa.moveToFront()
			Trace.log(stream, "Returning control to $nome: moved task ${info.taskId} to front; tasks: $inventario")
		}
		catch(e: RuntimeException)
		{
			// A tarefa pode ter sumido entre a listagem e aqui; o sistema avisa
			// com IllegalArgumentException.
			Trace.log(stream, "Returning control to $nome: task ${info.taskId} refused to move " +
					"(${e.javaClass.simpleName}: ${e.message}); tasks: $inventario")
		}
	}
}
