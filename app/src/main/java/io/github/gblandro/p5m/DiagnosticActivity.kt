// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Tela de diagnóstico, legível dentro do headset.
 *
 * O Quest costuma estar longe do PC, e sem `adb` uma falha na inicialização é
 * indistinguível de "abriu uma janela em branco". Esta tela mostra o último
 * crash e o log do próprio processo sem precisar de cabo.
 *
 * Deliberadamente construída sem AppCompat, sem layout XML, sem viewBinding e
 * sem o tema do chiaki-ng: ela não pode compartilhar os modos de falha daquilo
 * que veio diagnosticar. Tem entrada própria no lançador para continuar
 * acessível mesmo com o resto do app quebrado.
 */
class DiagnosticActivity: Activity()
{
	private lateinit var output: TextView
	private val server by lazy { LogServer(this) }

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setBackgroundColor(Color.parseColor("#101014"))
			setPadding(32, 32, 32, 32)
		}

		root.addView(TextView(this).apply {
			text = "P5M — diagnostics"
			setTextColor(Color.WHITE)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
			setTypeface(typeface, Typeface.BOLD)
		})

		root.addView(TextView(this).apply {
			text = deviceInfo()
			setTextColor(Color.parseColor("#9aa0a6"))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			setPadding(0, 12, 0, 12)
		})

		// Sozinho e no topo: e o unico botao aqui que serve a quem nao entende
		// nada do que esta escrito no resto da tela.
		root.addView(Button(this).apply {
			text = "Report a problem"
			setOnClickListener { reportProblem() }
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT)
		})

		// Duas fileiras, e nao oito botoes numa linha so.
		//
		// Com oito, cada um fica com um oitavo da largura, e acertar um deles
		// com o raio do controle dentro do headset e uma disputa. A divisao
		// tambem separa o que le do que apaga: nunca sao a mesma intencao, e o
		// vizinho errado ja custou um diario apagado sem querer.
		root.addView(LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			addView(button("Refresh") { refresh() })
			addView(button("Copy crash") { copyToClipboard(crashOnly = true) })
			addView(button("Copy all") { copyToClipboard(crashOnly = false) })
			addView(button("Copy summary") { copySummary() })
			addView(button("Previous version") { copiarAnterior() })
		})

		root.addView(LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			addView(button("Serve on network") { toggleServer() })
			addView(button("Send as file") { shareAsFile() })
			addView(button("Clear crash") {
				P5MApp.clear(this@DiagnosticActivity)
				refresh()
			})
			addView(button("Clear diary") {
				// Antes de um teste dirigido, comecar do zero evita ler o
				// registro da sessao anterior achando que e o da atual.
				Trace.clear(this@DiagnosticActivity)
				refresh()
			})
		})

		output = TextView(this).apply {
			setTextColor(Color.parseColor("#e8eaed"))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
			typeface = Typeface.MONOSPACE
			setTextIsSelectable(true)
			movementMethod = ScrollingMovementMethod()
		}
		root.addView(ScrollView(this).apply {
			addView(output)
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				0
			).apply { weight = 1f }
		})

		setContentView(root)
		refresh()

		// Quem chegou pelo "Report a problem" do lancador ja pediu o relatorio;
		// obrigar a achar o botao de novo seria repetir a pergunta.
		if(intent?.getBooleanExtra(EXTRA_REPORT, false) == true)
			reportProblem()
	}

	/**
	 * O caminho de um toque para o usuario nos mandar um log.
	 *
	 * Dentro do headset nao ha cabo, nao ha `adb`, e a lista de alvos de
	 * compartilhamento do Horizon OS costuma estar vazia -- entao "manda o
	 * arquivo" nao e um pedido razoavel. O que sempre existe e o navegador e a
	 * area de transferencia, e e sobre esses dois que este fluxo se apoia:
	 *
	 *  1. o resumo vai para a area de transferencia;
	 *  2. o servidor local sobe, para quem preferir pegar o diario inteiro do
	 *     celular, na mesma rede;
	 *  3. o navegador abre a pagina de nova issue com titulo e corpo prontos.
	 *
	 * O corpo vai na URL, e por isso truncado: o GitHub aceita a pagina, mas
	 * URLs enormes nao atravessam todo servidor no caminho. Se o recorte cortar
	 * algo, o texto completo continua na area de transferencia, e a instrucao na
	 * tela diz para colar.
	 */
	private fun reportProblem()
	{
		val report = buildReport()
		clipboard(report, "Report copied. Paste it if the page opens empty.")

		val serverLine = if(server.url != null) server.url
			else if(server.start()) server.url else null

		val body = "**What happened**\n\n(describe it here)\n\n" +
				"**What I expected**\n\n\n" +
				"<details><summary>Diagnostics</summary>\n\n```\n" +
				report.take(REPORT_URL_BUDGET) + "\n```\n</details>\n"
		val url = "$ISSUE_URL?title=" + Uri.encode("[beta] ") +
				"&labels=" + Uri.encode("beta") +
				"&body=" + Uri.encode(body)

		val opened = try
		{
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
			true
		}
		catch(e: Throwable)
		{
			Log.w("P5MVR", "No browser to open the issue page", e)
			false
		}

		output.text = buildString {
			append("--- how to send this ---\n\n")
			if(opened)
				append("A report page opened in the browser. Fill in what happened and\n" +
						"press Submit. If the box came up empty, paste: the report is\n" +
						"already on the clipboard.\n\n")
			else
				append("No browser answered here. The report is on the clipboard, so\n" +
						"open $ISSUE_URL on any device and paste it.\n\n")
			if(serverLine != null)
				append("For the full diary instead of the summary, open\n\n    " +
						"$serverLine\n\non your phone or PC, on this same network. It stops\n" +
						"when you leave this screen.\n\n")
			append("Nothing was sent anywhere on its own.\n\n")
			append(report)
		}
	}

	/** O recorte que vai no relatorio: o aparelho, a queda e os marcos. */
	private fun buildReport(): String
	{
		val trace = Trace.read(this).orEmpty().lines()
		val interesting = trace.filter { line -> SUMMARY_PATTERNS.any { line.contains(it) } }
		return buildString {
			append(deviceInfo())
			append("\n")
			P5MApp.lastCrash(this@DiagnosticActivity)?.let {
				append("\n--- crash ---\n")
				append(it.lines().take(12).joinToString("\n"))
				append("\n")
			}
			append("\n--- milestones ---\n")
			append(
				if(interesting.isEmpty()) "(nothing recorded yet)"
				else interesting.takeLast(SUMMARY_MAX_LINES).joinToString("\n")
			)
		}
	}


	private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
		text = label
		gravity = Gravity.CENTER
		setOnClickListener { onClick() }
		layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
			.apply { weight = 1f }
	}

	/**
	 * Copia só as linhas que costumam responder a pergunta.
	 *
	 * O diário inteiro não cabe num campo de texto e é penoso de manejar pelo
	 * teclado do headset. Este recorte tem dezenas de linhas, não centenas: a
	 * versão, o que o runtime ofereceu, o que foi escolhido e todo erro — que é
	 * o que decide quase toda investigação daqui.
	 */
	private fun copySummary()
	{
		val text = buildReport()
		clipboard(text, "${text.lines().size} summary line(s) copied")
	}

	private fun toggleServer()
	{
		if(server.url != null)
		{
			server.stop()
			output.text = "Log server stopped.\n\n${output.text}"
			return
		}
		if(server.start())
			output.text = "Open this address on your phone or PC, on the same network:\n\n" +
					"    ${server.url}\n\n" +
					"The page shows the diagnostics as plain text, where copying and\n" +
					"sending work normally. It stops when you leave this screen.\n\n" +
					"${output.text}"
		else
			output.text = "Could not start the server.\n\n${output.text}"
	}

	private fun refresh()
	{
		// Uma ultima captura antes de mostrar: o processo da sessao ja morreu,
		// mas as linhas dele podem continuar no buffer, e esta e a ultima
		// chance de salva-las.
		Trace.captureNativeLines(this)

		val crash = P5MApp.lastCrash(this)
		val pilha = PilhaNativa.ultima(this)
		output.text = buildString {
			// A pilha nativa primeiro: quando ela existe, e a resposta.
			if(pilha != null)
			{
				append("=== LAST NATIVE CRASH ===\n")
				append(pilha)
				append("\n\n")
			}
			if(crash != null)
			{
				append("=== LAST CRASH ===\n")
				append(crash)
				append("\n\n")
			}
			else
			{
				// "Nenhuma exceção Java", e não "nenhum crash": queda nativa mata
				// o processo sem passar pelo handler, e a frase antiga mandava
				// parar de procurar justamente quando havia o que achar.
				append("No Java exception recorded in this version.\n")
				append("(A native crash does not show up here: search the diary for " +
						"\"Previous process\".)\n\n")
			}
			append("=== APP DIARY ===\n")
			append(readTrace())
			append("\n\n=== PROCESS LOG ===\n")
			append(readOwnLogcat())
		}
	}

	/**
	 * Lê o logcat visível para este app.
	 *
	 * Desde o Android 4.1 um app só enxerga as linhas do próprio UID, então
	 * isto não exige permissão nenhuma.
	 *
	 * De propósito **não** filtramos por PID: o processo que interessa é
	 * justamente o anterior, o que caiu. Filtrar pelo PID atual esconderia
	 * exatamente o crash que viemos investigar -- inclusive os que acontecem
	 * antes do nosso handler ser instalado, que nem chegam ao arquivo.
	 */
	/**
	 * Diário em arquivo: o que o app registrou, sobrevivendo ao buffer do
	 * logcat.
	 *
	 * Vem antes do logcat na tela porque é a única parte que ainda existe
	 * quando o diagnóstico é aberto depois de uma sessão longa.
	 */
	private fun readTrace(): String =
		Trace.read(this) ?: "(diary empty — no session recorded yet)"

	private fun readOwnLogcat(): String = Trace.redact(lerLogcat())

	private fun lerLogcat(): String = try
	{
		val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
		val all = BufferedReader(InputStreamReader(process.inputStream)).use { it.readLines() }

		// O log cru vem cheio de ruido do driver grafico e do zygote. Filtrar
		// pelo que interessa reduz de milhares de linhas para dezenas, e cabe
		// numa issue.
		val relevant = all.filter { line -> INTERESTING.any { line.contains(it) } }

		// Um tombstone sozinho passa de cem linhas. Com dois deles, um corte
		// unico pelo fim engole justamente os marcos do app -- foi o que
		// aconteceu: o crash apareceu inteiro e nao sobrou nenhuma linha
		// dizendo ate onde a sessao tinha chegado. Cada grupo tem seu proprio
		// limite, e os marcos vem primeiro.
		val (marks, rest) = relevant.partition { line -> APP_TAGS.any { line.contains(it) } }

		// O chiaki loga uma linha por retransmissao de pacote e um dump hexa por
		// heartbeat. Numa sessao com rede ruim isso sozinho passa de mil linhas
		// e engole todo o orcamento -- ja aconteceu: o log chegou sem uma unica
		// linha nossa. Sai do corpo e vira uma contagem.
		val (noise, signal) = marks.partition { line -> NOISE.any { line.contains(it) } }

		// Nossas linhas antes das do chiaki: sao poucas e dizem onde a sessao
		// chegou. As do chiaki sao muitas e contam o resto da historia.
		val (ours, theirs) = signal.partition { it.contains("P5MVR") }

		when
		{
			relevant.isEmpty() && all.isEmpty() -> "(empty — the buffer may have been cleared)"
			relevant.isEmpty() -> all.takeLast(MAX_LOG_LINES).joinToString("\n")
			else -> buildString {
				if(ours.isNotEmpty())
				{
					append("--- P5M ---\n")
					append(ours.takeLast(MAX_MARK_LINES).joinToString("\n"))
					append("\n\n")
				}
				if(theirs.isNotEmpty())
				{
					append("--- chiaki ---\n")
					append(theirs.takeLast(MAX_MARK_LINES).joinToString("\n"))
					append("\n\n")
				}
				if(noise.isNotEmpty())
					append("(${noise.size} repetitive chiaki lines omitted: " +
							"retransmission, heartbeat, hex dump)\n\n")
				append("--- crash and system ---\n")
				append(rest.takeLast(MAX_LOG_LINES).joinToString("\n"))
			}
		}
	}
	catch(e: Exception)
	{
		"Could not read the logcat: ${e.message}"
	}

	/**
	 * @param crashOnly copia so o stack trace, sem o log do processo.
	 *
	 * O log inteiro estoura o limite de caracteres de uma issue do GitHub, e
	 * quase sempre a resposta esta no stack trace. Separar os dois evita ter
	 * que recortar texto dentro do headset.
	 */
	private fun copyToClipboard(crashOnly: Boolean)
	{
		val text = if(crashOnly)
			P5MApp.lastCrash(this) ?: "No crash recorded."
		else
			output.text.toString()

		clipboard(
			text,
			if(crashOnly) "Crash copied (${text.length} characters)."
			else "Full log copied (${text.length} characters)."
		)
	}

	/**
	 * Copia o diário e o crash da versão anterior do APK.
	 *
	 * Serve para um momento específico e desconfortável: algo quebrou, a saída
	 * foi voltar para uma build que funcionava, e a prova do que quebrou está na
	 * versão que acabou de sair. O diário some da vista quando a versão troca --
	 * misturar as duas já custou tempo -- mas não some do disco, e é isto que o
	 * traz de volta.
	 */
	private fun copiarAnterior()
	{
		val diario = Trace.readAnterior(this)
		val crash = P5MApp.lastCrashAnterior(this)
		if(diario == null && crash == null)
		{
			Toast.makeText(this,
					"There is no record from a previous version. It shows up here "
							+ "after the first APK change.", Toast.LENGTH_LONG).show()
			return
		}

		val texto = buildString {
			if(crash != null)
			{
				append("=== CRASH FROM THE PREVIOUS VERSION ===\n")
				append(crash)
				append("\n\n")
			}
			append("=== DIARY FROM THE PREVIOUS VERSION ===\n")
			append(diario ?: "(empty)")
		}
		clipboard(texto, "Record from the previous version copied " +
				"(${texto.length} characters).")
	}

	private fun clipboard(text: String, message: String)
	{
		val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
		manager.setPrimaryClip(ClipData.newPlainText("P5M diagnostics", text))
		Toast.makeText(this, message, Toast.LENGTH_LONG).show()
	}

	/**
	 * Compartilha o diagnóstico como arquivo.
	 *
	 * Colar texto esbarra no limite de caracteres de uma issue do GitHub;
	 * anexar arquivo não esbarra em nada. Usa um provider próprio, declarado no
	 * nosso manifesto, separado do provider do chiaki-ng.
	 */
	private fun shareAsFile()
	{
		try
		{
			val dir = File(cacheDir, "diagnostics").also { it.mkdirs() }
			val file = File(dir, "p5m-diagnostics.txt")
			file.writeText("${deviceInfo()}\n\n${output.text}", Charsets.UTF_8)

			val uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
			val intent = Intent(Intent.ACTION_SEND).apply {
				type = "text/plain"
				putExtra(Intent.EXTRA_STREAM, uri)
				putExtra(Intent.EXTRA_SUBJECT, "P5M — diagnostics")
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
			startActivity(Intent.createChooser(intent, "Send diagnostics"))
		}
		catch(e: Throwable)
		{
			output.text = "Failed to write the file: ${e::class.java.simpleName}: ${e.message}" +
					"\n\n${output.text}"
		}
	}

	private fun deviceInfo(): String
	{
		val version = try
		{
			packageManager.getPackageInfo(packageName, 0).versionName
		}
		catch(e: Exception)
		{
			"?"
		}
		return "version $version  ·  ${Build.MANUFACTURER} ${Build.MODEL}  ·  " +
				"Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
	}

	override fun onDestroy()
	{
		super.onDestroy()
		// Nao deixa servidor de pe depois que a tela fecha.
		server.stop()
	}

	companion object
	{
		private const val MAX_LOG_LINES = 400
		private const val MAX_MARK_LINES = 200
		private const val SUMMARY_MAX_LINES = 60

		/** O que quase sempre responde a pergunta, num diagnóstico daqui. */
		private val SUMMARY_PATTERNS = listOf(
			// O marcador de sessão primeiro: sem ele o resumo mistura sessões
			// diferentes sem dizer onde uma acaba e a outra começa.
			"=== session ",
			"LauncherActivity opened", "Opening the stream", "Video profile",
			"Extension enabled", "Rates the panel offers", "Panel at",
			"Video path", "Swapchain", "swapchain",
			"Recommended resolution", "Automatic layer filter", "Color space",
			"Vertical flip", "First submission", "Layer shape",
			"RENDERER_MAIN", "low latency mode", "Gamepad", "Input devices",
			"Virtual speakers", "asked for blind", "Window with shader",
			"Audio output", "Rumble", "P5M: ",
			// So a contagem, e nao as linhas "available:" com os nomes: a
			// lista inteira sao mais de dez linhas e o resumo tem sessenta.
			// Ela continua no diario, que o "Copy all" leva.
			"Extensions the runtime offers", "sustained high level",
			"Cinema mode accepted", "Performance counters offered", "level refused",
			"Headset ", "Performance notice", "Screen brightness",
			"GL:", "GL extensions available", "XrCompositionLayerSettingsFB",
			"first packet", "counter:", "Frame extrapolation", "FrameSync",
			"extrapolated frame", "glExtrapolateTex2DQCOM", "Cadence:",
			"E/", "W/P5MVR", "failed", "unavailable"
		)
		private const val FILE_PROVIDER_AUTHORITY = "io.github.gblandro.p5m.diagnostics"

		/** Extra que faz a tela ja abrir montando o relatorio. */
		const val EXTRA_REPORT = "io.github.gblandro.p5m.REPORT"

		private const val ISSUE_URL = "https://github.com/beecrepaldi-afk/P5M/issues/new"

		/**
		 * Quanto do relatorio cabe na URL.
		 *
		 * Nao e limite do GitHub, e do caminho: navegador, proxy e servidor tem
		 * cada um o seu, e oito mil e o menor que se encontra na pratica. O
		 * texto inteiro segue na area de transferencia.
		 */
		private const val REPORT_URL_BUDGET = 4000

		/**
		 * Linhas que o chiaki repete aos milhares e que nao dizem nada sozinhas.
		 * Viram uma contagem em vez de ocupar o orcamento.
		 */
		private val NOISE = listOf(
			"Takion Send Buffer re-sending packet",
			"Hit max retries of",
			"Ctrl received Heartbeat",
			"CTRL RECEIVED",
			"offset 0  1  2  3"
		)

		/** Linhas escritas pelo proprio app, que nunca podem ser engolidas. */
		private val APP_TAGS = listOf("P5MVR", "Chiaki")

		/** Tags que importam. O resto e ruido de driver e de sistema. */
		private val INTERESTING = listOf(
			"P5MVR", "Chiaki", "AndroidRuntime", "DEBUG",
			"System.err", "libc", "linker", "dlopen", "UnsatisfiedLink"
		)
	}
}
