// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

/**
 * Histograma de durações em faixas de 0,25 ms até 50 ms, para o diário.
 *
 * Existe para responder se um tempo se agrupa em múltiplos de um valor fixo --
 * a assinatura de um enlace Bluetooth que só fala em janelas --, e o pico ou a
 * média não respondem isso. 0,25 ms resolve os 0,625 ms do slot BR/EDR.
 * Compartilhado pela háptica (tempo de envio) e pela entrada (intervalo entre
 * relatórios), para as duas linhas terem o mesmo formato e se lerem juntas.
 */
class TimingHistogram
{
	companion object
	{
		const val FAIXA_NS = 250_000L
		const val FAIXAS = 200
		const val TITULO = "(0.25 ms bins, start:count)"
	}

	private val faixas = IntArray(FAIXAS + 1)
	var total = 0
		private set

	fun add(nanos: Long)
	{
		faixas[(nanos.coerceAtLeast(0L) / FAIXA_NS).coerceAtMost(FAIXAS.toLong()).toInt()]++
		total++
	}

	fun clear()
	{
		java.util.Arrays.fill(faixas, 0)
		total = 0
	}

	/** Só as faixas com contagem, como " início em ms:quantos"; a última é o que passou de 50 ms. */
	fun format(): String
	{
		val saida = StringBuilder()
		for(i in 0 until FAIXAS)
			if(faixas[i] > 0)
				saida.append(' ')
					.append(String.format(java.util.Locale.ROOT, "%.2f", i * FAIXA_NS / 1e6))
					.append(':').append(faixas[i])
		if(faixas[FAIXAS] > 0)
			saida.append(" >").append(FAIXAS * FAIXA_NS / 1_000_000L).append(':').append(faixas[FAIXAS])
		return if(saida.isEmpty()) " none" else saida.toString()
	}
}
