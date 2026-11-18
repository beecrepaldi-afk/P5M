// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/**
 * O DualSense como o console o quer: gatilhos, barra de luz, LEDs de jogador e
 * as intensidades escolhidas nos ajustes do PS5.
 *
 * Tudo isso ja vinha pela rede -- o patch 0030 so passou a entregar ao Kotlin
 * -- e tudo cabe no mesmo relatorio 0x31. Por isso e uma peca so, com uma
 * thread so: dois donos escrevendo no controle cada um o seu pedaco dariam a
 * vez um ao outro, e o relatorio de um apagaria a licenca do outro.
 *
 * Tres regras sairam da pesquisa e do que ja foi pago neste projeto:
 *
 *  - **Gatilho so se manda quando muda.** O efeito fica no controle ate ser
 *    trocado, e reenviar um efeito de arma no meio do aperto o reiniciaria.
 *    Um relatorio sem os bits de gatilho em `valid_flag0` nao mexe neles.
 *  - **Barra de luz se reescreve a cada meio segundo.** O controle volta ao
 *    azul quando o fluxo para (AGENTS.md, a receita da dev.141). Eram 50 ms,
 *    vinte relatorios por segundo na mesma fila da haptica, e o dono pediu
 *    menos (dev.240): cor nova do console sai na hora de qualquer jeito (o
 *    `cor()` acorda o laco), entao a cadencia so decide quanto tempo o azul
 *    do sistema fica visivel se ele passar por cima.
 *  - **Ao sair, os gatilhos voltam ao neutro.** Sem isso o ultimo efeito do
 *    jogo fica no controle depois de fechar o app -- a armadilha mais citada
 *    em quem ja fez isto no PC.
 *
 * Os gatilhos so chegam quando o cliente se anuncia como DualSense; a barra,
 * o jogador e as intensidades chegam nos dois anuncios.
 */
