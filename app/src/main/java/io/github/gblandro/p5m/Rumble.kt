// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.InputDevice

/**
 * Rota da vibração do console até o controle.
 *
 * Os eventos já chegavam do PS5 — o libchiaki os decodifica e os entrega como
 * `RumbleEvent` — e a activity imersiva os descartava. Nunca foi falta de dado;
 * era falta de destino.
 *
 * O destino é o vibrador do próprio gamepad, e não o do aparelho. Num celular
 * as duas coisas coincidem e o chiaki manda para o sistema; aqui o aparelho é
 * um headset na cabeça, e vibrar a cabeça de quem joga não é o que o jogo
 * pediu.
 *
 * ## Por que esta terceira versão existe
 *
 * A segunda perguntava a um controle só — o mesmo de onde vêm os analógicos — e
 * aceitava a primeira resposta como final. Num Quest isso erra por dois motivos
 * de uma vez: há sempre três gamepads presentes (o do jogador e os dois Touch
 * do headset), e o controle do jogador pode acordar **depois** da abertura do
 * stream. Um 8BitDo que respondeu "não tenho motor" aos dois segundos continuava
 * sendo o destino escolhido pelo resto da sessão.
 *
 * Esta versão varre todos, registra o que cada um respondeu, e volta a varrer
 * quando um evento chega sem ter para onde ir. Os Touch entram no diário mas
 * nunca viram destino: quem está com o 8BitDo na mão não quer a vibração no
 * controle que deixou no sofá.
 *
 * ## Por que disparo curto rearmado, e não uma onda que se repete
 *
 * O console não manda "vibre por tanto tempo": ele manda **a intensidade
 * agora**, e manda de novo quando ela muda — inclusive o zero que encerra. A
 * tradução natural disso é uma forma de onda que se repete, e era o que a
 * versão anterior fazia. Só que forma de onda com repetição é a parte da API
 * que os drivers HID genéricos menos implementam, e um driver que não a
 * implementa não devolve erro: ele simplesmente não vibra. Disparo de duração
 * fixa é o caminho que todo driver aceita.
 *
 * Para o disparo não morrer no meio de uma vibração que o jogo ainda pede, ele
 * é rearmado por um relógio próprio a três quartos da duração — sobreposição
 * suficiente para não abrir buraco, e cinco chamadas de binder por segundo em
 * vez das sessenta que os eventos do console trariam.
 */
class Rumble(private val enabled: Boolean)
{
	/** Um gamepad que respondeu ter motor, com o caminho por onde acioná-lo. */
	private class Destino(
		val nome: String,
		val manager: VibratorManager?,
		val ids: IntArray,
		val legacy: Vibrator?,
		val amplitudeControl: Boolean)

	private var destino: Destino? = null
	private var preferido: Int? = null
	private var ultimaVarreduraMs = 0L
	private var lastLeft = -1
	private var lastRight = -1
	private var loggedFirstEvent = false
	private var loggedFirstPlay = false

	// Relógio próprio: o rearme não pode disputar nem a thread principal (ANR)
	// nem a de eventos do chiaki (que também entrega vídeo).
	private var relogioThread: HandlerThread? = null
	private var relogio: Handler? = null

	private val rearme = object: Runnable
	{
		override fun run()
		{
			// A leitura do estado e curta e vai sob o lock; a chamada ao
			// sistema fica de fora. Vibrar e um binder ao servico do Android, e
			// segurar o lock durante ele faria o `set()` -- que vem da thread
			// de eventos do chiaki, dezenas de vezes por segundo -- esperar por
			// uma chamada de sistema que nao tem nada a ver com ele.
			val l: Int
			val r: Int
			synchronized(this@Rumble)
			{
				l = lastLeft
				r = lastRight
				if(l <= 0 && r <= 0)
					return
				relogio?.postDelayed(this, REARMAR_MS)
			}
			emitir(l, r, SUSTAIN_MS)
		}
	}

