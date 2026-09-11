package io.github.gblandro.p5m

import android.content.Context
import com.metallic.chiaki.lib.Session
import java.util.zip.CRC32

// Executa o laco de producao. So Android, JNI e transporte sao substituidos;
// a fila, o empacotamento e a decisao de enviar sao os do APK.
private fun runFrames(frames: List<ByteArray>, hid: DualSenseHid = DualSenseHid()):
	Pair<DualSenseHaptics, List<ByteArray>>
{
	val h = DualSenseHaptics(Context(), hid)
	val running = h.javaClass.getDeclaredField("rodando").also { it.isAccessible = true }
	running.setBoolean(h, true)
	var index = 0
	val session = Session { out ->
		val frame = frames[index++]
		frame.copyInto(out)
		if(index == frames.size) running.setBoolean(h, false)
		frame.size
	}
	h.javaClass.getDeclaredMethod("laco", Session::class.java).also {
		it.isAccessible = true
		it.invoke(h, session)
	}
	for(report in hid.reports)
	{
		check(report.size == 142 && report[0] == 0x32.toByte())
		val crc = CRC32().also { it.update(0xA2); it.update(report, 0, 138) }.value
		val received = (0..3).fold(0L) { v, i ->
			v or ((report[138 + i].toLong() and 255) shl (8 * i))
		}
		check(crc == received) { "CRC mismatch" }
	}
	return h to hid.reports
}

private fun payload(report: ByteArray) = report.copyOfRange(13, 77)

// Faixas do histograma de envio: inicio em ms (ou ">50") e contagem.
private fun sendBins(timing: String): List<Pair<Double, Int>> =
	Regex("(>?[\\d.]+):(\\d+)").findAll(timing.substringAfter("start:count)"))
		.map { m ->
			val start = m.groupValues[1]
			(if(start.startsWith(">")) Double.MAX_VALUE else start.toDouble()) to m.groupValues[2].toInt()
		}.toList()

