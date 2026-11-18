// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

/**
 * Intervalo entre relatórios de entrada do controle, como chegam ao Quest.
 *
 * É a medida que enxerga o enlace Bluetooth, e o tempo de envio da háptica não
 * é. O `sendData` só entrega o relatório à pilha e volta -- a dev.177 mostrou
 * os envios espalhados num pico único de ~2,5 ms, iguais antes e depois de
 * religar o Bluetooth, com o atraso sumindo e voltando sem mexer neles.
 *
 * A entrada vem pelo mesmo enlace no sentido contrário, e o horário de cada
 * amostra é o do kernel quando a pilha a entregou. Com o enlace em modo ativo,
 * os intervalos se juntam no período de relatório do controle. Com o enlace
 * falando só em janelas (sniff), os relatórios chegam em rajadas: um monte de
 * intervalos perto de zero e outro monte perto do intervalo da janela.
 *
 * **Analógico parado não gera evento**: o Android só entrega amostra quando um
 * eixo muda. Os intervalos só medem o enlace com um analógico em movimento, e
 * o que passar de 50 ms é quase sempre mão parada, não enlace.
 */
class InputArrival(private var inicio: Long)
{
	private val intervalos = TimingHistogram()
	private var ultima = 0L
	private var amostras = 0
	private var eventos = 0
	private var comHistorico = 0
	private var maiorLote = 0

	/**
	 * Um evento, com o tamanho do lote que ele trouxe.
	 *
	 * É o que separa as duas explicações possíveis para um pente largo. Se o
	 * Android estivesse juntando os relatórios do controle e entregando um
	 * punhado por quadro, cada evento traria histórico e o lote seria grande;
	 * se o histórico vier quase sempre vazio, os relatórios estão chegando
	 * espaçados de verdade, e o assunto é o enlace Bluetooth, não o Android.
	 */
	fun event(historico: Int)
	{
		eventos++
		if(historico > 0)
			comHistorico++
		if(historico > maiorLote)
			maiorLote = historico
	}

	/** Uma amostra, com o horário do evento em nanossegundos. */
	fun sample(nanos: Long)
	{
		amostras++
		// Amostra fora de ordem não vira intervalo negativo; a seguinte volta a
		// medir a partir dela.
		if(ultima in 1..nanos)
			intervalos.add(nanos - ultima)
		ultima = nanos
	}

	/**
	 * A linha do diário, e zera a contagem. `agora`, em ns como o `inicio`, só
	 * mede o intervalo do relato; `relogio` diz se os horários vieram em ns ou
	 * foram arredondados a ms, o que muda o que o histograma consegue separar.
	 */
	fun report(agora: Long, relogio: String): String
	{
		val decorrido = (agora - inicio) / 1_000_000L
		inicio = agora
		val linha = if(amostras == 0)
			"Input timing: $decorrido ms, 0 axis samples (a resting stick sends nothing; move one to measure)"
		else
			"Input timing: $decorrido ms, $amostras axis samples in $eventos events " +
					"($comHistorico batched, biggest batch $maiorLote), clock $relogio; " +
					"gap ms histogram ${TimingHistogram.TITULO}${intervalos.format()}"
		amostras = 0
		eventos = 0
		comHistorico = 0
		maiorLote = 0
		intervalos.clear()
		return linha
	}
}
