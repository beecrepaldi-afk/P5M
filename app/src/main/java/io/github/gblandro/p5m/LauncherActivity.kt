// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Porta de entrada do app.
 *
 * O Horizon OS mostra **um ícone por pacote**, não um por activity de
 * lançamento. A tela de diagnóstico, que tinha entrada própria no lançador,
 * simplesmente nunca apareceu na biblioteca -- ficou inalcançável justamente
 * quando era mais necessária.
 *
 * Esta tela resolve isso sendo o único ponto de entrada, e de quebra é um
 * experimento: ela não usa AppCompat, nem layout XML, nem tema do chiaki-ng,
 * nem carrega biblioteca nativa nenhuma. Se ela renderizar, sabemos que o
 * processo sobe e que painel 2D funciona, e o problema está adiante. Se nem
 * ela renderizar, o problema é anterior a qualquer activity.
 *
 * ## Por que ela foi redesenhada
 *
 * Ela cresceu por acumulação: cada recurso novo virou mais um botão de largura
 * inteira, com um parágrafo cinza embaixo. Quatorze botões idênticos depois,
 * "Abrir P5M" -- a única coisa que alguém abre o app para fazer -- tinha
 * exatamente o mesmo peso visual que "profundidade da tela no 3D", e os oito
 * parágrafos simultâneos transformavam explicação em ruído.
 *
 * Três decisões consertam isso, e todas são sobre **hierarquia**:
 *
 * - **Jogar não é um ajuste.** A ação principal é um bloco só, colorido, no
 *   topo. Todo o resto é lista.
 * - **Ajuste é linha, não botão.** Rótulo à esquerda, valor à direita. Uma
 *   lista de ajustes se lê de relance; uma pilha de botões com o valor
 *   embutido no texto tem de ser lida palavra por palavra.
 * - **Uma explicação só, embaixo, sobre o que você acabou de tocar.** Era o
 *   que mais poluía: oito parágrafos disputando atenção para explicar coisas
 *   que ninguém está fazendo agora. Como texto de ajuda só interessa sobre o
 *   que se está mexendo, ele fica num lugar fixo e muda com o toque.
 *
 * E os ajustes de 3D só aparecem com o 3D ligado. Dependência mostrada custa
 * menos que dependência explicada.
 */
class LauncherActivity: Activity()
{
	private lateinit var explicacao: TextView
	private lateinit var status: TextView

	/**
	 * O que refazer depois de qualquer toque.
	 *
	 * Cada linha registra aqui como se redesenhar. Antes cada botão tinha um
	 * campo `lateinit` e cada clique atualizava os vizinhos na mão -- foi assim
	 * que o painel passou a mostrar valor velho quando um ajuste mexia noutro.
	 * Com a lista, um toque redesenha tudo e não há como esquecer alguém.
	 */
	private val atualizacoes = mutableListOf<() -> Unit>()

