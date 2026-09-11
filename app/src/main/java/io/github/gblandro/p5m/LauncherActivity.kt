// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Lancador nativo: categorias compactas e uma pagina de ajustes por vez.
 * O layout acompanha o tamanho medido da janela, inclusive sem recriar a
 * activity. Os valores e a ajuda continuam vindo das preferencias reais.
 */
class LauncherActivity: Activity()
{
	private lateinit var explicacao: TextView
	private lateinit var status: TextView
	private var categoria = "Play"
	private var selecionarCategoria: ((String) -> Unit)? = null
	private var renderedDensity = 0
	private var renderedFontScale = 0f

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
		categoria = savedInstanceState?.getString("category") ?: "Play"
		construir()
	}

	private fun construir()
	{
		atualizacoes.clear()
		renderedDensity = resources.configuration.densityDpi
		renderedFontScale = resources.configuration.fontScale

		val coluna = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(0, 0, 0, dp(8))
		}

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

		// Antes de qualquer ajuste, e nao no rodape: o acorde e a unica forma de
		// mexer na tela depois que o jogo comeca, e ninguem descobre sozinho.
		coluna.addView(cartaoDoAcorde())

		// ------------------------------------------------------------ imagem
		coluna.addView(secao("Picture"))
		coluna.addView(linha("Mode", {
			if(prefs.syntheticStereo) "Immersive (3D)" else DisplayMode.label(currentMode())
		}, { modeHint() }, editavel = { !prefs.syntheticStereo }) {
			val mode = DisplayMode.toggle(this)
			Log.i(TAG, "Display mode: ${DisplayMode.label(mode)}")
		})
		coluna.addView(linha("Video path", { pathValue() }, { pathHint() },
			editavel = { currentMode() == DisplayMode.IMMERSIVE }) {
			// Três estados num botão só, e não dois, porque o segundo depende do
			// primeiro: quadro previsto precisa do pipeline de GL que só existe
			// no caminho com shader. Separados, seria possível pedir previsão no
			// caminho direto -- e não fazer nada.
			when
			{
				!prefs.immersiveUsesShader ->
				{
					prefs.toneMapped = true
					prefs.frameExtrapolation = false
				}
				!prefs.frameExtrapolation -> prefs.frameExtrapolation = true
				else ->
				{
					prefs.toneMapped = prefs.syntheticStereo || prefs.tenBit
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
				{ StreamQualityPrefs.SHARPNESS_NAMES[effectiveSharpness()] }, { sharpHint() }) {
			prefs.sharpness = if(currentMode() == DisplayMode.WINDOW)
				(effectiveSharpness() + 1) % 4 else (prefs.sharpness + 1) % 6
			Log.i(TAG, "Sharpness: ${prefs.sharpness} "
					+ "(${StreamQualityPrefs.SHARPNESS_NAMES[prefs.sharpness]})")
		})

		// --------------------------------------------------------------- 3D
		coluna.addView(secao("3D"))
		coluna.addView(linha("Emulated 3D", { if(prefs.syntheticStereo) "on" else "off" },
				{ stereoHint() }) {
			prefs.syntheticStereo = !prefs.syntheticStereo
			if(prefs.syntheticStereo) DisplayMode.set(this, DisplayMode.IMMERSIVE)
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
		coluna.addView(secao("Controller"))
		coluna.addView(linha("Rumble", { if(prefs.hapticRumble) "haptic" else "classic" },
				{ rumbleHint() }) {
			prefs.hapticRumble = !prefs.hapticRumble
			Log.i(TAG, "Rumble: ${if(prefs.hapticRumble) "haptic (DualSense)"
					else "classic (DualShock 4)"}")

			// A permissao e pedida aqui, ao ligar, e nao na hora de conectar: um
			// dialogo do sistema no meio da entrada do stream cairia por cima do
			// modo imersivo, e responder a ele de dentro do headset com a sessao
			// subindo e pior do que responder agora, parado no lancador.
			if(prefs.hapticRumble && !DualSenseHid.disponivel(this))
				requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT),
						PEDIDO_BLUETOOTH)
		})
		coluna.addView(linha("Spatial audio",
				{ if(currentMode() == DisplayMode.WINDOW) "System" else StreamQualityPrefs.SPATIAL_NAMES[prefs.spatialAudio] },
				{ audioHint() }, editavel = { currentMode() == DisplayMode.IMMERSIVE }) {
			prefs.spatialAudio = (prefs.spatialAudio + 1) % 4
			Log.i(TAG, "Spatial audio: ${prefs.spatialAudio} "
					+ "(${StreamQualityPrefs.SPATIAL_NAMES[prefs.spatialAudio]})")
		})
		coluna.addView(linha("Settings chord",
				{ StreamQualityPrefs.CHORD_NAMES[prefs.tuningChord] }, { chordHint() },
				visivel = { currentMode() == DisplayMode.IMMERSIVE }) {
			prefs.tuningChord = (prefs.tuningChord + 1) % StreamQualityPrefs.CHORD_NAMES.size
			Log.i(TAG, "Tuning chord: ${StreamQualityPrefs.CHORD_NAMES[prefs.tuningChord]}")
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
		coluna.addView(navegacao("Report a problem",
				"Packs up what happened in this session and opens a report you can send. "
						+ "Nothing leaves the headset until you press send.") {
			launch(Intent(this, DiagnosticActivity::class.java)
					.putExtra(DiagnosticActivity.EXTRA_REPORT, true), "the report")
		})

		// --------------------------------------------------------------- apoio
		// O apoio fica em Tools, junto do diagnostico e dos links externos.
		coluna.addView(navegacao("Support development on Patreon",
				"P5M is free and the source is open. If it is useful to you, chipping in "
						+ "pays for the hours that keep it moving.") {
			abrir(PATREON_URL, "Patreon")
		})

		// ------------------------------------------------------------ rodapé
		explicacao = TextView(this).apply {
			setTextColor(COR_APAGADA)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			setLineSpacing(dp(3).toFloat(), 1f)
			setPadding(dp(16), dp(13), dp(16), dp(13))
			background = fundoArredondado(COR_RODAPE)
			text = "Choose Play to connect, or select a setting to see what it does."
		}

		status = TextView(this).apply {
			setTextColor(COR_ALERTA)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			gravity = Gravity.CENTER
			visibility = View.GONE
		}

		montarMenu(coluna)

		atualizar()
		Log.i(TAG, "LauncherActivity opened, version ${versionName()}")
	}

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putString("category", categoria)
		super.onSaveInstanceState(outState)
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		// Largura/altura sao tratadas pelo layout, preservando foco e rolagem.
		// Recriar tudo a cada passo do resize gastava trabalho sem necessidade.
		// So refaz as medidas em dp/sp se a escala realmente tiver mudado.
		if(newConfig.densityDpi != renderedDensity || newConfig.fontScale != renderedFontScale)
			construir()
	}

	@Suppress("DEPRECATION")
	override fun onBackPressed() {
		if(categoria != "Play") selecionarCategoria?.invoke("Play")
		else super.onBackPressed()
	}

	private fun montarMenu(origem: LinearLayout) {
		val paginas = linkedMapOf<String, MutableList<View>>("Play" to mutableListOf())
		var grupo = "Play"
		while(origem.childCount > 0) {
			val view = origem.getChildAt(0)
			origem.removeViewAt(0)
			val nome = view.tag as? String
			if(nome != null) {
				grupo = nome
				paginas[grupo] = mutableListOf()
			} else paginas.getValue(grupo).add(view)
		}
		val descricoes = mapOf(
			"Play" to "Your console, ready when you are.",
			"Picture" to "Display mode, video and image clarity.",
			"3D" to "Experimental depth from a flat video stream.",
			"Controller" to "Haptics, sound and your in-game shortcut.",
			"Tools" to "Diagnostics, testing and project support.")
		val titulo = TextView(this).apply {
			setTextColor(COR_TEXTO)
			textSize = 26f
			setTypeface(typeface, Typeface.BOLD)
		}
		val subtitulo = TextView(this).apply {
			setTextColor(COR_APAGADA)
			textSize = 14f
			setPadding(0, dp(6), 0, dp(20))
		}
		val conteudo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		val rolagem = ScrollView(this).apply {
			isFillViewport = true
			addView(conteudo)
		}
		val detalhe = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(20), dp(16), dp(20), dp(16))
			background = fundoArredondado(COR_RODAPE)
			addView(titulo)
			addView(subtitulo)
			addView(rolagem, LinearLayout.LayoutParams(-1, 0, 1f))
			addView(explicacao)
			addView(status)
		}
		explicacao.setPadding(dp(2), dp(14), dp(2), 0)
		explicacao.background = null
		val categorias = LinearLayout(this)
		val barra = HorizontalScrollView(this).apply {
			isHorizontalScrollBarEnabled = false
		}
		val botoes = linkedMapOf<String, TextView>()
		fun selecionar(nome: String) {
			categoria = nome
			titulo.text = if(nome == "Play") "Let's play" else nome
			subtitulo.text = descricoes[nome]
			conteudo.removeAllViews()
			for(view in paginas.getValue(nome)) conteudo.addView(view)
			rolagem.scrollTo(0, 0)
			for((key, view) in botoes) {
				view.isSelected = key == nome
				view.setTextColor(if(key == nome) COR_ACENTO_CLARO else COR_APAGADA)
			}
			explicar(if(nome == "Play") "Choose Play to find a console on your local network."
				else "Select a setting to change it. Changes are saved automatically.")
			atualizar()
		}
		for(nome in paginas.keys) {
			val botao = TextView(this).apply {
				id = View.generateViewId()
				text = nome
				textSize = 16f
				setTypeface(typeface, Typeface.BOLD)
				gravity = Gravity.CENTER_VERTICAL
				setPadding(dp(16), dp(14), dp(16), dp(14))
				minHeight = dp(48)
				background = fundoTocavel(COR_FUNDO, COR_CARTAO_PRESSIONADO)
				isFocusable = true
				isClickable = true
				setOnClickListener { selecionar(nome) }
			}
			botoes[nome] = botao
			categorias.addView(botao)
		}
		selecionarCategoria = { nome -> selecionar(nome); botoes[nome]?.requestFocus() }
		selecionar(categoria.takeIf { it in paginas } ?: "Play")
		val paines = LinearLayout(this)
		val moldura = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(20), dp(16), dp(20), dp(16))
			addView(cabecalho())
			addView(paines, LinearLayout.LayoutParams(-1, 0, 1f))
			addView(TextView(context).apply {
				text = "D-pad / left stick  Navigate    Cross  Select    Circle  Back"
				textSize = 12f
				setTextColor(COR_APAGADA)
				setPadding(dp(4), dp(12), 0, 0)
			})
		}
		var largo: Boolean? = null
		var baixo: Boolean? = null
		// O painel do Quest muda de tamanho sem onCreate. O onMeasure usa a
		// largura atual, e o listener so troca a disposicao ao cruzar o limite.
		val raiz = object: FrameLayout(this) {
			override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
				val limite = minOf(View.MeasureSpec.getSize(widthMeasureSpec), dp(960))
				if(moldura.layoutParams.width != limite) moldura.layoutParams.width = limite
				super.onMeasure(widthMeasureSpec, heightMeasureSpec)
			}
		}.apply {
			setBackgroundColor(COR_FUNDO)
			addView(moldura, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
		}
		raiz.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
			val amplo = r - l >= dp(720)
			val curto = b - t < dp(560)
			if(largo != amplo) {
				largo = amplo
				val foco = currentFocus
				(categorias.parent as? android.view.ViewGroup)?.removeView(categorias)
				paines.removeAllViews()
				paines.orientation = if(amplo) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
				categorias.orientation = if(amplo) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
				for(botao in botoes.values) botao.layoutParams = LinearLayout.LayoutParams(
					if(amplo) -1 else -2, -2).apply { bottomMargin = if(amplo) dp(6) else 0 }
				if(amplo) {
					paines.addView(categorias, LinearLayout.LayoutParams(dp(166), -1).apply { rightMargin = dp(18) })
					paines.addView(detalhe, LinearLayout.LayoutParams(0, -1, 1f))
				} else {
					barra.addView(categorias)
					paines.addView(barra, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
					paines.addView(detalhe, LinearLayout.LayoutParams(-1, 0, 1f))
				}
				foco?.requestFocus()
			}
			if(baixo != curto) {
				baixo = curto
				// Em janela baixa, a ajuda rola junto: nunca esmaga os controles.
				(explicacao.parent as? android.view.ViewGroup)?.removeView(explicacao)
				if(curto) conteudo.addView(explicacao) else detalhe.addView(explicacao, 3)
			}
		}
		// Trocar categoria preserva o rodape no layout compacto.
		val trocar = selecionarCategoria!!
		selecionarCategoria = { nome ->
			trocar(nome)
			if(baixo == true && explicacao.parent == null) conteudo.addView(explicacao)
		}
		for((nome, botao) in botoes) botao.setOnClickListener { selecionarCategoria?.invoke(nome) }
		setContentView(raiz)
	}

	// ------------------------------------------------------------- as peças

	/**
	 * O lembrete do acorde, em destaque e nao numa nota de rodape.
	 *
	 * Vale so no modo imersivo: no modo janela nao existe painel de ajuste, e
	 * anunciar um atalho que nao faz nada e pior que nao anunciar nada.
	 */
	private fun cartaoDoAcorde(): View
	{
		val acorde = TextView(this).apply {
			setTextColor(COR_DESTAQUE)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
			typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
			setPadding(0, 0, dp(14), 0)
		}
		val fila = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(dp(18), dp(14), dp(18), dp(14))
			background = fundoArredondado(COR_DESTAQUE_FUNDO)
			addView(acorde)
			addView(TextView(context).apply {
				text = "While playing, this opens the panel that moves, resizes and "
						.plus("sharpens the screen, and clicks the touchpad.")
				setTextColor(COR_TEXTO)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
				setLineSpacing(dp(3).toFloat(), 1f)
			})
			layoutParams = comMargem(0, dp(22))
		}
		atualizacoes += {
			acorde.text = StreamQualityPrefs.CHORD_NAMES[prefs.tuningChord]
			fila.visibility = if(currentMode() == DisplayMode.IMMERSIVE) View.VISIBLE else View.GONE
		}
		return fila
	}

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
		setPadding(dp(20), dp(16), dp(20), dp(16))
		background = fundoTocavel(COR_ACENTO, COR_ACENTO_PRESSIONADO)
		isFocusable = true
		isClickable = true
		setOnFocusChangeListener { _, focused ->
			if(focused) explicar("Find your console on the local network and start playing.")
		}
		setOnClickListener { onClick() }
		minWidth = dp(180)
		layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) }
	}

	private fun acaoSecundaria(rotulo: String, dica: String, onClick: () -> Unit) =
			TextView(this).apply {
		text = rotulo
		setTextColor(COR_TEXTO)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
		gravity = Gravity.START or Gravity.CENTER_VERTICAL
		setPadding(dp(18), dp(14), dp(18), dp(14))
		background = fundoTocavel(COR_CARTAO, COR_CARTAO_PRESSIONADO)
		isFocusable = true
		isClickable = true
		setOnFocusChangeListener { _, focused -> if(focused) explicar(dica) }
		setOnClickListener { explicar(dica); onClick() }
		layoutParams = comMargem(0, dp(22))
	}

	/** Cabeçalho de seção: separa sem pesar. Por isso pequeno, espaçado e apagado. */
	private fun secao(titulo: String) = TextView(this).apply {
		tag = titulo
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
			visivel: () -> Boolean = { true }, editavel: () -> Boolean = { true },
			onClick: () -> Unit): View
	{
		val texto = TextView(this).apply {
			text = rotulo
			setTextColor(COR_TEXTO)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
			layoutParams = LinearLayout.LayoutParams(0,
					LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 0.55f }
		}
		val valorView = TextView(this).apply {
			layoutParams = LinearLayout.LayoutParams(0, -2, 0.45f)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
			setTypeface(typeface, Typeface.BOLD)
			gravity = Gravity.END
			maxWidth = dp(220)
			setPadding(dp(12), 0, 0, 0)
		}
		val fila = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(dp(16), dp(13), dp(16), dp(13))
			background = fundoTocavel(COR_CARTAO, COR_CARTAO_PRESSIONADO)
			isFocusable = true
			isClickable = true
			setOnFocusChangeListener { _, focused -> if(focused) explicar(dica()) }
			addView(texto)
			addView(valorView)
			layoutParams = comMargem(0, dp(6))
			setOnClickListener {
				if(editavel()) onClick()
				explicar(dica())
				atualizar()
			}
		}
		atualizacoes += {
			val v = valor()
			valorView.text = v
			fila.contentDescription = "$rotulo: $v"
			fila.isClickable = editavel()
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
		setPadding(dp(16), dp(13), dp(16), dp(13))
		background = fundoTocavel(COR_CARTAO, COR_CARTAO_PRESSIONADO)
		isFocusable = true
		isClickable = true
		setOnFocusChangeListener { _, focused -> if(focused) explicar(dica) }
		addView(TextView(context).apply {
			text = rotulo
			setTextColor(COR_TEXTO)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
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
		if(::explicacao.isInitialized) explicacao.text = texto
	}

	private fun atualizar()
	{
		for(f in atualizacoes)
			f()
	}

	// ------------------------------------------------------------ os textos

	private fun currentMode(): Int = DisplayMode.current(this)

	private fun modeHint() = if(prefs.syntheticStereo)
		"3D requires immersive mode. Turn off Emulated 3D to choose Window."
	else if(currentMode() == DisplayMode.IMMERSIVE)
		"Curved screen, managed color, refresh rate matched to the source, and in-game "
				.plus("tuning on L3+R3. Takes the whole display.")
	else
		"A system panel, side by side with your other apps. No curved screen, no managed "
				.plus("color, no L3+R3 tuning panel.")

	private fun pathValue() = when
	{
		currentMode() == DisplayMode.WINDOW -> if(prefs.windowUsesShader) "shader (automatic)" else "direct (automatic)"
		prefs.immersiveUsesShader && prefs.frameExtrapolation -> "shader + frames"
		prefs.syntheticStereo -> "shader (3D)"
		prefs.tenBit -> "shader (10-bit)"
		prefs.immersiveUsesShader -> "shader"
		else -> "direct"
	}

	private fun pathHint() = when
	{
		currentMode() == DisplayMode.WINDOW ->
			"Window selects its video path from Sharpness and Color. Frame prediction is only available in immersive mode."
		prefs.tenBit ->
			"10-bit color requires tone mapping, so the shader stays on. Select to toggle experimental frame prediction."
		prefs.syntheticStereo ->
			"3D requires the shader. Select to toggle experimental frame prediction."
		!prefs.toneMapped ->
			"From the network to the compositor with no copy. The lowest latency there is."
		!prefs.frameExtrapolation ->
			"Correct 10-bit color, at the cost of one GPU pass."
		else ->
			"120 frames per second out of the source's 60, extrapolated by the Adreno. "
					.plus("No added latency; artifacts wherever the guess is wrong.")
	}

	private fun colorHint() = if(prefs.tenBit)
		"10-bit gradients with shader tone mapping. Uses an extra GPU pass. Reconnect to switch."
	else
		"The Remote Play default, and what gives correct color today."

	private fun effectiveSharpness() = if(currentMode() == DisplayMode.WINDOW) prefs.windowSharpness else prefs.sharpness

	private fun sharpHint() = when
	{
		effectiveSharpness() == 0 ->
			"The image as the console delivers it, with no sharpening."
		effectiveSharpness() >= StreamQualityPrefs.SHARPNESS_MQSR ->
			if(currentMode() == DisplayMode.IMMERSIVE)
				"The compositor chooses the filter for the screen size and GPU load."
			else "Automatic filtering is available in immersive mode. Choose light, medium or strong for window sharpening."
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
		"Turning on 3D switches to immersive mode. Window is unavailable until 3D is off. "
				.plus("Reconnect to switch.")

	private fun strengthHint() =
		"How far the near and the far separate. The ceiling is physical, not taste: past "
				.plus("your interpupillary distance the eyes would have to diverge, which ")
				.plus("they cannot. Also on Options in the tuning panel, in game.")

	private fun convergenceHint() =
		"What sits exactly on the screen. Lower makes more of the scene pop out toward "
				.plus("you; higher pushes the scene behind the screen.")

	private fun chordHint() = when(prefs.tuningChord)
	{
		StreamQualityPrefs.CHORD_L3_R3 ->
			"Both stick buttons. Fastest one, but some games use that combination "
					.plus("themselves, and there the panel opens in the middle of a fight.")
		StreamQualityPrefs.CHORD_L3_R3_R1 ->
			"Both stick buttons plus R1. Three fingers, and almost no game asks for "
					.plus("that shape, so it stays out of the way.")
		else ->
			"Hold both stick buttons for about a second. Nothing to memorise, but the "
					.plus("game still sees the clicks while you hold them.")
	}

	private fun rumbleHint() = if(prefs.hapticRumble)
		"The console sends the raw haptics track and it goes straight to the DualSense "
				.plus("coils over Bluetooth. Needs the controller paired. Reconnect to switch.")
	else
		"The console itself reduces the game's haptics to two motors, the way it does "
				.plus("for a DualShock 4. Reconnect to switch.")

	private fun audioHint() = when
	{
		currentMode() != DisplayMode.IMMERSIVE ->
			"In window mode Horizon OS does the positioning, not this setting: the sound "
					.plus("goes out through the system mixer so that it can.")
		prefs.spatialAudio == 0 ->
			"Stereo as the console sends it, locked to your head."
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

	/**
	 * Abre um endereco no navegador do headset.
	 *
	 * Separado do `launch` de proposito: uma activity nossa que nao abre e um
	 * defeito do app, um link que nao abre e so um aparelho sem navegador.
	 */
	private fun abrir(url: String, what: String)
	{
		launch(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)), what)
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
		addState(intArrayOf(android.R.attr.state_focused), fundoArredondado(aceso).apply {
			setStroke(dp(2), COR_ACENTO_CLARO)
		})
		addState(intArrayOf(android.R.attr.state_hovered), fundoArredondado(aceso))
		addState(intArrayOf(android.R.attr.state_selected), fundoArredondado(COR_CARTAO_PRESSIONADO))
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
		private const val PEDIDO_BLUETOOTH = 4202

		/**
		 * Referenciada por nome para não arrastar as classes do chiaki-ng para
		 * dentro desta tela: se elas falharem ao carregar, o carregamento tem
		 * de acontecer só ao tocar no botão, não ao abrir o app.
		 */
		private const val CHIAKI_MAIN_ACTIVITY = "com.metallic.chiaki.main.MainActivity"
		private const val TAG = "P5MVR"

		private const val PATREON_URL = "https://patreon.com/gblandro"

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
		private val COR_DESTAQUE = Color.parseColor("#FFD678")
		private val COR_DESTAQUE_FUNDO = Color.parseColor("#1E1A12")
	}
}
