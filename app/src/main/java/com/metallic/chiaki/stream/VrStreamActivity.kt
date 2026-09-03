// SPDX-License-Identifier: AGPL-3.0-only
//
// Activity imersiva. Convive com a StreamActivity 2D do chiaki-ng em vez de
// substitui-la: a 2D fica compilada e inalcancavel, e um patch de uma linha
// redireciona a MainActivity do submodulo para ca.
//
// A primeira tentativa foi excluir a StreamActivity 2D do source set. Nao
// funciona: ela declara o enum TransformMode no topo do arquivo, e o
// AspectRatioFrameLayout -- que o viewBinding referencia -- depende dele.
package com.metallic.chiaki.stream

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.lib.ConnectedEvent
import com.metallic.chiaki.lib.LoginPinRequestEvent
import com.metallic.chiaki.lib.QuitEvent
import com.metallic.chiaki.lib.RumbleEvent
import com.metallic.chiaki.lib.Session
import com.metallic.chiaki.main.MainActivity
import com.metallic.chiaki.session.StreamInput
import io.github.gblandro.p5m.P5MApp
import io.github.gblandro.p5m.Rumble
import io.github.gblandro.p5m.ScreenPrefs
import io.github.gblandro.p5m.Trace
import io.github.gblandro.p5m.WifiLowLatency
import io.github.gblandro.p5m.StreamQualityPrefs
import io.github.gblandro.p5m.XrBridge

// Estende ComponentActivity, nao AppCompatActivity, de proposito: o AppCompat
// exige tema Theme.AppCompat para setContentView, e esta activity usa um tema
// fullscreen do sistema. A primeira versao quebrava exatamente ai -- ao tentar
// mostrar a mensagem de erro. ComponentActivity da o LifecycleOwner que o
// StreamInput precisa, sem a exigencia de tema.
class VrStreamActivity: ComponentActivity()
{
	companion object
	{
		// Mesmo valor da constante do chiaki-ng: o extra do Intent nao muda.
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val TAG = "P5MVR"

		/**
		 * Quanto esperar a furação de NAT desistir antes de liberar a sessão.
		 *
		 * Generoso o bastante para o cancelamento chegar, curto o bastante para
		 * não virar ANR: o onDestroy roda na thread principal.
		 */
		private const val PSN_JOIN_TIMEOUT_MS = 3000L

		/** Quanto uma forma de camada precisa sobreviver para ser considerada boa. */
		private const val STATE_LOG_INTERVAL_MS = 1000L

		// Tem de bater com kHudWidth/kHudHeight do lado nativo: o envio para a
		// textura recusa qualquer outro tamanho.
		/** Periodo de atualizacao dos medidores no painel de ajuste. */
		private const val METER_PERIOD_MS = 1000L
		// Dez segundos, a mesma janela do resumo de video: as duas linhas ficam
		// lado a lado no diario, e comparar deixa de exigir aritmetica.
		private const val HEALTH_PERIOD_MS = 10_000L
		private const val HUD_WIDTH = 1024
		// Precisa casar com kHudHeight no lado nativo: o swapchain e criado la
		// e o bitmap daqui e copiado nele sem redimensionar. Divergir nao da
		// erro, so faz o painel aparecer cortado ou com lixo na borda.
		private const val HUD_HEIGHT = 768

		// Folga entre o console aceitar a conexao e a primeira imagem existir
		// de fato dentro da Surface.
		private const val VIDEO_LAYER_DELAY_MS = 1500L

		/**
		 * Teclas que valem como clique do touchpad do DualSense.
		 *
		 * O chiaki-ng nao mapeia o touchpad em gamepad nenhum: a constante
		 * BUTTON_TOUCHPAD existe, mas so era alimentada pelos controles de toque
		 * na tela, que nao existem aqui. Sem isto, jogo que abre mapa ou
		 * inventario no touchpad fica sem esse comando.
		 *
		 * A lista e ampla de proposito. Controle com paddles traseiros (o
		 * 8BitDo Ultimate 2, por exemplo) costuma emitir um destes, e os que
		 * sao programaveis podem ser configurados para qualquer um deles.
		 * Nenhum destes codigos e usado pelo mapeamento do chiaki, entao
		 * incluir todos nao rouba comando de jogo.
		 */
		private val TOUCHPAD_KEYCODES = setOf(
			KeyEvent.KEYCODE_BUTTON_1,
			KeyEvent.KEYCODE_BUTTON_2,
			KeyEvent.KEYCODE_BUTTON_3,
			KeyEvent.KEYCODE_BUTTON_4,
			KeyEvent.KEYCODE_BUTTON_Z
		)
	}

	private lateinit var screenPrefs: ScreenPrefs
	private lateinit var qualityPrefs: StreamQualityPrefs
	private var xr: XrBridge? = null
	private var session: Session? = null
	private var input: StreamInput? = null
	/** A thread que fura o NAT na conexão remota, enquanto ela existir. */
	private var psnThread: Thread? = null

	private var hudBitmap: Bitmap? = null
	private val handler = Handler(Looper.getMainLooper())
	private var lastReceived = 0L
	private var lastLost = 0L
	private var lastDropped = 0L
	private var preferredDeviceId: Int? = null
	private var rumble: Rumble? = null

	/**
	 * Modo de ajuste. O setter e que cuida do painel: nao ha caminho para ligar
	 * o modo sem mostrar a legenda, que era exatamente o defeito antigo.
	 */
	private var adjustMode = false
		set(value)
		{
			field = value
			xr?.setHudVisible(value)
			if(value)
			{
				// Zera o direcional: se o modo abriu com ele pressionado, a
				// primeira leitura seria vista como transicao e mexeria na tela
				// sozinha.
				hatX = 0
				hatY = 0
				renderHud()
				// Os medidores mudam sozinhos, sem ninguem apertar nada. Um
				// segundo: rapido para acompanhar um engasgo, lento para nao
				// desenhar o painel inteiro a cada quadro so por causa de tres
				// numeros.
				handler.postDelayed(meterTick, METER_PERIOD_MS)
			}
			else
				handler.removeCallbacks(meterTick)
		}

	/**
	 * Redesenha o painel enquanto o modo de ajuste estiver aberto.
	 *
	 * Reagenda a si mesmo em vez de usar um temporizador fixo: assim ele para
	 * junto com o modo, e nao existe caminho em que fique batendo com o painel
	 * fechado.
	 */
	private val meterTick = object: Runnable
	{
		override fun run()
		{
			if(!adjustMode)
				return
			renderHud()
			handler.postDelayed(this, METER_PERIOD_MS)
		}
	}
	/**
	 * Uma linha de saúde do aparelho a cada dez segundos, no diário.
	 *
	 * Na mesma cadência do `Video 10s:` de propósito: as duas linhas ficam lado
	 * a lado no diário, e uma degradação de ritmo passa a ter, na linha de
	 * cima, o estado do aparelho no mesmo instante.
	 *
	 * A pergunta que ela existe para responder apareceu numa sessão de quatro
	 * minutos: o "no ritmo" caiu de 596 para 549 ao longo dela, com adiantados e
	 * atrasados crescendo em pares -- assinatura de bloqueio -- enquanto a
	 * decodificação ficava parada em 4 ms. Alguma coisa piora com o tempo, e
	 * térmico é o primeiro suspeito. Sem este número a resposta seria palpite.
	 *
	 * Roda sempre que há stream, e não só no modo de ajuste: o painel é para
	 * quem está olhando, e isto é para quem vai ler depois.
	 */
	private val healthTick = object: Runnable
	{
		override fun run()
		{
			logHealth()
			handler.postDelayed(this, HEALTH_PERIOD_MS)
		}
	}

