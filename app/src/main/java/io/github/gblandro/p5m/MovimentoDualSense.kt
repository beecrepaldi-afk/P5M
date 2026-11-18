// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.InputDevice
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.metallic.chiaki.session.StreamInput
import kotlin.math.sqrt

/**
 * O giroscopio e o acelerometro do DualSense, a caminho do console.
 *
 * O chiaki Android le os sensores padrao do aparelho, o que num celular e o
 * certo: o celular e o controle. No Quest o aparelho e o headset, entao a mira
 * de um jogo com giroscopio seguia a cabeca do jogador -- ou nada, se o
 * sistema nao expusesse o sensor. O DualSense se declara com SOURCE_SENSOR, e
 * desde a API 31 o Android entrega os sensores dele por
 * `InputDevice.getSensorManager()`, ja em unidades do SI.
 *
 * Os eixos sao os mesmos que o SDL entrega ao chiaki-ng no PC: os dois vem do
 * relatorio cru do controle, sem troca de eixo nem de sinal (o driver do kernel
 * repassa x, y e z na ordem do relatorio, e o Android so converte a unidade).
 * A orientacao sai do mesmo Madgwick do chiaki (lib/src/orientation.c),
 * portado aqui, com o mesmo zero de aceleracao que o console pode pedir.
 */
/**
 * [diarioProprio]: escreve sozinho a linha de dez segundos no diario. A tela
 * imersiva ja faz isso no resumo de saude dela e passa false; o modo janela nao
 * tem resumo nenhum, e sem esta linha nao dava para saber se o controle fica
 * lento durante a janela -- a suspeita da dev.206 e da dev.209, em que o enlace
 * lento aparecia logo na sessao seguinte a uma janela.
 */
