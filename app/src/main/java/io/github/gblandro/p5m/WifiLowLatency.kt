// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Pede ao Wi-Fi que abra mão de economizar energia enquanto o stream roda.
 *
 * Fora de uso intenso, o rádio Wi-Fi dorme entre pacotes e só acorda nos
 * intervalos de beacon do roteador. Para navegar isso é invisível; para Remote
 * Play é latência que entra e sai sem explicação, porque um pacote que chega
 * logo depois de o rádio dormir espera o próximo despertar.
 *
 * `WIFI_MODE_FULL_LOW_LATENCY` desliga esse comportamento e ainda pede ao
 * driver preferência por latência em vez de throughput. Existe desde o Android
 * 10 e é pouco usado — a maioria dos apps de streaming ainda pede o
 * `WIFI_MODE_FULL_HIGH_PERF`, que é mais antigo e não trata de latência.
 *
 * Só vale com a tela ligada e o app em primeiro plano, que é exatamente o caso
 * aqui. Custa bateria, então o lock é solto ao sair da sessão.
 */
class WifiLowLatency(context: Context)
{
	private val wifi = context.applicationContext
		.getSystemService(Context.WIFI_SERVICE) as? WifiManager

	private var lock: WifiManager.WifiLock? = null

	fun acquire()
	{
		if(lock != null)
			return
		try
		{
			val manager = wifi ?: return
			lock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "P5M")
				.also {
					it.setReferenceCounted(false)
					it.acquire()
				}
			Log.i(TAG, "Wi-Fi in low latency mode")
		}
		catch(e: Exception)
		{
			// Sem lock o stream funciona igual, só com mais variação de latência:
			// não é motivo para impedir a sessão de começar.
			Log.w(TAG, "Could not ask the Wi-Fi for low latency: ${e.message}")
			lock = null
		}
	}

	fun release()
	{
		try
		{
			lock?.takeIf { it.isHeld }?.release()
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Failed to release the Wi-Fi lock: ${e.message}")
		}
		lock = null
	}

	private companion object
	{
		const val TAG = "P5MVR"
	}
}