	private fun logHealth()
	{
		val perf = xr?.readPerformance() ?: return
		val partes = ArrayList<String>(6)

		// Térmico pelo Android, e não pelo OpenXR.
		//
		// A extensão de temperatura não existe neste runtime: o
		// `xrGetInstanceProcAddr(xrThermalGetTemperatureTrendEXT)` falha na
		// abertura, e por isso a linha de saúde saía sem térmico nenhum --
		// justamente o número que ela foi criada para trazer.
		//
		// O `getThermalHeadroom` do Android existe desde a API 30 e não depende
		// de extensão nenhuma. A escala é ao contrário da do OpenXR: **1.0 é o
		// ponto de estrangulamento**, não a folga. Vai escrito assim, por
		// extenso, porque um número que significa o oposto do que o nome sugere
		// é pior do que não ter número.
		val pm = getSystemService(android.os.PowerManager::class.java)
		val termico = runCatching { pm?.getThermalHeadroom(0) }.getOrNull()
		if(termico != null && !termico.isNaN())
			partes += "thermal %.2f (1.0 = the limit)".format(java.util.Locale.US, termico)
		// Folga térmica: 1 é frio, 0 é o ponto em que o sistema começa a cortar
		// relógio. A inclinação diz para onde está indo.
		if(perf[8] > 0f)
		{
			val rumo = when
			{
				perf[10] > 0.01f -> "warming up"
				perf[10] < -0.01f -> "cooling down"
				else -> "steady"
			}
			partes += "thermal headroom %.0f%% (%s)".format(java.util.Locale.US,
					perf[9] * 100f, rumo)
		}
		// Sem multiplicar por cem: o XR_META_performance_metrics ja entrega
		// porcentagem. A primeira versao multiplicava, e o diario saiu com
		// "gpu 3526%" -- absurdo o bastante para nao enganar ninguem, mas
		// inutil. O mesmo erro estava no painel de ajuste desde sempre.
		if(perf[2] > 0f)
			partes += "gpu %.0f%%".format(java.util.Locale.US, perf[3])
		if(perf[13] > 0f)
			partes += "cpu %.0f%%".format(java.util.Locale.US, perf[14])
		if(perf[4] > 0f)
			partes += "app %.1f ms".format(java.util.Locale.US, perf[5])
		if(perf[6] > 0f)
			partes += "compositor %.1f ms".format(java.util.Locale.US, perf[7])
		if(perf[0] > 0f)
			partes += "dropped %d".format(perf[1].toLong())
		if(perf[11] > 0f)
			partes += "head-to-photon %.1f ms".format(java.util.Locale.US, perf[12])
		if(partes.isEmpty())
			return
		trace("Health 10s: " + partes.joinToString(" | "))
	}

	private var lastStateLogMs = 0L
	private val wifiLock by lazy { WifiLowLatency(this) }
	private var hatX = 0
	private var hatY = 0
	private var thumbLDown = false
	private var thumbRDown = false

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		Log.i(TAG, "VrStreamActivity.onCreate action=${intent?.action} " +
				"categories=${intent?.categories} temConnectInfo=" +
				"${intent?.hasExtra(EXTRA_CONNECT_INFO)}")

