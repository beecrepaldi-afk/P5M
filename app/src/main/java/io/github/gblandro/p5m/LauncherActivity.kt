// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Lancador nativo: categorias compactas e uma pagina de ajustes por vez.
 * O layout acompanha o tamanho medido da janela, inclusive sem recriar a
 * activity. Os valores e a ajuda continuam vindo das preferencias reais.
 *
 * Reorganizado na dev.213 (revisao de design): Play / Screen / Stream /
 * Controller / Help, no lugar de Play / Picture / 3D / Controller / Tools. O
 * "Picture" misturava como a imagem aparece com o que a rede transmite, e o
 * "3D" era uma pagina inteira para uma a tres linhas. Visual do PS5 com vidro,
 * em [Estilo].
 */
class LauncherActivity: Activity()
{
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
	private var cartaoDeVersao: LinearLayout? = null
	private var cartaoDeEnlace: LinearLayout? = null
	private val enlaceLento by lazy { EnlaceLento(this) }
	private val repetirEnlace = android.os.Handler(android.os.Looper.getMainLooper())
	private val conferirEnlace: Runnable = object: Runnable
	{
		override fun run()
		{
			enlaceLento.medir { lento -> preencherCartaoDeEnlace(lento == true) }
			repetirEnlace.postDelayed(this, 15_000L)
		}
	}

	private val prefs by lazy { StreamQualityPrefs(this) }

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		categoria = savedInstanceState?.getString("category") ?: "Play"
		construir()

		// Gatilhos e luzes nascem ligados, e sem esta permissao nenhum dos dois
		// chega ao controle. Pedir so quando alguem os liga deixaria quem nunca
		// mexeu no ajuste sem eles, achando que o jogo nao usa. O Android para
		// de perguntar sozinho depois de duas recusas.
		if(savedInstanceState == null)
			pedirBluetoothSeFaltar(prefs.adaptiveTriggers || prefs.controllerLights)
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