	@Synchronized
	fun attach(deviceId: Int?)
	{
		if(!enabled)
		{
			Log.i(TAG, "Rumble is turned off in the settings")
			return
		}
		preferido = deviceId

		val thread = HandlerThread("p5m-rumble")
		thread.start()
		relogioThread = thread
		relogio = Handler(thread.looper)

		val achado = varrer()
		destino = achado
		if(achado == null)
		{
			Log.i(TAG, "Rumble has no target: no connected gamepad exposes a motor to "
					+ "Android. The console keeps sending the events, and we will look "
					+ "again as soon as one arrives")
			return
		}

		Log.i(TAG, "Rumble target: '${achado.nome}', "
				+ "${if(achado.manager != null) "${achado.ids.size} motor(s)" else "1 motor"}, "
				+ "${if(achado.amplitudeControl) "variable" else "fixed"} strength")

		// Buzina de teste. Não é enfeite: sem ela, "o jogo não vibrou" e "a rota
		// não funciona" continuam indistinguíveis, e a diferença entre as duas
		// decide se vale mexer em mais alguma coisa deste lado.
		emitir(200, 200, BUZINA_MS, sustentado = false)
		Log.i(TAG, "Rumble: test buzz sent. If you felt nothing when the stream opened, "
				+ "the Bluetooth driver is not forwarding it to the pad")
	}

	/**
	 * Intensidades do console, de 0 a 255.
	 *
	 * Chamado da thread de eventos do chiaki, não da principal. Valor repetido é
	 * descartado antes de virar chamada de binder: durante vibração contínua os
	 * eventos chegam sem parar, e refazer o mesmo pedido dezenas de vezes por
	 * segundo trava a thread que também entrega vídeo.
	 */
	@Synchronized
	fun set(left: Int, right: Int)
	{
		if(!loggedFirstEvent)
		{
			loggedFirstEvent = true
			Log.i(TAG, "Rumble: first event from the console (left=$left right=$right)")
		}
		if(!enabled)
			return
		if(left == lastLeft && right == lastRight)
			return
		lastLeft = left
		lastRight = right

		if(destino == null)
		{
			// O controle pode ter acordado depois da abertura do stream, e um
			// evento de silêncio não é motivo para varrer o barramento inteiro.
			if(left == 0 && right == 0)
				return
			if(SystemClock.uptimeMillis() - ultimaVarreduraMs < REVARREDURA_MS)
				return
			val achado = varrer() ?: return
			destino = achado
			Log.i(TAG, "Rumble target found late: '${achado.nome}' — it was not exposing "
					+ "a motor when the stream opened")
		}

		if(left == 0 && right == 0)
		{
			// Cancela sem passar por stop(): aquele também zera o último valor
			// lido, e zerá-lo aqui desfaria a deduplicação que acabamos de
			// fazer -- cada evento de silêncio repetido viraria outra chamada de
			// binder, na thread que também entrega vídeo.
			relogio?.removeCallbacks(rearme)
			cancel()
			return
		}

		emitir(left, right, SUSTAIN_MS)
		relogio?.removeCallbacks(rearme)
		// So quem nao se sustenta precisa de relogio. Rearmar uma onda que ja
		// se repete e o que fazia o motor pulsar: cada reemissao reinicia o
		// efeito do zero.
		if(!sustentaSozinho())
			relogio?.postDelayed(rearme, REARMAR_MS)
	}

	/**
	 * Pergunta a todo gamepad presente o que ele tem, e escolhe um destino.
	 *
	 * A varredura inteira vai para o diário mesmo quando dá certo. É a única
	 * forma de responder, olhando um log colado por um testador, se o controle
	 * dele expõe motor ao Android — e essa é a pergunta que separa "o app não
	 * manda" de "o driver não entrega".
	 */
	private fun varrer(): Destino?
	{
		ultimaVarreduraMs = SystemClock.uptimeMillis()
		var escolhido: Destino? = null
		var escolhidoEhPreferido = false

		for(id in InputDevice.getDeviceIds())
		{
			val device = InputDevice.getDevice(id) ?: continue
			val fontes = device.sources
			val ehGamepad =
				fontes and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
				fontes and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
			if(!ehGamepad)
				continue

			// Os Touch do headset entram como "Device 0x...". Eles têm motor e
			// aceitariam o comando; o jogador só não está com eles na mão.
			val doHeadset = device.name.startsWith("Device 0x")
			val achado = probe(device)

			val resposta = when
			{
				achado == null -> "no motor exposed to Android"
				achado.manager != null -> "${achado.ids.size} motor(s) via vibrator manager, " +
						"${if(achado.amplitudeControl) "variable" else "fixed"} strength"
				else -> "1 motor via the legacy path, " +
						"${if(achado.amplitudeControl) "variable" else "fixed"} strength"
			}
			Log.i(TAG, "Rumble scan: '${device.name}' (id=$id) — $resposta" +
					if(doHeadset) " [headset controller, never used as a rumble target]" else "")

			if(achado == null || doHeadset || escolhidoEhPreferido)
				continue
			if(id == preferido)
			{
				escolhido = achado
				escolhidoEhPreferido = true
			}
			else if(escolhido == null)
				escolhido = achado
		}
		return escolhido
	}