		val incoming = intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)
		if(incoming == null)
		{
			// O Horizon OS pode escolher esta activity como entrada do app, por
			// ela declarar MAIN junto da categoria VR. Sem ConnectInfo nao ha o
			// que transmitir, e encerrar aqui aparece para o usuario como uma
			// janela em branco que some. Mandamos para o painel 2D, que e a
			// entrada de verdade.
			Log.w(TAG, "Opened with no ConnectInfo; redirecting to the 2D panel")
			startActivity(Intent(this, MainActivity::class.java).apply {
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
			})
			finish()
			return
		}

		screenPrefs = ScreenPrefs(this)
		qualityPrefs = StreamQualityPrefs(this)

		// O painel 2D vem do app Android do chiaki-ng, que ainda oferece presets
		// de celular. Descartamos o perfil dele e negociamos o teto do protocolo:
		// 1080p60 HEVC no bitrate que a nossa rede aguenta.
		val connectInfo = incoming.copy(videoProfile = qualityPrefs.videoProfile())
		Log.i(TAG, "Video profile: ${connectInfo.videoProfile.width}x" +
				"${connectInfo.videoProfile.height}@${connectInfo.videoProfile.maxFPS} " +
				"${connectInfo.videoProfile.codec} ${connectInfo.videoProfile.bitrate}kbps")

		val bridge = XrBridge(this)
		if(!bridge.create())
		{
			// Nao encerrar em silencio: para o usuario isso e o app fechando
			// sozinho, sem pista nenhuma do motivo. O detalhe de qual extensao
			// faltou esta no log nativo, que a tela de diagnostico mostra.
			fail("The OpenXR session could not be created.\n\n${bridge.lastError}")
			return
		}
		xr = bridge

		// O caminho tem de ser escolhido antes do swapchain: um cria swapchain de
		// Surface, o outro de textura GL.
		// O 3D sintetizado tem de vir antes de tudo que dependa dele: ele obriga
		// o caminho com shader (a deformacao precisa de uma passada de GPU
		// nossa) e dobra a largura do alvo (cada olho ocupa uma metade). Os dois
		// sao decididos na criacao do swapchain, que acontece logo abaixo --
		// ligar depois nao teria efeito nenhum nesta sessao.
		val synth3d = qualityPrefs.syntheticStereo
		bridge.setStereoMode(if(synth3d) 1 else qualityPrefs.stereoMode)
		if(synth3d)
		{
			bridge.setStereoTuning(qualityPrefs.stereoDisparity(),
					qualityPrefs.stereoConvergence)
			trace("Synthetic 3D on: depth guessed from the image, "
					+ "strength ${(qualityPrefs.stereoStrength * 100).toInt()}%, "
					+ "convergence ${(qualityPrefs.stereoConvergence * 100).toInt()}%")
		}

		val toneMapped = qualityPrefs.toneMapped || synth3d
		bridge.setRenderPath(if(toneMapped) 1 else 0, qualityPrefs.tenBit)
		trace("Video path: ${if(toneMapped) "shader" else "direct"}, " +
				"source ${if(qualityPrefs.tenBit) "10-bit PQ" else "8-bit SDR"}")

		// O swapchain tem exatamente a resolucao negociada com o console: qualquer
		// escala aqui viraria trabalho extra de GPU no caminho critico.
		val surface = if(toneMapped)
		{
			// A ordem importa: o swapchain de destino nasce aqui dentro, e a
			// Surface que o console alimenta vem da SurfaceTexture, nao do
			// runtime.
			bridge.createVideoSurface(connectInfo.videoProfile.width,
					connectInfo.videoProfile.height)
			bridge.createToneMappedSurface()
		}
		else
			bridge.createVideoSurface(connectInfo.videoProfile.width,
					connectInfo.videoProfile.height)
		if(surface == null)
		{
			fail("The video swapchain could not be created.\n\n${bridge.lastError}")
			return
		}

		// Se a tentativa anterior nao chegou a ser marcada como concluida, o app
		bridge.setLayerShape(screenPrefs.layerShape)
		bridge.setVerticalFlip(screenPrefs.verticalFlip)
		// O gamut acompanha a profundidade, mas so no caminho direto: com shader
		// a conversao BT.2020 -> BT.709 ja aconteceu antes de a imagem chegar ao
		// compositor, e declarar 2020 aqui a converteria duas vezes.
		bridge.setWideColor(qualityPrefs.tenBit && !toneMapped)
		Log.i(TAG, "Layer shape: " +
				if(screenPrefs.layerShape == ScreenPrefs.SHAPE_QUAD) "quad" else "cylinder")

		applyScreenParams()
		applyQualityParams()
		bridge.setPassthrough(screenPrefs.passthroughEnabled && bridge.isPassthroughSupported)

		// 60 fps de fonte pede 120 Hz de painel: múltiplo exato, cadência
		// uniforme. Ver SelectDisplayRefreshRate no lado nativo.
		// O retorno costuma ser 0: a sessão ainda não começou, e o pedido fica
		// guardado para o frame loop cumprir logo após o xrBeginSession. Quem
		// anuncia a taxa aplicada é o lado nativo, na linha "Painel em ... Hz".
		bridge.selectDisplayRefreshRate(StreamQualityPrefs.SOURCE_FPS)

		val streamInput = StreamInput(this, Preferences(this))
		streamInput.observe(this)
		input = streamInput

		val logManager = LogManager(this)
		val newSession = try
		{
			Session(connectInfo, logManager.createNewFile().file.absolutePath, false)
		}
		catch(e: Exception)
		{
			P5MApp.saveCrash(this, "Session", e)
			fail("Failed to create the chiaki session.\n\n" +
					"${e::class.java.simpleName}: ${e.message}")
			return
		}

		newSession.eventCallback = { event ->
			when(event)
			{
				is ConnectedEvent ->
				{
					trace("Connected to the console")
					// Conectado nao quer dizer decodificado: o primeiro frame
					// ainda tem que atravessar rede e MediaCodec. So depois
					// disso a swapchain-Surface tem conteudo, e so entao a
					// camada pode ser submetida ao compositor.
					Handler(Looper.getMainLooper()).postDelayed({
						trace("Releasing the video layer")
						bridge.setVideoLayerEnabled(true)
					}, VIDEO_LAYER_DELAY_MS)
				}
				is LoginPinRequestEvent ->
				{
					// Nao ha como pedir PIN dentro da camada imersiva: nao existe
					// superficie 2D nesta activity. Encerramos e o usuario resolve
					// pelo painel. Ver LIMITACOES no README.
					Log.e(TAG, "The console asked for a login PIN; use the 2D panel first")
					runOnUiThread { finish() }
				}
				is QuitEvent ->
				{
					Log.i(TAG, "Session ended: ${event.reason} ${event.reasonString ?: ""}")
					runOnUiThread { finish() }
				}
				is RumbleEvent -> rumble?.set(event.left.toInt(), event.right.toInt())
			}
		}
		streamInput.controllerStateChangedCallback = { state ->
			newSession.setControllerState(state)
			logControllerState(state)
		}
		logInputDevices()
		logFrameSync()

		// Depois do logInputDevices, que e quem decide o preferredDeviceId: o
		// destino da vibracao e o mesmo controle de onde vem os analogicos.
		rumble = Rumble(Preferences(this).rumbleEnabled).also { it.attach(preferredDeviceId) }

		newSession.setSurface(surface)
		session = newSession

		// O frame loop comeca ja: ele roda vazio (sem camada de video) ate o
		// ConnectedEvent liberar a submissao.
		wifiLock.acquire()
		bridge.start()

		// A tela nasce a frente de onde a cabeca esta apontando no inicio.
		bridge.recenter()

		// A saude comeca junto com o stream e para no onDestroy. A primeira
		// leitura vai depois de uma janela: no instante da abertura o aparelho
		// ainda nao esquentou nem trabalhou, e o numero de la nao representa
		// nada.
		handler.postDelayed(healthTick, HEALTH_PERIOD_MS)

		// Conexão remota via PSN: antes de iniciar a sessão é preciso furar o
		// NAT dos dois lados, e isso bloqueia por dezenas de segundos falando
		// com os servidores da Sony. Vai para uma thread, e o loop de quadro
		// acima já está rodando — vazio, mas rodando —, então o headset mostra
		// passthrough em vez de uma tela preta indistinguível de travamento.
		//
		// Numa conexão local o duid vem vazio e nada disso acontece: o start é
		// imediato, exatamente como sempre foi.
		if(connectInfo.duid.isEmpty())
			newSession.start()
		else
		{
			trace("Remote connection: asking PSN to put the two sides in touch")
			psnThread = Thread {
				// A furação de NAT também fala com a PSN por curl, e pode ser a
				// primeira coisa a fazê-lo se o stream foi aberto sem passar
				// pela tela de conexão remota.
				io.github.gblandro.p5m.CaBundle.ensureNetwork(this)
				val err = newSession.connectPsn(connectInfo.duid, connectInfo.ps5)
				runOnUiThread {
					// A activity pode ter sido encerrada enquanto a thread
					// falava com a Sony; nesse caso a sessão já foi liberada e
					// tocar nela seria uso de memória morta.
					if(session !== newSession)
						return@runOnUiThread
					if(err.isSuccess)
					{
						trace("Remote connection established; starting the session")
						newSession.start()
					}
					else
						fail("The remote connection through PSN failed.\n\n$err\n\n" +
								"The console has to be on, or in rest mode with " +
								"Remote Play enabled.")
				}
			}
			psnThread?.start()
		}
	}

	/**
	 * Mostra o motivo da falha em vez de encerrar em silencio.
	 *
	 * Encerrar direto aparece para o usuario como o app fechando sozinho -- foi
	 * assim que a falha se apresentou no teste em hardware, sem nenhuma pista.
	 * A activity fica aberta com o texto na tela, e o log detalhado continua
	 * disponivel no Diagnostico.
	 */
	private fun fail(message: String)
	{
		Log.e(TAG, message.replace("\n", " "))
		P5MApp.saveCrash(this, "fail", RuntimeException(message))
		xr?.destroy()
		xr = null
		try
		{
			showMessage(message)
		}
		catch(e: Throwable)
		{
			// Se nem mostrar a mensagem funcionar, encerra -- mas o motivo ja
			// esta gravado no arquivo de crash e visivel no Diagnostico.
			Log.e(TAG, "Could not show the error message", e)
			finish()
		}
	}

	private fun showMessage(message: String)
	{
		setContentView(TextView(this).apply {
			text = "P5M\n\n$message"
			setTextColor(Color.WHITE)
			setBackgroundColor(Color.parseColor("#101014"))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
			gravity = Gravity.CENTER
			setPadding(64, 64, 64, 64)
		})
	}

	private fun applyQualityParams()
	{
		val cinema = ScreenPrefs.CINEMA_GRADE[screenPrefs.cinema]
		xr?.setQuality(qualityPrefs.sharpness, qualityPrefs.sharpenAmount,
				qualityPrefs.passthroughOpacity, cinema[0], cinema[1], cinema[2],
				qualityPrefs.brightnessScale, qualityPrefs.frameExtrapolation)
		xr?.setSpatialAudio(qualityPrefs.spatialStrength)
	}

	private fun applyScreenParams()
	{
		xr?.setScreenParams(
			screenPrefs.radius,
			screenPrefs.centralAngle,
			0f,
			screenPrefs.heightOffset,
			screenPrefs.curvature
		)
	}

	override fun onResume()
	{
		super.onResume()
		trace("Lifecycle: onResume")
		xr?.start()
		// Reabrir o portao fechado no onPause. Se a activity nunca voltar, esta
		// linha nao roda e nao faz falta; se voltar, sem ela a tela fica parada
		// com a sessao viva, que e o pior dos dois enganos possiveis aqui.
		session?.setVideoRenderToSurface(true)
	}

	override fun onPause()
	{
		super.onPause()
		// Marcado no diário de propósito.
		//
		// Uma tentativa de conexão remota morreu aos 20 segundos de furação sem
		// deixar rastro: o diário mostrava "Foco de janela: false" e, um segundo
		// e meio depois, o cancelamento que só o onDestroy dispara. O que houve
		// entre as duas linhas não estava escrito em lugar nenhum, e sem PC não
		// há como olhar de fora. Agora está.
		trace("Lifecycle: onPause (isFinishing=$isFinishing)")
		// Fechar a escrita na Surface ANTES de parar a sessao imersiva.
		//
		// A especificacao do XR_KHR_android_surface_swapchain e explicita: a
		// aplicacao tem de garantir que nenhuma thread esteja escrevendo nas
		// Surfaces criadas pela extensao antes de chamar xrEndSession -- fora
		// disso o efeito e indefinido. Quem escreve nela e a thread de saida do
		// MediaCodec, dentro da libchiaki, e o xrEndSession sai do
		// XR_SESSION_STATE_STOPPING, que o runtime so manda depois que a
		// activity perde a visibilidade. Ou seja: depois desta linha. E a unica
		// ordem que o ciclo de vida do Android garante de graca.
		//
		// Fechar o portao nao derruba o decodificador, e essa e a diferenca que
		// importa. A saida obvia -- setSurface(null) -- mata o codec com a
		// thread de video ainda entregando quadros, e foi exatamente ela que
		// produziu o SIGSEGV em ANativeWindow_release.
		session?.setVideoRenderToSurface(false)
		xr?.stop()
	}

	override fun onStop()
	{
		super.onStop()
		trace("Lifecycle: onStop (isFinishing=$isFinishing)")
	}

	override fun finish()
	{
		// Quem manda encerrar aparece na pilha; o diário guarda só o essencial,
		// que é a primeira linha nossa depois do próprio finish.
		val de = Throwable().stackTrace.drop(1)
			.firstOrNull { it.className.startsWith("com.") }
		trace("Finishing the activity, asked for by ${de ?: "an unknown source"}")
		super.finish()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		trace("Lifecycle: onDestroy (isFinishing=$isFinishing)")
		// Antes do dispose, e nao depois: o tick dos medidores lê o contador de
		// pacotes através da sessão nativa, e uma leitura depois do dispose seria
		// uso de memória liberada. Hoje as duas coisas correm na mesma thread e
		// não se intercalam, mas depender disso é depender de um detalhe que a
		// próxima mudança pode quebrar em silêncio.
		handler.removeCallbacks(meterTick)
		handler.removeCallbacks(healthTick)
		// Cancelar E ESPERAR, antes de qualquer dispose.
		//
		// A furação de NAT corre numa thread e usa a sessão nativa por dentro
		// dela. Só cancelar não basta: o cancelamento pede para parar, e entre
		// o pedido e a parada de verdade existe uma janela em que liberar a
		// sessão é uso de memória liberada. Isso derrubou o processo com
		// "pthread_mutex_lock called on a destroyed mutex", dentro de
		// chiaki_holepunch_session_start.
		val furacaoPresa = psnThread?.let { t ->
			if(!t.isAlive)
				false
			else
			{
				session?.cancelPsn()
				t.join(PSN_JOIN_TIMEOUT_MS)
				t.isAlive
			}
		} ?: false
		psnThread = null

		if(furacaoPresa)
		{
			// Vazar a sessão é ruim; liberar memória que outra thread ainda usa
			// é pior, e era o que matava o processo.
			Log.w(TAG, "NAT traversal did not finish in time; leaving the native " +
					"session behind instead of freeing it out from under it")
			session = null
		}
		else
			session?.let {
				// Parar ANTES de mexer na superfície, e não depois.
				//
				// A ordem antiga desligava a superfície com o stream ainda
				// entregando quadros. Desligar a superfície mata o
				// decodificador, e a thread de vídeo continuava mandando dado
				// para ele -- o processo morreu com "incStrong() ... strong
				// refs = 0" dentro do dequeueInputBuffer, ao encerrar uma
				// sessão imersiva remota que tinha rodado sete minutos.
				//
				// E o setSurface(null) era redundante: o dispose faz join na
				// sessão (que encerra a thread de vídeo) e só então o
				// sessionFree, que já derruba o decodificador. Fazer a mesma
				// coisa mais cedo, à mão, só servia para criar a corrida.
				it.stop()
				it.dispose()
			}
		wifiLock.release()
		rumble?.stop()
		rumble = null
		session = null
		xr?.destroy()
		xr = null
		hudBitmap?.recycle()
		hudBitmap = null
	}

	// --- Entrada -----------------------------------------------------------
	//
	// O DualSense (e qualquer gamepad HID que o Horizon OS pareie) chega aqui
	// como evento Android normal, e o StreamInput do chiaki-ng ja faz o
	// mapeamento para o estado de controle do PlayStation. Nao reimplementamos
	// nada disso; so interceptamos antes o acorde que abre o modo de ajuste.

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		logIncomingKey(event)
		if(handleAdjustChord(event))
			return true
		if(adjustMode)
			return handleAdjustKey(event)

		if(handleTouchpadKey(event))
			return true

		val streamInput = input ?: return super.dispatchKeyEvent(event)
		if(streamInput.dispatchKeyEvent(event))
			return true

		// Tecla que o chiaki nao consome tem de seguir para o sistema, senao
		// coisas como o botao voltar do Horizon OS morrem aqui dentro.
		logUnmappedKey(event)
		return super.dispatchKeyEvent(event)
	}

	/**
	 * Injeta o clique do touchpad direto no estado de toque do StreamInput.
	 *
	 * O getter controllerState do chiaki ja combina esse estado com o do
	 * teclado e o dos analogicos, entao basta ligar o bit e reenviar.
	 */
	private fun handleTouchpadKey(event: KeyEvent): Boolean
	{
		if(event.keyCode !in TOUCHPAD_KEYCODES)
			return false
		val streamInput = input ?: return false

		val state = streamInput.touchControllerState
		state.buttons = when(event.action)
		{
			KeyEvent.ACTION_DOWN -> state.buttons or ControllerState.BUTTON_TOUCHPAD
			KeyEvent.ACTION_UP -> state.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
			else -> return true
		}
		session?.setControllerState(streamInput.controllerState)
		return true
	}

	/**
	 * Registra toda tecla de gamepad que entra, antes de qualquer tratamento.
	 *
	 * O log de teclas sem mapeamento nao bastava: ele so ve o que o chiaki
	 * recusa, e o que o chiaki aceita -- justamente o que precisamos conferir
	 * contra o que o controle deveria emitir -- passava invisivel. Sao poucos
	 * eventos, um por toque, entao nao ha o que limitar.
	 */
	private fun logIncomingKey(event: KeyEvent)
	{
		if(event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0)
			return
		trace("Key received: ${KeyEvent.keyCodeToString(event.keyCode)} " +
				"(${event.keyCode}) from '${event.device?.name}' source=0x${event.source.toString(16)}")
	}

	/**
	 * Registra no logcat e no diário em arquivo.
	 *
	 * O diário existe porque o buffer do logcat gira rápido demais em sessão: o
	 * chiaki escreve centenas de linhas por segundo, e o que aconteceu durante o
	 * jogo já sumiu quando a tela de diagnóstico abre depois.
	 */
	private fun trace(message: String) = Trace.log(this, message)

	/**
	 * Registra tecla de gamepad que ninguem consumiu.
	 *
	 * Serve para descobrir o que os botoes extras de um controle emitem sem ter
	 * o hardware em maos: aperte o botao, leia o keycode no logcat, mapeie com
	 * certeza em vez de adivinhar.
	 */
	private fun logUnmappedKey(event: KeyEvent)
	{
		if(event.action != KeyEvent.ACTION_DOWN)
			return
		if(event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD &&
				event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK)
			return
		Log.i(TAG, "Unmapped gamepad key: keyCode=${event.keyCode} " +
				"(${KeyEvent.keyCodeToString(event.keyCode)}) from '${event.device?.name}'")
	}

	/**
	 * Lista o que o sistema considera controle no momento em que o stream sobe.
	 *
	 * Responde de uma vez a pergunta que nenhuma teoria resolve: o controle
	 * esta pareado com o headset (e passa por aqui) ou com o console (e nunca
	 * chega)? Se a lista vier vazia, o input nao e nosso -- e o modo de ajuste
	 * jamais vai responder, por mais correto que o codigo esteja.
	 */
	private fun logInputDevices()
	{
		val ids = InputDevice.getDeviceIds()
		val named = mutableListOf<Int>()
		var gamepads = 0
		for(id in ids)
		{
			val device = InputDevice.getDevice(id) ?: continue
			val isGamepad = device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
					device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
			if(!isGamepad)
				continue
			gamepads++
			// Os controles Touch do headset entram sem nome de HID, no formato
			// "Device 0x...". Um gamepad pareado se identifica.
			if(!device.name.startsWith("Device 0x"))
				named.add(id)

			// hasKeys responde o que o driver realmente expoe. Se L3 e R3 vierem
			// false, o acorde do modo de ajuste e impossivel neste controle e o
			// problema nao esta no codigo que o trata.
			val keys = device.hasKeys(
				KeyEvent.KEYCODE_BUTTON_THUMBL,
				KeyEvent.KEYCODE_BUTTON_THUMBR,
				KeyEvent.KEYCODE_BUTTON_A,
				KeyEvent.KEYCODE_BUTTON_MODE
			)
			trace("Gamepad '${device.name}' id=$id sources=0x${device.sources.toString(16)} " +
					"L3=${keys[0]} R3=${keys[1]} Cross=${keys[2]} PS=${keys[3]} " +
					"eixos=${device.motionRanges.size}")
		}
		trace("Input devices: ${ids.size} in total, $gamepads gamepad(s)")

		// Nomeado ganha de anonimo, e o primeiro nomeado ganha dos demais.
		preferredDeviceId = named.firstOrNull()
		if(preferredDeviceId != null)
			trace("Sticks taken from '${InputDevice.getDevice(preferredDeviceId!!)?.name}' " +
					"(id=$preferredDeviceId); the others are ignored for axes")
		else
			trace("No named gamepad; accepting axes from all of them")
		if(gamepads == 0)
			trace("No gamepad visible to the app: the controller is probably " +
					"paired with the console instead of the headset")
	}

	/**
	 * Confirma que a etiqueta do FrameSync chegou ao APK.
	 *
	 * Lida do próprio manifesto empacotado, e não de uma constante nossa: uma
	 * constante diria o que eu quis, e o que interessa é o que foi instalado.
	 * Sem esta linha, "liguei o FrameSync" e "achei que liguei" ficariam
	 * indistinguíveis — que é exatamente o erro que a linha do filtro
	 * automático cometeu por semanas.
	 *
	 * Isto confirma o pedido, não o atendimento: se o Horizon OS desta versão
	 * honra a etiqueta é outra coisa, e quem responde é o contador de latência
	 * do painel.
	 */
	private fun logFrameSync()
	{
		val declared = try
		{
			packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
				.metaData?.getBoolean("com.oculus.enable_frame_sync", false) ?: false
		}
		catch(e: Exception)
		{
			false
		}
		Log.i(TAG, "FrameSync requested in the manifest: $declared")
	}

	/**
	 * A zona morta saiu daqui.
	 *
	 * Ela vivia nesta activity, com um número fixo de 10% escolhido a partir de
	 * um controle só. Agora é medida por controle na tela de calibração e
	 * aplicada dentro do `StreamInput` (patch 0019), que é o único ponto por
	 * onde os dois modos passam -- o imersivo tinha zona morta e o modo janela
	 * não tinha nenhuma, com o mesmo controle gasto andando sozinho num e não
	 * no outro.
	 *
	 * Aplicar aqui também faria a conta duas vezes: a segunda reescala comeria
	 * mais um pedaço do curso, e o analógico ficaria curto sem nada na tela
	 * explicando por quê.
	 */

	/**
	 * Amostra o estado que sai daqui para o console.
	 *
	 * Uma linha por segundo, no maximo: serve para separar "o botao nao chegou
	 * ao app" de "chegou, foi enviado, e o console ignorou" -- que sao problemas
	 * em lados opostos da rede.
	 */
	private var lastStateLine: String? = null

	private fun logControllerState(state: ControllerState)
	{
		val now = SystemClock.elapsedRealtime()
		if(now - lastStateLogMs < STATE_LOG_INTERVAL_MS)
			return
		// toLong antes do toString: buttons e UInt, e a sobrecarga com radix
		// nos tipos sem sinal ainda e experimental no Kotlin desta build.
		val linha = "State -> console: buttons=0x${state.buttons.toLong().toString(16)} " +
				"L2=${state.l2State} R2=${state.r2State} " +
				"LX=${state.leftX} LY=${state.leftY} RX=${state.rightX} RY=${state.rightY}"
		// Uma linha por segundo com o controle parado enche o diário de zeros e
		// empurra para fora justamente o que se foi ler. O que interessa é que o
		// estado *mudou*; parado, uma linha só já disse tudo.
		if(linha == lastStateLine)
			return
		lastStateLogMs = now
		lastStateLine = linha
		trace(linha)
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		// Uma activity imersiva que nao desenha nada na propria janela pode
		// ficar sem foco sem que nada mais denuncie. Sem foco, nenhum KeyEvent
		// chega -- e o sintoma e identico ao de um controle nao pareado.
		trace("Window focus: $hasFocus")
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		// Em modo de ajuste os analogicos ficam mudos, para nao vazar movimento
		// para o jogo enquanto o usuario mexe na tela -- mas o D-pad tem de ser
		// lido aqui, porque e aqui que ele chega.
		if(adjustMode)
		{
			handleAdjustHat(event)
			return true
		}
		if(!isPreferredGamepad(event.deviceId))
			return true
		return input?.onGenericMotionEvent(event) ?: super.onGenericMotionEvent(event)
	}

	/**
	 * Tamanho e distância da tela, pelo D-pad.
	 *
	 * O D-pad do DualSense não chega como tecla: vem como os eixos `HAT_X` e
	 * `HAT_Y`, que é como quase todo gamepad HID o reporta. O tratamento de
	 * ajuste escutava `KEYCODE_DPAD_*`, que nunca chegava — e o diário provava
	 * isso desde o teste de input, onde apareceram treze botões e nenhum DPAD.
	 *
	 * Pior: o modo de ajuste descartava todo evento de eixo para não vazar
	 * movimento ao jogo, então bloqueava justamente o caminho por onde o D-pad
	 * chega. Tamanho e distância nunca tiveram como funcionar.
	 *
	 * Age só na transição, não a cada evento: o eixo é reenviado continuamente
	 * enquanto o direcional está pressionado, e reagir a todos faria a tela
	 * disparar de um extremo ao outro num toque.
	 */
	private fun handleAdjustHat(event: MotionEvent)
	{
		if(!isPreferredGamepad(event.deviceId))
			return

		fun step(value: Float) = when
		{
			value > 0.5f -> 1
			value < -0.5f -> -1
			else -> 0
		}

		val x = step(event.getAxisValue(MotionEvent.AXIS_HAT_X))
		val y = step(event.getAxisValue(MotionEvent.AXIS_HAT_Y))

		if(x != hatX)
		{
			hatX = x
			// Direita afasta, esquerda aproxima.
			if(x != 0)
				screenPrefs.radius = screenPrefs.radius + ScreenPrefs.RADIUS_STEP * x
		}
		if(y != hatY)
		{
			hatY = y
			// HAT_Y é negativo para cima, e para cima a tela cresce.
			if(y != 0)
				screenPrefs.centralAngle = screenPrefs.centralAngle - ScreenPrefs.ANGLE_STEP * y
		}

		if(x != 0 || y != 0)
		{
			applyScreenParams()
			renderHud()
		}
	}

	/**
	 * Decide de qual controle os analogicos valem.
	 *
	 * O headset conta como dois gamepads a mais: os controles Touch aparecem
	 * como joystick e reportam eixos o tempo todo, mesmo parados na mesa. Como
	 * o chiaki processa todo evento de movimento que chega, os eixos deles
	 * sobrescreviam os do controle de verdade -- no diario, os analogicos nunca
	 * ficavam em zero em repouso, e dentro do jogo isso e a camera derivando
	 * sozinha.
	 *
	 * Os Touch nao tem nome de HID e aparecem como "Device 0x...", enquanto um
	 * gamepad pareado se identifica. Na duvida, aceita: e melhor deixar passar
	 * um controle desconhecido do que ignorar o unico que existe.
	 */
	private fun isPreferredGamepad(deviceId: Int): Boolean
	{
		val preferred = preferredDeviceId ?: return true
		return deviceId == preferred
	}

	/**
	 * L3 + R3 pressionados juntos alternam o modo de ajuste. E um acorde que
	 * praticamente nao aparece em jogo, entao nao rouba input do console.
	 */
	private fun handleAdjustChord(event: KeyEvent): Boolean
	{
		val down = event.action == KeyEvent.ACTION_DOWN
		when(event.keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_THUMBL -> thumbLDown = down
			KeyEvent.KEYCODE_BUTTON_THUMBR -> thumbRDown = down
			else -> return false
		}

		if(down && thumbLDown && thumbRDown)
		{
			adjustMode = !adjustMode
			trace(if(adjustMode) "Tuning mode on" else "Tuning mode off")
			// O pressionar do acorde ja foi para o console antes de o segundo
			// botao fechar a combinacao. Solta os dois agora, senao o console
			// segue achando que estao apertados.
			releaseChordButtons()
			return true
		}
		// Enquanto o modo esta ligado, L3/R3 nao chegam ao console.
		return adjustMode
	}

	/**
	 * Manda ao console o soltar de L3 e R3.
	 *
	 * O acorde so e reconhecido quando o segundo botao desce, e a essa altura o
	 * primeiro ja foi encaminhado como pressionado. Sem este desfazer, o estado
	 * do console fica com L3 (0x400) ou R3 (0x800) ligado indefinidamente.
	 */
	private fun releaseChordButtons()
	{
		val streamInput = input ?: return
		for(keyCode in intArrayOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR))
			streamInput.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
		session?.setControllerState(streamInput.controllerState)
	}

	private fun handleAdjustKey(event: KeyEvent): Boolean
	{
		// O soltar tem de chegar ao console mesmo com o modo ligado.
		//
		// Sem isto, apertar um botao fora do modo e solta-lo dentro dele deixa
		// o botao ligado para sempre do lado do console: o pressionar passou, o
		// soltar foi engolido aqui. Foi o que aconteceu com o L3 do acorde --
		// ele ficou preso, e L3 preso e corrida ou mira travada na maior parte
		// dos jogos.
		if(event.action == KeyEvent.ACTION_UP)
		{
			input?.dispatchKeyEvent(event)
			return true
		}
		if(event.action != KeyEvent.ACTION_DOWN)
			return true

		when(event.keyCode)
		{
			KeyEvent.KEYCODE_DPAD_UP ->
				screenPrefs.centralAngle = screenPrefs.centralAngle + ScreenPrefs.ANGLE_STEP
			KeyEvent.KEYCODE_DPAD_DOWN ->
				screenPrefs.centralAngle = screenPrefs.centralAngle - ScreenPrefs.ANGLE_STEP
			KeyEvent.KEYCODE_DPAD_RIGHT ->
				screenPrefs.radius = screenPrefs.radius + ScreenPrefs.RADIUS_STEP
			KeyEvent.KEYCODE_DPAD_LEFT ->
				screenPrefs.radius = screenPrefs.radius - ScreenPrefs.RADIUS_STEP
			KeyEvent.KEYCODE_BUTTON_R1 ->
				screenPrefs.heightOffset = screenPrefs.heightOffset + ScreenPrefs.HEIGHT_STEP
			KeyEvent.KEYCODE_BUTTON_L1 ->
				screenPrefs.heightOffset = screenPrefs.heightOffset - ScreenPrefs.HEIGHT_STEP
			KeyEvent.KEYCODE_BUTTON_A -> // Cross: recentrar
				xr?.recenter()
			KeyEvent.KEYCODE_BUTTON_Y -> // Triângulo: quanto do quarto aparece
			{
				val bridge = xr
				if(bridge != null && bridge.isPassthroughSupported)
				{
					// Cinco estados num botão só, porque é tudo a mesma
					// pergunta: desligado → normal → suave → médio → forte.
					// Separar "ligar o passthrough" de "escurecer o
					// passthrough" em dois botões daria dois botões para
					// escolher uma coisa.
					if(!screenPrefs.passthroughEnabled)
					{
						screenPrefs.passthroughEnabled = true
						screenPrefs.cinema = 0
					}
					else if(screenPrefs.cinema >= 3)
					{
						screenPrefs.passthroughEnabled = false
						screenPrefs.cinema = 0
					}
					else
						screenPrefs.cinema += 1

					bridge.setPassthrough(screenPrefs.passthroughEnabled)
					applyQualityParams()
					Log.i(TAG, "Passthrough: ${screenPrefs.passthroughEnabled}, "
							+ "cinema ${ScreenPrefs.CINEMA_NAMES[screenPrefs.cinema]}")
				}
			}
			KeyEvent.KEYCODE_BUTTON_X -> // Square: cicla a nitidez
			{
				// Seis degraus: quatro de intensidade, e depois os dois
				// algoritmos do compositor — MQSR e a escolha automática.
				qualityPrefs.sharpness = (qualityPrefs.sharpness + 1) % 6
				applyQualityParams()
				Log.i(TAG, "Sharpness: ${qualityPrefs.sharpness} "
						+ "(${StreamQualityPrefs.SHARPNESS_NAMES[qualityPrefs.sharpness]})")
			}
			KeyEvent.KEYCODE_BUTTON_R2 ->
			{
				// Cicla a curvatura da tela curva. Passos, e nao um eixo
				// continuo: sao poucos valores uteis e cada um se reconhece de
				// imediato, o que um ajuste fino nao daria.
				screenPrefs.curvature = when
				{
					screenPrefs.curvature < 0.3f -> 0.35f
					screenPrefs.curvature < 0.5f -> 0.6f
					screenPrefs.curvature < 0.8f -> 1.0f
					else -> ScreenPrefs.CURVATURE_MIN
				}
				trace("Curvatura: %.2f".format(screenPrefs.curvature))
			}
			KeyEvent.KEYCODE_BUTTON_L2 ->
			{
				// Alterna cilindro e quad em jogo.
				//
				// Vale por si -- ha quem prefira tela plana -- e serve para
				// isolar o artefato circular do MQSR: a documentacao da Meta
				// trata o sharpening para camadas de projecao e quad, e nao diz
				// nada sobre cilindro. Se o circulo sumir no quad, o filtro nao
				// se aplica a esta forma; se continuar, o problema e outro.
				val shape = if(screenPrefs.layerShape == ScreenPrefs.SHAPE_CYLINDER)
					ScreenPrefs.SHAPE_QUAD else ScreenPrefs.SHAPE_CYLINDER
				screenPrefs.layerShape = shape
				xr?.setLayerShape(shape)
				trace("Layer shape: ${if(shape == ScreenPrefs.SHAPE_QUAD) "quad" else "cylinder"}")
			}
			KeyEvent.KEYCODE_BUTTON_START ->
			{
				// Options: forca do 3D sintetizado.
				//
				// Este botao fazia a mesma coisa que o Share, e ter dois botoes
				// para um ajuste enquanto o efeito que mais precisa ser afinado
				// nao tinha nenhum era desperdicio. A forca e o ajuste que so se
				// acerta sentindo: o numero certo muda com o jogo, com a cena e
				// com quem esta jogando, e tirar o headset a cada tentativa
				// tornaria a busca impraticavel.
				//
				// Fora do 3D o botao nao faz nada, e e melhor assim do que
				// mexer em algo sem relacao.
				if(qualityPrefs.syntheticStereo)
				{
					// Cinco degraus, do desligado ao maximo. O maximo continua
					// limitado pela distancia interpupilar do lado nativo.
					val passo = ((qualityPrefs.stereoStrength * 4f).toInt() + 1) % 5
					qualityPrefs.stereoStrength = passo / 4f
					xr?.setStereoTuning(qualityPrefs.stereoDisparity(),
							qualityPrefs.stereoConvergence)
					trace("3D strength: ${(qualityPrefs.stereoStrength * 100).toInt()}%")
				}
			}
			KeyEvent.KEYCODE_BUTTON_SELECT ->
			{
				// Share: brilho da tela.
				//
				// Este botao era o interruptor do espelhamento vertical, que
				// nasceu para investigar a imagem de cabeca para baixo e ficou
				// depois de o compositor passar a corrigir sozinho. Um botao
				// gasto num ajuste que nunca mais precisou ser mexido, enquanto
				// o brilho -- que muda com a hora do dia e com os 10 bits
				// ligados -- nao tinha nenhum. A preferencia continua existindo
				// e continua ligada; so deixou de ocupar um botao.
				qualityPrefs.brightness = (qualityPrefs.brightness + 1) % 4
				applyQualityParams()
				Log.i(TAG, "Screen brightness: "
						+ StreamQualityPrefs.BRIGHTNESS_NAMES[qualityPrefs.brightness])
			}
			KeyEvent.KEYCODE_BUTTON_B -> // Circle: sair do modo de ajuste
				adjustMode = false
			else -> return true
		}
		applyScreenParams()
		// Os valores no painel tem de acompanhar o que acabou de mudar; um
		// painel que mostra o estado anterior e pior que nenhum.
		if(adjustMode)
			renderHud()
		return true
	}

	/**
	 * Desenha o painel de ajuda do modo de ajuste.
	 *
	 * Existe porque o modo de ajuste era invisível: ligava sem sinal nenhum e
	 * cada botão tinha de ser descoberto por tentativa. Mostra o que cada um faz
	 * e o valor atual de cada ajuste, porque saber que "D-pad muda o tamanho"
	 * não ajuda sem saber em que tamanho a tela está.
	 */
	/** Nome legível da curvatura: o número em si não diz nada a quem olha. */
	private fun curvatureName() = when
	{
		screenPrefs.layerShape == ScreenPrefs.SHAPE_QUAD -> "n/a (flat screen)"
		screenPrefs.curvature < 0.3f -> "almost flat"
		screenPrefs.curvature < 0.5f -> "subtle"
		screenPrefs.curvature < 0.8f -> "medium"
		else -> "maximum"
	}

	private fun renderHud()
	{
		val bitmap = hudBitmap ?: Bitmap.createBitmap(HUD_WIDTH, HUD_HEIGHT, Bitmap.Config.ARGB_8888)
			.also { hudBitmap = it }
		val canvas = Canvas(bitmap)
		canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

		val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(214, 12, 14, 20) }
		canvas.drawRoundRect(RectF(8f, 8f, HUD_WIDTH - 8f, HUD_HEIGHT - 8f), 28f, 28f, bg)

		val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.STROKE
			strokeWidth = 3f
			color = Color.argb(150, 120, 170, 255)
		}
		canvas.drawRoundRect(RectF(8f, 8f, HUD_WIDTH - 8f, HUD_HEIGHT - 8f), 28f, 28f, edge)

		val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.rgb(140, 190, 255)
			textSize = 44f
			typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
		}
		canvas.drawText("Tuning mode", 44f, 78f, title)

		val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.argb(180, 200, 210, 230)
			textSize = 26f
		}
		canvas.drawText("L3 + R3 to leave  ·  the console receives nothing right now",
			44f, 116f, hint)

		val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.rgb(255, 214, 120)
			textSize = 30f
			typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
		}
		val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.rgb(232, 236, 245)
			textSize = 30f
		}

		val filterName = StreamQualityPrefs.SHARPNESS_NAMES[qualityPrefs.sharpness]
		// Arco em graus e distância em metros: radianos não dizem nada a quem
		// está olhando para a tela.
		val shapeName = if(screenPrefs.layerShape == ScreenPrefs.SHAPE_QUAD) "flat" else "curved"
		val rows = listOf(
			"D-pad ↑↓" to "screen size — ${Math.toDegrees(screenPrefs.centralAngle.toDouble()).toInt()}°"
					+ nitidezVerdict(),
			"D-pad ←→" to "distance — %.1f m".format(screenPrefs.radius),
			"L1 / R1" to "height — %+.2f m".format(screenPrefs.heightOffset),
			"L2" to "screen shape — $shapeName",
			"R2" to "curvature — ${curvatureName()}",
			"Square" to "sharpness — $filterName",
			"Triangle" to "room — ${if(!screenPrefs.passthroughEnabled) "hidden"
					else ScreenPrefs.CINEMA_NAMES[screenPrefs.cinema]}",
			"X" to "recenter the screen in front of you",
			"Share" to "screen brightness — ${StreamQualityPrefs.BRIGHTNESS_NAMES[qualityPrefs.brightness]}",
			"Options" to if(qualityPrefs.syntheticStereo)
					"3D strength — ${(qualityPrefs.stereoStrength * 100).toInt()}%"
				else "3D is off (turn it on in the launcher)",
			"Circle" to "leave tuning mode"
		)

		var y = 178f
		for((key, description) in rows)
		{
			canvas.drawText(key, 44f, y, label)
			canvas.drawText(description, 250f, y, text)
			y += 40f
		}

		drawMeters(canvas, y + 22f, label, text)

		xr?.setHudBitmap(bitmap)
	}

	/**
	 * Veredito sobre o tamanho da tela, ao lado do tamanho.
	 *
	 * O compositor sabe quantos pixels ele gostaria de ter nesta camada, do
	 * jeito que ela está agora — é função do ângulo que a tela ocupa na sua
	 * visão e da densidade do painel. Comparado com os 1920x1080 que o Remote
	 * Play entrega, isso vira o único critério objetivo que existe nesta cadeia
	 * para escolher o tamanho da tela.
	 *
	 * Abaixo da fonte, sobra detalhe que não está sendo usado: crescer a tela é
	 * de graça. Acima, o compositor passa a esticar 1080p, e a imagem amolece.
	 * O ponto é a igualdade.
	 *
	 * Em texto e não em número cru porque "1527x841" não diz a ninguém o que
	 * fazer com ele — foi exatamente assim que essa linha confundiu em vez de
	 * informar, quando existia só no log.
	 */
	private fun nitidezVerdict(): String
	{
		val rec = xr?.recommendedResolution() ?: return ""
		val (recWidth, _) = rec
		val source = 1920
		val ratio = recWidth.toFloat() / source
		return when
		{
			ratio < 0.92f -> "  ·  can grow (uses %.0f%% of the source)".format(ratio * 100f)
			ratio > 1.08f -> "  ·  too large (asks for %.0f%% of the source)".format(ratio * 100f)
			else -> "  ·  just right"
		}
	}

	/**
	 * Medidores: o que está acontecendo, e não o que está configurado.
	 *
	 * As duas metades da cadeia que antes não tinham número nenhum. Do lado da
	 * rede havia só o bloco de "FEC failed" no log, que diz que houve perda mas
	 * não quanta, e só aparece depois que a imagem já quebrou. Do compositor
	 * para cá não havia nem isso — engasgo era impressão.
	 *
	 * Cada linha só aparece se o valor existir de verdade. Um contador que este
	 * runtime não oferece não é zero, e mostrar zero seria pior do que não
	 * mostrar nada: pareceria medição, e seria invenção.
	 */
	private fun drawMeters(canvas: Canvas, top: Float, label: Paint, text: Paint)
	{
		var y = top
		val divider = Paint().apply { color = Color.argb(70, 140, 170, 220) }
		canvas.drawRect(44f, y - 26f, HUD_WIDTH - 44f, y - 25f, divider)

		val stats = session?.packetStats()
		if(stats != null)
		{
			val (received, lost) = stats
			// Diferença desde a última leitura, e não o total desde o início: o
			// acumulado de uma sessão inteira dilui um problema que está
			// acontecendo agora até ele sumir na média.
			val dReceived = (received - lastReceived).coerceAtLeast(0L)
			val dLost = (lost - lastLost).coerceAtLeast(0L)
			lastReceived = received
			lastLost = lost
			val total = dReceived + dLost
			val line = if(total == 0L)
				"no traffic this second"
			else
				"%.2f%% lost  ·  %d packets/s".format(100.0 * dLost / total, total)
			canvas.drawText("Network", 44f, y, label)
			canvas.drawText(line, 250f, y, text)
			y += 40f
		}

		val perf = xr?.readPerformance()
		if(perf != null && perf.size >= 15)
		{
			if(perf[11] > 0f)
			{
				// Movimento da cabeça até o fóton, medido pelo compositor. É a
				// metade da latência que é nossa: o resto — rede, codificação,
				// decodificação — mora do lado do console e do Wi-Fi.
				canvas.drawText("Latency", 44f, y, label)
				canvas.drawText("%.1f ms from head to photon".format(perf[12]), 250f, y, text)
				y += 40f
			}
			if(perf[0] > 0f)
			{
				// Também em diferença: o compositor conta desde que a sessão
				// abriu, e um descarte de dez minutos atrás não é notícia.
				val dropped = perf[1].toLong()
				val delta = (dropped - lastDropped).coerceAtLeast(0L)
				lastDropped = dropped
				canvas.drawText("Compositor", 44f, y, label)
				canvas.drawText(if(delta == 0L) "no dropped frames"
						else "$delta dropped frame(s)", 250f, y, text)
				y += 40f
			}
			val gpu = StringBuilder()
			if(perf[2] > 0f)
				gpu.append("usage %.0f%%".format(perf[3]))
			if(perf[4] > 0f)
			{
				if(gpu.isNotEmpty()) gpu.append("  ·  ")
				gpu.append("app %.1f ms".format(perf[5]))
			}
			if(perf[6] > 0f)
			{
				if(gpu.isNotEmpty()) gpu.append("  ·  ")
				gpu.append("compositor %.1f ms".format(perf[7]))
			}
			if(perf[13] > 0f)
			{
				if(gpu.isNotEmpty()) gpu.append("  ·  ")
				gpu.append("cpu %.0f%%".format(perf[14]))
			}
			if(gpu.isNotEmpty())
			{
				canvas.drawText("Load", 44f, y, label)
				canvas.drawText(gpu.toString(), 250f, y, text)
				y += 40f
			}
			if(perf[8] > 0f)
			{
				// Folga térmica: 1 é frio, 0 é o ponto em que o sistema começa
				// a cortar relógio. A inclinação diz para onde está indo, que é
				// o que permite ver a queda chegando antes de ela aparecer como
				// engasgo.
				val trend = when
				{
					perf[10] > 0.01f -> "warming up"
					perf[10] < -0.01f -> "cooling down"
					else -> "steady"
				}
				canvas.drawText("Thermal", 44f, y, label)
				canvas.drawText("headroom %.0f%%  ·  $trend".format(perf[9] * 100f), 250f, y, text)
				y += 40f
			}
		}
	}
}
