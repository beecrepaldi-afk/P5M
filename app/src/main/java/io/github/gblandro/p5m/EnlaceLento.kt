// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.InputDevice

/**
 * Diz se o DualSense chegou em sniff, para o menu pedir que seja religado
 * ANTES da partida, e nao no meio dela.
 *
 * O [EnlaceAtivo] impede o sniff com o P5M aberto, mas nao desfaz: o controle
 * que ficou 30 s ligado com o P5M fechado ja chega lento, e so religar
 * resolve. A medida e o giroscopio do proprio controle, que manda o tempo todo
 * no ritmo do enlace: ~177 amostras/s em modo ativo e ~85/s em sniff (diarios
 * de 18/11/2026 e da dev.204/205). A linha fica em [LIMITE_HZ].
 */
internal class EnlaceLento(private val context: Context)
{
	companion object
	{
		private const val JANELA_MS = 2_000L
		private const val LIMITE_HZ = 130.0
		// InputDevice.SOURCE_SENSOR, so no SDK 31.
		private const val SOURCE_SENSOR = 0x04000000
	}

	/**
	 * Mede e devolve na thread principal: true em sniff, false em modo ativo,
	 * null sem controle com giroscopio (nada a dizer).
	 */
	fun medir(resultado: (Boolean?) -> Unit)
	{
		val principal = Handler(Looper.getMainLooper())
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
		{
			principal.post { resultado(null) }
			return
		}
		val controle = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
			.firstOrNull {
				it.name.contains("DualSense", ignoreCase = true) &&
						it.sources and SOURCE_SENSOR == SOURCE_SENSOR
			}
		val sm = controle?.sensorManager
		val giro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
		if(sm == null || giro == null)
		{
			principal.post { resultado(null) }
			return
		}
		val thread = HandlerThread("p5m-enlace-lento").also { it.start() }
		val h = Handler(thread.looper)
		var amostras = 0
		val ouvinte = object: SensorEventListener
		{
			override fun onSensorChanged(e: SensorEvent) { amostras++ }
			override fun onAccuracyChanged(s: Sensor, a: Int) {}
		}
		sm.registerListener(ouvinte, giro, 0, h)
		h.postDelayed({
			sm.unregisterListener(ouvinte)
			thread.quitSafely()
			val hz = amostras * 1000.0 / JANELA_MS
			// Zero amostras e sensor que nao abriu, nao enlace lento.
			val lento: Boolean? = if(amostras == 0) null else hz < LIMITE_HZ
			Trace.log(context, "Controller link check: ${"%.0f".format(java.util.Locale.ROOT, hz)} gyro samples/s " +
				"from '${controle.name}' -> " + when(lento) {
					true -> "slow (Bluetooth power saving); asking to turn the controller off and on"
					false -> "fast"
					null -> "no samples"
				})
			principal.post { resultado(lento) }
		}, JANELA_MS)
	}
}
