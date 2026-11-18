// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context

/**
 * Sondas tiradas da engenharia reversa do app oficial do Remote Play 9.5.0
 * (dev.242). Cada uma muda um detalhe do que se pede ao PS5 para ver, pelo
 * diário, se ele responde diferente. Desligadas por padrão e fora do caminho
 * de quem só joga: os interruptores ficam nas ferramentas do Diagnóstico.
 *
 * - **maxFps 999**: o oficial tem três valores de quadro no launchSpec — 30,
 *   60 e 999 (sem teto). A pergunta é se um jogo de 120 Hz passa de 60 fps.
 *   Só o pedido muda; o perfil, o decodificador e o painel continuam como são.
 * - **Takion v20** (ligada por padrão, como no Mac): o oficial negocia a
 *   versão 20 do protocolo do stream; o PS5 aceita, e a troca de chaves passa
 *   a P-521. Sem resposta em 1 s, a sessão segue na 12. O interruptor existe
 *   para comparar com a 12 e para fugir dela se algo quebrar.
 * - **Mais informação no ctrl**: `RP-ReqMoreInfo: 1`, que o oficial manda
 *   sempre ao PS5. Em troca o console diria a qualidade do Wi-Fi dele e outros
 *   campos (`P5M: ctrl response extras` no diário). O `RP-PadProcNo: 3`, que
 *   andava junto, sai sempre desde a dev.244 (patch 0053): sem ele o PS5 manda
 *   alguns efeitos de gatilho num formato que o DualSense não aplica.
 *
 * A escolha mora na libp5m-vr.so; a libchiaki a lê no início de cada sessão
 * (patch 0051). Por isso [aplicar] roda antes de todo stream.
 */
object SondasDeProtocolo
{
	init { System.loadLibrary("p5m-vr") }

	private const val PREFS = "p5m-sondas"
	private const val KEY_FPS = "launch-max-fps-999"
	private const val KEY_INFO = "ctrl-more-info"
	private const val KEY_V20 = "takion-v20"

	fun maxFps999(context: Context) = prefs(context).getBoolean(KEY_FPS, false)
	fun maisInfo(context: Context) = prefs(context).getBoolean(KEY_INFO, false)

	fun definirMaxFps999(context: Context, ligado: Boolean) =
		prefs(context).edit().putBoolean(KEY_FPS, ligado).apply()

	fun definirMaisInfo(context: Context, ligado: Boolean) =
		prefs(context).edit().putBoolean(KEY_INFO, ligado).apply()

	fun takionV20(context: Context) = prefs(context).getBoolean(KEY_V20, true)

	fun definirTakionV20(context: Context, ligado: Boolean) =
		prefs(context).edit().putBoolean(KEY_V20, ligado).apply()

	/** Entrega a escolha à parte nativa e a registra no diário da sessão. */
	fun aplicar(context: Context)
	{
		val fps = maxFps999(context)
		val info = maisInfo(context)
		val v20 = takionV20(context)
		nativeDefinir(if(fps) 999 else 0, info, v20)
		if(fps || info || !v20)
			Trace.log(context, "Protocol probes: maxFps 999 ${if(fps) "on" else "off"}, " +
					"ctrl more info ${if(info) "on" else "off"}, Takion v20 ${if(v20) "on" else "off"}")
	}

	private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	private external fun nativeDefinir(maxFps: Int, maisInfo: Boolean, takionV20: Boolean)
}
