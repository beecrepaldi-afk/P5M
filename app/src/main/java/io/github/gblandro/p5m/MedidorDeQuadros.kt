// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.util.Log

/**
 * Mede o meio do caminho do vídeo, do lado de cá da fronteira nativa.
 *
 * É a mesma medição do `patches/0018`, reescrita em Kotlin e **sem nenhuma
 * dependência do chiaki**. Existe em duplicata de propósito: o patch mede o
 * caminho real, com o console do outro lado; esta classe mede qualquer
 * decodificador que a gente alimente, e é a versão que sobrevive a este
 * repositório. Se um dia os dois discordarem num número, é porque as duas
 * medições existem e podem ser comparadas.
 *
 * Duas medidas por quadro, e elas respondem perguntas diferentes:
 *
 * - **Decodificação**: da entrega ao codec até a saída. É o pedaço que ninguém
 *   media, entre as estatísticas de rede e a latência do compositor.
 * - **Entrega**: o intervalo entre um quadro e o anterior. A média disso é
 *   quase sempre 16,7 ms e não diz nada; o que se sente é a distribuição. Mil
 *   quadros com média perfeita continuam tremendo se alguns segurarem um
 *   quadro a mais.
 *
 * A linha sai com a tag `P5MVR`, que é a que o [Trace] captura para o
 * diário. Sem isso ela existiria só no logcat, que dentro de um headset é o
 * mesmo que não existir.
 */
class MedidorDeQuadros(private val rotulo: String)
{
	// Carimbo de entrada por PTS. O PTS anda de um em um, então o resto da
	// divisão endereça direto. Guardar o PTS junto faz uma volta completa do
	// anel aparecer como "sem par" em vez de virar um número errado.
	private val carimboPts = LongArray(ANEL) { Long.MIN_VALUE }
	private val carimboNs = LongArray(ANEL)

	private var janelaInicioNs = System.nanoTime()
	private var ultimaEntregaNs = 0L

	private var decodeSomaUs = 0L
	private var decodeN = 0
	private var decodeMinUs = Long.MAX_VALUE
	private var decodeMaxUs = 0L

	private var passoSomaUs = 0L
	private var passoN = 0
	private var passoMinUs = Long.MAX_VALUE
	private var passoMaxUs = 0L
	private val faixa = IntArray(4)

	private var quadrosEntregues = 0
	private var quadrosSegurados = 0
	private var semPar = 0

	/** Chamado ao entregar a unidade de acesso ao decodificador. */
	@Synchronized
	fun entrada(pts: Long)
	{
		val i = (pts % ANEL).toInt()
		carimboPts[i] = pts
		carimboNs[i] = System.nanoTime()
	}

	/**
	 * Chamado quando o quadro sai do decodificador.
	 *
	 * @param entregue false quando o quadro foi descartado em vez de ir para a
	 *        Surface — o portão fechado do encerramento. Contado à parte para
	 *        que uma janela de encerramento não pareça uma janela ruim.
	 */
	@Synchronized
	fun saida(pts: Long, entregue: Boolean)
	{
		val agora = System.nanoTime()

		if(entregue)
		{
			quadrosEntregues++
			if(ultimaEntregaNs != 0L)
			{
				val passoUs = (agora - ultimaEntregaNs) / 1_000
				passoSomaUs += passoUs
				passoN++
				if(passoUs < passoMinUs) passoMinUs = passoUs
				if(passoUs > passoMaxUs) passoMaxUs = passoUs
				when
				{
					passoUs < ADIANTADO_US -> faixa[0]++
					passoUs <= NO_RITMO_US -> faixa[1]++
					passoUs <= UM_ATRASO_US -> faixa[2]++
					else -> faixa[3]++
				}
			}
			ultimaEntregaNs = agora
		}
		else
			quadrosSegurados++

		val i = (pts % ANEL).toInt()
		if(carimboPts[i] == pts)
		{
			val decodeUs = (agora - carimboNs[i]) / 1_000
			carimboPts[i] = Long.MIN_VALUE
			decodeSomaUs += decodeUs
			decodeN++
			if(decodeUs < decodeMinUs) decodeMinUs = decodeUs
			if(decodeUs > decodeMaxUs) decodeMaxUs = decodeUs
		}
		else
			semPar++

		if(agora - janelaInicioNs >= JANELA_NS && quadrosEntregues > 0)
		{
			Log.i(TAG, resumo())
			zerar(agora)
		}
	}

	/**
	 * Fecha a janela corrente na marra e devolve a linha.
	 *
	 * Para o fim da sessão: sem isto, uma sessão de oito segundos não deixaria
	 * número nenhum, que é exatamente o tipo de teste curto que se faz quando
	 * alguma coisa está errada.
	 */
	@Synchronized
	fun encerrar(): String
	{
		val linha = resumo()
		Log.i(TAG, linha)
		zerar(System.nanoTime())
		return linha
	}

	private fun resumo(): String
	{
		// Locale.US para o separador decimal ser sempre ponto. Sem isto o
		// aparelho em português escreve vírgula, e a mesma medida sai com duas
		// grafias -- a do ensaio com vírgula, a do caminho real com ponto --
		// justamente nas duas linhas que existem para ser comparadas.
		val L = java.util.Locale.US
		val passoMed = if(passoN > 0) passoSomaUs.toDouble() / passoN / 1000.0 else 0.0
		val decodeMed = if(decodeN > 0) decodeSomaUs.toDouble() / decodeN / 1000.0 else 0.0
		return "%s: %d frames | delivery %.1f/%.1f/%.1f ms (min/avg/max) | ".format(L,
					rotulo, quadrosEntregues,
					if(passoN > 0) passoMinUs / 1000.0 else 0.0, passoMed, passoMaxUs / 1000.0) +
				"pacing %d early, %d on time, %d one late, %d two or more | ".format(L,
					faixa[0], faixa[1], faixa[2], faixa[3]) +
				"decode %.1f/%.1f/%.1f ms | %d unpaired, %d with the gate shut".format(L,
					if(decodeN > 0) decodeMinUs / 1000.0 else 0.0, decodeMed,
					decodeMaxUs / 1000.0, semPar, quadrosSegurados)
	}

	private fun zerar(agora: Long)
	{
		janelaInicioNs = agora
		decodeSomaUs = 0; decodeN = 0; decodeMinUs = Long.MAX_VALUE; decodeMaxUs = 0
		passoSomaUs = 0; passoN = 0; passoMinUs = Long.MAX_VALUE; passoMaxUs = 0
		faixa.fill(0)
		quadrosEntregues = 0
		quadrosSegurados = 0
		semPar = 0
		// ultimaEntregaNs não se zera: o intervalo entre a última entrega de uma
		// janela e a primeira da seguinte é um intervalo como outro qualquer, e
		// perdê-lo esconderia justamente a travada que cai na virada.
	}

	companion object
	{
		private const val TAG = "P5MVR"
		private const val ANEL = 256

		// Dez segundos: curto o bastante para uma travada aparecer isolada numa
		// janela em vez de diluída na média da sessão, longo o bastante para o
		// diário não virar uma linha por segundo.
		private const val JANELA_NS = 10_000_000_000L

		// Faixas em torno dos 16,7 ms de uma fonte de 60 fps. Não dependem da
		// taxa do painel de propósito: a pergunta é se a fonte entregou no ritmo
		// dela, e ela é a mesma a 72, 90 ou 120 Hz.
		private const val ADIANTADO_US = 12_500L
		private const val NO_RITMO_US = 20_800L
		private const val UM_ATRASO_US = 29_200L
	}
}
