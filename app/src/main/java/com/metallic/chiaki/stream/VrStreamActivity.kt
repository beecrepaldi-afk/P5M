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
import com.metallic.chiaki.lib.TriggerEffectsEvent
import com.metallic.chiaki.lib.TriggerIntensityEvent
import com.metallic.chiaki.lib.HapticIntensityEvent
import com.metallic.chiaki.lib.LedColorEvent
import com.metallic.chiaki.lib.PlayerIndexEvent
import com.metallic.chiaki.lib.LedBrightnessEvent
import com.metallic.chiaki.lib.MotionResetEvent
import com.metallic.chiaki.lib.ControllerTouch
import com.metallic.chiaki.lib.Session
import com.metallic.chiaki.main.MainActivity
import com.metallic.chiaki.session.StreamInput
import io.github.gblandro.p5m.P5MApp
import io.github.gblandro.p5m.DualSenseHaptics
import io.github.gblandro.p5m.DualSenseHid
import io.github.gblandro.p5m.EspelhoDualSense
import io.github.gblandro.p5m.ForcaGatilhos
import io.github.gblandro.p5m.MovimentoDualSense
import io.github.gblandro.p5m.PainelDeAjuste
import io.github.gblandro.p5m.TecladoPin
import io.github.gblandro.p5m.InputArrival
import io.github.gblandro.p5m.Rumble
import io.github.gblandro.p5m.ScreenPrefs
import io.github.gblandro.p5m.TouchpadPointer
import io.github.gblandro.p5m.Trace
import io.github.gblandro.p5m.WifiLowLatency
import io.github.gblandro.p5m.StreamQualityPrefs
import io.github.gblandro.p5m.XrBridge
import java.util.concurrent.atomic.AtomicInteger

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
		private const val HUD_WIDTH = 2048
		// Precisa casar com kHudHeight no lado nativo: o swapchain e criado la
		// e o bitmap daqui e copiado nele sem redimensionar. Divergir nao da
		// erro, so faz o painel aparecer cortado ou com lixo na borda.
		private const val HUD_HEIGHT = 464
		/** Quanto tempo a linha do ajuste que acabou de mudar fica realcada. */
		private const val REALCE_MS = 1500L

		// Folga entre o console aceitar a conexao e a primeira imagem existir
		// de fato dentro da Surface.
		private const val VIDEO_LAYER_DELAY_MS = 1500L
		/** Quanto o onDestroy espera a liberacao da sessao antes de seguir sem ela. */
		private const val LIBERAR_MS = 2000L

		/**
		 * Quanto tempo o lembrete do L3+R3 fica na frente do jogador.
		 *
		 * Sete segundos: tempo de ler duas linhas sem pressa, e curto o bastante
		 * para nao atrapalhar quem ja sabe e entrou direto no jogo.
		 */
		private const val HINT_MS = 7000L
		private const val AVISO_ENLACE_MS = 12000L
		// Abaixo disto, em 10 s, o enlace esta em economia: sniff da ~850,
		// ativo da ~2000 (dev.204 e dev.205, mesmo controle).
		private const val LIMIAR_GIRO_ENLACE_LENTO = 1300

		/** Duracao de um clique de touchpad disparado por nos. */
		private const val TOUCHPAD_CLICK_MS = 80L

		/**
		 * Quanto tempo o Circle precisa ficar apertado para encerrar a sessao.
		 * Um segundo: longe de um toque, perto o bastante para nao parecer que o
		 * botao nao respondeu.
		 */
		private const val QUIT_HOLD_MS = 1000L

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
		private val AJUSTES_QUE_REPETEM = setOf(
			KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
			KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
			KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L1)

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
	// Aberto so enquanto o console espera a senha de login. Com ele de pe, toda
	// tecla e todo direcional vao para ele, e nada para o console.
	private var tecladoPin: TecladoPin? = null
	private val handler = Handler(Looper.getMainLooper())
	private val liberarCamada = Runnable {
		trace("Releasing the video layer")
		xr?.setVideoLayerEnabled(true)
		showStartHint()
	}
	/**
	 * O runtime pode encerrar a sessao imersiva por conta propria (saida pelo
	 * menu do sistema, sessao perdida). O loop de quadros para ali, e sem isto
	 * a activity seguia viva com o som do jogo sobre uma tela que nao voltava.
	 */
	private val vigiaSessaoXr = object: Runnable
	{
		override fun run()
		{
			if(xr?.sessionEnded == true)
			{
				trace("The runtime ended the immersive session; closing the stream")
				// Sem trazer o menu para a frente: quem encerra aqui e o
				// sistema, quase sempre porque o usuario mandou fechar o app, e
				// puxar a tarefa do menu contrariaria esse pedido.
				returnActivated = true
				finish()
				return
			}
			handler.postDelayed(this, 1000L)
		}
	}
	/**
	 * Um so relogio para todas as faixas (lembrete do acorde, avisos de
	 * controle): uma faixa nova reinicia a contagem, em vez de a de uma
	 * anterior apaga-la antes da hora.
	 */
	private val esconderFaixa = Runnable {
		if(!adjustMode && tecladoPin == null)
			xr?.setHudVisible(false)
	}
	private var lastReceived = 0L
	private var lastLost = 0L
	private var lastDropped = 0L
	// Bitrate automatico: a conta da sessao, fechada no onDestroy. Contadores
	// proprios, e nao os dos medidores: aqueles so andam com o painel aberto.
	private var redeRecebidos = -1L
	private var redePerdidos = 0L
	private var redeJanelas = 0
	private var redeJanelasRuins = 0
	private var redeInicioMs = 0L

	/** Largura pedida ao console: 1920, ou 2560 no experimento de 1440p. */
	private var larguraFonte = 1920
	/** Botao do ultimo ajuste feito no painel, para realcar a linha dele. */
	private var ultimoAjuste = 0
	private var ultimoAjusteMs = 0L
	private val preferredDeviceId: Int? get() = input?.preferredGamepadId
	private var rumble: Rumble? = null
	// Quantos pacotes de vibracao o console mandou na janela de dez segundos, e
	// qual foi o mais forte. Sem isto nao ha como separar "o console nao manda"
	// de "o console manda e nos nao entregamos" -- e era essa a duvida quando a
	// vibracao classica nao funcionou em cenario nenhum, com a buzina de teste
	// sendo a unica coisa sentida na sessao inteira.
	//
	// Atomicos porque sobem na thread de eventos da sessao e sao lidos e
	// zerados pelo tick, na principal.
	private val rumbleEventos = AtomicInteger()
	private val rumblePico = AtomicInteger()
	private var haptics: DualSenseHaptics? = null
	@Volatile private var microfone: io.github.gblandro.p5m.Microfone? = null
	/**
	 * Ligado no primeiro passo do onDestroy. O callback de eventos da sessao
	 * roda na thread nativa e segue vivo ate o stop(); um ConnectedEvent que
	 * chegasse nesse meio criava um microfone que ninguem mais pararia,
	 * entregando voz a uma sessao ja liberada.
	 */
	@Volatile private var encerrando = false
	// Gatilhos, barra de luz, jogador e intensidades, como o console pede.
	private var espelho: EspelhoDualSense? = null
	// Giroscopio e acelerometro do proprio controle, no lugar dos do headset.
	private var movimento: MovimentoDualSense? = null
	private var avisouEnlaceLento = false
	// dev.216: o enlace lento visto na ultima janela, o reinicio pedido pelo
	// painel, o controle sumido, e o ultimo degrau de bateria avisado.
	private var enlaceLento = false
	private var reinicioPendente = false
	private var controleAusente = false
	private var bateriaAvisada = 101
	private var bateriaNoDiario = -1
	// A intensidade da vibracao do PS5 como fator de amplitude, para o motor.
	// A haptica crua tem o seu, no DualSenseHaptics.
	@Volatile private var ganhoVibracao = 1f
	// R3 sozinho, dentro do painel, liga e desliga os gatilhos. "Sozinho" e o
	// que separa o toque do acorde que fecha o painel: se L3 descer enquanto
	// R3 esta apertado, era o acorde.
	private var r3Solo = false
	// Como esta sessao se anunciou ao console. Os gatilhos so chegam anunciado
	// como DualSense, e o anuncio nao muda depois de conectar.
	private var anunciouDualSense = false
	/**
	 * O touchpad do controle. Mesma classe do modo janela, de proposito: dois
	 * tratamentos para o mesmo botao acabariam divergindo.
	 */
	private val touchpadPointer = TouchpadPointer({ dedos -> setTouchpadFingers(dedos) }) { pressed ->
		setTouchpadPressed(pressed)
	}
	private val inputArrival = InputArrival(System.nanoTime())
	private var touchpadDown = false

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
			// Antes da saúde, que volta cedo quando a sessão OpenXR não existe.
			trace(inputArrival.report(System.nanoTime(),
					if(android.os.Build.VERSION.SDK_INT >= 34) "ns" else "ms"))
			espelho?.let { trace(it.janela()) }
			movimento?.let { trace(it.janela()); avisarSeEnlaceLento(it.giroNaUltimaJanela) }
			verificarBateria()
			// A vibracao que o console pediu, e nao a que o motor fez. Zero aqui
			// com o jogo tremendo na tela quer dizer que o console nao esta
			// mandando: o assunto e como o cliente se anuncia, e nao o motor.
			trace("Rumble events 10s: ${rumbleEventos.getAndSet(0)} from the console, " +
					"strongest ${rumblePico.getAndSet(0)} of 255" +
					(rumble?.soltosPelaVigia?.takeIf { it > 0 }?.let { ", released $it time(s) after the track stopped" } ?: ""))
			contarRede()
			logHealth()
			handler.postDelayed(this, HEALTH_PERIOD_MS)
		}
	}

	/**
	 * Uma janela de dez segundos para o bitrate automatico. A primeira leitura
	 * so marca o ponto de partida: o que veio antes dela e a abertura, com
	 * perda que nao diz nada sobre a rede.
	 */
	private fun contarRede()
	{
		val (recebidos, perdidos) = session?.packetStats() ?: return
		if(redeRecebidos < 0)
		{
			redeRecebidos = recebidos
			redePerdidos = perdidos
			redeInicioMs = SystemClock.uptimeMillis()
			return
		}
		val r = (recebidos - redeRecebidos).coerceAtLeast(0L)
		val l = (perdidos - redePerdidos).coerceAtLeast(0L)
		redeRecebidos = recebidos
		redePerdidos = perdidos
		// Janela quase sem trafego (menu parado, sessao suspensa) nao conta.
		if(r + l < 200)
			return
		redeJanelas++
		val perda = 100.0 * l / (r + l)
		if(perda >= 1.0)
		{
			redeJanelasRuins++
			trace("Network 10s: %.1f%% of %d packets lost (%d of %d windows with loss)"
					.format(java.util.Locale.US, perda, r + l, redeJanelasRuins, redeJanelas))
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
	private var r1Down = false
	private var quitArmed = false
	private var returnActivated = false

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
		larguraFonte = connectInfo.videoProfile.width
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
		// nossa) e faz nascer um segundo swapchain, um por olho. Os dois sao
		// decididos na criacao do swapchain, que acontece logo abaixo -- ligar
		// depois nao teria efeito nenhum nesta sessao.
		val synth3d = qualityPrefs.syntheticStereo
		bridge.setSyntheticStereo(synth3d)
		if(synth3d)
		{
			bridge.setStereoTuning(qualityPrefs.stereoStrength,
					qualityPrefs.stereoConvergence)
			bridge.setStereoPopOut(qualityPrefs.stereoPopOut)
			val neural = qualityPrefs.neuralDepth
			trace("Synthetic 3D on: depth "
					+ (if(neural) "from the neural model (image cues until it loads), " else "guessed from the image, ")
					+ "strength ${(qualityPrefs.stereoStrength * 100).toInt()}%, "
					+ "convergence ${(qualityPrefs.stereoConvergence * 100).toInt()}%"
					+ (if(qualityPrefs.stereoPopOut) ", pop-out with floating window" else ""))
			// A rede carrega em paralelo com a conexao: os primeiros segundos
			// saem com as pistas, e o mapa dela entra sozinho quando estiver
			// pronto.
			if(neural)
				io.github.gblandro.p5m.ProfundidadeNeural.start(this)
		}

		// Os 10 bits da sessao, e nao da preferencia: com PS4 a fonte e sempre
		// de 8 bits, e o caminho com shader so por causa deles seria desperdicio.
		val dezBits = qualityPrefs.tenBitNaSessao
		val toneMapped = qualityPrefs.toneMapped || qualityPrefs.syntheticStereo || dezBits
		bridge.setRenderPath(if(toneMapped) 1 else 0, dezBits)
		trace("Video path: ${if(toneMapped) "shader" else "direct"}, " +
				"source ${if(dezBits) "10-bit PQ" else "8-bit SDR"}" +
				(if(qualityPrefs.tenBit && !dezBits)
					(if(StreamQualityPrefs.sessaoPs4) " (10-bit is PS5 only)" else " (10-bit is off in 3D)")
				else ""))

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
		// Sempre Rec.709: os 10 bits obrigam o shader, e com ele a conversao
		// BT.2020 -> BT.709 ja aconteceu antes de a imagem chegar ao compositor.
		// Declarar 2020 aqui a converteria duas vezes. (Era `dezBits &&
		// !toneMapped`, que nao tinha como dar verdadeiro.)
		bridge.setWideColor(false)
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
		bridge.selectDisplayRefreshRate(qualityPrefs.sourceFps, qualityPrefs.panelMaxHz)

		val streamInput = StreamInput(this, Preferences(this))
		input = streamInput
		streamInput.gamepadChangedCallback = { resetGamepadGestures(); movimento?.religar(); controleMudou() }
		// O observe() liga o monitor de controles (reconexao no meio da
		// sessao); os sensores do headset ele nao registra mais (patch 0031).
		// Sem ele, a dev.204-206 perdia analogicos e direcional ao religar.
		streamInput.observe(this)
		movimento = MovimentoDualSense(this, streamInput, diarioProprio = false).also { lifecycle.addObserver(it) }

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
					// O canal do microfone so existe depois da conexao.
					if(qualityPrefs.microphone && !encerrando)
						microfone = io.github.gblandro.p5m.Microfone(this).also { it.start(newSession) }
					// Conectado nao quer dizer decodificado: o primeiro frame
					// ainda tem que atravessar rede e MediaCodec. So depois
					// disso a swapchain-Surface tem conteudo, e so entao a
					// camada pode ser submetida ao compositor.
					// No handler da activity, e nao num solto: o onDestroy
					// precisa poder cancelar, ou quem sai no primeiro segundo
					// e meio ganha um bitmap do painel recriado depois de
					// reciclado, e que ninguem mais recicla.
					if(!encerrando)
						handler.postDelayed(liberarCamada, VIDEO_LAYER_DELAY_MS)
				}
				is LoginPinRequestEvent ->
				{
					// A sessao fica parada ate alguem responder, sem prazo. A
					// resposta vem do teclado desenhado no painel: nesta
					// activity nao existe superficie 2D nem teclado do sistema.
					trace("The console asked for the login passcode" +
							if(event.pinIncorrect) " again (the last one was wrong)" else "")
					runOnUiThread { abrirTecladoPin(event.pinIncorrect) }
				}
				is QuitEvent ->
				{
					Log.i(TAG, "Session ended: ${event.reason} ${event.reasonString ?: ""}")
					runOnUiThread { finish() }
				}
				// Com a trilha crua saindo, o motor cala.
				//
				// As duas saidas continuam ligadas de proposito -- se a ponte com
				// o controle nao subir, a envoltoria no motor e o que salva a
				// sessao --, mas enquanto as duas funcionam quem se sente e o
				// motor: ele e grosseiro e muito mais forte que a bobina, e o
				// resultado e a vibracao de sempre com um chiado por baixo, que
				// e o oposto do que a trilha crua existe para dar.
				//
				// Zero em vez de nao mandar nada: o Rumble descarta valor
				// repetido, entao insistir em zero e barato, e parar de falar
				// com ele deixaria a ultima intensidade presa no motor.
				is RumbleEvent ->
				{
					rumbleEventos.incrementAndGet()
					rumblePico.accumulateAndGet(maxOf(event.left.toInt(), event.right.toInt()), ::maxOf)
					val g = ganhoVibracao
					val esq = (event.left.toInt() * g).toInt()
					val dir = (event.right.toInt() * g).toInt()
					val e = espelho
					if(haptics?.entregando == true)
						rumble?.set(0, 0)
					else if(e != null && e.motoresPeloEspelho && e.conectado)
					{
						// O zero ao Rumble solta o que ele tenha segurado
						// enquanto o espelho ainda procurava o DualSense.
						rumble?.set(0, 0)
						e.motores(esq, dir)
					}
					else
						rumble?.set(esq, dir)
				}
				// O resto do controle: tudo vai ao espelho, que e quem fala com
				// o DualSense. Nenhum destes toca em view, entao ficam na thread
				// dos eventos.
				is TriggerEffectsEvent -> espelho?.gatilhos(event.right, event.left)
				is TriggerIntensityEvent -> espelho?.intensidadeGatilhos(event.intensity)
				is HapticIntensityEvent ->
				{
					val g = ForcaGatilhos.fator(event.intensity)
					ganhoVibracao = g
					haptics?.ganho = g
					espelho?.intensidadeVibracao(event.intensity)
					if(espelho == null)
						trace("PS5 vibration intensity: x$g")
				}
				is MotionResetEvent -> movimento?.zerar()
				is LedColorEvent -> espelho?.cor(event.red, event.green, event.blue)
				is PlayerIndexEvent -> espelho?.jogador(event.index)
				is LedBrightnessEvent -> espelho?.brilho(event.level)
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
		// Com a haptica crua em jogo, o Rumble nasce desligado.
		//
		// Nao e so para nao somar as duas: a buzina de teste que ele manda ao
		// abrir a sessao passa pelo caminho de vibracao do Android, e mandar
		// intensidade poe a bobina do controle em modo motor -- onde ela ignora
		// a trilha de audio. A buzina trocava o modo do controle bem na hora em
		// que a entrega crua comecava, e o que se sentia era so ela.
		//
		// Desligado ele nao varre, nao busca alvo e nao buzina. Se a ponte com o
		// controle nao subir, um Rumble de verdade nasce no lugar.
		val crua = qualityPrefs.hapticRumble && DualSenseHid.disponivel(this)
		val ligarRumble = {
			rumble = Rumble(Preferences(this).rumbleEnabled).also { it.attach(preferredDeviceId) }
		}
		if(crua)
			rumble = Rumble(false)
		else
			ligarRumble()

		// Com o cliente anunciado como DualSense, o console manda a haptica crua
		// e a envoltoria que o patch 0009 calcula nao serve para nada -- ela
		// existe para o caso de nao haver onde entregar a trilha. Havendo, a
		// trilha vai inteira.
		//
		// As duas saidas ficam ligadas ao mesmo tempo de proposito: se a ponte
		// com o controle nao subir, a envoltoria continua chegando ao motor, e a
		// sessao degrada em vez de ficar muda. O `Rumble` ja descarta valor
		// repetido, entao o custo de manter os dois e o de uma comparacao.
		if(crua)
			haptics = DualSenseHaptics(this, DualSenseHid(this)).also { h ->
				h.start(newSession) { runOnUiThread { ligarRumble() } }
			}
		else if(qualityPrefs.hapticRumble)
			trace("Raw haptics asked for, but Bluetooth permission is missing")

		// O espelho nasce antes do start da sessao: os primeiros pacotes de
		// estado do controle chegam logo na abertura, e perde-los seria
		// comecar com a barra na cor errada ate o jogo mudar de novo.
		anunciouDualSense = qualityPrefs.anunciarDualSense()
		if(DualSenseHid.disponivel(this))
			espelho = EspelhoDualSense(this, DualSenseHid(this),
					qualityPrefs.adaptiveTriggers, qualityPrefs.controllerLights).also {
				// Sem a trilha crua (motor e classico), a vibracao sai pelo
				// espelho: um escritor so do relatorio 0x31. Ver motoresPeloEspelho.
				// Mas nunca com a vibracao desligada: o anuncio de DualSense (pelos
				// gatilhos) faz o console mandar a trilha mesmo assim, e a rota
				// pelo espelho nao passava pelo bloqueio do Rumble (dev.208).
				it.motoresPeloEspelho = !crua && !Rumble.desligado && Preferences(this).rumbleEnabled
				if(it.motoresPeloEspelho)
					trace("Rumble goes through the controller mirror (one writer for the controller's output report)")
				it.start()
			}
		else
			trace("Controller mirror: Bluetooth permission missing; triggers and lights stay with the system")

		newSession.setSurface(surface)
		session = newSession

		// O frame loop comeca ja: ele roda vazio (sem camada de video) ate o
		// ConnectedEvent liberar a submissao.
		// Ligado a sessao inteira. A dev.199 alternou em fases de 30 s e o
		// envio da haptica ficou igual nas duas (~3,0 ms): nao e o Wi-Fi que
		// atrasa o Bluetooth, e desligar so arriscava a rede.
		wifiLock.acquire()
		bridge.start()

		// A tela nasce a frente de onde a cabeca esta apontando no inicio.
		bridge.recenter()

		// A saude comeca junto com o stream e para no onDestroy. A primeira
		// leitura vai depois de uma janela: no instante da abertura o aparelho
		// ainda nao esquentou nem trabalhou, e o numero de la nao representa
		// nada.
		handler.postDelayed(healthTick, HEALTH_PERIOD_MS)
		handler.postDelayed(vigiaSessaoXr, 1000L)

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
				//
				// Com o motivo na tela, e não com o app caindo: uma exceção
				// solta nesta thread não passa por nenhum fail().
				val err = try
				{
					io.github.gblandro.p5m.CaBundle.ensureNetwork(this)
					newSession.connectPsn(connectInfo.duid, connectInfo.ps5)
				}
				catch(e: Exception)
				{
					P5MApp.saveCrash(this, "psn", e)
					runOnUiThread {
						if(session === newSession)
							fail("The remote connection through PSN failed before reaching the console.\n\n" +
									"${e::class.java.simpleName}: ${e.message}")
					}
					return@Thread
				}
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
		microfone?.retomar()
		espelho?.retomar()
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
		microfone?.pausar()
		espelho?.pausar()
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
		if(!returnActivated)
		{
			returnActivated = true
			io.github.gblandro.p5m.StreamReturn.activate(this, intent)
		}
		super.finish()
	}

	override fun onDestroy()
	{
		resetGamepadGestures()
		super.onDestroy()
		trace("Lifecycle: onDestroy (isFinishing=$isFinishing)")
		encerrando = true
		val vigia = vigiarEncerramento()
		// Antes do dispose, e nao depois: o tick dos medidores lê o contador de
		// pacotes através da sessão nativa, e uma leitura depois do dispose seria
		// uso de memória liberada. Hoje as duas coisas correm na mesma thread e
		// não se intercalam, mas depender disso é depender de um detalhe que a
		// próxima mudança pode quebrar em silêncio.
		handler.removeCallbacks(meterTick)
		handler.removeCallbacks(healthTick)
		handler.removeCallbacks(vigiaSessaoXr)
		handler.removeCallbacks(esconderFaixa)
		if(redeInicioMs > 0L)
		{
			val segundos = ((SystemClock.uptimeMillis() - redeInicioMs) / 1000L).toInt()
			qualityPrefs.avaliarSessao(segundos, redeJanelas, redeJanelasRuins)?.let { trace(it) }
		}
		handler.removeCallbacks(pinQuitTick)
		handler.removeCallbacks(liberarCamada)
		// Cancelar E ESPERAR, antes de qualquer dispose.
		//
		// A furação de NAT corre numa thread e usa a sessão nativa por dentro
		// dela. Só cancelar não basta: o cancelamento pede para parar, e entre
		// o pedido e a parada de verdade existe uma janela em que liberar a
		// sessão é uso de memória liberada. Isso derrubou o processo com
		// "pthread_mutex_lock called on a destroyed mutex", dentro de
		// chiaki_holepunch_session_start.
		// A haptica para ANTES de tudo que possa liberar a sessao, e nao junto
		// com o rumble no fim.
		//
		// O laco dela le do ponteiro nativo cem vezes por segundo. Parar depois
		// do dispose -- que e onde ele estava, e onde a leitura de codigo nao
		// acusa nada -- deixa uma thread lendo memoria liberada por ate um
		// periodo inteiro. Este projeto ja perdeu duas sessoes para exatamente
		// esta forma de corrida, uma no decodificador e outra no holepunch, e as
		// duas apareceram como queda nativa sem linha nossa na pilha.
		//
		// O stop() faz join na thread de entrega, entao quando ele volta nao ha
		// mais ninguem lendo.
		haptics?.stop()
		haptics = null
		// Mesmo motivo: a thread do microfone entrega a sessao nativa.
		microfone?.stop()
		microfone = null
		io.github.gblandro.p5m.ProfundidadeNeural.stop()
		// Devolve os gatilhos ao neutro antes de soltar a ponte.
		espelho?.stop()
		espelho = null
		movimento?.let { lifecycle.removeObserver(it); it.desligar() }
		movimento = null

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
				medirPasso("session stop") { it.stop() }
				liberarSessao(it)
			}
		wifiLock.release()

		rumble?.stop()
		rumble = null
		session = null
		medirPasso("XR destroy") { xr?.destroy() }
		xr = null
		hudBitmap?.recycle()
		hudBitmap = null
		vigia.interrupt()
	}

	/**
	 * O sistema mata o processo quando o onDestroy passa de 10 s
	 * ("destroyTimeout"), e o diario ficava mudo sobre onde ele travou
	 * (dev.219, dev.224). Aos 3 s, esta thread escreve a pilha da thread
	 * principal: uma chamada nativa presa aparece nela pelo nome.
	 */
	private fun vigiarEncerramento(): Thread
	{
		val principal = Looper.getMainLooper().thread
		return Thread({
			try
			{
				Thread.sleep(3000)
				// As marcas do lado nativo dizem em que passo a liberacao esta.
				io.github.gblandro.p5m.Trace.captureNativeLines(this)
				val pilha = principal.stackTrace.take(12).joinToString(" <- ") {
					"${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
				trace("Shutdown stuck for 3 s in: $pilha")
			}
			catch(e: InterruptedException) { }
		}, "p5m-shutdown-watch").apply { isDaemon = true; start() }
	}

	/**
	 * Libera a sessão nativa fora da thread principal, esperando até 2 s.
	 *
	 * A liberação travou duas vezes (dev.224 e dev.225) dentro do sessionFree,
	 * e travada na thread principal ela segurava o onDestroy até o sistema
	 * matar o processo: tela preta por 10 s e o menu sem o DualSense. Se não
	 * terminar em 2 s, fica terminando sozinha, e a tela fecha. O portão da
	 * Surface já está fechado desde o onPause, então o XR pode ser encerrado
	 * mesmo com o decodificador ainda de pé. Custo do caso raro: a sessão
	 * presa fica na memória até o app fechar -- o mesmo que já se aceita
	 * quando a furação da PSN não termina.
	 */
	private fun liberarSessao(s: Session)
	{
		val inicio = SystemClock.uptimeMillis()
		val t = Thread({
			s.dispose()
			val ms = SystemClock.uptimeMillis() - inicio
			if(ms > LIBERAR_MS)
				Log.i(TAG, "Session release finished after $ms ms, in the background")
		}, "p5m-session-release").apply { start() }
		t.join(LIBERAR_MS)
		val ms = SystemClock.uptimeMillis() - inicio
		if(t.isAlive)
			trace("Shutdown: the session release is still running after $ms ms; " +
					"leaving it in the background so the screen can close")
		else if(ms > 300)
			trace("Shutdown: session release took $ms ms")
	}

	private inline fun medirPasso(nome: String, passo: () -> Unit)
	{
		val inicio = SystemClock.uptimeMillis()
		passo()
		val ms = SystemClock.uptimeMillis() - inicio
		if(ms > 300)
			trace("Shutdown: $nome took $ms ms")
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
		tecladoPin?.let { return teclaNoPin(it, event) }
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
	/**
	 * Aperta ou solta o botao do touchpad do lado do console.
	 *
	 * Um so lugar porque ele passou a ter tres entradas -- tecla de gamepad,
	 * clique de mouse e toque -- e o estado preso e o modo de falha classico
	 * deste botao: apertar por um caminho e soltar por outro deixaria o mapa
	 * aberto para sempre.
	 */
	private fun setTouchpadPressed(pressed: Boolean)
	{
		if(pressed == touchpadDown)
			return
		val streamInput = input ?: return
		touchpadDown = pressed
		val state = streamInput.touchControllerState
		state.buttons = if(pressed) state.buttons or ControllerState.BUTTON_TOUCHPAD
			else state.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
		session?.setControllerState(streamInput.controllerState)
	}

	/**
	 * Os dedos no touchpad, em coordenada do console. So chegam com o ponteiro
	 * capturado; sem captura o Android transforma o touchpad em mouse.
	 */
	private fun setTouchpadFingers(dedos: List<TouchpadPointer.Toque>)
	{
		val streamInput = input ?: return
		val state = streamInput.touchControllerState
		state.touches = Array(2) { i ->
			dedos.getOrNull(i)?.let { ControllerTouch(it.x.toUShort(), it.y.toUShort(), it.id.toByte()) }
				?: ControllerTouch()
		}
		session?.setControllerState(streamInput.controllerState)
	}

	/**
	 * Um clique completo, para quem chega por um caminho que so avisa uma vez.
	 *
	 * Oitenta milissegundos: o console precisa ver o botao apertado por pelo
	 * menos um pacote de estado, e o intervalo de envio e bem menor que isso.
	 */
	private fun clickTouchpad()
	{
		setTouchpadPressed(true)
		handler.postDelayed({ setTouchpadPressed(false) }, TOUCHPAD_CLICK_MS)
	}

	private fun handleTouchpadKey(event: KeyEvent): Boolean
	{
		if(event.keyCode !in TOUCHPAD_KEYCODES)
			return false
		val streamInput = input ?: return false

		when(event.action)
		{
			KeyEvent.ACTION_DOWN -> setTouchpadPressed(true)
			KeyEvent.ACTION_UP -> setTouchpadPressed(false)
		}
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
		var gamepads = 0
		for(id in ids)
		{
			val device = InputDevice.getDevice(id) ?: continue
			val isGamepad = device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
					device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
			if(!isGamepad)
			{
				// O touchpad do DualSense entra como dispositivo separado, e
				// nao se declara gamepad. Contando so gamepad, ele era
				// invisivel no diario -- e a ausencia parecia prova de que o
				// controle nao mandava nada, quando mandava por outra porta.
				if(device.sources and (InputDevice.SOURCE_MOUSE or
								InputDevice.SOURCE_TOUCHPAD or
								InputDevice.SOURCE_TOUCHSCREEN) != 0)
					trace("Pointer device '${device.name}' id=$id " +
							"sources=0x${device.sources.toString(16)}")
				continue
			}
			gamepads++
			// Os controles Touch do headset entram sem nome de HID, no formato
			// "Device 0x...". Um gamepad pareado se identifica.

			// hasKeys responde o que o driver realmente expoe. Se L3 e R3 vierem
			// false, o acorde do modo de ajuste e impossivel neste controle e o
			// problema nao esta no codigo que o trata.
			val keys = device.hasKeys(
				KeyEvent.KEYCODE_BUTTON_THUMBL,
				KeyEvent.KEYCODE_BUTTON_THUMBR,
				KeyEvent.KEYCODE_BUTTON_A,
				KeyEvent.KEYCODE_BUTTON_MODE
			)
			// Quais das teclas candidatas ao touchpad este driver expoe. Se
			// vierem todas false e o clique tambem nao aparecer como ponteiro,
			// o controle nao entrega esse botao e nao ha o que mapear.
			val extras = device.hasKeys(*TOUCHPAD_KEYCODES.toIntArray())
			val presentes = TOUCHPAD_KEYCODES.filterIndexed { i, _ -> extras[i] }
					.joinToString(",") { KeyEvent.keyCodeToString(it) }
			trace("Gamepad '${device.name}' id=$id sources=0x${device.sources.toString(16)} " +
					"L3=${keys[0]} R3=${keys[1]} Cross=${keys[2]} PS=${keys[3]} " +
					"eixos=${device.motionRanges.size} " +
					"touchpad keys=${if(presentes.isEmpty()) "none" else presentes}")
		}
		trace("Input devices: ${ids.size} in total, $gamepads gamepad(s)")

		// A selecao acompanha reconexoes no StreamInput dos dois modos.
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
		// Com o ponteiro capturado o touchpad do DualSense chega como dedos em
		// coordenada absoluta, e nao como mouse. O imersivo nao tem nada 2D
		// para o raio da mao apontar, entao capturar aqui nao tira nada. O
		// Android solta a captura sozinho quando o foco sai.
		if(hasFocus)
		{
			val v = window.decorView
			// Sem isto o Android junta os eventos do analogico e entrega um lote
			// por quadro de tela: ate um quadro inteiro de atraso antes de o
			// estado chegar ao console. O `Input timing` do diario (batched,
			// biggest batch) mostra se o lote sumiu.
			if(android.os.Build.VERSION.SDK_INT >= 31)
				v.requestUnbufferedDispatch(InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD)
			v.setOnCapturedPointerListener { _, e -> touchpadPointer.handle(e) }
			v.requestPointerCapture()
		}
	}

	override fun onPointerCaptureChanged(hasCapture: Boolean)
	{
		super.onPointerCaptureChanged(hasCapture)
		trace("Touchpad pointer capture: ${if(hasCapture) "on" else "off"}")
	}

	/**
	 * O clique do touchpad chega por aqui, e nao pelo caminho de gamepad.
	 *
	 * Tres portas porque o Android classifica o mesmo clique de tres jeitos
	 * conforme o driver: botao de mouse vira `dispatchGenericMotionEvent`, toque
	 * vira `dispatchTouchEvent`, e ha aparelho que manda `ACTION_BUTTON_PRESS`
	 * pelos dois. Cobrir as tres e barato; adivinhar qual e a certa custou uma
	 * versao inteira.
	 */
	override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(touchpadPointer.handle(event))
			return true
		return super.dispatchGenericMotionEvent(event)
	}

	override fun dispatchTouchEvent(event: MotionEvent): Boolean
	{
		if(touchpadPointer.handle(event))
			return true
		return super.dispatchTouchEvent(event)
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		registrarChegada(event)
		tecladoPin?.let {
			hatNoPin(it, event)
			return true
		}
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
	 * Alimenta o `InputArrival` com cada amostra dos analógicos do controle.
	 *
	 * Antes do desvio do modo de ajuste, que emudece os analógicos para o jogo
	 * mas não para a medida. As amostras em lote vêm como históricas, cada uma
	 * com o próprio horário; contar só o do evento esconderia justamente as
	 * rajadas que a medida procura. Nanossegundos só existem na API 34; abaixo
	 * dela o horário vem arredondado a ms, e a linha diz qual foi.
	 */
	private fun registrarChegada(event: MotionEvent)
	{
		if(event.actionMasked != MotionEvent.ACTION_MOVE ||
				event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
				!isPreferredGamepad(event.deviceId))
			return
		val nanos = android.os.Build.VERSION.SDK_INT >= 34
		inputArrival.event(event.historySize)
		for(i in 0 until event.historySize)
			inputArrival.sample(if(nanos) event.getHistoricalEventTimeNanos(i)
					else event.getHistoricalEventTime(i) * 1_000_000L)
		inputArrival.sample(if(nanos) event.eventTimeNanos else event.eventTime * 1_000_000L)
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
		return input?.acceptsGamepadAxes(deviceId) ?: true
	}

	/** Desligar o controle nao produz ACTION_UP. Nao deixar um acorde ou
	 * uma saida por Circle disparar sozinho depois da reconexao.
	 */
	private fun resetGamepadGestures()
	{
		thumbLDown = false
		thumbRDown = false
		r1Down = false
		r3Solo = false
		hatX = 0
		hatY = 0
		quitArmed = false
		handler.removeCallbacks(holdTick)
		handler.removeCallbacks(quitTick)
		handler.removeCallbacks(pinQuitTick)
		touchpadPointer.release()
		// O clique que veio por tecla (BUTTON_1..Z) nao passa pelo ponteiro.
		// Sem zerar aqui, o proximo aperto caia no "ja esta apertado" e sumia.
		touchpadDown = false
	}

	/**
	 * L3 + R3 pressionados juntos alternam o modo de ajuste. E um acorde que
	 * praticamente nao aparece em jogo, entao nao rouba input do console.
	 */
	private fun handleAdjustChord(event: KeyEvent): Boolean
	{
		val down = event.action == KeyEvent.ACTION_DOWN
		val isL3 = event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL
		val isR3 = event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR
		val isR1 = event.keyCode == KeyEvent.KEYCODE_BUTTON_R1
		if(!isL3 && !isR3 && !isR1)
			return false

		if(isL3) thumbLDown = down
		if(isR3) thumbRDown = down
		if(isR1) r1Down = down


		// R3 sozinho no painel: liga e desliga os gatilhos, no soltar. No
		// soltar porque so ali se sabe que nao era o comeco do acorde.
		if(isL3 && down)
			r3Solo = false
		if(isR3)
		{
			if(down)
				r3Solo = adjustMode && !thumbLDown
			else
			{
				if(r3Solo && adjustMode)
					alternarGatilhos()
				r3Solo = false
			}
		}

		val chord = qualityPrefs.tuningChord
		val sticks = thumbLDown && thumbRDown

		// O acorde de segurar so vale enquanto os dois seguem apertados. Soltar
		// qualquer um antes da hora e um clique de jogo, nao um pedido de painel.
		// A repeticao automatica do sistema (repeatCount > 0) nao e aperto novo:
		// com os botoes segurados ela chega a cada ~50 ms, e cada uma alternava
		// o painel de novo -- ele piscava enquanto o acorde estivesse apertado.
		val primeiro = event.repeatCount == 0
		if(chord == StreamQualityPrefs.CHORD_HOLD_L3_R3)
		{
			if(sticks && down && primeiro)
				handler.postDelayed(holdTick, StreamQualityPrefs.CHORD_HOLD_MS)
			else if(!sticks)
				handler.removeCallbacks(holdTick)
		}
		else
		{
			val complete = when(chord)
			{
				StreamQualityPrefs.CHORD_L3_R3_R1 -> sticks && r1Down
				else -> sticks
			}
			if(down && complete)
			{
				if(primeiro)
					toggleAdjustMode()
				return true
			}
		}

		// Fora do modo, cada botao do acorde continua sendo botao de jogo: R1
		// atira, L3 corre, R3 e melee. Segurar qualquer um deles seria pior que
		// nao ter atalho nenhum.
		if(!adjustMode)
			return false
		// Ligado, L3 e R3 nao chegam ao console. R1 segue adiante porque dentro
		// do painel ele e a altura da tela, tratada logo em seguida.
		return !isR1
	}

	/**
	 * Liga ou desliga os gatilhos adaptativos no meio do jogo.
	 *
	 * Vale na hora -- desligar devolve os dois gatilhos ao neutro, ligar
	 * reaplica o ultimo efeito que o console mandou, sem esperar o jogo mandar
	 * outro -- e fica gravado para as proximas sessoes, como o resto do painel.
	 */
	private fun alternarGatilhos()
	{
		val e = espelho
		if(e == null || !anunciouDualSense || StreamQualityPrefs.sessaoPs4)
		{
			trace("Adaptive triggers: not available in this session")
			renderHud()
			return
		}
		val ligar = !e.gatilhosLigados
		e.ligarGatilhos(ligar)
		qualityPrefs.adaptiveTriggers = ligar
		ultimoAjuste = KeyEvent.KEYCODE_BUTTON_THUMBR
		ultimoAjusteMs = SystemClock.uptimeMillis()
		renderHud()
	}

	/** O acorde de segurar, quando os setecentos milissegundos se completam. */
	private val holdTick = Runnable {
		if(thumbLDown && thumbRDown)
			toggleAdjustMode()
	}

	private fun toggleAdjustMode()
	{
		adjustMode = !adjustMode
		trace(if(adjustMode) "Tuning mode on" else "Tuning mode off")
		handler.removeCallbacks(holdTick)
		// O pressionar do acorde ja foi para o console antes de o ultimo botao
		// fechar a combinacao. Solta todos agora, senao o console segue achando
		// que estao apertados.
		releaseChordButtons()
	}

	/**
	 * Manda ao console o soltar de L3 e R3.
	 *
	 * O acorde so e reconhecido quando o segundo botao desce, e a essa altura o
	 * primeiro ja foi encaminhado como pressionado. Sem este desfazer, o estado
	 * do console fica com L3 (0x400) ou R3 (0x800) ligado indefinidamente.
	 */
	/**
	 * Circle dentro do painel: solta fecha o painel, segurar encerra a sessao.
	 *
	 * Encerrar precisava existir. Nao havia saida nenhuma no modo imersivo: a
	 * unica forma era o botao do sistema, que mata o processo pelo lado de fora
	 * -- e matar o processo nao manda Disconnect nenhum ao console, que segue
	 * achando que o Remote Play esta em uso. O `Remote is already in use` da
	 * proxima tentativa nascia ai.
	 *
	 * Segurar, e nao um botao proprio, porque o painel ja usa todos: dez botoes
	 * para dez ajustes. E porque encerrar por engano custa a sessao inteira, o
	 * que um toque acidental nao pode poder fazer.
	 */
	private fun handleQuitHold(event: KeyEvent): Boolean
	{
		if(event.action == KeyEvent.ACTION_DOWN)
		{
			// repeatCount > 0 e a repeticao automatica do sistema, e nao um novo
			// aperto: agendar de novo a cada uma adiaria o encerramento para
			// sempre.
			if(event.repeatCount == 0)
			{
				quitArmed = true
				handler.postDelayed(quitTick, QUIT_HOLD_MS)
			}
			return true
		}
		if(event.action == KeyEvent.ACTION_UP)
		{
			handler.removeCallbacks(quitTick)
			// O soltar segue para o console mesmo com o painel aberto: quem
			// apertou Circle fora do painel e soltou dentro dele deixaria o
			// botao presa la do outro lado. Mesma armadilha que prendeu o L3.
			input?.dispatchKeyEvent(event)
			if(quitArmed)
			{
				quitArmed = false
				adjustMode = false
			}
		}
		return true
	}

	private val quitTick = Runnable {
		quitArmed = false
		trace("Ending the session from the tuning panel")
		adjustMode = false
		finish()
	}

	private fun releaseChordButtons()
	{
		val streamInput = input ?: return
		for(keyCode in intArrayOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
				KeyEvent.KEYCODE_BUTTON_R1))
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
		if(event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
			return handleQuitHold(event)
		if(event.action == KeyEvent.ACTION_UP)
		{
			input?.dispatchKeyEvent(event)
			return true
		}
		if(event.action != KeyEvent.ACTION_DOWN)
			return true
		// Segurar repete so o que e passo de ajuste (direcional, R1/L1). Nos
		// botoes que alternam ou ciclam, a repeticao girava o valor sem parar.
		if(event.repeatCount > 0 && event.keyCode !in AJUSTES_QUE_REPETEM)
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
				// Cinco degraus: quatro de intensidade e o automatico do compositor.
				qualityPrefs.sharpness = StreamQualityPrefs.sharpnessNext(qualityPrefs.sharpness, 1)
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
				trace("Curvature: %.2f".format(java.util.Locale.US, screenPrefs.curvature))
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
					// Cinco degraus, do desligado ao maximo. Cada um e uma fracao
					// do orcamento que o nativo tira da distancia interpupilar.
					val passo = ((qualityPrefs.stereoStrength * 4f).toInt() + 1) % 5
					qualityPrefs.stereoStrength = passo / 4f
					xr?.setStereoTuning(qualityPrefs.stereoStrength,
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
			KeyEvent.KEYCODE_BUTTON_MODE ->
			{
				// PS: clique do touchpad, e fecha o painel.
				//
				// Existe como saida de emergencia. Se o clique do touchpad nao
				// chegar sozinho neste controle -- e ha driver em que ele nao
				// chega -- o mapa do jogo fica inalcancavel, e um comando que so
				// se abre em dois passos ainda e melhor que um que nao existe.
				adjustMode = false
				clickTouchpad()
			}
			else -> return true
		}
		applyScreenParams()
		ultimoAjuste = event.keyCode
		ultimoAjusteMs = SystemClock.uptimeMillis()
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

	private fun abrirTecladoPin(incorreto: Boolean)
	{
		val bridge = xr
		if(bridge == null)
		{
			// Sem camada imersiva nao ha onde desenhar; o caminho antigo.
			trace("No immersive layer to ask for the passcode on; leaving")
			finish()
			return
		}
		adjustMode = false
		val teclado = TecladoPin(incorreto)
		tecladoPin = teclado
		hatX = 0
		hatY = 0
		desenharTecladoPin(teclado)
		// Antes do video nao ha tela para ficar embaixo: o teclado vem para a
		// frente, um pouco abaixo da linha dos olhos.
		bridge.setHudBelowScreen(false)
		bridge.setHudVisible(true)
	}

	private fun desenharTecladoPin(teclado: TecladoPin)
	{
		val bitmap = hudBitmap ?: Bitmap.createBitmap(HUD_WIDTH, HUD_HEIGHT, Bitmap.Config.ARGB_8888)
			.also { hudBitmap = it }
		teclado.desenhar(bitmap)
		xr?.setHudBitmap(bitmap)
	}

	private fun executarNoPin(teclado: TecladoPin, acao: TecladoPin.Acao)
	{
		when(acao)
		{
			TecladoPin.Acao.Nada -> {}
			TecladoPin.Acao.Redesenhar -> desenharTecladoPin(teclado)
			is TecladoPin.Acao.Enviar ->
			{
				// Nunca o valor no diario: o diario sai do aparelho pelo botao de
				// relatar problema.
				trace("Sending the login passcode (${acao.pin.length} digits)")
				fecharTecladoPin()
				session?.setLoginPin(acao.pin)
			}
		}
	}

	private fun fecharTecladoPin()
	{
		handler.removeCallbacks(pinQuitTick)
		tecladoPin = null
		xr?.setHudVisible(false)
		xr?.setHudBelowScreen(true)
	}

	// Segurar Circle desiste, com o mesmo tempo do painel de ajustes. Um toque
	// so apaga: Circle e o "voltar" do PlayStation, e um toque a mais depois de
	// apagar tudo nao pode custar a sessao.
	private val pinQuitTick = Runnable {
		if(tecladoPin == null)
			return@Runnable
		trace("Passcode entry cancelled; leaving")
		fecharTecladoPin()
		finish()
	}

	private fun teclaNoPin(teclado: TecladoPin, event: KeyEvent): Boolean
	{
		// O soltar segue para o StreamInput, como no painel de ajustes: um
		// Cross apertado antes do teclado abrir nao pode ficar preso do lado do
		// console quando a sessao comecar.
		if(event.action == KeyEvent.ACTION_UP)
		{
			if(event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK)
				handler.removeCallbacks(pinQuitTick)
			input?.dispatchKeyEvent(event)
			return true
		}
		if(event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0)
			return true
		if(event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK)
			handler.postDelayed(pinQuitTick, QUIT_HOLD_MS)
		val acao = when(event.keyCode)
		{
			KeyEvent.KEYCODE_DPAD_UP -> teclado.mover(0, -1)
			KeyEvent.KEYCODE_DPAD_DOWN -> teclado.mover(0, 1)
			KeyEvent.KEYCODE_DPAD_LEFT -> teclado.mover(-1, 0)
			KeyEvent.KEYCODE_DPAD_RIGHT -> teclado.mover(1, 0)
			KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> teclado.apertar()
			KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> teclado.apagar()
			// Digito de teclado fisico, se alguem parear um.
			in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
			{
				teclado.digitar(event.keyCode - KeyEvent.KEYCODE_0)
			}
			else -> TecladoPin.Acao.Nada
		}
		executarNoPin(teclado, acao)
		return true
	}

	/**
	 * O D-pad do DualSense chega como eixo HAT; o analogico esquerdo tambem
	 * move, porque e o que a mao procura primeiro. So a transicao conta, e o
	 * analogico so vale do controle preferido: os Touch mandam eixo parados.
	 */
	private fun hatNoPin(teclado: TecladoPin, event: MotionEvent)
	{
		fun step(value: Float) = when
		{
			value > 0.5f -> 1
			value < -0.5f -> -1
			else -> 0
		}
		val analogico = isPreferredGamepad(event.deviceId)
		fun eixo(hat: Int, stick: Int): Int
		{
			val h = step(event.getAxisValue(hat))
			return if(h != 0 || !analogico) h else step(event.getAxisValue(stick))
		}
		val x = eixo(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_X)
		val y = eixo(MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_Y)
		if(x != hatX)
		{
			hatX = x
			if(x != 0)
				executarNoPin(teclado, teclado.mover(x, 0))
		}
		if(y != hatY)
		{
			hatY = y
			if(y != 0)
				executarNoPin(teclado, teclado.mover(0, y))
		}
	}

	/**
	 * Mostra, no comeco do stream, como se abre o painel de ajustes.
	 *
	 * Sem isso o acorde e invisivel: nada na tela imersiva sugere que L3+R3
	 * existe, e quem instala o app nao tem onde descobrir. Um beta inteiro pode
	 * passar sem que a pessoa encontre o tamanho da tela.
	 *
	 * Reaproveita a camada do painel em vez de criar outra: e a mesma textura,
	 * so com um desenho diferente. Nao mexe em `adjustMode`, entao o console
	 * continua recebendo tudo enquanto o lembrete esta na tela.
	 */
	/**
	 * O enlace Bluetooth do controle preso em economia (sniff).
	 *
	 * Nesse modo o controle so fala a cada ~11 ms: sao umas 89 vagas por
	 * segundo para os 94 relatorios da haptica, e o que nao cabe se acumula --
	 * a vibracao atrasa cada vez mais. O dono achou a saida: desligar e ligar o
	 * controle, que reconecta fora da economia (a dev.205 voltou com ~200
	 * leituras de giroscopio por segundo em vez de 85). O app nao tem como
	 * tirar o enlace da economia sozinho (ver AGENTS.md, dev.196), entao avisa.
	 */
	private fun avisarSeEnlaceLento(giroEm10s: Int)
	{
		if(giroEm10s >= LIMIAR_GIRO_ENLACE_LENTO)
		{
			enlaceLento = false
			if(reinicioPendente)
			{
				reinicioPendente = false
				trace("Controller restart: back at full speed ($giroEm10s motion samples in 10 s)")
				mostrarFaixa("CONTROLLER AT FULL SPEED", "Reconnected fresh; vibration is back in step.", "")
			}
			return
		}
		// Poucas amostras e controle desligado ou parado de chegar, nao economia.
		if(giroEm10s < 200)
			return
		// Uma linha na entrada do estado, e nao uma a cada dez segundos.
		if(!enlaceLento)
			trace("Controller link: reduced input rate ($giroEm10s motion samples in 10 s, " +
					"about 2000 when healthy)")
		enlaceLento = true
		reinicioPendente = false
		if(avisouEnlaceLento || adjustMode)
			return
		avisouEnlaceLento = true
		// Com o jogo aberto, e nao antes: na dev.216 religar no lancador nao
		// curou, e religar no meio da sessao sim (2521 amostras logo depois).
		mostrarFaixa("CONTROLLER RUNNING SLOW",
				"Vibration may lag. Fix it now, with the game running:",
				"hold the controller's PS button for 10 s, then press it again.")
	}

	/** Controle sumiu ou voltou: a faixa na tela, e a medida do enlace de novo. */
	private fun controleMudou()
	{
		val presente = input?.preferredGamepadId != null
		if(!presente && !controleAusente)
		{
			controleAusente = true
			trace("Controller gone")
			// O console nao tem como saber: o Remote Play nao leva a queda do
			// controle (dev.219, o aviso pelo protocolo foi aceito e ignorado).
			mostrarFaixa("CONTROLLER DISCONNECTED", "The game keeps running; the console can't tell.",
					"Press the PS button on the controller to reconnect.")
		}
		else if(presente && controleAusente)
		{
			controleAusente = false
			trace("Controller back")
			// Voltou de um enlace lento: a proxima janela diz se curou.
			reinicioPendente = enlaceLento
			avisouEnlaceLento = false
			enlaceLento = false
			bateriaAvisada = 101
			mostrarFaixa("CONTROLLER BACK", "Connected again.",
					if(reinicioPendente) "Checking its speed in the next few seconds." else "")
		}
	}

	/** Bateria do controle, pelo Android (API 31): (porcento ou null, carregando). */
	private fun bateriaDoControle(): Pair<Int?, Boolean>?
	{
		if(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S)
			return null
		val id = input?.preferredGamepadId ?: return null
		val estado = InputDevice.getDevice(id)?.batteryState ?: return null
		if(!estado.isPresent)
			return null
		val c = estado.capacity
		// A primeira leitura da dev.216 veio 0% com o estado ainda desconhecido,
		// e 100% carregando dez segundos depois: sem estado, o numero nao vale.
		val pct = if(c.isNaN() || c < 0f || estado.status == android.hardware.BatteryState.STATUS_UNKNOWN) null
			else (c * 100f + 0.5f).toInt().coerceIn(0, 100)
		val carregando = estado.status == android.hardware.BatteryState.STATUS_CHARGING ||
				estado.status == android.hardware.BatteryState.STATUS_FULL
		return pct to carregando
	}

	/** A cada dez segundos: diario quando muda, e aviso aos 15% e aos 5%. */
	private fun verificarBateria()
	{
		val (pct, carregando) = bateriaDoControle() ?: return
		if(pct == null)
			return
		if(bateriaNoDiario < 0 || kotlin.math.abs(pct - bateriaNoDiario) >= 5)
		{
			bateriaNoDiario = pct
			trace("Controller battery: $pct%" + (if(carregando) ", charging" else ""))
		}
		if(carregando)
		{
			bateriaAvisada = 101
			return
		}
		val degrau = when { pct <= 5 -> 5; pct <= 15 -> 15; else -> 101 }
		if(degrau < bateriaAvisada && !adjustMode)
		{
			bateriaAvisada = degrau
			mostrarFaixa("CONTROLLER BATTERY LOW", "$pct% left.",
					"A USB-C cable keeps it going while you play.")
		}
	}

	/** O medidor do controle no painel: enlace lento primeiro, depois bateria. */
	private fun medidorDoControle(): PainelDeAjuste.Medidor?
	{
		if(enlaceLento)
			return PainelDeAjuste.Medidor("Controller", "Slow", "hold PS 10 s, turn on again",
					PainelDeAjuste.Estado.RUIM)
		val (pct, carregando) = bateriaDoControle() ?: return null
		val valor = pct?.let { "$it%" } ?: if(carregando) "Charging" else "On"
		val detalhe = when
		{
			carregando -> "battery · charging"
			pct != null && pct <= 15 -> "battery low"
			else -> "battery"
		}
		val estado = when
		{
			carregando || pct == null -> PainelDeAjuste.Estado.BOM
			pct <= 10 -> PainelDeAjuste.Estado.RUIM
			pct <= 25 -> PainelDeAjuste.Estado.ATENCAO
			else -> PainelDeAjuste.Estado.BOM
		}
		return PainelDeAjuste.Medidor("Controller", valor, detalhe, estado)
	}

	/** Uma faixa abaixo da tela, por alguns segundos, com o jogo aparecendo atras. */
	private fun mostrarFaixa(titulo: String, linha1: String, linha2: String)
	{
		// Com o teclado do PIN aberto o painel e dele: a faixa desenhava por
		// cima e, 12 s depois, escondia o painel com o teclado ainda tomando as
		// teclas -- PIN digitado as cegas.
		if(tecladoPin != null)
			return
		val bitmap = hudBitmap ?: Bitmap.createBitmap(HUD_WIDTH, HUD_HEIGHT, Bitmap.Config.ARGB_8888)
			.also { hudBitmap = it }
		PainelDeAjuste.faixa(bitmap, titulo, Color.rgb(255, 214, 120), linha1, linha2)
		xr?.setHudBitmap(bitmap)
		xr?.setHudVisible(true)
		handler.removeCallbacks(esconderFaixa)
		handler.postDelayed(esconderFaixa, AVISO_ENLACE_MS)
	}

	private fun showStartHint()
	{
		if(adjustMode || tecladoPin != null)
			return

		val bitmap = hudBitmap ?: Bitmap.createBitmap(HUD_WIDTH, HUD_HEIGHT, Bitmap.Config.ARGB_8888)
			.also { hudBitmap = it }
		// So uma faixa, e nao o painel inteiro: o resto da textura fica
		// transparente e o jogo aparece atras dela.
		PainelDeAjuste.faixa(bitmap, StreamQualityPrefs.CHORD_NAMES[qualityPrefs.tuningChord].uppercase(),
				Color.WHITE, "opens the settings panel",
				"Move, resize and sharpen the screen, and click the touchpad. Same chord closes it.",
				pilulaTitulo = true)

		xr?.setHudBitmap(bitmap)
		xr?.setHudVisible(true)
		trace("Showing the L3+R3 hint")

		// Se o painel de verdade abrir enquanto o lembrete esta na tela, quem
		// manda e ele: esconder aqui apagaria o painel que a pessoa acabou de
		// pedir (o esconderFaixa confere).
		handler.removeCallbacks(esconderFaixa)
		handler.postDelayed(esconderFaixa, HINT_MS)
	}

	private fun renderHud()
	{
		val bitmap = hudBitmap ?: Bitmap.createBitmap(HUD_WIDTH, HUD_HEIGHT, Bitmap.Config.ARGB_8888)
			.also { hudBitmap = it }

		// O realce dura um instante: o bastante para confirmar que o botao
		// pegou. O medidor redesenha a cada segundo, e e ele quem apaga.
		val realce = ultimoAjuste.takeIf { SystemClock.uptimeMillis() - ultimoAjusteMs < REALCE_MS }
		fun r(vararg teclas: Int) = realce != null && realce in teclas

		val graus = Math.toDegrees(screenPrefs.centralAngle.toDouble()).toInt()
		val plana = screenPrefs.layerShape == ScreenPrefs.SHAPE_QUAD
		val tela = listOf(
			PainelDeAjuste.Linha(PainelDeAjuste.Botao.DirecionalVertical, "Size", "$graus°",
					nitidezVerdict()?.let { (texto, estado) -> PainelDeAjuste.Extra.Etiqueta(texto, estado) }
						?: PainelDeAjuste.Extra.Nada,
					r(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)),
			PainelDeAjuste.Linha(PainelDeAjuste.Botao.DirecionalHorizontal, "Distance",
					"%.1f m".format(screenPrefs.radius),
					realce = r(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)),
			PainelDeAjuste.Linha(PainelDeAjuste.Botao.Nome("L1  R1"), "Height",
					"%+.2f m".format(screenPrefs.heightOffset),
					realce = r(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1)),
			PainelDeAjuste.Linha(PainelDeAjuste.Botao.Nome("L2"), "Shape", if(plana) "Flat" else "Curved",
					realce = r(KeyEvent.KEYCODE_BUTTON_L2)),
			PainelDeAjuste.Linha(PainelDeAjuste.Botao.Nome("R2"), "Curvature",
					curvatureName().replaceFirstChar { it.uppercase() },
					if(plana) PainelDeAjuste.Extra.Nada else PainelDeAjuste.Extra.Degraus(4, curvatureStep()),
					r(KeyEvent.KEYCODE_BUTTON_R2)),
			PainelDeAjuste.Linha(PainelDeAjuste.Botao.Cruz, "Recenter", "In front of you",
					realce = r(KeyEvent.KEYCODE_BUTTON_A))
		)

		val sala = when
		{
			xr?.isPassthroughSupported != true -> "Not available"
			!screenPrefs.passthroughEnabled -> "Hidden"
			else -> ScreenPrefs.CINEMA_NAMES[screenPrefs.cinema].replaceFirstChar { it.uppercase() }
		}
		val imagem = listOf(
			PainelDeAjuste.Linha(PainelDeAjuste.Botao.Quadrado, "Sharpness",
					StreamQualityPrefs.SHARPNESS_NAMES[qualityPrefs.sharpness].replaceFirstChar { it.uppercase() },
					PainelDeAjuste.Extra.Degraus(StreamQualityPrefs.SHARPNESS_STEPS.size,
							StreamQualityPrefs.sharpnessStep(qualityPrefs.sharpness)),
					r(KeyEvent.KEYCODE_BUTTON_X)),
			PainelDeAjuste.Linha(PainelDeAjuste.Botao.Nome("Share"), "Brightness",
					StreamQualityPrefs.BRIGHTNESS_NAMES[qualityPrefs.brightness].replaceFirstChar { it.uppercase() },
					PainelDeAjuste.Extra.Degraus(StreamQualityPrefs.BRIGHTNESS_NAMES.size, qualityPrefs.brightness),
					r(KeyEvent.KEYCODE_BUTTON_SELECT)),
			PainelDeAjuste.Linha(PainelDeAjuste.Botao.Triangulo, "Room", sala,
					if(xr?.isPassthroughSupported == true)
						PainelDeAjuste.Extra.Degraus(ScreenPrefs.CINEMA_NAMES.size + 1,
								if(screenPrefs.passthroughEnabled) screenPrefs.cinema + 1 else 0)
					else PainelDeAjuste.Extra.Nada,
					r(KeyEvent.KEYCODE_BUTTON_Y))
		)

		val gatilhos = when
		{
			StreamQualityPrefs.sessaoPs4 -> PainelDeAjuste.Linha(PainelDeAjuste.Botao.Nome("R3"), "Adaptive triggers",
					"PS5 only", realce = r(KeyEvent.KEYCODE_BUTTON_THUMBR))
			espelho == null -> PainelDeAjuste.Linha(PainelDeAjuste.Botao.Nome("R3"), "Adaptive triggers",
					"Need Bluetooth permission", realce = r(KeyEvent.KEYCODE_BUTTON_THUMBR))
			!anunciouDualSense -> PainelDeAjuste.Linha(PainelDeAjuste.Botao.Nome("R3"), "Adaptive triggers",
					"Not with classic rumble", realce = r(KeyEvent.KEYCODE_BUTTON_THUMBR))
			else ->
			{
				val ligados = espelho?.gatilhosLigados == true
				PainelDeAjuste.Linha(PainelDeAjuste.Botao.Nome("R3"), "Adaptive triggers",
						if(ligados) "On" else "Off", PainelDeAjuste.Extra.Chave(ligados),
						r(KeyEvent.KEYCODE_BUTTON_THUMBR))
			}
		}
		val outros = listOf(
			if(qualityPrefs.syntheticStereo)
				PainelDeAjuste.Linha(PainelDeAjuste.Botao.Nome("Options"), "3D strength",
						"${(qualityPrefs.stereoStrength * 100).toInt()}%",
						PainelDeAjuste.Extra.Degraus(5, (qualityPrefs.stereoStrength * 4f + 0.5f).toInt()),
						r(KeyEvent.KEYCODE_BUTTON_START))
			else
				PainelDeAjuste.Linha(PainelDeAjuste.Botao.Nome("Options"), "3D strength",
						"3D off in the launcher"),
			gatilhos,
			PainelDeAjuste.Linha(PainelDeAjuste.Botao.Nome("PS"), "Touchpad click", "Map · closes this")
		)

		PainelDeAjuste.desenhar(bitmap, StreamQualityPrefs.CHORD_NAMES[qualityPrefs.tuningChord].uppercase(),
				tela, imagem, outros, medidores())
		xr?.setHudBitmap(bitmap)
	}

	/** Degrau da curvatura, na mesma escada do R2. */
	private fun curvatureStep() = when
	{
		screenPrefs.curvature < 0.3f -> 0
		screenPrefs.curvature < 0.5f -> 1
		screenPrefs.curvature < 0.8f -> 2
		else -> 3
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
	private fun nitidezVerdict(): Pair<String, PainelDeAjuste.Estado>?
	{
		val rec = xr?.recommendedResolution() ?: return null
		val (recWidth, _) = rec
		val source = larguraFonte
		val ratio = recWidth.toFloat() / source
		return when
		{
			ratio < 0.92f -> "can grow" to PainelDeAjuste.Estado.ATENCAO
			ratio > 1.08f -> "too large" to PainelDeAjuste.Estado.ATENCAO
			else -> "just right" to PainelDeAjuste.Estado.BOM
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
	 * Cada medidor só aparece se o valor existir de verdade. Um contador que
	 * este runtime não oferece não é zero, e mostrar zero seria pior do que não
	 * mostrar nada: pareceria medição, e seria invenção.
	 */
	private fun medidores(): List<PainelDeAjuste.Medidor>
	{
		val lista = ArrayList<PainelDeAjuste.Medidor>(5)
		medidorDoControle()?.let { lista += it }

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
			lista += if(total == 0L)
				PainelDeAjuste.Medidor("Network", "No traffic", "this second", PainelDeAjuste.Estado.ATENCAO)
			else
			{
				val perda = 100.0 * dLost / total
				PainelDeAjuste.Medidor("Network", "%.2f%% loss".format(perda), "$total packets/s", when
				{
					perda < 0.5 -> PainelDeAjuste.Estado.BOM
					perda < 2.0 -> PainelDeAjuste.Estado.ATENCAO
					else -> PainelDeAjuste.Estado.RUIM
				})
			}
		}

		val perf = xr?.readPerformance()
		if(perf != null && perf.size >= 15)
		{
			if(perf[11] > 0f)
			{
				// Movimento da cabeça até o fóton, medido pelo compositor. É a
				// metade da latência que é nossa: o resto — rede, codificação,
				// decodificação — mora do lado do console e do Wi-Fi.
				lista += PainelDeAjuste.Medidor("Latency", "%.0f ms".format(perf[12]), "head to photon",
						if(perf[12] < 40f) PainelDeAjuste.Estado.BOM else PainelDeAjuste.Estado.ATENCAO)
			}
			if(perf[0] > 0f)
			{
				// Também em diferença: o compositor conta desde que a sessão
				// abriu, e um descarte de dez minutos atrás não é notícia.
				val dropped = perf[1].toLong()
				val delta = (dropped - lastDropped).coerceAtLeast(0L)
				lastDropped = dropped
				// A carga vai no detalhe: sozinha ela nao pede acao, e so
				// explica um descarte quando ele aparece.
				val carga = StringBuilder()
				if(perf[2] > 0f)
					carga.append("gpu %.0f%%".format(perf[3]))
				if(perf[13] > 0f)
				{
					if(carga.isNotEmpty()) carga.append(" · ")
					carga.append("cpu %.0f%%".format(perf[14]))
				}
				lista += PainelDeAjuste.Medidor("Frames",
						if(delta == 0L) "No drops" else "$delta dropped",
						carga.toString().ifEmpty { "compositor" },
						if(delta == 0L) PainelDeAjuste.Estado.BOM else PainelDeAjuste.Estado.ATENCAO)
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
				lista += PainelDeAjuste.Medidor("Thermal", "%.0f%%".format(perf[9] * 100f), "headroom · $trend",
						when
						{
							perf[9] >= 0.4f -> PainelDeAjuste.Estado.BOM
							perf[9] >= 0.15f -> PainelDeAjuste.Estado.ATENCAO
							else -> PainelDeAjuste.Estado.RUIM
						})
			}
		}
		return lista
	}
}
