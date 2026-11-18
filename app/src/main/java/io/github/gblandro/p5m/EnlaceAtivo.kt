// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock

/**
 * Impede que o Bluetooth do Quest ponha o DualSense em sniff enquanto o P5M
 * estiver aberto.
 *
 * O atraso do controle que some ao religa-lo (dev.175 a dev.206) e o enlace em
 * sniff: a entrada chega em lotes a cada 11,25 ms em vez de ~1 ms. Em
 * 18/11/2026, com adb, o log da pilha de Bluetooth mostrou a regra: o Android
 * forca o sniff 30 s depois do ultimo relatorio que o Quest manda ao controle
 * (conexao as 02:44:08, sniff as 02:44:38; fim do espelho as 02:47:09, sniff
 * as 02:47:39), e uma vez em sniff nada tira o enlace de la -- foi o que a
 * dev.196 mediu com o keepalive dentro da sessao. O que o controle manda nao
 * conta: ele manda o tempo todo. E com a sessao aberta, o controle parado por
 * mais de 70 s e o espelho escrevendo, o sniff nao entrou.
 *
 * Entao a alavanca e prevenir, e nao curar: com qualquer tela do P5M aberta,
 * mesmo em segundo plano, se ninguem mandou nada ao controle ha [OCIOSO_MS],
 * sai um 0x31 que nao muda nada. Na sessao o espelho e a haptica ja mandam, e
 * este fica quieto. Aberta e nao visivel porque a pausa da sessao (headset
 * tirado, menu do sistema) para o espelho (dev.239), e 30 s depois o enlace
 * entrava em sniff: foi o que aconteceu as 03:02:28 de 18/11.
 *
 * Nao conserta o controle que ficou 30 s parado com o P5M fechado: esse ja
 * chega em sniff, e so religar resolve.
 */
internal object EnlaceAtivo: Application.ActivityLifecycleCallbacks
{
	private const val INTERVALO_MS = 5_000L
	private const val OCIOSO_MS = 4_000L
	// Sem Bluetooth ou sem proxy, tentar de novo a cada 5 s so encheria o
	// diario com a mesma recusa.
	private const val ESPERA_SEM_PONTE_MS = 60_000L

	private lateinit var contexto: Context
	private val trava = Object()
	private var abertas = 0
	private var thread: Thread? = null

	fun instalar(app: Application)
	{
		contexto = app.applicationContext
		app.registerActivityLifecycleCallbacks(this)
	}

	override fun onActivityCreated(a: Activity, b: Bundle?)
	{
		synchronized(trava)
		{
			abertas++
			if(thread == null)
				thread = Thread(::laco, "P5M-link-keepalive").also { it.isDaemon = true; it.start() }
		}
	}

	override fun onActivityDestroyed(a: Activity)
	{
		synchronized(trava) { abertas = (abertas - 1).coerceAtLeast(0) }
	}

	private fun laco()
	{
		val ponte = DualSenseHid(contexto)
		var enviados = 0
		var recusados = 0
		var proximaPonte = 0L
		Trace.log(contexto, "Link keepalive: on (a do-nothing report after ${OCIOSO_MS / 1000} s without traffic, " +
			"so Bluetooth never puts the DualSense in sniff)")
		try
		{
			while(true)
			{
				synchronized(trava)
				{
					if(abertas == 0)
					{
						thread = null
						return
					}
				}
				val agora = SystemClock.elapsedRealtime()
				if(!ponte.ponteAberta && agora >= proximaPonte)
				{
					if(!ponte.conectar() && !ponte.ponteAberta)
						proximaPonte = agora + ESPERA_SEM_PONTE_MS
				}
				if(ponte.ponteAberta && System.nanoTime() / 1_000_000 - DualSenseOutputSequence.ultimoEnvioMs >= OCIOSO_MS)
				{
					if(ponte.manterAcordado())
						enviados++
					else
					{
						recusados++
						ponte.recuperar()
					}
				}
				Thread.sleep(INTERVALO_MS)
			}
		}
		catch(e: InterruptedException)
		{
			synchronized(trava) { thread = null }
		}
		finally
		{
			ponte.fechar()
			Trace.log(contexto, "Link keepalive: off ($enviados sent, $recusados refused)")
		}
	}

	override fun onActivityStarted(a: Activity) {}
	override fun onActivityResumed(a: Activity) {}
	override fun onActivityPaused(a: Activity) {}
	override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
	override fun onActivityStopped(a: Activity) {}
}