	private val prefs by lazy { StreamQualityPrefs(this) }

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val coluna = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(28), dp(24), dp(28), dp(28))
		}

		coluna.addView(cabecalho())

		if(P5MApp.lastCrash(this) != null)
			coluna.addView(avisoDeQueda())

		// -------------------------------------------------------------- jogar
		coluna.addView(acaoPrincipal("Play") {
			launch(Intent().setClassName(packageName, CHIAKI_MAIN_ACTIVITY), "the console list")
		})
		coluna.addView(acaoSecundaria("Remote connection (PSN)",
				"For playing away from home. The list on the main screen is found by "
						+ "broadcast on your local network, which does not cross the "
						+ "internet; this addresses the console through your PSN account.") {
			launch(Intent(this, PsnRemoteActivity::class.java), "the remote connection")
		})

		// ------------------------------------------------------------ imagem
		coluna.addView(secao("Picture"))
		coluna.addView(linha("Mode", { DisplayMode.label(currentMode()) }, { modeHint() }) {
			val mode = DisplayMode.toggle(this)
			Log.i(TAG, "Display mode: ${DisplayMode.label(mode)}")
		})
		coluna.addView(linha("Video path", { pathValue() }, { pathHint() }) {
			// Três estados num botão só, e não dois, porque o segundo depende do
			// primeiro: quadro previsto precisa do pipeline de GL que só existe
			// no caminho com shader. Separados, seria possível pedir previsão no
			// caminho direto -- e não fazer nada.
			when
			{
				!prefs.toneMapped ->
				{
					prefs.toneMapped = true
					prefs.frameExtrapolation = false
				}
				!prefs.frameExtrapolation -> prefs.frameExtrapolation = true
				else ->
				{
					prefs.toneMapped = false
					prefs.frameExtrapolation = false
				}
			}
			Log.i(TAG, "Video path: shader=${prefs.toneMapped} "
					+ "extrapolated=${prefs.frameExtrapolation}")
		})
		coluna.addView(linha("Color", { if(prefs.tenBit) "10-bit" else "8-bit" }, { colorHint() }) {
			prefs.tenBit = !prefs.tenBit
			Log.i(TAG, "10-bit: ${prefs.tenBit}")
		})
		coluna.addView(linha("Sharpness",
				{ StreamQualityPrefs.SHARPNESS_NAMES[prefs.sharpness] }, { sharpHint() }) {
			prefs.sharpness = (prefs.sharpness + 1) % 6
			Log.i(TAG, "Sharpness: ${prefs.sharpness} "
					+ "(${StreamQualityPrefs.SHARPNESS_NAMES[prefs.sharpness]})")
		})

		// --------------------------------------------------------------- 3D
		coluna.addView(secao("3D"))
		coluna.addView(linha("Emulated 3D", { if(prefs.syntheticStereo) "on" else "off" },
				{ stereoHint() }) {
			prefs.syntheticStereo = !prefs.syntheticStereo
			Log.i(TAG, "Synthetic 3D: ${prefs.syntheticStereo}")
		})
		// Só aparecem com o 3D ligado: sem ele são dois controles que não fazem
		// nada, e um controle inerte na tela é pior que um ausente.
		coluna.addView(linha("Strength", { pct(prefs.stereoStrength) }, { strengthHint() },
				visivel = { prefs.syntheticStereo }) {
			prefs.stereoStrength = (((prefs.stereoStrength * 4f).toInt() + 1) % 5) / 4f
			Log.i(TAG, "3D strength: ${(prefs.stereoStrength * 100).toInt()}%")
		})
		coluna.addView(linha("Screen depth", { pct(prefs.stereoConvergence) },
				{ convergenceHint() }, visivel = { prefs.syntheticStereo }) {
			prefs.stereoConvergence = (((prefs.stereoConvergence * 4f).toInt() + 1) % 5) / 4f
			Log.i(TAG, "3D convergence: ${(prefs.stereoConvergence * 100).toInt()}%")
		})

		// ------------------------------------------------------- mãos e ouvidos
		coluna.addView(secao("Controller and sound"))
		coluna.addView(linha("Rumble", { if(prefs.hapticRumble) "haptic" else "classic" },
				{ rumbleHint() }) {
			prefs.hapticRumble = !prefs.hapticRumble
			Log.i(TAG, "Rumble: ${if(prefs.hapticRumble) "haptic (DualSense)"
					else "classic (DualShock 4)"}")
		})
		coluna.addView(linha("Spatial audio",
				{ StreamQualityPrefs.SPATIAL_NAMES[prefs.spatialAudio] }, { audioHint() }) {
			prefs.spatialAudio = (prefs.spatialAudio + 1) % 4
			Log.i(TAG, "Spatial audio: ${prefs.spatialAudio} "
					+ "(${StreamQualityPrefs.SPATIAL_NAMES[prefs.spatialAudio]})")
		})
		coluna.addView(navegacao("Stick calibration",
				"Measures where each stick rests and how much it jitters, and sets the "
						+ "deadzone from that. Worth doing once per controller.") {
			launch(Intent(this, StickCalibrationActivity::class.java), "stick calibration")
		})

		// ----------------------------------------------------------- ferramentas
		coluna.addView(secao("Tools"))
		coluna.addView(navegacao("Diagnostics",
				"The app diary, the last crash, and the process log — readable inside "
						+ "the headset, with no cable.") {
			launch(Intent(this, DiagnosticActivity::class.java), "diagnostics")
		})
		coluna.addView(navegacao("Test bench",
				"Runs the whole video path against a source generated on this device — "
						+ "no console and no network. Tells network apart from everything else.") {
			launch(Intent(this, BancoDeEnsaioActivity::class.java), "the test bench")
		})

		// ------------------------------------------------------------ rodapé
		explicacao = TextView(this).apply {
			setTextColor(COR_APAGADA)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			setLineSpacing(dp(3).toFloat(), 1f)
			setPadding(dp(18), dp(16), dp(18), dp(16))
			background = fundoArredondado(COR_RODAPE)
			text = "Touch anything to see what it does."
		}
		coluna.addView(explicacao, comMargem(dp(20), 0))

		status = TextView(this).apply {
			setTextColor(COR_ALERTA)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			gravity = Gravity.CENTER
			visibility = View.GONE
		}
		coluna.addView(status, comMargem(dp(14), 0))

		// Com rolagem: os ajustes cresceram e o que ficava no fim caía abaixo da
		// borda da janela, inalcançável -- e o primeiro a cair foi justamente o
		// diagnóstico, que existe para quando algo dá errado.
		setContentView(ScrollView(this).apply {
			setBackgroundColor(COR_FUNDO)
			isFillViewport = true
			addView(coluna, LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT))
		})

		atualizar()
		Log.i(TAG, "LauncherActivity opened, version ${versionName()}")
	}

	// ------------------------------------------------------------- as peças

	private fun cabecalho() = LinearLayout(this).apply {
		orientation = LinearLayout.HORIZONTAL
		gravity = Gravity.BOTTOM
		setPadding(dp(4), 0, dp(4), dp(22))
		addView(TextView(context).apply {
			text = "P5M"
			setTextColor(Color.WHITE)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
			setTypeface(typeface, Typeface.BOLD)
			layoutParams = LinearLayout.LayoutParams(0,
					LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
		})
		addView(TextView(context).apply {
			text = versionName()
			setTextColor(COR_APAGADA)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			setPadding(0, 0, 0, dp(5))
		})
	}

	/** O aviso de queda tem cor própria: ele não é um ajuste, é uma notícia. */
	private fun avisoDeQueda() = TextView(this).apply {
		text = "The app crashed the last time it ran. The reason is in Diagnostics."
		setTextColor(COR_ALERTA)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
		setPadding(dp(18), dp(14), dp(18), dp(14))
		background = fundoArredondado(COR_ALERTA_FUNDO)
		layoutParams = comMargem(0, dp(16))
	}

	private fun acaoPrincipal(rotulo: String, onClick: () -> Unit) = TextView(this).apply {
		text = rotulo
		setTextColor(Color.WHITE)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
		setTypeface(typeface, Typeface.BOLD)
		gravity = Gravity.CENTER
		setPadding(dp(20), dp(22), dp(20), dp(22))
		background = fundoTocavel(COR_ACENTO, COR_ACENTO_PRESSIONADO)
		isFocusable = true
		isClickable = true
		setOnClickListener { onClick() }
		layoutParams = comMargem(0, dp(10))
	}

	private fun acaoSecundaria(rotulo: String, dica: String, onClick: () -> Unit) =
			TextView(this).apply {
		text = rotulo
		setTextColor(COR_TEXTO)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
		gravity = Gravity.CENTER
		setPadding(dp(20), dp(18), dp(20), dp(18))
		background = fundoTocavel(COR_CARTAO, COR_CARTAO_PRESSIONADO)
		isFocusable = true
		isClickable = true
		setOnClickListener { explicar(dica); onClick() }
		layoutParams = comMargem(0, dp(22))
	}

	/** Cabeçalho de seção: separa sem pesar. Por isso pequeno, espaçado e apagado. */
	private fun secao(titulo: String) = TextView(this).apply {
		text = titulo.uppercase()
		setTextColor(COR_SECAO)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
		setTypeface(typeface, Typeface.BOLD)
		letterSpacing = 0.14f
		setPadding(dp(6), dp(18), dp(6), dp(10))
	}

	/**
	 * Uma linha de ajuste: rótulo à esquerda, valor à direita.
	 *
	 * O valor vem de uma função e não de um texto porque ele muda com o toque --
	 * e às vezes com o toque em **outra** linha, como o caminho de vídeo, que o
	 * 3D força. Guardar o texto pronto foi o que fez o painel mostrar valor
	 * velho; guardar como calculá-lo não tem esse defeito.
	 */
	private fun linha(rotulo: String, valor: () -> String, dica: () -> String,
			visivel: () -> Boolean = { true }, onClick: () -> Unit): View
	{
		val texto = TextView(this).apply {
			text = rotulo
			setTextColor(COR_TEXTO)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
			layoutParams = LinearLayout.LayoutParams(0,
					LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
		}
		val valorView = TextView(this).apply {
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
			setTypeface(typeface, Typeface.BOLD)
			gravity = Gravity.END
		}
		val fila = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(dp(18), dp(16), dp(18), dp(16))
			background = fundoTocavel(COR_CARTAO, COR_CARTAO_PRESSIONADO)
			isFocusable = true
			isClickable = true
			addView(texto)
			addView(valorView)
			layoutParams = comMargem(0, dp(6))
			setOnClickListener {
				onClick()
				explicar(dica())
				atualizar()
			}
		}
		atualizacoes += {
			val v = valor()
			valorView.text = v
			// Valor desligado fica apagado. É o que deixa a lista inteira
			// legível de relance: o que está ativo salta, o resto recua.
			valorView.setTextColor(if(v == "off" || v == "off (2D)") COR_APAGADA else COR_ACENTO_CLARO)
			fila.visibility = if(visivel()) View.VISIBLE else View.GONE
		}
		return fila
	}

	/** Linha que abre outra tela. O chevron diz que leva a algum lugar. */
	private fun navegacao(rotulo: String, dica: String, onClick: () -> Unit) =
			LinearLayout(this).apply {
		orientation = LinearLayout.HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
		setPadding(dp(18), dp(16), dp(18), dp(16))
		background = fundoTocavel(COR_CARTAO, COR_CARTAO_PRESSIONADO)
		isFocusable = true
		isClickable = true
		addView(TextView(context).apply {
			text = rotulo
			setTextColor(COR_TEXTO)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
			layoutParams = LinearLayout.LayoutParams(0,
					LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
		})
		addView(TextView(context).apply {
			text = "›"
			setTextColor(COR_SECAO)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
		})
		layoutParams = comMargem(0, dp(6))
		setOnClickListener { explicar(dica); onClick() }
	}

	private fun explicar(texto: String)
	{
		explicacao.text = texto
	}

	private fun atualizar()
	{
		for(f in atualizacoes)
			f()
	}

	// ------------------------------------------------------------ os textos

	private fun currentMode(): Int = DisplayMode.current(this)

	private fun modeHint() = if(currentMode() == DisplayMode.IMMERSIVE)
		"Curved screen, managed color, refresh rate matched to the source, and in-game "
				.plus("tuning on L3+R3. Takes the whole display.")
	else
		"A system panel, side by side with your other apps. No curved screen, no managed "
				.plus("color, no L3+R3 tuning panel.")

	private fun pathValue() = when
	{
		prefs.syntheticStereo -> "shader (3D)"
		!prefs.toneMapped -> "direct"
		!prefs.frameExtrapolation -> "shader"
		else -> "shader + frames"
	}

	private fun pathHint() = when
	{
		prefs.syntheticStereo ->
			"3D needs a GPU pass of ours to warp the image, so it holds the shader path "
					.plus("on. Turn 3D off to get this back.")
		!prefs.toneMapped ->
			"From the network to the compositor with no copy. The lowest latency there is."
		!prefs.frameExtrapolation ->
			"Correct 10-bit color, at the cost of one GPU pass."
		else ->
			"120 frames per second out of the source's 60, extrapolated by the Adreno. "
					.plus("No added latency; artifacts wherever the guess is wrong.")
	}

	private fun colorHint() = if(prefs.tenBit)
		"No banding in gradients, but blown-out whites: this decoder does not tone map. "
				.plus("Reconnect to switch.")
	else
		"The Remote Play default, and what gives correct color today."

	private fun sharpHint() = when
	{
		prefs.sharpness == 0 ->
			"The image as the console delivers it, with no sharpening."
		prefs.syntheticStereo && prefs.sharpness >= StreamQualityPrefs.SHARPNESS_MQSR ->
			"MQSR does not apply in 3D — the layer is half of a texture there, and the "
					.plus("compositor filter draws an X across it. Falls back to the shader.")
		prefs.sharpness >= StreamQualityPrefs.SHARPNESS_MQSR ->
			"A compositor filter, not a strength. MQSR only does anything with the screen "
					.plus("big enough that there is something to upscale.")
		currentMode() == DisplayMode.IMMERSIVE ->
			"This also changes in game, with Square on the tuning panel (L3+R3)."
		else ->
			"In window mode the sharpening is a shader: it costs one GPU pass, because "
					.plus("there is no layer of ours in the compositor.")
	}

	private fun stereoHint() = if(prefs.syntheticStereo)
		"The console sends a flat image. The app guesses depth from it and builds the "
				.plus("second eye. It is a guess, so some scenes come out wrong — the aim ")
				.plus("is a sense of volume, not accurate depth.")
	else
		"Turn this on to play in 3D. It holds the shader video path on and costs GPU. "
				.plus("Reconnect to switch.")

	private fun strengthHint() =
		"How far the near and the far separate. The ceiling is physical, not taste: past "
				.plus("your interpupillary distance the eyes would have to diverge, which ")
				.plus("they cannot. Also on Options in the tuning panel, in game.")

	private fun convergenceHint() =
		"What sits exactly on the screen. Lower makes more of the scene pop out toward "
				.plus("you; higher pushes the scene behind the screen.")

	private fun rumbleHint() = if(prefs.hapticRumble)
		"The console sends raw haptics and we guess the strength from the envelope. It "
				.plus("buzzes at moments the game never asked for. Reconnect to switch.")
	else
		"The console itself reduces the game's haptics to two motors, the way it does "
				.plus("for a DualShock 4. Reconnect to switch.")

	private fun audioHint() = when
	{
		prefs.spatialAudio == 0 ->
			"Stereo as the console sends it, locked to your head."
		currentMode() != DisplayMode.IMMERSIVE ->
			"In window mode Horizon OS does the positioning, not this setting: the sound "
					.plus("goes out through the system mixer so that it can.")
		else ->
			"The two channels become speakers on the screen. Turn your head and the sound "
					.plus("stays where the screen is.")
	}

	private fun pct(v: Float) = "${(v * 100).toInt()}%"

	// ------------------------------------------------------------- utilidades

	/**
	 * Abre outra activity mostrando o erro na tela se falhar.
	 *
	 * Sem isto, uma activity que nao inicia devolve o usuario para uma tela sem
	 * explicacao -- que foi exatamente como a falha original se apresentou. O
	 * log do primeiro teste em hardware mostrou processo saudavel, sem crash,
	 * e nenhuma linha das nossas activities: elas simplesmente nunca rodaram.
	 */
	private fun launch(intent: Intent, what: String)
	{
		try
		{
			Log.i(TAG, "Opening $what")
			startActivity(intent)
		}
		catch(e: Throwable)
		{
			Log.e(TAG, "Failed to open $what", e)
			P5MApp.saveCrash(this, "launch:$what", e)
			status.visibility = View.VISIBLE
			status.text = "Failed to open $what:\n${e::class.java.simpleName}: ${e.message}"
		}
	}

	private fun dp(valor: Int): Int =
		(valor * resources.displayMetrics.density).toInt()

	private fun comMargem(topo: Int, base: Int) = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, topo, 0, base) }

	private fun fundoArredondado(cor: Int) = GradientDrawable().apply {
		shape = GradientDrawable.RECTANGLE
		cornerRadius = dp(14).toFloat()
		setColor(cor)
	}

	/**
	 * Fundo com estado de toque e de foco.
	 *
	 * Os dois importam por motivos diferentes: dentro do headset o raio do
	 * controle dá *hover*, e o gamepad dá *foco*. Sem retorno visual, a tela
	 * parece travada enquanto se aponta para ela.
	 */
	private fun fundoTocavel(normal: Int, aceso: Int) = StateListDrawable().apply {
		addState(intArrayOf(android.R.attr.state_pressed), fundoArredondado(aceso))
		addState(intArrayOf(android.R.attr.state_focused), fundoArredondado(aceso))
		addState(intArrayOf(android.R.attr.state_hovered), fundoArredondado(aceso))
		addState(intArrayOf(), fundoArredondado(normal))
	}

	private fun versionName(): String = try
	{
		packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
	}
	catch(e: Exception)
	{
		"?"
	}

	companion object
	{
		/**
		 * Referenciada por nome para não arrastar as classes do chiaki-ng para
		 * dentro desta tela: se elas falharem ao carregar, o carregamento tem
		 * de acontecer só ao tocar no botão, não ao abrir o app.
		 */
		private const val CHIAKI_MAIN_ACTIVITY = "com.metallic.chiaki.main.MainActivity"
		private const val TAG = "P5MVR"

		private val COR_FUNDO = Color.parseColor("#0E0E13")
		private val COR_CARTAO = Color.parseColor("#191921")
		private val COR_CARTAO_PRESSIONADO = Color.parseColor("#262631")
		private val COR_RODAPE = Color.parseColor("#15151C")
		private val COR_TEXTO = Color.parseColor("#E8EAED")
		private val COR_APAGADA = Color.parseColor("#8A9099")
		private val COR_SECAO = Color.parseColor("#6E7480")
		private val COR_ACENTO = Color.parseColor("#2F6FE4")
		private val COR_ACENTO_PRESSIONADO = Color.parseColor("#4886F0")
		private val COR_ACENTO_CLARO = Color.parseColor("#79A9FF")
		private val COR_ALERTA = Color.parseColor("#FF8A80")
		private val COR_ALERTA_FUNDO = Color.parseColor("#2A1618")
	}
}