class EspelhoDualSense(
	private val context: Context,
	private val hid: DualSenseHid,
	gatilhosLigados: Boolean,
	private val luzesLigadas: Boolean)
{
	companion object
	{
		private const val CADENCIA_LUZES_MS = 500L
		private const val ESPERA_OCIOSA_MS = 1_000L
		// LEDs de jogador e brilho, sem barra para reescrever: reforco a cada
		// dois segundos, e nao na cadencia da barra. Se o sistema os desfizer como faz
		// com a barra, o diario mostra; se nao, e quase trafego nenhum.
		private const val REFORCO_INDICADORES_MS = 2_000L
		// Sem sinal de vida da trilha por tanto tempo, os motores param.
		private const val VIGIA_MOTOR_MS = 300L
		// O controle pode estar dormindo ou reconectando quando a sessao abre.
		private const val NOVA_TENTATIVA_MS = 2_000L
		// Uma rajada de efeitos do console vira no maximo um envio por
		// intervalo: o controle so sente o ultimo, e a fila do Bluetooth e a
		// mesma da haptica.
		private const val INTERVALO_MINIMO_MS = 8L

		/** Modo 0x05 com parametros zerados: o "desligado" oficial. */
		private val NEUTRO = ByteArray(11).also { it[0] = 0x05 }

		// A escala do protocolo: 0 desligado, 1 forte, 2 media, 3 fraca.
		const val INTENSIDADE_DESLIGADA = 0
		const val INTENSIDADE_FORTE = 1

		// A intensidade do PS5 escala a forca dentro do efeito (ForcaGatilhos),
		// e "desligado" para de repassar. Ela NAO vai ao byte 39 (reducao de
		// potencia): as fontes discordam sobre qual nibble e dos gatilhos e
		// qual e da vibracao, e na dev.199 -- com o PS5 em gatilhos "media" --
		// o dono sentiu a vibracao pior. Escrever o nibble errado corta a
		// bobina sem aviso nenhum.
		private fun nomeIntensidade(i: Int) = when(i) { 0 -> "off"; 1 -> "strong"; 2 -> "medium"; 3 -> "weak"; else -> "unknown ($i)" }

		// LEDs de jogador como o PS5 os acende: um no centro, dois, tres,
		// quatro. Os mesmos padroes do driver do kernel e do SDL.
		// O console conta de zero: a dev.200 recebeu 0 com um jogador so.
		private val LEDS_JOGADOR = intArrayOf(0x04, 0x0A, 0x15, 0x1B, 0x1F)
		// A cor do sistema de cada jogador, a que o PS5 poe quando o jogo nao
		// escolhe nenhuma: azul, vermelho, verde, rosa.
		private val CORES_DO_SISTEMA = intArrayOf(0x0000FF, 0xFF0000, 0x00FF00, 0xFF00C0)
		// Marca em `cor`: o console mandou preto, vale a cor do sistema.
		private const val COR_DO_SISTEMA = -2
		private fun corDoSistema(jogador: Int) =
			CORES_DO_SISTEMA[if(jogador in CORES_DO_SISTEMA.indices) jogador else 0]
		private fun nomeBrilho(b: Int) = when(b) { 0 -> "bright"; 1 -> "medium"; 2 -> "dim"; else -> "unknown ($b)" }

		/**
		 * Bancada dos gatilhos, sem console: um efeito conhecido em cada um.
		 *
		 * O esquerdo resiste a partir da metade (Feedback, 0x21); o direito
		 * estala como gatilho de arma (Weapon, 0x25). Os dois formatos sao os
		 * da tabela do Nielk1, a mesma que DSX e companhia usam. Se o dedo
		 * sentir, o caminho app -> controle esta provado, e o que faltar numa
		 * sessao e o console nao mandar.
		 *
		 * **Bloqueia** por uns dez segundos. Quem chama roda em thread propria.
		 */
		fun ensaiar(context: Context, hid: DualSenseHid): String
		{
			val linhas = StringBuilder()
			if(!hid.conectar())
				return "The DualSense bridge did not open.\n\n" + hid.texto()
			if(!hid.alvoEDualSense())
				return "The HID device found is not a DualSense; nothing was sent.\n\n" + hid.texto()

			val efeito = feedback(4, 7) + arma(3, 6, 8)
			val sentA = hid.espelhar(efeito, -1, -1, -1)
			linhas.append("Effects sent: ${if(sentA) "yes" else "REFUSED"}\n")
			Trace.log(context, "Trigger bench: left feedback from 40%, right weapon 30-60%: " +
					if(sentA) "sent" else "refused")
			try { Thread.sleep(4_000) } catch(e: InterruptedException) { }
			// Um reenvio no meio: se o controle soltar o efeito sozinho, a
			// segunda metade ainda da para sentir, e o relato diz qual foi.
			val sentB = hid.espelhar(efeito, -1, -1, -1)
			linhas.append("Sent again at 4 s: ${if(sentB) "yes" else "REFUSED"}\n")
			try { Thread.sleep(4_000) } catch(e: InterruptedException) { }
			val cleared = hid.espelhar(NEUTRO + NEUTRO, -1, -1, -1)
			linhas.append("Triggers back to neutral: ${if(cleared) "yes" else "REFUSED"}\n")
			Trace.log(context, "Trigger bench: back to neutral: " + if(cleared) "sent" else "refused")
			hid.fechar()
			return linhas.toString()
		}

		/**
		 * Resistencia continua a partir de uma posicao: `posicao` de 0 a 9 (a
		 * zona onde comeca), `forca` de 1 a 8.
		 */
		fun feedback(posicao: Int, forca: Int): ByteArray
		{
			val r = ByteArray(11)
			val valor = (forca.coerceIn(1, 8) - 1) and 0x07
			var zonasAtivas = 0
			var forcas = 0L
			for(i in posicao.coerceIn(0, 9)..9)
			{
				forcas = forcas or (valor.toLong() shl (3 * i))
				zonasAtivas = zonasAtivas or (1 shl i)
			}
			r[0] = 0x21
			r[1] = (zonasAtivas and 0xFF).toByte()
			r[2] = ((zonasAtivas shr 8) and 0xFF).toByte()
			for(b in 0 until 4)
				r[3 + b] = ((forcas shr (8 * b)) and 0xFF).toByte()
			return r
		}

		/**
		 * Gatilho de arma: resiste entre `inicio` (2 a 7) e `fim` (inicio+1 a
		 * 8), e cede de uma vez -- o estalo. `forca` de 1 a 8.
		 */
		fun arma(inicio: Int, fim: Int, forca: Int): ByteArray
		{
			val r = ByteArray(11)
			val a = inicio.coerceIn(2, 7)
			val b = fim.coerceIn(a + 1, 8)
			val zonas = (1 shl a) or (1 shl b)
			r[0] = 0x25
			r[1] = (zonas and 0xFF).toByte()
			r[2] = ((zonas shr 8) and 0xFF).toByte()
			r[3] = (forca.coerceIn(1, 8) - 1).toByte()
			return r
		}
	}

	private val trava = java.lang.Object()
	private var thread: Thread? = null
	@Volatile private var rodando = false
	@Volatile private var aberto = false

	/**
	 * Se ha um DualSense do outro lado. Sem ele, os motores entregues aqui nao
	 * vao a lugar nenhum -- na dev.231 o modo motor com um 8BitDo mandou a
	 * sessao inteira para um espelho que nunca abriu.
	 */
	val conectado: Boolean get() = aberto

	// Estado pedido pelo console. So a thread de eventos escreve, so a do
	// espelho le, e as duas sob a trava.
	private var efeito: ByteArray? = null
	private var gatilhosPendentes = true   // o primeiro envio limpa sobra de sessao anterior
	private var gatilhosLigadosAgora = gatilhosLigados
	private var intensidadeGatilhos = INTENSIDADE_FORTE
	private var cor = -1
	private var jogador = -1
	private var brilho = -1
	private var indicadoresPendentes = false
	private var ultimoIndicadores = 0L
	// So a thread do espelho usa. Ver DualSenseHid.soltarBarraDoFirmware.
	private var barraSolta = false

	/**
	 * Vibracao classica pelo espelho, e nao pelo driver do Android.
	 *
	 * Na dev.207 o dono sentiu o modo motor fraco com a barra seguindo o
	 * console, e sem gatilhos: o driver do Android e o espelho escreviam o
	 * mesmo relatorio 0x31, cada um com o seu estado, e um desfazia o outro.
	 * Ligado, todo relatorio do espelho leva os motores atuais -- um escritor
	 * so, como o chiaki-ng faz no PC. Definir antes do start().
	 */
	@Volatile var motoresPeloEspelho = false
	private var motorEsquerdo = 0
	private var motorDireito = 0
	private var motoresPendentes = false
	private var ultimoMotorMs = 0L
	private val soltosPelaVigia = AtomicInteger()

	// Para o diario. Sobem nas threads do espelho e dos eventos e sao lidos e
	// zerados pelo tick, na principal.
	private val efeitosRecebidos = AtomicInteger()
	private val mandados = AtomicInteger()
	private val recusados = AtomicInteger()

	val gatilhosLigados: Boolean get() = synchronized(trava) { gatilhosLigadosAgora }

	fun start()
	{
		if(rodando)
			return
		rodando = true
		thread = Thread({ laco() }, "p5m-dualsense-mirror").also { it.start() }
	}

	/**
	 * Stream pausado (headset fora da cabeca, app em segundo plano). A barra
	 * deixa de ser reescrita a 20 Hz e, sem DualSense, a ponte deixa de ser
	 * procurada: nos dois casos era Bluetooth e diario gastos com ninguem
	 * olhando. Gatilhos e o que o console pedir no meio da pausa continuam
	 * saindo; na volta, a barra retoma na proxima volta do laco.
	 */
	@Volatile private var pausado = false

	fun pausar()
	{
		pausado = true
	}

	fun retomar()
	{
		pausado = false
		synchronized(trava) { trava.notifyAll() }
	}

	fun stop()
	{
		if(!rodando)
			return
		rodando = false
		synchronized(trava) { trava.notifyAll() }
		thread?.interrupt()
		thread?.join(1_000)
		thread = null
	}

	// ------------------------------------------------ o que o console pede

	/** Intensidades de vibracao 0-255, ja com a intensidade do PS5 aplicada. */
	fun motores(esquerdo: Int, direito: Int)
	{
		synchronized(trava)
		{
			ultimoMotorMs = System.currentTimeMillis()
			if(esquerdo == motorEsquerdo && direito == motorDireito)
				return
			motorEsquerdo = esquerdo.coerceIn(0, 255)
			motorDireito = direito.coerceIn(0, 255)
			motoresPendentes = true
			trava.notifyAll()
		}
	}

	fun gatilhos(direito: ByteArray, esquerdo: ByteArray)
	{
		synchronized(trava)
		{
			efeito = direito + esquerdo
			efeitosRecebidos.incrementAndGet()
			gatilhosPendentes = true
			trava.notifyAll()
		}
	}

	fun intensidadeGatilhos(i: Int)
	{
		Trace.log(context, "PS5 trigger effect intensity: ${nomeIntensidade(i)}")
		synchronized(trava)
		{
			intensidadeGatilhos = i
			gatilhosPendentes = true
			trava.notifyAll()
		}
	}

	/**
	 * So registra: quem aplica a intensidade da vibracao e a activity, na
	 * amplitude da haptica e do motor. Ver a nota sobre o byte 39 no companion.
	 */
	fun intensidadeVibracao(i: Int)
	{
		Trace.log(context, "PS5 vibration intensity: ${nomeIntensidade(i)}")
	}

	fun cor(vermelho: Int, verde: Int, azul: Int)
	{
		Trace.log(context, "Console lightbar colour: %d %d %d".format(vermelho, verde, azul))
		// Preto e o que o console manda quando o jogo nao escolheu cor nenhuma
		// -- ao abrir a sessao e ao voltar de um jogo que tinha cor. O PS5 poe
		// a cor do sistema do jogador; ignorar o preto deixava a barra presa na
		// cor do jogo anterior (dev.202: vermelho que ficou ao trocar de jogo).
		synchronized(trava)
		{
			cor = if(vermelho == 0 && verde == 0 && azul == 0) COR_DO_SISTEMA
				else (vermelho shl 16) or (verde shl 8) or azul
			trava.notifyAll()
		}
	}

	fun jogador(indice: Int)
	{
		Trace.log(context, "Console player index: $indice")
		synchronized(trava)
		{
			jogador = indice
			indicadoresPendentes = true
			trava.notifyAll()
		}
	}

	/** O brilho dos indicadores escolhido no PS5 (byte 14 do pacote de estado). */
	fun brilho(nivel: Int)
	{
		Trace.log(context, "PS5 controller indicator brightness: ${nomeBrilho(nivel)}")
		synchronized(trava)
		{
			brilho = nivel
			indicadoresPendentes = true
			trava.notifyAll()
		}
	}

	/** Liga ou desliga os gatilhos na hora, pelo painel ou pelo lancador. */
	fun ligarGatilhos(ligar: Boolean)
	{
		synchronized(trava)
		{
			if(gatilhosLigadosAgora == ligar)
				return
			gatilhosLigadosAgora = ligar
			gatilhosPendentes = true
			trava.notifyAll()
		}
		Trace.log(context, "Adaptive triggers turned ${if(ligar) "on" else "off"} in session")
	}

	/**
	 * A linha do diario da janela de dez segundos, do mesmo tick que escreve o
	 * `Input timing:`.
	 */
	fun janela(): String
	{
		if(!aberto)
			return "Controller mirror 10s: not running"
		val (c, j, ef, lig) = synchronized(trava) { Quad(cor, jogador, efeito, gatilhosLigadosAgora) }
		val b = synchronized(trava) { brilho }
		val modos = ef?.let { "R 0x%02X, L 0x%02X".format(it[0].toInt() and 0xFF, it[11].toInt() and 0xFF) } ?: "none yet"
		val linha = "Controller mirror 10s: triggers ${if(lig) "on" else "off"}, " +
				"${efeitosRecebidos.getAndSet(0)} effect(s) from the console (last $modos), " +
				"lightbar ${if(!luzesLigadas) "not mirrored" else if(c == COR_DO_SISTEMA) "system colour #%06X".format(corDoSistema(j))
					else if(c < 0) "no colour yet" else "#%06X".format(c)}, " +
				"player ${if(j < 0) "?" else j}, brightness ${if(b < 0) "?" else nomeBrilho(b)}, " +
				"${mandados.getAndSet(0)} sent, " +
				"${recusados.getAndSet(0)} refused" +
				(if(motoresPeloEspelho) ", rumble through the mirror" +
					(soltosPelaVigia.getAndSet(0).takeIf { it > 0 }?.let { ", released $it time(s) after the track stopped" } ?: "")
				else "")
		return linha
	}

	private data class Quad(val a: Int, val b: Int, val c: ByteArray?, val d: Boolean)

	// ------------------------------------------------------------- a thread

	private fun laco()
	{
		try
		{
			// Tenta de novo em vez de desistir: na dev.200 uma sessao abriu com
			// o controle fora do HID naquele instante, e o espelho ficou morto
			// a sessao inteira com o controle de volta segundos depois.
			var tentativas = 0
			while(rodando)
			{
				if(pausado)
				{
					Thread.sleep(NOVA_TENTATIVA_MS)
					continue
				}
				val ponte = hid.conectar()
				if(ponte && hid.alvoEDualSense())
					break
				if(tentativas++ == 0)
					Trace.log(context, "Controller mirror: no DualSense on the HID bridge yet; " +
							"trying again every ${NOVA_TENTATIVA_MS / 1000} s")
				hid.fechar()
				// Depois de meio minuto sem controle, mais devagar: cada tentativa
				// escreve umas linhas no diario. E com a ponte aberta sobre outro
				// controle (8BitDo, Xbox), bem mais devagar: quem joga sem
				// DualSense pagava a sessao inteira um proxy de Bluetooth, uma
				// varredura por reflexao e seis linhas de diario a cada 2 s, com
				// os avisos chegando pela thread principal.
				Thread.sleep(when
				{
					ponte -> 15 * NOVA_TENTATIVA_MS
					tentativas < 15 -> NOVA_TENTATIVA_MS
					else -> 5 * NOVA_TENTATIVA_MS
				})
			}
			if(!rodando)
				return
			aberto = true
			Trace.log(context, "Controller mirror: on (triggers ${if(gatilhosLigados) "on" else "off"}, " +
					"lightbar ${if(luzesLigadas) "follows the console" else "left to the system"})")

			while(rodando)
			{
				var gatilhos: ByteArray? = null
				var corAgora = -1
				var ledsAgora = -1
				var brilhoAgora = -1
				var motoresAgora = -1
				var mandarMotores = false
				synchronized(trava)
				{
					val luzesAtivas = luzesLigadas && cor != -1 && !pausado
					val indicadoresConhecidos = luzesLigadas && !pausado && (jogador >= 0 || brilho >= 0)
					val vibrando = motoresPeloEspelho && (motorEsquerdo > 0 || motorDireito > 0)
					if(!gatilhosPendentes && !indicadoresPendentes && !motoresPendentes && rodando)
						trava.wait(if(luzesAtivas) CADENCIA_LUZES_MS
							else if(vibrando) VIGIA_MOTOR_MS / 3
							else if(indicadoresConhecidos) REFORCO_INDICADORES_MS
							else ESPERA_OCIOSA_MS)

					// A mesma vigia do Rumble: no modo motor o fim de um efeito
					// nao manda zero, a trilha so para (patch 0032).
					if(vibrando && Rumble.vigiarTrilha &&
							System.currentTimeMillis() - ultimoMotorMs > VIGIA_MOTOR_MS)
					{
						motorEsquerdo = 0
						motorDireito = 0
						motoresPendentes = true
						if(soltosPelaVigia.incrementAndGet() == 1)
							Trace.log(context, "Controller mirror: the console's haptic track stopped " +
									"without a zero; motors released after $VIGIA_MOTOR_MS ms")
					}
					if(motoresPeloEspelho)
						motoresAgora = (motorEsquerdo shl 8) or motorDireito
					mandarMotores = motoresPendentes
					motoresPendentes = false

					if(gatilhosPendentes)
					{
						val repassar = gatilhosLigadosAgora &&
								intensidadeGatilhos != INTENSIDADE_DESLIGADA
						gatilhos = (if(repassar) efeito?.let {
								ForcaGatilhos.escalar(it, ForcaGatilhos.fator(intensidadeGatilhos))
							} else null) ?: (NEUTRO + NEUTRO)
						gatilhosPendentes = false
					}
					if(luzesLigadas && cor != -1 && !pausado)
						corAgora = if(cor == COR_DO_SISTEMA) corDoSistema(jogador) else cor
					// Jogador e brilho vao junto com a barra, quando mudam, e num
					// reforco lento quando nao ha barra para reescrever.
					val agora = System.currentTimeMillis()
					if(luzesLigadas && (corAgora >= 0 || indicadoresPendentes ||
							(!pausado && agora - ultimoIndicadores >= REFORCO_INDICADORES_MS)))
					{
						if(jogador in LEDS_JOGADOR.indices)
							ledsAgora = LEDS_JOGADOR[jogador]
						brilhoAgora = brilho
						if(ledsAgora >= 0 || brilhoAgora >= 0)
							ultimoIndicadores = agora
					}
					indicadoresPendentes = false
				}
				if(!rodando)
					break
				if(gatilhos == null && corAgora < 0 && ledsAgora < 0 && brilhoAgora < 0 && !mandarMotores)
					continue

				if(corAgora >= 0 && !barraSolta)
				{
					barraSolta = true
					val ok = hid.soltarBarraDoFirmware()
					Trace.log(context, "Controller mirror: lightbar released from the firmware " +
							"(light out) before the first colour: " + if(ok) "sent" else "refused")
					Thread.sleep(300)
				}

				if(hid.espelhar(gatilhos, corAgora, ledsAgora, -1, brilhoAgora, motoresAgora))
					mandados.incrementAndGet()
				else
				{
					recusados.incrementAndGet()
					// O que nao foi precisa ir de novo; e se o controle voltou
					// de uma reconexao, ele voltou com os gatilhos no neutro.
					synchronized(trava) { gatilhosPendentes = true; indicadoresPendentes = true; motoresPendentes = true }
					// Duas quedas diferentes. Controle desligado com o proxy de pe:
					// `recuperar` acha o controle quando ele volta. Proxy derrubado
					// pelo sistema: `recuperar` nao tem com o que procurar, e so
					// reabrir a ponte resolve -- sem isto o espelho mandava para o
					// nada ate o fim da sessao (apontado na revisao de 24/09).
					val voltou = if(hid.ponteAberta) hid.recuperar() else
					{
						hid.fechar()
						hid.conectar() && hid.alvoEDualSense()
					}
					if(voltou)
					{
						// Controle que volta vem com a barra de novo no firmware
						// e os gatilhos no neutro.
						barraSolta = false
						Trace.log(context, "Controller mirror: DualSense back; sending its state again")
					}
					Thread.sleep(if(hid.ponteAberta) 250L else NOVA_TENTATIVA_MS)
					continue
				}
				if(gatilhos != null)
					try { Thread.sleep(INTERVALO_MINIMO_MS) } catch(e: InterruptedException) { }
			}
		}
		catch(e: InterruptedException) { }
		finally
		{
			// O stop() interrompe esta thread; a marca de interrupcao nao pode
			// atrapalhar o ultimo envio.
			Thread.interrupted()
			// Os gatilhos voltam ao neutro antes de a ponte fechar.
			if(aberto)
			{
				val ok = hid.espelhar(NEUTRO + NEUTRO, -1, -1, -1, -1, if(motoresPeloEspelho) 0 else -1)
				Trace.log(context, "Controller mirror: off, triggers back to neutral: " +
						if(ok) "sent" else "refused")
			}
			aberto = false
			hid.fechar()
		}
	}
}
