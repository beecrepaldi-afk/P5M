package tests

import io.github.gblandro.p5m.InputArrival

const val MS = 1_000_000L

// Faixas do histograma: início em ms e contagem.
fun bins(line: String): Map<String, Int> =
	Regex("(>?[\\d.]+):(\\d+)").findAll(line.substringAfter("start:count)"))
		.associate { it.groupValues[1] to it.groupValues[2].toInt() }

fun main()
{
	// Enlace ativo: um relatório a cada 4 ms, tudo numa faixa só.
	val active = InputArrival(0L)
	for(i in 1..100) active.sample(1_000 * MS + i * 4 * MS)
	val a = active.report(10_000 * MS, "ns")
	check(a.startsWith("Input timing: 10000 ms, 100 axis samples, clock ns;")) { a }
	check(bins(a) == mapOf("4.00" to 99)) { a }

	// Enlace em janelas de 11,25 ms: três relatórios colados por janela. A
	// medida tem de mostrar os dois montes, perto de zero e perto da janela.
	val sniff = InputArrival(0L)
	for(w in 0 until 50)
		for(k in 0 until 3)
			sniff.sample(w * 11_250_000L + k * 100_000L + MS)
	val s = bins(sniff.report(10_000 * MS, "ns"))
	check(s["0.00"] == 100 && s["11.00"] == 49 && s.size == 2) { s }

	// Fora de ordem não vira intervalo negativo, e mão parada cai acima de 50.
	val odd = InputArrival(0L)
	odd.sample(100 * MS); odd.sample(90 * MS); odd.sample(94 * MS); odd.sample(200 * MS)
	check(bins(odd.report(1_000 * MS, "ms")) == mapOf("4.00" to 1, ">50" to 1))

	// Sem amostra, a linha diz por quê, e a contagem recomeça do relato anterior.
	val idle = InputArrival(0L)
	check(idle.report(10_000 * MS, "ns") ==
		"Input timing: 10000 ms, 0 axis samples (a resting stick sends nothing; move one to measure)")
	idle.sample(10_001 * MS)
	check(idle.report(20_000 * MS, "ns").startsWith("Input timing: 10000 ms, 1 axis samples"))
	println("PASS: input arrival separates a steady link from one that talks in windows")
}