class MovimentoDualSense(private val context: Context, private val input: StreamInput,
		private val diarioProprio: Boolean = true)
	: DefaultLifecycleObserver, SensorEventListener
{
	private var gerente: SensorManager? = null
	private var dispositivo: String? = null
	private var ligado = false
	// Os sensores chegam numa thread so deles. Juntos passam de 400 eventos
	// por segundo, e na principal cada um disputava lugar com os botoes e o
	// ciclo de vida: qualquer engasgo da interface atrasava a mira.
	private var threadSensores: android.os.HandlerThread? = null

	/**
	 * Movimento para o console, escolhido no lancador (Motion controls),
	 * desligado de fabrica. Desligado, os sensores continuam sendo lidos, mas
	 * so contados: a contagem e o termometro do enlace Bluetooth (ver
	 * [giroNaUltimaJanela]) e o aviso de enlace lento depende dela. O filtro de
	 * orientacao e o envio ao console, que sao o custo, ficam de fora.
	 */
	private val enviar = StreamQualityPrefs(context).motionControls

	// A ultima leitura pronta para o console, e se o envio ja esta na fila da
	// principal. O StreamInput do chiaki so e tocado pela principal (botoes,
	// analogicos); o movimento entra por ela tambem, sem virar a segunda
	// thread a mexer no estado do controle. Um Runnable so, reaproveitado:
	// leituras que chegam enquanto ele espera so atualizam os numeros.
	private val saida = FloatArray(10)
	private var envioNaFila = false
	private val enviarAgora = Runnable {
		val v = FloatArray(10)
		synchronized(trava)
		{
			saida.copyInto(v)
			envioNaFila = false
		}
		input.p5mMovimento(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9])
	}

	// Tudo abaixo so e tocado na thread dos sensores, menos o zerar(), que
	// entra pela trava.
	private val trava = Any()
	private var gx = 0f; private var gy = 0f; private var gz = 0f
	private var ax = 0f; private var ay = 1f; private var az = 0f
	private var realX = 0f; private var realY = 1f; private var realZ = 0f
	private var zeroX = 0f; private var zeroY = 0f; private var zeroZ = 0f
	private val orientacao = Madgwick()
	private var ultimoEnvioNs = 0L

	// Para o diario.
	@Volatile private var amostrasGiro = 0
	@Volatile private var amostrasAcel = 0
	@Volatile private var primeiraAnotada = false

	/**
	 * Controle religado no meio da sessao volta como outro InputDevice, e os
	 * sensores do antigo morrem calados: a dev.206 ficou com 0 amostras ate o
	 * fim da sessao depois de o dono desligar e ligar o DualSense. O aviso de
	 * dispositivo do chiaki nao chegava aqui; o do proprio Android chega.
	 */
	private val principal = Handler(Looper.getMainLooper())
	private val aoMudarDispositivo = object: InputManager.InputDeviceListener
	{
		override fun onInputDeviceAdded(deviceId: Int) = agendarReligar()
		override fun onInputDeviceChanged(deviceId: Int) {}
		override fun onInputDeviceRemoved(deviceId: Int) = agendarReligar()
	}
	private val religarAgora = Runnable { religar() }

	private val diario = object: Runnable
	{
		override fun run()
		{
			Trace.log(context, janela())
			val g = giroNaUltimaJanela
			// Mesma regua do aviso da tela imersiva (VrStreamActivity).
			if(g in 200 until 1300)
				Trace.log(context, "Controller link: reduced input rate ($g motion samples in 10 s, " +
						"about 2000 when healthy)")
			principal.postDelayed(this, 10_000L)
		}
	}

	private fun agendarReligar()
	{
		// Meio segundo: o controle recem-chegado ainda esta montando os
		// dispositivos irmaos (touchpad, sensores) quando o primeiro aviso sai.
		principal.removeCallbacks(religarAgora)
		principal.postDelayed(religarAgora, 500)
	}

	override fun onResume(owner: LifecycleOwner)
	{
		(context.getSystemService(Context.INPUT_SERVICE) as InputManager)
			.registerInputDeviceListener(aoMudarDispositivo, principal)
		ligar()
		if(diarioProprio)
		{
			amostrasGiro = 0
			amostrasAcel = 0
			principal.removeCallbacks(diario)
			principal.postDelayed(diario, 10_000L)
		}
	}

	override fun onPause(owner: LifecycleOwner)
	{
		(context.getSystemService(Context.INPUT_SERVICE) as InputManager)
			.unregisterInputDeviceListener(aoMudarDispositivo)
		principal.removeCallbacks(religarAgora)
		principal.removeCallbacks(diario)
		desligar()
	}

	/** Procura o controle de novo: ele pode ter chegado depois da sessao. */
	fun religar()
	{
		desligar()
		ligar()
	}

	fun ligar()
	{
		if(ligado)
			return
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
		{
			Trace.log(context, "Controller motion: needs Android 12 for the controller's own sensors")
			return
		}
		val controle = acharControle()
		if(controle == null)
		{
			Trace.log(context, "Controller motion: no gamepad with motion sensors connected")
			return
		}
		val sm = controle.sensorManager
		val acel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
		val giro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
		if(acel == null || giro == null)
		{
			Trace.log(context, "Controller motion: '${controle.name}' has no " +
					(if(acel == null) "accelerometer " else "") + (if(giro == null) "gyroscope" else ""))
			return
		}
		val t = android.os.HandlerThread("p5m-movimento").also { it.start() }
		threadSensores = t
		val h = Handler(t.looper)
		sm.registerListener(this, acel, PERIODO_US, h)
		sm.registerListener(this, giro, PERIODO_US, h)
		gerente = sm
		dispositivo = controle.name
		ligado = true
		Trace.log(context, "Controller motion: reading '${controle.name}' " +
				"(accel '${acel.name}' range ${acel.maximumRange}, gyro '${giro.name}' range ${giro.maximumRange})" +
				if(enviar) "" else "; counted only, motion controls are off")
	}

	fun desligar()
	{
		if(!ligado)
			return
		gerente?.unregisterListener(this)
		gerente = null
		threadSensores?.quitSafely()
		threadSensores = null
		principal.removeCallbacks(enviarAgora)
		synchronized(trava) { envioNaFila = false }
		ligado = false
	}

	/**
	 * O console pediu para zerar: a posicao atual do controle vira a de
	 * repouso. O mesmo que o chiaki-ng faz com o evento MOTION_RESET.
	 */
	fun zerar()
	{
		synchronized(trava)
		{
			zeroX = realX
			zeroY = realY - 1f
			zeroZ = realZ
			orientacao.reiniciar()
		}
		Trace.log(context, "Controller motion: the console asked for a motion reset")
	}

	/**
	 * Amostras de giroscopio na ultima janela de dez segundos, ou -1.
	 *
	 * E tambem o termometro do enlace Bluetooth: o controle manda um relatorio
	 * por intervalo de conexao. A dev.204 (enlace em sniff, ~11 ms) teve ~850;
	 * a dev.205, com o controle religado, ~2000. Em sniff a haptica atrasa.
	 */
	@Volatile var giroNaUltimaJanela = -1
		private set

	/** A linha do diario, a cada dez segundos. */
	fun janela(): String
	{
		if(!ligado)
			return "Controller motion 10s: off"
		val g = amostrasGiro; amostrasGiro = 0
		giroNaUltimaJanela = g
		val a = amostrasAcel; amostrasAcel = 0
		val (ox, oy, oz, ow) = synchronized(trava) { orientacao.paraOConsole() }
		return "Controller motion 10s: $g gyro and $a accel samples from '$dispositivo', " +
				"gyro %.2f %.2f %.2f rad/s, accel %.2f %.2f %.2f g, orient %.2f %.2f %.2f %.2f"
					.format(gx, gy, gz, ax, ay, az, ox, oy, oz, ow)
	}

	override fun onSensorChanged(event: SensorEvent)
	{
		val v = event.values
		if(!enviar)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> amostrasAcel++
				Sensor.TYPE_GYROSCOPE -> amostrasGiro++
			}
			return
		}
		val microssegundos = event.timestamp / 1000L
		synchronized(trava)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER ->
				{
					amostrasAcel++
					realX = v[0] / SensorManager.GRAVITY_EARTH
					realY = v[1] / SensorManager.GRAVITY_EARTH
					realZ = v[2] / SensorManager.GRAVITY_EARTH
					ax = realX - zeroX
					ay = realY - zeroY
					az = realZ - zeroZ
					orientacao.atualizar(gx, gy, gz, ax, ay, az, microssegundos)
				}
				Sensor.TYPE_GYROSCOPE ->
				{
					amostrasGiro++
					gx = v[0]
					gy = v[1]
					gz = v[2]
					orientacao.atualizar(gx, gy, gz, ax, ay, az, microssegundos)
				}
				else -> return
			}
		}
		if(!primeiraAnotada)
		{
			primeiraAnotada = true
			Trace.log(context, "Controller motion: first sample, ${event.sensor.name} " +
					v.joinToString(" ") { "%.3f".format(it) })
		}
		// Os dois sensores juntos passam de 400 amostras por segundo, e cada
		// envio atravessa o JNI. O chiaki manda o estado ao console no maximo a
		// cada 8 ms; mais que isso aqui so gastaria CPU.
		val agora = System.nanoTime()
		if(agora - ultimoEnvioNs < INTERVALO_ENVIO_NS)
			return
		ultimoEnvioNs = agora
		val postar = synchronized(trava)
		{
			val (ox, oy, oz, ow) = orientacao.paraOConsole()
			saida[0] = gx; saida[1] = gy; saida[2] = gz
			saida[3] = ax; saida[4] = ay; saida[5] = az
			saida[6] = ox; saida[7] = oy; saida[8] = oz; saida[9] = ow
			!envioNaFila.also { envioNaFila = true }
		}
		if(postar)
			principal.post(enviarAgora)
	}

	override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

	private fun acharControle(): InputDevice?
	{
		val candidatos = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter {
			it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD &&
					it.sources and SOURCE_SENSOR == SOURCE_SENSOR
		}
		return candidatos.firstOrNull { it.name.contains("DualSense", ignoreCase = true) }
			?: candidatos.firstOrNull()
	}

	/** O Madgwick do chiaki, linha a linha, com o giro de 90 graus em x dele. */
	private class Madgwick
	{
		private var w = COS_1_4_PI; private var x = SIN_1_4_PI; private var y = 0f; private var z = 0f
		private var amostra = 0
		private var ultimoUs = 0L

		fun reiniciar()
		{
			w = COS_1_4_PI; x = SIN_1_4_PI; y = 0f; z = 0f
			amostra = 0
		}

		fun atualizar(gx: Float, gy: Float, gz: Float, ax0: Float, ay0: Float, az0: Float, us: Long)
		{
			amostra++
			if(amostra <= 1)
			{
				ultimoUs = us
				return
			}
			val passo = ((us - ultimoUs).coerceIn(0L, 100_000L)) / 1_000_000f
			ultimoUs = us
			val beta = if(amostra < AQUECIMENTO) BETA_AQUECIMENTO else BETA
			var q0 = w; var q1 = x; var q2 = y; var q3 = z
			var d1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
			var d2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
			var d3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
			var d4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)
			if(!(ax0 == 0f && ay0 == 0f && az0 == 0f))
			{
				val n = 1f / sqrt(ax0 * ax0 + ay0 * ay0 + az0 * az0)
				val ax = ax0 * n; val ay = ay0 * n; val az = az0 * n
				val _2q0 = 2f * q0; val _2q1 = 2f * q1; val _2q2 = 2f * q2; val _2q3 = 2f * q3
				val _4q0 = 4f * q0; val _4q1 = 4f * q1; val _4q2 = 4f * q2
				val _8q1 = 8f * q1; val _8q2 = 8f * q2
				val q0q0 = q0 * q0; val q1q1 = q1 * q1; val q2q2 = q2 * q2; val q3q3 = q3 * q3
				var s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay
				var s1 = _4q1 * q3q3 - _2q3 * ax + 4f * q0q0 * q1 - _2q0 * ay - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az
				var s2 = 4f * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az
				var s3 = 4f * q1q1 * q3 - _2q1 * ax + 4f * q2q2 * q3 - _2q2 * ay
				val m = s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3
				if(m > 0.000001f)
				{
					val r = 1f / sqrt(m)
					s0 *= r; s1 *= r; s2 *= r; s3 *= r
					d1 -= beta * s0; d2 -= beta * s1; d3 -= beta * s2; d4 -= beta * s3
				}
			}
			q0 += d1 * passo; q1 += d2 * passo; q2 += d3 * passo; q3 += d4 * passo
			val r = 1f / sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
			q0 *= r; q1 *= r; q2 *= r; q3 *= r
			w = filtrar(q0, w); x = filtrar(q1, x); y = filtrar(q2, y); z = filtrar(q3, z)
		}

		/** O filtro de ruido do chiaki, igual ao do sistema de entrada do kernel. */
		private fun filtrar(atual: Float, anterior: Float): Float = when
		{
			atual < anterior + FUZZ / 2 && atual > anterior - FUZZ / 2 -> anterior
			atual < anterior + FUZZ && atual > anterior - FUZZ -> PESO * anterior + (1 - PESO) * atual
			atual < anterior + FUZZ * 2 && atual > anterior - FUZZ * 2 -> PESO_2X * anterior + (1 - PESO_2X) * atual
			else -> atual
		}

		/** x, y, z, w como o console espera: desfaz o giro de 90 graus em x. */
		fun paraOConsole(): FloatArray = floatArrayOf(
			COS_NEG_1_4_PI * x + SIN_NEG_1_4_PI * w,
			COS_NEG_1_4_PI * y - SIN_NEG_1_4_PI * z,
			COS_NEG_1_4_PI * z + SIN_NEG_1_4_PI * y,
			COS_NEG_1_4_PI * w - SIN_NEG_1_4_PI * x)
	}

	companion object
	{
		private const val SOURCE_SENSOR = 0x04000000
		private const val PERIODO_US = 4000
		private const val INTERVALO_ENVIO_NS = 4_000_000L

		private const val SIN_1_4_PI = 0.7071067811865475f
		private const val SIN_NEG_1_4_PI = -0.7071067811865475f
		private const val COS_1_4_PI = 0.7071067811865476f
		private const val COS_NEG_1_4_PI = 0.7071067811865476f
		private const val AQUECIMENTO = 30
		private const val BETA_AQUECIMENTO = 20f
		private const val BETA = 0.05f
		private const val FUZZ = 0.0007f
		private const val PESO = 0.75f
		private const val PESO_2X = 0.6f
	}
}