	/** O que este dispositivo expõe, ou null se não expuser motor nenhum. */
	private fun probe(device: InputDevice): Destino?
	{
		var manager: VibratorManager? = null
		var ids = IntArray(0)
		var legacy: Vibrator? = null
		var amplitude = false

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
		{
			val found = device.vibratorManager
			val encontrados = found.vibratorIds
			if(encontrados.isNotEmpty())
			{
				manager = found
				ids = encontrados
				amplitude = encontrados.all { found.getVibrator(it).hasAmplitudeControl() }
			}
		}
		if(manager == null)
		{
			@Suppress("DEPRECATION")
			val single = device.vibrator
			if(single != null && single.hasVibrator())
			{
				legacy = single
				amplitude = single.hasAmplitudeControl()
			}
		}
		if(manager == null && legacy == null)
			return null
		return Destino(device.name, manager, ids, legacy, amplitude)
	}

	/**
	 * @param sustentado true para o comando que dura enquanto o jogo pedir,
	 *        false para um toque que tem de acabar sozinho. A buzina de teste
	 *        e o unico caso do segundo tipo, e ela precisa dele: com controle
	 *        de amplitude o comando sustentado e uma onda que se repete sem
	 *        fim, e a buzina ficaria girando o motor ate o fim da sessao.
	 */
	private fun emitir(left: Int, right: Int, durationMs: Long, sustentado: Boolean = true)
	{
		val alvo = destino ?: return
		val l = left.coerceIn(0, 255)
		val r = right.coerceIn(0, 255)
		try
		{
			val mgr = alvo.manager
			if(mgr != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
			{
				val combination = CombinedVibration.startParallel()
				for((index, id) in alvo.ids.withIndex())
				{
					// Qual id é qual motor o Android não diz, e a ordem é a que
					// o driver devolveu. Se em jogo o grave e o agudo saírem
					// trocados, é aqui que se inverte.
					val amplitude = when
					{
						alvo.ids.size < 2 -> maxOf(l, r)
						index == 0 -> l
						else -> r
					}
					combination.addVibrator(id, efeito(amplitude, durationMs, alvo, sustentado))
				}
				mgr.vibrate(combination.combine(), MEDIA_ATTRIBUTES)
			}
			else
			{
				// Num motor só, o maior dos dois e não a média: o console usa o
				// esquerdo para o grave e o direito para o agudo, e quase todo
				// efeito de jogo pesa num só. A média transformaria uma pancada
				// forte de um lado em meia pancada.
				legacyVibrate(alvo, maxOf(l, r), durationMs, sustentado)
			}

			if(!loggedFirstPlay)
			{
				loggedFirstPlay = true
				Log.i(TAG, "Rumble: the system accepted the command without error")
			}
		}
		catch(e: Exception)
		{
			// Uma vez: se o driver recusa, recusa sempre, e uma exceção por
			// evento afogaria o diário como já aconteceu com o framebuffer.
			if(!loggedFirstPlay)
			{
				loggedFirstPlay = true
				Log.e(TAG, "Rumble refused by the system: ${e.message}")
			}
		}
	}

	/**
	 * O caminho antigo, de um motor só.
	 *
	 * Com [AudioAttributes] e não com [VibrationAttributes]: a sobrecarga do
	 * [Vibrator] que aceita a segunda só existe da API 33 em diante, e o Quest 3
	 * roda Android 12L, que é a 32. Declarar o uso continua importando -- sem
	 * declaração nenhuma a vibração entra como uso desconhecido e o sistema pode
	 * filtrá-la em silêncio.
	 */
	@Suppress("DEPRECATION")
	private fun legacyVibrate(alvo: Destino, amplitude: Int, durationMs: Long,
			sustentado: Boolean)
	{
		alvo.legacy?.vibrate(efeito(amplitude, durationMs, alvo, sustentado), AUDIO_ATTRIBUTES)
	}

	/**
	 * A forma do comando, escolhida pelo que o driver sabe fazer.
	 *
	 * **Com controle de amplitude** (DualSense e qualquer driver decente):
	 * forma de onda que se repete, com amplitude constante. Vibra liso e sem
	 * fim até o próximo pedido substituí-la, que é exatamente o que o console
	 * está descrevendo -- ele manda "a intensidade agora", não "vibre por tanto
	 * tempo".
	 *
	 * **Sem controle de amplitude** (pad HID simples): disparo de duração fixa,
	 * rearmado pelo relógio. Onda que se repete é a parte da API que esses
	 * drivers menos implementam, e um driver que não a implementa não devolve
	 * erro -- ele simplesmente não vibra.
	 *
	 * A versão anterior usava o disparo rearmado para todo mundo, e foi um
	 * erro pago em hardware: no DualSense, um `createOneShot` novo a cada
	 * 150 ms reinicia o efeito, e o motor pulsa quase sete vezes por segundo em
	 * vez de girar liso. Pior: a troca tinha sido feita para o 8BitDo, que a
	 * varredura depois mostrou não expor motor nenhum -- não consertou nada e
	 * estragou o controle que funcionava.
	 */
	private fun efeito(amplitude: Int, durationMs: Long, alvo: Destino,
			sustentado: Boolean): VibrationEffect
	{
		val level = amplitude.coerceIn(1, 255)
		if(!alvo.amplitudeControl)
			return VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
		if(!sustentado)
			return VibrationEffect.createOneShot(durationMs, level)
		// Repetição a partir do índice 0: o primeiro passo tem duração zero, o
		// segundo é o que vibra, e o laço volta ao começo -- na prática,
		// amplitude constante para sempre.
		return VibrationEffect.createWaveform(
			longArrayOf(0, durationMs), intArrayOf(level, level), 0)
	}

	/** true quando o efeito se sustenta sozinho e não precisa de rearme. */
	private fun sustentaSozinho(): Boolean = destino?.amplitudeControl == true

	private fun cancel()
	{
		try
		{
			destino?.manager?.cancel()
			destino?.legacy?.cancel()
		}
		catch(e: Exception)
		{
			// Cancelar não pode derrubar nada, nem no meio do jogo nem na saída.
		}
	}

	/** Silencia, solta o relógio e esquece o último valor. Para o fim da sessão. */
	@Synchronized
	fun stop()
	{
		relogio?.removeCallbacks(rearme)
		cancel()
		relogioThread?.quitSafely()
		relogioThread = null
		relogio = null
		lastLeft = -1
		lastRight = -1
	}

	companion object
	{
		private const val TAG = "P5MVR"

		/**
		 * O gamepad que deve receber a vibração, ou null se não houver.
		 *
		 * A activity imersiva descobre isto de passagem, enquanto escreve a
		 * lista de dispositivos no diário. O modo janela é código do chiaki e
		 * não tem esse levantamento, mas precisa da mesma resposta -- e precisa
		 * dela pela mesma razão: o destino é o vibrador do controle, não o do
		 * aparelho.
		 *
		 * Mesma regra dos analógicos, para os dois modos não discordarem sobre
		 * qual controle é "o" controle: nomeado ganha de anônimo (os Touch do
		 * headset entram como "Device 0x..."), e o primeiro nomeado ganha dos
		 * demais.
		 */
		fun gamepadDeviceId(): Int?
		{
			var anonimo: Int? = null
			for(id in InputDevice.getDeviceIds())
			{
				val device = InputDevice.getDevice(id) ?: continue
				val isGamepad =
					device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
					device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
				if(!isGamepad)
					continue
				if(!device.name.startsWith("Device 0x"))
					return id
				if(anonimo == null)
					anonimo = id
			}
			return anonimo
		}

		// Duração de cada disparo. Longa o bastante para o rearme ter folga,
		// curta o bastante para o motor parar sozinho se o app morrer no meio.
		private const val SUSTAIN_MS = 200L

		// Três quartos da duração: a sobreposição é o que impede um buraco
		// audível entre um disparo e o seguinte.
		private const val REARMAR_MS = 150L

		private const val BUZINA_MS = 220L

		// Varrer o barramento de entrada não é de graça. Uma vez por segundo,
		// no máximo, e só enquanto não houver destino nenhum.
		private const val REVARREDURA_MS = 1_000L

		/**
		 * Uso declarado como mídia.
		 *
		 * Sem atributo a vibração entra como uso desconhecido, e o sistema pode
		 * filtrá-la conforme as preferências de feedback do usuário -- o que
		 * seria um comando engolido em silêncio, sem erro nenhum para investigar.
		 */
		// Pelo Builder, e nao por createForUsage: aquele so existe da API 33 em
		// diante, e o Quest 3 roda Android 12L, que e a 32.
		private val MEDIA_ATTRIBUTES: VibrationAttributes = VibrationAttributes.Builder()
			.setUsage(VibrationAttributes.USAGE_MEDIA)
			.build()

		/** O mesmo recado para o caminho antigo, que fala a língua da API 26. */
		private val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_GAME)
			.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
			.build()
	}
}
