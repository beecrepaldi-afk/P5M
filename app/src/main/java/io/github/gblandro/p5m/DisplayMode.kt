// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Como o stream é exibido: janela do sistema ou camada imersiva.
 *
 * Os dois modos são caminhos de vídeo diferentes, não uma opção cosmética.
 *
 * Em **janela**, quem compõe é o Horizon OS. O app vira um painel comum, que o
 * sistema posiciona, redimensiona e mostra ao lado de navegador, Spotify e o que
 * mais estiver aberto. Em troca, tudo que depende de compor a própria camada
 * deixa de existir: a tela curva, os bits de nitidez do compositor, a fixação em
 * Rec.709 e o casamento da taxa de atualização com os 60 fps da fonte. O
 * passthrough continua, mas é o do sistema, não o nosso.
 *
 * A nitidez é a exceção, e por um caminho diferente: quando pedida, a
 * [WindowVideo] põe um shader nosso entre o decodificador e o painel. Não são os
 * bits do compositor -- esses continuam fora de alcance --, é uma passada de GPU
 * que fazemos por conta, e por isso ela só existe quando alguém a pede.
 *
 * O áudio segue o caminho oposto: na janela quem espacializa é o sistema, e para
 * isso o som tem de sair pelo mixer dele. Ver [AudioRoute].
 *
 * Em **imersivo**, o app entrega a imagem direto ao compositor e controla a
 * camada inteira — é onde aquelas quatro coisas vivem —, mas toma a tela por
 * completo: não há multitarefa possível enquanto ele está aberto.
 *
 * Não dá para ter os dois ao mesmo tempo, e nenhum é "o certo": depende de estar
 * jogando concentrado ou acompanhando outra coisa em paralelo.
 */
object DisplayMode
{
	const val WINDOW = 0
	const val IMMERSIVE = 1

	private const val PREFS = "p5m_display"
	private const val KEY_MODE = "display_mode"
	private const val TAG = "P5MVR"

	private fun prefs(context: Context) =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	fun current(context: Context): Int =
		prefs(context).getInt(KEY_MODE, WINDOW).coerceIn(0, 1)

	fun set(context: Context, mode: Int)
	{
		prefs(context).edit().putInt(KEY_MODE, mode.coerceIn(0, 1)).apply()
	}

	fun toggle(context: Context): Int
	{
		val next = if(current(context) == IMMERSIVE) WINDOW else IMMERSIVE
		set(context, next)
		return next
	}

	fun label(mode: Int) = if(mode == IMMERSIVE) "Immersive" else "Window"

	/**
	 * Abre o stream na activity do modo escolhido.
	 *
	 * Fica aqui, e não no patch do submódulo, de propósito: assim o patch da
	 * MainActivity continua sendo uma linha, e a decisão mora em código nosso,
	 * onde dá para mudar sem mexer no upstream.
	 */
	private var streamsAlive = 0

	/**
	 * Conta quantas telas de stream estao vivas.
	 *
	 * Feito por fora, no ciclo de vida do processo, porque uma das duas telas
	 * e do chiaki-ng: contar aqui vale para as duas sem tocar no submodulo, e
	 * nao ha caminho em que uma delas esqueca de avisar.
	 */
	fun watchStreamActivities(app: android.app.Application)
	{
		app.registerActivityLifecycleCallbacks(object:
				android.app.Application.ActivityLifecycleCallbacks
		{
			private fun isStream(a: android.app.Activity) =
				a is com.metallic.chiaki.stream.StreamActivity ||
						a is com.metallic.chiaki.stream.VrStreamActivity

			override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?)
			{
				if(isStream(a)) streamsAlive++
			}
			override fun onActivityDestroyed(a: android.app.Activity)
			{
				if(isStream(a)) streamsAlive = (streamsAlive - 1).coerceAtLeast(0)
			}
			override fun onActivityStarted(a: android.app.Activity) {}
			override fun onActivityResumed(a: android.app.Activity) {}
			override fun onActivityPaused(a: android.app.Activity) {}
			override fun onActivityStopped(a: android.app.Activity) {}
			override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
		})
	}

	fun startStream(context: Context, connectInfo: com.metallic.chiaki.lib.ConnectInfo)
	{
		// Um stream de cada vez.
		//
		// O diario de 03/09 mostrou duas sessoes abrindo com cinco segundos de
		// diferenca, sem a primeira ter sido fechada. O console respondeu
		// "Remote is already in use" -- e o dono da sessao que ele recusava era
		// o proprio app. Logo depois a Surface da primeira morria embaixo da
		// segunda, e a tela ficava preta ate matar o processo. Dois toques na
		// lista de consoles bastam para chegar ai.
		if(streamsAlive > 0)
		{
			Log.i(TAG, "A stream is already open; ignoring this second request")
			return
		}

		val mode = current(context)
		val target = if(mode == IMMERSIVE)
			com.metallic.chiaki.stream.VrStreamActivity::class.java
		else
			com.metallic.chiaki.stream.StreamActivity::class.java

		// O perfil de video passa a ser o nosso nos dois modos. Antes so a
		// activity imersiva o usava, e a de janela herdava o do chiaki -- entao
		// bitrate e profundidade de cor mudavam sozinhos conforme o modo, sem
		// nada na tela explicando por que.
		// Os controles virtuais do chiaki-ng vem ligados por padrao, o que faz
		// sentido num celular: sem eles nao se joga. Aqui ha controle fisico, e
		// eles so ocupam a imagem -- botoes desenhados por cima do jogo, para
		// um dedo que nunca vai encostar na tela.
		//
		// Desligados a cada abertura, e nao uma vez so: o switch continua no
		// overlay da propria StreamActivity, entao quem quiser liga durante a
		// sessao, e a proxima volta ao padrao sem deixar estado escondido.
		val chiakiPrefs = com.metallic.chiaki.common.Preferences(context)
		chiakiPrefs.onScreenControlsEnabled = false
		chiakiPrefs.touchpadOnlyEnabled = false

		// Antes de abrir o stream, e nao depois: quem lê isto é a saída de áudio
		// do chiaki, no momento em que monta o stream do Oboe. Ver AudioRoute.
		AudioRoute.setPrefersSystemMixer(mode == WINDOW)

		val quality = StreamQualityPrefs(context)
		val profile = quality.videoProfile()

		// Marca a sessão no diário antes de qualquer linha dela. É por este
		// marcador que o aparo corta: sessões inteiras saem, e as que ficam
		// ficam com a abertura -- que é onde moram as respostas.
		Trace.beginSession(context, "${label(mode)} " +
				java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US)
						.format(java.util.Date()))

		Log.i(TAG, "Opening the stream in ${label(mode)} mode, profile " +
				"${profile.width}x${profile.height}@${profile.maxFPS} " +
				"${profile.codec} ${profile.bitrate}kbps, rumble " +
				(if(quality.hapticRumble) "haptic (DualSense)" else "classic (DualShock 4)"))

		// Aqui, e não em cada tela que abre um stream: as duas passam por este
		// ponto -- a lista local do chiaki e a conexão remota pela PSN -- e uma
		// preferência que valesse só numa delas seria pior que não existir.
		context.startActivity(Intent(context, target).apply {
			putExtra(com.metallic.chiaki.stream.StreamActivity.EXTRA_CONNECT_INFO,
					connectInfo.copy(videoProfile = profile, dualsense = quality.hapticRumble))
		})
	}
}
