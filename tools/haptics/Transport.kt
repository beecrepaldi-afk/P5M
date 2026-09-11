package io.github.gblandro.p5m

import android.content.Context

class DualSenseHid
{
	val reports = mutableListOf<ByteArray>()
	// Milissegundos que o envio de indice N demora; imita o sendData esperando.
	var sendDelayMs: (Int) -> Long = { 0L }
	var accept = true
	var recoveries = 0
	fun recuperar(): Boolean { recoveries++; return false }
	fun conectar() = true
	fun potenciaCheia() = true
	fun texto() = "test transport"
	fun fechar() {}
	fun mandar(report: ByteArray): Boolean
	{
		DualSenseOutputSequence.preparar(report)
		sendDelayMs(reports.size).takeIf { it > 0 }?.let { Thread.sleep(it) }
		if(!accept)
			return false
		reports.add(report.copyOf())
		return true
	}
}

object Trace
{
	val lines = mutableListOf<String>()
	fun log(context: Context, text: String) { lines.add(text) }
}
