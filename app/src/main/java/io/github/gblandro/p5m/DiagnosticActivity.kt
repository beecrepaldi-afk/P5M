// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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
	private var hid: DualSenseHid? = null
	// As duas bancadas dividem a ponte, e a haptica a fecha ao terminar: uma
	// segunda por cima derrubava a primeira no meio e o relatorio saia
	// "failed" sem ter tentado. So a thread principal mexe nisto.
	private var bancadaRodando = false

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		server.abrir()

		// O visual de todas as telas, em Estilo (dev.213). Tres grupos, e nao
		// treze botoes iguais: o que serve a qualquer um (relatar), o que leva
		// o diario para fora (compartilhar), e o que so interessa a quem
		// desenvolve -- recolhido, para ninguem apagar o diario sem querer.
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			background = Estilo.fundoDaTela()
			setPadding(dp(24), dp(20), dp(24), dp(16))
		}

		root.addView(Estilo.marca(this, "Diagnostics", 26f))
		root.addView(Estilo.apoio(this, deviceInfo()).apply {
			setTextColor(Estilo.SECAO)
			setPadding(dp(4), dp(10), 0, dp(14))
		})

		// Sozinho e no topo: e o unico botao aqui que serve a quem nao entende
		// nada do que esta escrito no resto da tela. O mesmo botao principal do
		// "Play" do lancador, pela mesma razao: e a acao que importa primeiro.
		root.addView(Estilo.botaoPrincipal(this, "Report a problem") { reportProblem() }.apply {
			layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(4) }
		})

		root.addView(Estilo.secao(this, "Share the diary"))
		root.addView(fileiraDeBotoes(
			"Serve on network" to { toggleServer() },
			"Send as file" to { shareAsFile() },
			"Copy all" to { copyToClipboard(crashOnly = false) },
			"Copy summary" to { copySummary() }))

		// Ferramentas de quem desenvolve: fechadas por padrao. Apagar e ler
		// nunca ficam lado a lado com o que o usuario comum procura -- o vizinho
		// errado ja custou um diario apagado sem querer.
		val ferramentas = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			visibility = View.GONE
			addView(fileiraDeBotoes(
				"Refresh" to { refresh() },
				"Copy crash" to { copyToClipboard(crashOnly = true) },
				"Previous version" to { copiarAnterior() }))
			addView(fileiraDeBotoes(
				"Clear crash" to {
					P5MApp.clear(this@DiagnosticActivity)
					refresh()
				},
				"Clear diary" to {
					// Antes de um teste dirigido, comecar do zero evita ler o
					// registro da sessao anterior achando que e o da atual.
					Trace.clear(this@DiagnosticActivity)
					refresh()
				}))
			// O controle e o video: perguntas pelos olhos e pela mao. Bancadas de
			// laboratorio, so com o diario detalhado ligado (dev.220): na build
			// publica ficam fora do caminho de quem so quer jogar.
			addView(fileiraDeBotoes(
				"Controller lights" to { sondarDualSense() },
				"Controller haptics" to { ensaiarHaptica() },
				"Controller triggers" to { ensaiarGatilhos() },
				"Video benchmark" to { abrirBancada() }).apply {
				visibility = if(Trace.detalhado) View.VISIBLE else View.GONE
			})
		}
		// O interruptor do diario detalhado: ligado de fabrica nas builds -dev,
		// desligado na publica. Quem ajuda um testador pede para ligar aqui.
		val detalhe = Estilo.botao(this, "") {}.apply {
			layoutParams = Estilo.margem(context, 16, 0)
		}
		fun rotuloDoDetalhe() {
			detalhe.text = "Detailed diary: " + (if(Trace.detalhado) "On" else "Off") +
					"   ·   periodic measurements, for troubleshooting"
		}
		rotuloDoDetalhe()
		detalhe.setOnClickListener {
			Trace.definirDetalhe(this, !Trace.detalhado)
			rotuloDoDetalhe()
			Trace.log(this, "Detailed diary: ${if(Trace.detalhado) "on" else "off"}")
		}
		root.addView(detalhe)
		// Sondas do app oficial (dev.242): desligadas de fabrica, so com o
		// diario detalhado, que e quando alguem esta testando de proposito.
		fun interruptor(rotulo: String, explica: String, isOn: () -> Boolean,
				definir: (Boolean) -> Unit) = Estilo.botao(this, "") {}.apply {
			layoutParams = Estilo.margem(context, 16, 0)
			visibility = if(Trace.detalhado) View.VISIBLE else View.GONE
			fun rotular() { text = "$rotulo: " + (if(isOn()) "On" else "Off") + "   ·   $explica" }
			rotular()
			setOnClickListener {
				definir(!isOn())
				rotular()
				Trace.log(this@DiagnosticActivity, "$rotulo: ${if(isOn()) "on" else "off"}")
			}
		}
		// Diario ao vivo (dev.242): visivel sempre, porque e o que deixa quem
		// ajuda acompanhar um teste pela rede sem o testador mandar nada.
		root.addView(interruptor("Live diary on network",
				"a computer on this network follows the diary while you play",
				{ LogServer.aoVivoEscolhido(this) }, { LogServer.definirAoVivo(this, it) }).apply {
			visibility = View.VISIBLE
		})
		root.addView(interruptor("Takion v20",
				"stream protocol of the official app; off falls back to v12",
				{ SondasDeProtocolo.takionV20(this) }, { SondasDeProtocolo.definirTakionV20(this, it) }))
		root.addView(interruptor("Probe: ask for uncapped fps",
				"maxFps 999 in the launch spec, try a 120 Hz game",
				{ SondasDeProtocolo.maxFps999(this) }, { SondasDeProtocolo.definirMaxFps999(this, it) }))
		root.addView(interruptor("Probe: ask the console for more info",
				"RP-ReqMoreInfo, console Wi-Fi in the diary",
				{ SondasDeProtocolo.maisInfo(this) }, { SondasDeProtocolo.definirMaisInfo(this, it) }))
		val abrir = Estilo.botao(this, "Developer tools  ›") {}.apply {
			layoutParams = Estilo.margem(context, 16, 8)
			setTextColor(Estilo.SECUNDARIO)
		}
		abrir.setOnClickListener {
			val aberto = ferramentas.visibility != View.VISIBLE
			ferramentas.visibility = if(aberto) View.VISIBLE else View.GONE
			abrir.text = if(aberto) "Developer tools  ⌄" else "Developer tools  ›"
		}
		root.addView(abrir)
		root.addView(ferramentas)

		output = TextView(this).apply {
			setTextColor(Estilo.TEXTO)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			typeface = Typeface.MONOSPACE
			setTextIsSelectable(true)
			movementMethod = ScrollingMovementMethod()
		}
		root.addView(ScrollView(this).apply {
			addView(output)
			background = Estilo.vidro(context, 0)
			setPadding(dp(16), dp(14), dp(16), dp(14))
			clipToOutline = true
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				0
			).apply { weight = 1f; topMargin = dp(8) }
		})

		setContentView(root)
		// Quem chegou pelo "Report a problem" do lancador ja pediu o relatorio;
		// obrigar a achar o botao de novo seria repetir a pergunta. Depois da
		// primeira leitura, que agora chega por fora da thread principal e
		// escreveria por cima do relatorio.
		refresh {
			if(intent?.getBooleanExtra(EXTRA_REPORT, false) == true)
				reportProblem()
		}
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


	/** Uma fileira de cartoes tocaveis, o mesmo cartao de todas as telas. */
	private fun fileiraDeBotoes(vararg botoes: Pair<String, () -> Unit>) =
			LinearLayout(this).apply {
		orientation = LinearLayout.HORIZONTAL
		layoutParams = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.WRAP_CONTENT
		).apply { bottomMargin = dp(8) }
		for((rotulo, onClick) in botoes)
			addView(Estilo.botao(context, rotulo, onClick).apply {
				gravity = Gravity.CENTER
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
				layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
					.apply { weight = 1f; marginStart = dp(4); marginEnd = dp(4) }
			})
	}

	/**
	 * A bancada de video, que morava no lancador como "Test bench" e abria uma
	 * tela preta sem dizer nada -- o dono perguntou se era defeito. Agora ela
	 * avisa antes o que vai acontecer.
	 */
	private fun abrirBancada()
	{
		output.text = "--- video benchmark ---\n\n" +
				"Opening a test in the headset: a pattern generated on this device plays\n" +
				"through the whole video path, with no console and no network. The screen\n" +
				"may look dark or plain -- that is the test. It closes by itself; the\n" +
				"result goes to the diary (Refresh here afterwards).\n"
		try
		{
			startActivity(Intent(this, BancoDeEnsaioActivity::class.java))
		}
		catch(e: Throwable)
		{
			output.append("\nCould not open it: ${e::class.java.simpleName}: ${e.message}\n")
		}
	}

	private fun dp(valor: Int): Int = Estilo.dp(this, valor)

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
			output.text = "Could not start the server (${server.falha}).\n\n${output.text}"
	}

	/**
	 * Monta o texto fora da thread principal e so entrega o pronto.
	 *
	 * Sao dois `logcat -d` (processos externos), o diario de meio mega e a
	 * redacao sobre o logcat inteiro: na principal isso travava a tela a cada
	 * abertura e a cada Refresh.
	 */
	private fun refresh(depois: (() -> Unit)? = null)
	{
		Thread({
			val texto = montarTexto()
			runOnUiThread {
				if(isDestroyed)
					return@runOnUiThread
				output.text = texto
				depois?.invoke()
			}
		}, "p5m-diagnostico").start()
	}

	private fun montarTexto(): String
	{
		// Uma ultima captura antes de mostrar: o processo da sessao ja morreu,
		// mas as linhas dele podem continuar no buffer, e esta e a ultima
		// chance de salva-las.
		Trace.captureNativeLines(this)

		val crash = P5MApp.lastCrash(this)
		val pilha = PilhaNativa.ultima(this)
		return buildString {
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

	/**
	 * Responde a unica pergunta que sustenta haptica crua e gatilhos no Quest:
	 * um relatorio HID de saida chega ao DualSense por aqui?
	 *
	 * A resposta e a lightbar, e nao o texto: se ela varrer vermelho, verde e
	 * azul, chegou. O texto na tela existe para o caso contrario, onde o que
	 * importa e qual das quatro formas foi tentada e o que cada uma respondeu.
	 */
	private fun sondarDualSense()
	{
		if(!DualSenseHid.disponivel(this))
		{
			// O pedido de permissao volta em onRequestPermissionsResult, e a
			// sonda recomeca de la. Pedir e sondar na mesma passada rodaria a
			// sonda antes de o usuario ter respondido.
			output.text = "Asking for Bluetooth permission. Answer the prompt, then press the button again."
			requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), PEDIDO_BLUETOOTH)
			return
		}

		if(bancadaRodando)
			return
		bancadaRodando = true
		output.text = "Two passes of red, green and blue, each colour held for a second, with a short pause between them. Watch the lightbar: pass 1 is exactly what a game session sends, pass 2 releases the lightbar from the firmware first."
		val sonda = hid ?: DualSenseHid(this).also { hid = it }
		Thread {
			val relatorio = sonda.sondar()
			runOnUiThread {
				bancadaRodando = false
				output.text = "--- DualSense HID probe ---\n\n" + relatorio +
						"\nBoth passes go through the interrupt channel. Did either one light up?"
			}
		}.start()
	}

	/**
	 * Bancada da haptica: um tom conhecido na bobina, sem console no meio.
	 *
	 * Em jogo a resposta chega como "achei estranho", porque o que se sente e a
	 * soma de tudo. Aqui e um tom de 90 Hz quase na escala cheia, quatro vezes,
	 * e a unica diferenca entre as passadas e como a rota de audio foi armada
	 * antes. O que se quer saber e uma coisa so: qual delas fez a bobina tocar.
	 */
	private fun ensaiarHaptica()
	{
		if(!DualSenseHid.disponivel(this))
		{
			output.text = "Asking for Bluetooth permission. Answer the prompt, then press the button again."
			requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), PEDIDO_BLUETOOTH)
			return
		}

		if(bancadaRodando)
			return
		bancadaRodando = true
		output.text = "Three bursts of a 90 Hz tone: full strength, two thirds, one third. Hold the controller and judge the first one — that is the ceiling."
		val ponte = hid ?: DualSenseHid(this).also { hid = it }
		Thread {
			val relatorio = DualSenseHaptics(this, ponte).ensaiar()
			runOnUiThread {
				bancadaRodando = false
				output.text = "--- DualSense haptics bench ---\n\n" + relatorio +
						"\nWas the first burst strong? If yes, the ceiling is high and the game track is what is quiet. If no, this is as hard as the coil goes."
			}
		}.start()
	}

	/**
	 * Bancada dos gatilhos adaptativos: um efeito conhecido em cada um, sem
	 * console. Responde se o caminho app -> controle funciona antes de alguem
	 * procurar um jogo que use gatilho.
	 */
	private fun ensaiarGatilhos()
	{
		if(!DualSenseHid.disponivel(this))
		{
			output.text = "Asking for Bluetooth permission. Answer the prompt, then press the button again."
			requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), PEDIDO_BLUETOOTH)
			return
		}
		if(bancadaRodando)
			return
		bancadaRodando = true
		output.text = "Pull both triggers now, for eight seconds. The left one should resist from " +
				"about halfway down; the right one should resist and then give way with a click, " +
				"like a gun trigger."
		val ponte = hid ?: DualSenseHid(this).also { hid = it }
		Thread {
			val relatorio = EspelhoDualSense.ensaiar(this, ponte)
			runOnUiThread {
				bancadaRodando = false
				output.text = "--- DualSense adaptive triggers bench ---\n\n" + relatorio +
						"\nDid you feel both? If yes, the triggers work, and a game that does not " +
						"move them is not sending effects. If no, send the diary."
			}
		}.start()
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
		grantResults: IntArray)
	{
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		// Nao refaz a acao sozinho: o pedido pode ter vindo de qualquer um dos
		// dois botoes, e adivinhar qual deles erraria metade das vezes.
		if(requestCode == PEDIDO_BLUETOOTH && DualSenseHid.disponivel(this))
			output.text = "Bluetooth permission granted. Press the button again."
	}

	override fun onDestroy()
	{
		super.onDestroy()
		// Nao deixa servidor de pe depois que a ultima tela de diagnostico fecha.
		server.fechar()
		hid?.fechar()
	}

	companion object
	{
		private const val PEDIDO_BLUETOOTH = 4201
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
			"Audio output", "Rumble", "P5M: ", "Haptics 10s:", "Haptics timing:", "Input timing:", "Menu control:",
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
