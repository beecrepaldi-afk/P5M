// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager

/**
 * O foco da janela do menu na volta do stream (dev.219).
 *
 * A causa do DualSense morto no menu, achada pelo dono na dev.218: a janela
 * ganha o foco na volta e o perde milissegundos depois, para o Horizon que
 * ainda sai do imersivo. Sem foco nenhum botao chega; os analogicos, que o
 * sistema entrega por outro caminho, continuavam aparecendo no diario, e foi
 * isso que escondeu a causa desde a dev.176. Um clique do touchpad na janela
 * devolvia tudo.
 *
 * Aqui o menu pede o foco de volta sozinho, alternando dois jeitos: trazer a
 * propria tarefa para a frente, e ligar e desligar FLAG_NOT_FOCUSABLE, que
 * obriga o gerenciador de janelas a recalcular quem tem o foco. O diario diz
 * qual funcionou.
 *
 * Fora do MenuController de proposito: o teste dele o compila com um Android
 * de mentira, e este codigo precisa do de verdade.
 */
object FocoDoRetorno
{
	// Foco perdido ate 5 s depois da volta e o do Horizon saindo do imersivo;
	// depois disso e um dialogo, um menu, a pessoa.
	private const val JANELA_MS = 5000L
	private val ESPERAS = longArrayOf(400L, 600L, 1000L, 1500L)

	private val principal = Handler(Looper.getMainLooper())
	private var voltaMs = -100_000L
	private var tentativas = 0
	private var pedido: Runnable? = null

	/** O menu reapareceu depois de um stream. */
	fun voltou()
	{
		voltaMs = SystemClock.uptimeMillis()
	}

	fun perdeu(activity: Activity)
	{
		if(SystemClock.uptimeMillis() - voltaMs >= JANELA_MS)
			return
		cancelar()
		tentativas = 0
		val r = object: Runnable
		{
			override fun run()
			{
				// Saindo sem mais pedidos, solta a referencia: este objeto e do
				// processo, e segurava a activity do menu ja destruida ate o
				// proximo stream.
				if(activity.hasWindowFocus() || activity.isFinishing)
				{
					pedido = null
					return
				}
				val n = ++tentativas
				val tarefa = n % 2 == 1
				Trace.log(activity, "Menu focus: lost right after the stream; asking for it back (" +
						(if(tarefa) "move task to front" else "refocusable window") + ", try $n)")
				try
				{
					if(tarefa)
						activity.getSystemService(ActivityManager::class.java)?.moveTaskToFront(activity.taskId, 0)
					else
					{
						val w = activity.window
						w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
						principal.post { w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) }
					}
				}
				catch(e: RuntimeException)
				{
					Trace.log(activity, "Menu focus: request failed (${e.javaClass.simpleName}: ${e.message})")
				}
				if(n < ESPERAS.size)
					principal.postDelayed(this, ESPERAS[n])
				else
				{
					// Guardado como pedido, para o cancelar() alcancar tambem este.
					val ultimo = Runnable {
						pedido = null
						if(!activity.hasWindowFocus() && !activity.isFinishing)
							Trace.log(activity, "Menu focus: still without focus after $n requests")
					}
					pedido = ultimo
					principal.postDelayed(ultimo, 1000L)
				}
			}
		}
		pedido = r
		principal.postDelayed(r, ESPERAS[0])
	}

	fun ganhou(activity: Activity)
	{
		if(tentativas > 0)
			Trace.log(activity, "Menu focus: window focus back after $tentativas request(s)")
		tentativas = 0
		cancelar()
	}

	private fun cancelar()
	{
		pedido?.let { principal.removeCallbacks(it) }
		pedido = null
	}
}