		// -------------------------------------------------------------- jogar
		if(P5MApp.lastCrash(this) != null)
			coluna.addView(avisoDeQueda())
		coluna.addView(LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			cartaoDeVersao = this
		})
		preencherCartaoDeVersao()
		coluna.addView(LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			cartaoDeEnlace = this
		})
		coluna.addView(Estilo.botaoPrincipal(this, "Play") {
			launch(Intent().setClassName(packageName, CHIAKI_MAIN_ACTIVITY), "the console list")
		}.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(14) } })
		coluna.addView(navegacao("Remote connection (PSN)",
				"For playing away from home. The console list finds consoles on your "
						+ "local network, which does not cross the internet; this reaches the "
						+ "console through your PSN account.") {
			launch(Intent(this, PsnRemoteActivity::class.java), "the remote connection")
		})
		// Antes de qualquer ajuste, e nao no rodape: o atalho e a unica forma de
		// mexer na tela depois que o jogo comeca, e ninguem descobre sozinho.
		coluna.addView(cartaoDoAtalho())

		// -------------------------------------------------------------- tela
		coluna.addView(pagina("Screen"))
		coluna.addView(linha("Mode", {
			if(prefs.syntheticStereo) "Immersive (3D)" else DisplayMode.label(currentMode())
		}, { modeHint() }, editavel = { !prefs.syntheticStereo },
				degraus = { 2 to currentMode() }, anterior = { DisplayMode.toggle(this) }) {
			val mode = DisplayMode.toggle(this)
			Log.i(TAG, "Display mode: ${DisplayMode.label(mode)}")
		})
		coluna.addView(linha("Sharpness",
				{ nome(StreamQualityPrefs.SHARPNESS_NAMES[effectiveSharpness()]) }, { sharpHint() },
				degraus = { passosDeNitidez() to StreamQualityPrefs.sharpnessStep(effectiveSharpness()) },
				anterior = { prefs.sharpness = StreamQualityPrefs.sharpnessNext(effectiveSharpness(), -1, passosDeNitidez()) }) {
			prefs.sharpness = StreamQualityPrefs.sharpnessNext(effectiveSharpness(), 1, passosDeNitidez())
			Log.i(TAG, "Sharpness: ${prefs.sharpness} "
					+ "(${StreamQualityPrefs.SHARPNESS_NAMES[prefs.sharpness]})")
		})
		coluna.addView(linha("Emulated 3D", { liga(prefs.syntheticStereo) }, { stereoHint() }) {
			prefs.syntheticStereo = !prefs.syntheticStereo
			if(prefs.syntheticStereo) DisplayMode.set(this, DisplayMode.IMMERSIVE)
			Log.i(TAG, "Synthetic 3D: ${prefs.syntheticStereo}")
		})
		// Só aparecem com o 3D ligado: sem ele são dois controles que não fazem
		// nada, e um controle inerte na tela é pior que um ausente.
		coluna.addView(linha("3D strength", { pct(prefs.stereoStrength) }, { strengthHint() },
				visivel = { prefs.syntheticStereo }, degraus = { 5 to (prefs.stereoStrength * 4f).toInt() },
				anterior = { prefs.stereoStrength = (((prefs.stereoStrength * 4f).toInt() + 4) % 5) / 4f }) {
			prefs.stereoStrength = (((prefs.stereoStrength * 4f).toInt() + 1) % 5) / 4f
			Log.i(TAG, "3D strength: ${(prefs.stereoStrength * 100).toInt()}%")
		})
		coluna.addView(linha("Pop-out", { liga(prefs.stereoPopOut) }, { popOutHint() },
				visivel = { prefs.syntheticStereo }) {
			prefs.stereoPopOut = !prefs.stereoPopOut
			Log.i(TAG, "3D pop-out: ${prefs.stereoPopOut}")
		})
		coluna.addView(linha("Neural depth", { liga(prefs.neuralDepth) }, { neuralHint() },
				visivel = { prefs.syntheticStereo }) {
			prefs.neuralDepth = !prefs.neuralDepth
			Log.i(TAG, "3D neural depth: ${prefs.neuralDepth}")
		})
		coluna.addView(linha("Screen depth", { pct(prefs.stereoConvergence) },
				{ convergenceHint() }, visivel = { prefs.syntheticStereo },
				degraus = { 5 to (prefs.stereoConvergence * 4f).toInt() },
				anterior = { prefs.stereoConvergence = (((prefs.stereoConvergence * 4f).toInt() + 4) % 5) / 4f }) {
			prefs.stereoConvergence = (((prefs.stereoConvergence * 4f).toInt() + 1) % 5) / 4f
			Log.i(TAG, "3D convergence: ${(prefs.stereoConvergence * 100).toInt()}%")
		})

		// ------------------------------------------------------------ stream
		coluna.addView(pagina("Stream"))
		coluna.addView(linha("Bitrate", { bitrateValue() }, { bitrateHint() },
				degraus = { StreamQualityPrefs.BITRATE_STEPS.size to
						StreamQualityPrefs.BITRATE_STEPS.indexOfFirst { it >= prefs.bitrateKbps }
							.let { if(it < 0) StreamQualityPrefs.BITRATE_STEPS.size - 1 else it } },
				anterior = { prefs.bitrateKbps = StreamQualityPrefs.BITRATE_STEPS.lastOrNull { it < prefs.bitrateKbps }
						?: StreamQualityPrefs.BITRATE_STEPS.last() }) {
			prefs.bitrateKbps = prefs.proximoBitrate()
			Log.i(TAG, "Bitrate: ${prefs.bitrateKbps} kbps")
		})
		coluna.addView(linha("Auto bitrate", { StreamQualityPrefs.AUTO_NAMES[prefs.autoBitrateMode] },
				{ autoBitrateHint() },
				degraus = { 3 to prefs.autoBitrateMode },
				anterior = { prefs.autoBitrateMode = (prefs.autoBitrateMode + 2) % 3 }) {
			prefs.autoBitrateMode = (prefs.autoBitrateMode + 1) % 3
			Log.i(TAG, "Auto bitrate: ${StreamQualityPrefs.AUTO_NAMES[prefs.autoBitrateMode]}")
		})
		coluna.addView(linha("Color", { if(prefs.tenBit) "10-bit" else "8-bit" }, { colorHint() },
				degraus = { 2 to (if(prefs.tenBit) 1 else 0) }) {
			prefs.tenBit = !prefs.tenBit
			Log.i(TAG, "10-bit: ${prefs.tenBit}")
		})
		// Tecnico demais para ficar no meio: quem precisa sabe procurar.
		coluna.addView(secao("Advanced"))
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
		coluna.addView(linha("Panel rate", { if(prefs.panel180) "180 Hz" else "120 Hz" },
			{ if(prefs.panel180) "Each console frame shown three times. About 3 ms less latency, more battery and heat."
				else "Each console frame shown twice. The default." },
			editavel = { currentMode() == DisplayMode.IMMERSIVE },
			degraus = { 2 to (if(prefs.panel180) 1 else 0) }) {
			prefs.panel180 = !prefs.panel180
			Log.i(TAG, "Panel rate: ${prefs.panelMaxHz} Hz")
		})

		// ------------------------------------------------- controle e som
		coluna.addView(pagina("Controller"))
		coluna.addView(linha("Rumble",
				{ nome(StreamQualityPrefs.RUMBLE_NAMES[prefs.rumbleMode]) }, { rumbleHint() },
				degraus = { StreamQualityPrefs.RUMBLE_NAMES.size to prefs.rumbleMode },
				anterior = { prefs.rumbleMode = (prefs.rumbleMode + StreamQualityPrefs.RUMBLE_NAMES.size - 1) %
						StreamQualityPrefs.RUMBLE_NAMES.size; pedirBluetoothSeCoil() }) {
			prefs.rumbleMode = (prefs.rumbleMode + 1) % StreamQualityPrefs.RUMBLE_NAMES.size
			Log.i(TAG, "Rumble: ${StreamQualityPrefs.RUMBLE_NAMES[prefs.rumbleMode]}")
			pedirBluetoothSeCoil()
		})
		coluna.addView(linha("Adaptive triggers",
				{ if(prefs.rumbleMode == StreamQualityPrefs.RUMBLE_CLASSICO) "Unavailable"
					else liga(prefs.adaptiveTriggers) },
				{ gatilhosHint() }) {
			prefs.adaptiveTriggers = !prefs.adaptiveTriggers
			Log.i(TAG, "Adaptive triggers: ${prefs.adaptiveTriggers}")
			pedirBluetoothSeFaltar(prefs.adaptiveTriggers)
		})
		coluna.addView(linha("Controller lights",
				{ if(prefs.controllerLights) "Follow console" else "System" },
				{ luzesHint() }, degraus = { 2 to (if(prefs.controllerLights) 1 else 0) }) {
			prefs.controllerLights = !prefs.controllerLights
			Log.i(TAG, "Controller lights: ${prefs.controllerLights}")
			pedirBluetoothSeFaltar(prefs.controllerLights)
		})
		coluna.addView(linha("Motion controls",
				{ liga(prefs.motionControls) }, { movimentoHint() },
				degraus = { 2 to (if(prefs.motionControls) 1 else 0) }) {
			prefs.motionControls = !prefs.motionControls
			Log.i(TAG, "Motion controls: ${prefs.motionControls}")
		})
		coluna.addView(linha("Spatial audio",
				{ if(currentMode() == DisplayMode.WINDOW) "Head-locked" else nome(StreamQualityPrefs.SPATIAL_NAMES[prefs.spatialAudio]) },
				{ audioHint() }, editavel = { currentMode() == DisplayMode.IMMERSIVE },
				degraus = { 5 to prefs.spatialAudio }, anterior = { prefs.spatialAudio = (prefs.spatialAudio + 4) % 5 }) {
			prefs.spatialAudio = (prefs.spatialAudio + 1) % 5
			Log.i(TAG, "Spatial audio: ${prefs.spatialAudio} "
					+ "(${StreamQualityPrefs.SPATIAL_NAMES[prefs.spatialAudio]})")
		})
		coluna.addView(linha("Microphone",
				{ if(prefs.microphone && !Microfone.permitido(this)) "No permission" else liga(prefs.microphone) },
				{ microfoneHint() }, degraus = { 2 to (if(prefs.microphone) 1 else 0) }) {
			prefs.microphone = !prefs.microphone
			Log.i(TAG, "Microphone: ${prefs.microphone}")
			// Pedida aqui, parado no lancador, pelo mesmo motivo do Bluetooth.
			if(prefs.microphone && !Microfone.permitido(this))
				requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), PEDIDO_MICROFONE)
		})
		coluna.addView(linha("Screen settings shortcut",
				{ StreamQualityPrefs.CHORD_NAMES[prefs.tuningChord] }, { chordHint() },
				visivel = { currentMode() == DisplayMode.IMMERSIVE },
				degraus = { StreamQualityPrefs.CHORD_NAMES.size to prefs.tuningChord },
				anterior = { prefs.tuningChord = (prefs.tuningChord + StreamQualityPrefs.CHORD_NAMES.size - 1) %
						StreamQualityPrefs.CHORD_NAMES.size }) {
			prefs.tuningChord = (prefs.tuningChord + 1) % StreamQualityPrefs.CHORD_NAMES.size
			Log.i(TAG, "Tuning chord: ${StreamQualityPrefs.CHORD_NAMES[prefs.tuningChord]}")
		})
		coluna.addView(navegacao("Stick calibration",
				"Measures where each stick rests and how much it jitters, and sets the "
						+ "deadzone from that. Worth doing once per controller.") {
			launch(Intent(this, StickCalibrationActivity::class.java), "stick calibration")
		})

		// -------------------------------------------------------------- ajuda
		coluna.addView(pagina("Help"))
		coluna.addView(navegacao("Diagnostics & report",
				"Report a problem, share the diary with a computer on your network, and "
						+ "the tools for testing the controller and the video path.") {
			launch(Intent(this, DiagnosticActivity::class.java), "diagnostics")
		})
		coluna.addView(linha("Check for updates", { liga(Atualizacao.ligado(this)) }, {
			"Looks for a new public release on GitHub when P5M opens, at most every six " +
					"hours. Downloading is up to you: the button opens the APK in the browser."
		}) {
			Atualizacao.definirLigado(this, !Atualizacao.ligado(this))
			Log.i(TAG, "Check for updates: ${Atualizacao.ligado(this)}")
			preencherCartaoDeVersao()
			Atualizacao.consultar(this) { runOnUiThread { preencherCartaoDeVersao() } }
		})
		coluna.addView(navegacao("Support development on Patreon",
				"P5M is free and the source is open. If it is useful to you, chipping in "
						+ "pays for the hours that keep it moving.") {
			abrir(PATREON_URL, "Patreon")
		})

		status = TextView(this).apply {
			setTextColor(Estilo.ALERTA)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, Estilo.TAM_APOIO)
			setPadding(dp(4), dp(10), dp(4), 0)
			visibility = View.GONE
		}

		montarMenu(coluna)

		atualizar()
		Log.i(TAG, "LauncherActivity opened, version ${versionName()}")
		Atualizacao.consultar(this) { runOnUiThread { preencherCartaoDeVersao() } }
	}

	/**
	 * O aviso de versão nova, no topo, onde o aviso de queda também mora.
	 * Pular esconde só aquela versão; a seguinte volta a aparecer.
	 */
	private fun preencherCartaoDeVersao()
	{
		val cartao = cartaoDeVersao ?: return
		cartao.removeAllViews()
		cartao.background = null
		val oferta = Atualizacao.oferta(this) ?: return
		cartao.addView(TextView(this).apply {
			text = "P5M ${oferta.versao} is available. You have ${versionName()}. " +
					"Update downloads it and opens the installer."
			setTextColor(Estilo.DESTAQUE)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, Estilo.TAM_APOIO)
			setPadding(dp(18), dp(14), dp(18), dp(8))
		})
		cartao.addView(LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(dp(12), 0, dp(12), dp(12))
			var botaoUpdate: TextView? = null
			botaoUpdate = Estilo.botao(context, "Update") {
				Trace.log(context, "Update: updating to ${oferta.versao}")
				InstaladorDeAtualizacao.atualizar(this@LauncherActivity, oferta,
					progresso = { pct -> botaoUpdate?.text = if(pct >= 0) "Downloading $pct%" else "Downloading..." },
					fim = { motivo ->
						botaoUpdate?.text = "Update"
						if(motivo != null)
						{
							status.visibility = View.VISIBLE
							status.text = motivo
						}
					})
			}.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), 0, dp(6), 0) } }
			addView(botaoUpdate)
			addView(Estilo.botao(context, "Skip this version") {
				Atualizacao.pular(context, oferta.versao)
				preencherCartaoDeVersao()
			}.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), 0, dp(6), 0) } })
		})
		cartao.background = Estilo.arredondado(this, Estilo.DESTAQUE_FUNDO)
		cartao.layoutParams = Estilo.margem(this, 0, 16)
	}

	/**
	 * O aviso de controle em sniff (ver [EnlaceLento]). Some sozinho quando a
	 * medida seguinte, a cada 15 s, acha o enlace rapido de novo.
	 */
	private fun preencherCartaoDeEnlace(lento: Boolean)
	{
		val cartao = cartaoDeEnlace ?: return
		cartao.removeAllViews()
		cartao.background = null
		if(!lento)
			return
		cartao.addView(TextView(this).apply {
			text = "Your DualSense is in Bluetooth power saving mode, which adds input lag. " +
					"Turn it off (hold the PS button for 10 seconds) and on again before you play."
			setTextColor(Estilo.DESTAQUE)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, Estilo.TAM_APOIO)
			setPadding(dp(18), dp(14), dp(18), dp(14))
		})
		cartao.background = Estilo.arredondado(this, Estilo.DESTAQUE_FUNDO)
		cartao.layoutParams = Estilo.margem(this, 0, 16)
	}

	override fun onResume()
	{
		super.onResume()
		repetirEnlace.removeCallbacks(conferirEnlace)
		repetirEnlace.post(conferirEnlace)
	}

	override fun onPause()
	{
		repetirEnlace.removeCallbacks(conferirEnlace)
		super.onPause()
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
			val nome = (view.tag as? String)?.removePrefix(PAGINA)
			if(nome != null && (view.tag as String).startsWith(PAGINA)) {
				grupo = nome
				paginas[grupo] = mutableListOf()
			} else paginas.getValue(grupo).add(view)
		}
		val descricoes = mapOf(
			"Play" to "Your console, ready when you are.",
			"Screen" to "How the game looks in the headset.",
			"Stream" to "What the console sends over the network.",
			"Controller" to "Haptics, triggers, lights, sound and your in-game shortcut.",
			"Help" to "Report a problem, diagnostics and project support.")
		val titulo = Estilo.titulo(this, "")
		val subtitulo = Estilo.apoio(this, "").apply { setPadding(0, dp(4), 0, dp(18)) }
		val conteudo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		val rolagem = ScrollView(this).apply {
			isFillViewport = true
			isVerticalScrollBarEnabled = false
			addView(conteudo)
		}
		val detalhe = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(24), dp(20), dp(24), dp(18))
			background = Estilo.vidro(context, 0, 26)
			addView(titulo)
			addView(subtitulo)
			addView(rolagem, LinearLayout.LayoutParams(-1, 0, 1f))
			addView(status)
		}
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
				view.setTextColor(if(key == nome) Estilo.TEXTO else Estilo.SECUNDARIO)
			}
			status.visibility = View.GONE
			atualizar()
		}
		for(nome in paginas.keys) {
			val botao = TextView(this).apply {
				id = View.generateViewId()
				text = nome
				setTextSize(TypedValue.COMPLEX_UNIT_SP, Estilo.TAM_ITEM)
				typeface = Estilo.MEDIA
				gravity = Gravity.CENTER_VERTICAL
				setPadding(dp(18), dp(14), dp(18), dp(14))
				minHeight = dp(52)
				background = Estilo.tocavel(context)
				isFocusable = true
				isClickable = true
			}
			botoes[nome] = botao
			categorias.addView(botao)
		}
		selecionarCategoria = { nome -> selecionar(nome); botoes[nome]?.requestFocus() }
		for((nome, botao) in botoes) botao.setOnClickListener { selecionarCategoria?.invoke(nome) }
		selecionar(categoria.takeIf { it in paginas } ?: "Play")
		val paines = LinearLayout(this)
		val moldura = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(24), dp(20), dp(24), dp(16))
			addView(cabecalho())
			addView(paines, LinearLayout.LayoutParams(-1, 0, 1f))
			addView(Estilo.ajudaDoControle(context,
					"D-pad  Move      Cross  Select      Square  Step back      Circle  Back"))
		}
		var largo: Boolean? = null
		// O painel do Quest muda de tamanho sem onCreate. O onMeasure usa a
		// largura atual, e o listener so troca a disposicao ao cruzar o limite.
		val raiz = object: FrameLayout(this) {
			override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
				val limite = minOf(View.MeasureSpec.getSize(widthMeasureSpec), dp(1000))
				if(moldura.layoutParams.width != limite) moldura.layoutParams.width = limite
				super.onMeasure(widthMeasureSpec, heightMeasureSpec)
			}
		}.apply {
			background = Estilo.fundoDaTela()
			addView(moldura, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
		}
		raiz.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
			val amplo = r - l >= dp(720)
			if(largo != amplo) {
				largo = amplo
				val foco = currentFocus
				(categorias.parent as? android.view.ViewGroup)?.removeView(categorias)
				paines.removeAllViews()
				paines.orientation = if(amplo) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
				categorias.orientation = if(amplo) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
				for(botao in botoes.values) botao.layoutParams = LinearLayout.LayoutParams(
					if(amplo) -1 else -2, -2).apply {
						bottomMargin = if(amplo) dp(8) else 0
						rightMargin = if(amplo) 0 else dp(8)
					}
				if(amplo) {
					paines.addView(categorias, LinearLayout.LayoutParams(dp(180), -1).apply { rightMargin = dp(20) })
					paines.addView(detalhe, LinearLayout.LayoutParams(0, -1, 1f))
				} else {
					barra.addView(categorias)
					paines.addView(barra, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
					paines.addView(detalhe, LinearLayout.LayoutParams(-1, 0, 1f))
				}
				foco?.requestFocus()
			}
		}
		setContentView(raiz)
	}

	// ------------------------------------------------------------- as peças

	/** Marca o começo de uma página: o [montarMenu] corta a lista por aqui. */
	private fun pagina(nome: String) = View(this).apply { tag = PAGINA + nome }

	private fun secao(titulo: String) = Estilo.secao(this, titulo)

	/**
	 * O lembrete do atalho, em destaque e nao numa nota de rodape.
	 *
	 * Vale so no modo imersivo: no modo janela nao existe painel de ajuste, e
	 * anunciar um atalho que nao faz nada e pior que nao anunciar nada.
	 */
	private fun cartaoDoAtalho(): View
	{
		val acorde = TextView(this).apply {
			setTextColor(Estilo.DESTAQUE)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, Estilo.TAM_ITEM)
			typeface = Estilo.NEGRITO
			setPadding(0, 0, dp(16), 0)
		}
		val fila = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(dp(20), dp(16), dp(20), dp(16))
			background = android.graphics.drawable.LayerDrawable(arrayOf(
					Estilo.arredondado(context, Estilo.DESTAQUE_FUNDO), Estilo.vidro(context, 1)))
			addView(acorde)
			addView(Estilo.apoio(context, "While playing, this opens Screen settings: move, resize "
					+ "and sharpen the screen, and click the touchpad.").apply { setTextColor(Estilo.TEXTO) })
			layoutParams = Estilo.margem(context, 14, 8)
		}
		atualizacoes += {
			acorde.text = StreamQualityPrefs.CHORD_NAMES[prefs.tuningChord]
			fila.visibility = if(currentMode() == DisplayMode.IMMERSIVE) View.VISIBLE else View.GONE
		}
		return fila
	}

	private fun cabecalho() = LinearLayout(this).apply {
		orientation = LinearLayout.HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
		setPadding(dp(4), 0, dp(4), dp(20))
		addView(Estilo.marca(context), LinearLayout.LayoutParams(0, -2, 1f))
		addView(TextView(context).apply {
			text = versionName()
			setTextColor(Estilo.SECAO)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
		})
	}

	/** O aviso de queda tem cor própria: ele não é um ajuste, é uma notícia. */
	private fun avisoDeQueda() = TextView(this).apply {
		text = "The app crashed the last time it ran. The reason is in Help › Diagnostics & report."
		setTextColor(Estilo.ALERTA)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, Estilo.TAM_APOIO)
		setPadding(dp(18), dp(14), dp(18), dp(14))
		background = Estilo.arredondado(context, Estilo.ALERTA_FUNDO)
		layoutParams = Estilo.margem(context, 0, 16)
	}

	/**
	 * Uma linha de ajuste: rótulo à esquerda, valor à direita, a explicação
	 * logo abaixo quando a linha está selecionada.
	 *
	 * A explicação morava no rodapé, e no headset isso obrigava o olho a descer
	 * a tela inteira a cada linha (revisão de design, dev.213). Agora ela abre
	 * embaixo da própria linha.
	 *
	 * O valor vem de uma função e não de um texto porque ele muda com o toque --
	 * e às vezes com o toque em **outra** linha, como o caminho de vídeo, que o
	 * 3D força. Guardar o texto pronto foi o que fez o painel mostrar valor
	 * velho; guardar como calculá-lo não tem esse defeito.
	 *
	 * [degraus] devolve (quantos, qual) e desenha os pontinhos da posição, como
	 * no painel do jogo; [anterior] é o Square, um degrau para trás -- sem ele o
	 * bitrate, de onze degraus, pedia a volta inteira para descer um.
	 */
	private fun linha(rotulo: String, valor: () -> String, dica: () -> String,
			visivel: () -> Boolean = { true }, editavel: () -> Boolean = { true },
			degraus: (() -> Pair<Int, Int>)? = null, anterior: (() -> Unit)? = null,
			onClick: () -> Unit): View
	{
		val texto = TextView(this).apply {
			text = rotulo
			setTextColor(Estilo.TEXTO)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, Estilo.TAM_ITEM)
			layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
		}
		val valorView = TextView(this).apply {
			setTextSize(TypedValue.COMPLEX_UNIT_SP, Estilo.TAM_ITEM)
			typeface = Estilo.MEDIA
			gravity = Gravity.END
			setPadding(dp(12), 0, 0, 0)
		}
		val pontos = Degraus(this)
		val lado = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.END
			// O valor com largura própria, e não a herdada: numa coluna vertical
			// que se ajusta ao conteúdo, o filho MATCH_PARENT não conta na
			// largura quando há outro filho visível (os pontinhos). A coluna
			// ficava da largura dos pontos e cortava o nome da opção (dev.215).
			addView(valorView, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
			addView(pontos, LinearLayout.LayoutParams(-2, dp(10)).apply { topMargin = dp(6); gravity = Gravity.END })
		}
		val topo = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			addView(texto)
			addView(lado)
		}
		val explicacao = Estilo.apoio(this, "").apply {
			setPadding(0, dp(10), 0, 0)
			visibility = View.GONE
		}
		val fila = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(18), dp(14), dp(18), dp(14))
			background = Estilo.tocavel(context)
			isFocusable = true
			isClickable = true
			addView(topo)
			addView(explicacao)
			layoutParams = Estilo.margem(context, 0, 8)
			setOnFocusChangeListener { _, focused ->
				explicacao.text = dica()
				explicacao.visibility = if(focused) View.VISIBLE else View.GONE
			}
			setOnClickListener {
				if(editavel()) onClick()
				atualizar()
				explicacao.text = dica()
			}
			if(anterior != null)
				setOnKeyListener { _, keyCode, event ->
					if(keyCode != KeyEvent.KEYCODE_BUTTON_X) return@setOnKeyListener false
					if(event.action == KeyEvent.ACTION_UP && editavel())
					{
						anterior()
						atualizar()
						explicacao.text = dica()
					}
					true
				}
		}
		atualizacoes += {
			val v = valor()
			valorView.text = v
			fila.contentDescription = "$rotulo: $v"
			fila.isClickable = editavel()
			// Valor desligado fica apagado. É o que deixa a lista inteira
			// legível de relance: o que está ativo salta, o resto recua.
			valorView.setTextColor(when
			{
				!editavel() -> Estilo.SECAO
				v == "Off" || v == "Unavailable" -> Estilo.SECUNDARIO
				else -> Estilo.AZUL_CLARO
			})
			val d = degraus?.invoke()
			pontos.definir(d?.first ?: 0, d?.second ?: 0)
			pontos.visibility = if(d != null && d.first > 1) View.VISIBLE else View.GONE
			fila.visibility = if(visivel()) View.VISIBLE else View.GONE
			if(explicacao.visibility == View.VISIBLE) explicacao.text = dica()
		}
		return fila
	}

	/** Os pontinhos da posição: o mesmo desenho do painel dentro do jogo. */
	private class Degraus(context: Context): View(context)
	{
		private var total = 0
		private var atual = 0
		private val d = 8f * context.resources.displayMetrics.density
		private val g = 5f * context.resources.displayMetrics.density
		private val aceso = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Estilo.AZUL_CLARO }
		private val apagado = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }

		fun definir(total: Int, atual: Int)
		{
			if(total == this.total && atual == this.atual) return
			this.total = total
			this.atual = atual
			requestLayout()
			invalidate()
		}

		override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int)
		{
			val w = if(total <= 0) 0 else (total * d + (total - 1) * g).toInt()
			setMeasuredDimension(w, d.toInt())
		}

		override fun onDraw(canvas: Canvas)
		{
			var x = 0f
			for(k in 0 until total)
			{
				canvas.drawRoundRect(x, 0f, x + d, d, d / 3f, d / 3f, if(k <= atual) aceso else apagado)
				x += d + g
			}
		}
	}

	/** Linha que abre outra tela. O chevron diz que leva a algum lugar. */
	private fun navegacao(rotulo: String, dica: String, onClick: () -> Unit): View
	{
		val explicacao = Estilo.apoio(this, dica).apply {
			setPadding(0, dp(10), 0, 0)
			visibility = View.GONE
		}
		return LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(18), dp(14), dp(18), dp(14))
			background = Estilo.tocavel(context)
			isFocusable = true
			isClickable = true
			addView(LinearLayout(context).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
				addView(TextView(context).apply {
					text = rotulo
					setTextColor(Estilo.TEXTO)
					setTextSize(TypedValue.COMPLEX_UNIT_SP, Estilo.TAM_ITEM)
					layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
				})
				addView(TextView(context).apply {
					text = "›"
					setTextColor(Estilo.SECUNDARIO)
					setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
				})
			})
			addView(explicacao)
			layoutParams = Estilo.margem(context, 0, 8)
			setOnFocusChangeListener { _, focused ->
				explicacao.visibility = if(focused) View.VISIBLE else View.GONE
			}
			setOnClickListener { onClick() }
		}
	}

	private fun atualizar()
	{
		for(f in atualizacoes)
			f()
	}

	// ------------------------------------------------------------ os textos

	private fun currentMode(): Int = DisplayMode.current(this)

	/** "haptic coil" vira "Haptic coil": inicial maiúscula em todo valor. */
	private fun nome(texto: String) = texto.replaceFirstChar { it.uppercase() }

	private fun liga(ligado: Boolean) = if(ligado) "On" else "Off"

	/** No modo janela nao ha automatico: o filtro do compositor e do imersivo. */
	private fun passosDeNitidez() =
		if(currentMode() == DisplayMode.WINDOW) 4 else StreamQualityPrefs.SHARPNESS_STEPS.size

	private fun pedirBluetoothSeCoil()
	{
		// A permissao e pedida aqui, ao ligar, e nao na hora de conectar: um
		// dialogo do sistema no meio da entrada do stream cairia por cima do
		// modo imersivo, e responder a ele de dentro do headset com a sessao
		// subindo e pior do que responder agora, parado no lancador.
		if(prefs.rumbleMode == StreamQualityPrefs.RUMBLE_BOBINA && !DualSenseHid.disponivel(this))
			requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), PEDIDO_BLUETOOTH)
	}

	private fun modeHint() = if(prefs.syntheticStereo)
		"3D requires immersive mode. Turn off Emulated 3D to choose Window."
	else if(currentMode() == DisplayMode.IMMERSIVE)
		"Curved screen, managed color, refresh rate matched to the source, and Screen "
				.plus("settings in game. Takes the whole display.")
	else
		"A system panel, side by side with your other apps. No curved screen, no managed "
				.plus("color, no Screen settings in game.")

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
		prefs.syntheticStereo ->
			"3D requires the shader. Select to toggle experimental frame prediction."
		prefs.tenBit ->
			"10-bit color requires tone mapping, so the shader stays on. Select to toggle experimental frame prediction."
		!prefs.toneMapped ->
			"From the network to the compositor with no copy. The lowest latency there is."
		!prefs.frameExtrapolation ->
			"Correct 10-bit color, at the cost of one GPU pass."
		else ->
			"120 frames per second out of the source's 60, extrapolated by the Adreno. "
					.plus("No added latency; artifacts wherever the guess is wrong.")
	}

	private fun colorHint() = if(prefs.tenBit)
		"10-bit gradients with shader tone mapping. Uses an extra GPU pass. 3D stays at native 8-bit. Reconnect to switch."
	else
		"The Remote Play default, and what gives correct color today."

	/** O escolhido, e onde o automatico esta quando saiu dele. */
	private fun bitrateValue(): String
	{
		val efetivo = prefs.bitrateEfetivo
		return if(efetivo != prefs.bitrateKbps)
			"${efetivo / 1000} Mbps (auto, from ${prefs.bitrateKbps / 1000})"
		else "${prefs.bitrateKbps / 1000} Mbps"
	}

	private fun autoBitrateHint() = when
	{
		!prefs.autoBitrate ->
			"Every session uses the bitrate above. Pick this, or Only lowers, for the lowest latency."
		prefs.bitrateTetoRuim > 0 ->
			"Lowered after ${prefs.bitrateTetoRuim / 1000} Mbps lost packets. It tries that again after five clean sessions."
		prefs.autoBitrateMode == StreamQualityPrefs.AUTO_SO_DESCE ->
			"The bitrate above is the ceiling. A session that keeps losing packets makes the next one lower; clean sessions climb back."
		else ->
			"Climbs a step after each clean session, for a sharper picture. Higher bitrate also takes the headset a few ms longer to decode."
	}

	private fun bitrateHint() = when
	{
		prefs.bitrateKbps > StreamQualityPrefs.BITRATE_EXPERIMENTAL ->
			"Experimental: above what Remote Play normally uses. The diary shows what the console actually sent."
		prefs.bitrateKbps < 20000 ->
			"Lighter on the network. Pick this if the picture stutters or freezes on weak Wi-Fi."
		prefs.bitrateKbps <= 25000 ->
			"The default. Fine on Wi-Fi 6 with the console on a cable."
		else ->
			"Sharper in fast scenes, but needs a strong link. If you see stutter or blocks, step down."
	}

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
			"This also changes in game, with Square in Screen settings. Square here steps back."
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
		"How deep the scene goes. At 100% the far end reaches most of the way to what "
				.plus("your eyes can fuse; the limit comes from your interpupillary distance ")
				.plus("and the screen size. Also on Options in Screen settings, in game.")

	private fun popOutHint() = if(prefs.stereoPopOut)
		"The nearest part of the scene comes out of the screen toward you. A thin "
				.plus("black edge on each eye makes the frame float in front, so nothing ")
				.plus("breaks at the borders. Screen depth is capped at 55% while this is on.")
	else
		"The scene stays behind the screen, like looking through a window. The most "
				.plus("comfortable. Turn on to let near things come out toward you.")

	private fun neuralHint() = if(prefs.neuralDepth)
		"Depth comes from a neural network (ZipDepth) running on the headset GPU, "
				.plus("about 20 times a second. Shapes and distances are far more accurate ")
				.plus("than the image cues. The first seconds of a session use the cues while it loads.")
	else
		"Depth is guessed from the picture itself: what is low on the screen, detailed "
				.plus("or saturated comes forward, sky and haze go back. Lighter on the GPU.")

	private fun convergenceHint() =
		"What sits exactly on the screen. At 100% the whole scene stays behind it, like "
				.plus("looking through a window, which is the most comfortable. Lower lets ")
				.plus("the nearest things come out toward you.")

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

	/**
	 * Pede a permissao de Bluetooth ao ligar algo que fala direto com o
	 * controle, pela mesma razao do modo bobina: aqui, parado no lancador, e
	 * nao com o dialogo caindo por cima da entrada no modo imersivo.
	 */
	private fun pedirBluetoothSeFaltar(ligou: Boolean)
	{
		if(ligou && !DualSenseHid.disponivel(this))
			requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT),
					PEDIDO_BLUETOOTH)
	}

	private fun gatilhosHint() = when
	{
		prefs.rumbleMode == StreamQualityPrefs.RUMBLE_CLASSICO ->
			"Classic rumble announces a DualShock 4, which has no adaptive triggers, so "
					.plus("the console sends none. Pick haptic coil, haptic motor or off to get them.")
		prefs.adaptiveTriggers ->
			"The triggers resist, click and vibrate the way the game asks, like on the PS5. "
					.plus("R3 in Screen settings, in game, turns them off ")
					.plus("and on at any moment. Immersive mode only for now.")
		else ->
			"The triggers stay free. Turn this on here, or with R3 in Screen settings, in game."
	}

	private fun movimentoHint() =
		if(prefs.motionControls)
			"Tilting the controller aims and steers in games that use motion, "
					.plus("as on the console. Takes effect on the next stream.")
		else
			"The console gets no motion from the controller. Turn on for games that aim or steer by tilting."

	private fun microfoneHint() =
		if(prefs.microphone)
			"Your voice goes to the console for party and game chat, with the headset's "
					.plus("echo cancelling so the game sound does not come back.")
		else
			"The console hears nothing from the headset."

	private fun luzesHint() =
		if(prefs.controllerLights)
			"The lightbar takes the colour the game picks and the LEDs show your player "
					.plus("number, like on the PS5. Immersive mode only for now.")
		else
			"The lightbar and player LEDs stay as the headset sets them."

	private fun rumbleHint() = when(prefs.rumbleMode)
	{
		StreamQualityPrefs.RUMBLE_CLASSICO ->
			"The console reduces the game's haptics to two motors and sends that. The most "
					.plus("faithful on paper, but on 12/09 a whole session came back with zero ")
					.plus("rumble packets while the game was shaking on screen.")
		StreamQualityPrefs.RUMBLE_BOBINA ->
			"The console sends its full haptic track and the app plays it on the DualSense "
					.plus("coils. The best when it works, and the one that can start seconds ")
					.plus("late until you turn the controller off and on again.")
		StreamQualityPrefs.RUMBLE_DESLIGADO ->
			"Nothing vibrates, not even the test buzz when the stream opens. The client "
					.plus("announces itself as DualShock 4, so the console does not even open ")
					.plus("the haptic track.")
		else ->
			"The console sends its full haptic track and the app turns it into motor "
					.plus("strength. No coil, so no delay, and it works on controllers that are ")
					.plus("not a DualSense. It vibrates for footsteps and rain too.")
	}

	private fun audioHint() = when
	{
		currentMode() != DisplayMode.IMMERSIVE ->
			"In window mode the sound follows your head. Spatial audio needs immersive mode."
		prefs.spatialAudio == 0 ->
			"Stereo as the console sends it, locked to your head."
		prefs.spatialAudio == 4 ->
			"Two speakers in a small room: both ears hear both speakers, with head shadow, "
					.plus("wall reflections and a short tail. The sound stays outside your head.")
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
		private const val PEDIDO_MICROFONE = 4203
		private const val PAGINA = "pagina:"

		/**
		 * Referenciada por nome para não arrastar as classes do chiaki-ng para
		 * dentro desta tela: se elas falharem ao carregar, o carregamento tem
		 * de acontecer só ao tocar no botão, não ao abrir o app.
		 */
		private const val CHIAKI_MAIN_ACTIVITY = "com.metallic.chiaki.main.MainActivity"
		private const val TAG = "P5MVR"

		private const val PATREON_URL = "https://patreon.com/gblandro"
	}
}