fun main()
{
	var failed = 0
	fun test(name: String, body: () -> Unit)
	{
		try { body(); println("PASS: $name") }
		catch(e: Throwable) { failed++; println("FAIL: $name: ${e.message}") }
	}
	test("short onset after idle is sent in the arrival cycle") {
		val impulse = ByteArray(60) { if(it % 2 == 0) 25 else -25 }
		// O ultimo ciclo e exatamente aquele em que o pacote chega: sem
		// voltas vazias posteriores para esconder a espera de 21 ms.
		val (_, reports) = runFrames(List(40) { byteArrayOf() } + listOf(impulse))
		check(payload(reports.last()).copyOfRange(0, 60).contentEquals(impulse))
		check(payload(reports.last()).takeLast(4).all { it == 0.toByte() })
	}
	test("waking short onset does not pad every continuous packet") {
		val impulse = ByteArray(60) { 9 }
		val (_, reports) = runFrames(List(40) { byteArrayOf() } + List(16) { impulse } + List(5) { byteArrayOf() })
		val active = reports.map(::payload).filter { p -> p.any { it != 0.toByte() } }
		check(active.flatMap { it.toList() }.count { it == 9.toByte() } == 60 * 16)
		check(active.drop(1).dropLast(1).all { p -> p.all { it == 9.toByte() } })
	}
	test("isolated 30-frame effect drains without a second effect") {
		val impulse = ByteArray(60) { if(it % 2 == 0) 25 else -25 }
		val (_, reports) = runFrames(listOf(impulse) + List(5) { byteArrayOf() })
		val active = reports.map(::payload).filter { p -> p.any { it != 0.toByte() } }
		check(active.size == 1) { "short effect stuck or repeated" }
		check(active[0].copyOfRange(0, 60).contentEquals(impulse))
		check(active[0].takeLast(4).all { it == 0.toByte() })
	}
	test("continuous zero PCM parks and a one-step signal wakes it") {
		val frames = List(40) { ByteArray(64) } + listOf(ByteArray(64) { 1 })
		val (h, reports) = runFrames(frames)
		check(h.estacionadas == 1L) { "zero PCM never parked" }
		check(reports.size == 24) { "silence kept filling transport: ${reports.size}" }
		check(payload(reports.last()).all { it == 1.toByte() })
		check(h.mudos == 40L) { "silent PCM missing from diagnostics" }
	}
	test("30-frame network chunks preserve stereo order across 32-frame reports") {
		val input = ByteArray(60 * 16) { ((it % 120) + 1).toByte() }
		val frames = (0 until 16).map { input.copyOfRange(it * 60, (it + 1) * 60) }
		val (_, reports) = runFrames(frames + List(5) { byteArrayOf() })
		val active = reports.map(::payload).filter { p -> p.any { it != 0.toByte() } }
		check(active.flatMap { it.toList() }.toByteArray().contentEquals(input))
	}
	test("queue overflow keeps newest stereo frames") {
		val (_, reports) = runFrames(listOf(ByteArray(256) { (it / 2).toByte() }))
		check(payload(reports.single()).contentEquals(ByteArray(64) { ((it + 64) / 2).toByte() }))
	}
	test("missing source parks without replaying an old effect") {
		val (h, reports) = runFrames(listOf(ByteArray(64) { -1 }) + List(40) { byteArrayOf() })
		check(h.estacionadas == 1L)
		check(reports.count { p -> payload(p).any { it != 0.toByte() } } == 1)
	}
	test("diagnostic RMS measures actual signed stereo output") {
		Trace.lines.clear()
		runFrames(List(950) { ByteArray(64) { if(it % 2 == 0) -3 else 4 } })
		check(Trace.lines.any { it.contains("output RMS 3.54, output peak 4") })
		val timing = Trace.lines.first { it.startsWith("Haptics timing:") }
		val counts = Regex("(\\d+) loops, (\\d+) active sent, (\\d+) zero sent, (\\d+) idle, (\\d+) refused")
			.find(timing)!!.groupValues.drop(1).map { it.toLong() }
		check(counts[0] == counts.drop(1).sum()) { "overlapping timing counters: $timing" }
		check(counts[0] == counts[1] && counts.drop(2).all { it == 0L })
		check(timing.contains("source ${counts[0] * 64} B, consumed ${counts[0] * 64} B, dropped 0 B"))
		check(sendBins(timing).sumOf { it.second }.toLong() == counts[1] + counts[2] + counts[4]) {
			"send histogram does not cover every send: $timing" }
	}
	test("send histogram separates sends that wait from the ones that do not") {
		Trace.lines.clear()
		val hid = DualSenseHid().also { it.sendDelayMs = { i -> if(i % 4 == 3) 3L else 0L } }
		runFrames(List(950) { ByteArray(64) { 5 } }, hid)
		val timing = Trace.lines.first { it.startsWith("Haptics timing:") }
		val sent = Regex("(\\d+) active sent").find(timing)!!.groupValues[1].toInt()
		val bins = sendBins(timing)
		check(bins.sumOf { it.second } == sent) { "histogram total differs from sends: $timing" }
		val slow = bins.filter { it.first >= 3.0 }.sumOf { it.second }
		check(slow in sent / 4..sent / 4 + 2) { "expected ${sent / 4} sends at 3 ms or more: $timing" }
		check(bins.none { it.first in 0.5..2.75 }) { "fast sends leaked into slow bins: $timing" }
	}
	test("timing distinguishes zero reports from parked loops") {
		Trace.lines.clear()
		runFrames(List(950) { ByteArray(64) })
		val timing = Trace.lines.first { it.startsWith("Haptics timing:") }
		val counts = Regex("(\\d+) loops, (\\d+) active sent, (\\d+) zero sent, (\\d+) idle, (\\d+) refused")
			.find(timing)!!.groupValues.drop(1).map { it.toLong() }
		check(counts[0] == counts.drop(1).sum())
		check(counts[1] == 0L && counts[2] == 23L && counts[3] > 0L && counts[4] == 0L)
	}
	test("refused sends ask the transport to look for the controller again") {
		val hid = DualSenseHid().also { it.accept = false }
		val (h, _) = runFrames(List(10) { ByteArray(64) { 7 } }, hid)
		check(h.recusados > 0) { "nothing was refused" }
		check(hid.recoveries.toLong() == h.recusados) { "${hid.recoveries} looks for ${h.recusados} refusals" }
	}
	test("transport sequence continues across haptic owners") {
		val first = DualSenseHid()
		val a = ByteArray(142).also { it[0] = 0x32 }
		val power = ByteArray(78).also { it[0] = 0x31 }
		val second = DualSenseHid()
		val b = ByteArray(142).also { it[0] = 0x32 }
		first.mandar(a)
		first.mandar(power)
		second.mandar(b)
		val reportSeq = listOf(a, power, b).map { (it[1].toInt() ushr 4) and 0x0F }
		check(reportSeq[1] == (reportSeq[0] + 1) and 0x0F)
		check(reportSeq[2] == (reportSeq[1] + 1) and 0x0F)
		check((b[10].toInt() and 0xFF) == ((a[10].toInt() + 1) and 0xFF))
	}
	check(failed == 0) { "$failed regression(s)" }
}
