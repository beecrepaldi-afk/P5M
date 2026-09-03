// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.content.SharedPreferences
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectVideoProfile

/**
 * Perfil de vídeo e filtro de camada.
 *
 * Existe porque a UI 2D do chiaki-ng é a do app Android de 2019: ela expõe
 * presets conservadores e nem oferece HEVC Main10. A libchiaki por baixo
 * suporta bem mais que isso. Aqui montamos o perfil direto, ignorando os
 * presets, e a activity VR sobrescreve o que veio do painel.
 */
class StreamQualityPrefs(context: Context)
{
	private val prefs: SharedPreferences =
		context.getSharedPreferences("p5m_vr_quality", Context.MODE_PRIVATE)

	/**
	 * Teto de bitrate em kbps.
	 *
	 * 1080p60 é o limite do protocolo de Remote Play — não existe 4K aqui, então
	 * o único jeito de ganhar imagem é gastar bits. O padrão do chiaki é 15000;
	 * num Quest 3 em Wi-Fi 6E com o console no cabo, 25000 passa sem sufoco e a
	 * diferença aparece em cena com muito movimento.
	 *
	 * Só vale mexer se a rede aguentar: bitrate alto demais em link marginal
	 * troca artefato de compressão por perda de pacote, que é muito pior.
	 */
	var bitrateKbps: Int
		get() = prefs.getInt(KEY_BITRATE, DEFAULT_BITRATE).coerceIn(2000, 40000)
		set(value) { prefs.edit().putInt(KEY_BITRATE, value.coerceIn(2000, 40000)).apply() }

	/**
	 * HEVC Main10: 10 bits por canal em vez de 8.
	 *
	 * No Remote Play, 10 bits não existem separados do HDR — o único jeito de
	 * pedir Main10 ao PS5 é o perfil HDR, que traz curva PQ e gamut BT.2020
	 * junto. O painel do Quest 3 é LCD com pico de 100 nits, o mesmo nível de
	 * referência do SDR, então não há brilho de HDR a ganhar aqui e entregar PQ
	 * cru deixaria a imagem lavada.
	 *
	 * O que se ganha é a profundidade: 1024 níveis por canal em vez de 256, que
	 * é o que faz diferença em céu, névoa e penumbra — onde 8 bits mostram
	 * faixas. O mapeamento de tons acontece dentro do decodificador, em
	 * hardware, pela chave `color-transfer-request` do patch 0001.
	 *
	 * Medido no Quest 3: o decodificador entrega P010 (10 bits, confirmado) mas
	 * devolve `color-transfer=6`, que é PQ — ou seja, ignora o pedido de
	 * mapeamento de tons. O resultado é gradiente limpo com brancos altos, que é
	 * a curva PQ sendo lida como se fosse SDR.
	 *
	 * Fica desligado por padrão por isso: 10 bits sem mapeamento troca um
	 * defeito visível (faixas) por outro (brancos estourados), e o segundo
	 * aparece em toda cena clara, não só em gradiente. Corrigir de verdade exige
	 * fazer o mapeamento por conta própria, com shader — e aí o vídeo deixa de
	 * ir direto para o compositor.
	 */
	var tenBit: Boolean
		get() = prefs.getBoolean(KEY_HDR, false)
		set(value) { prefs.edit().putBoolean(KEY_HDR, value).apply() }

	/**
	 * Como a textura submetida carrega os dois olhos: 0 mono, 1 lado a lado,
	 * 2 uma sobre a outra.
	 *
	 * **Sem botão no lançador, e é de propósito.** Isto nasceu para conteúdo
	 * que já vem estéreo, e o Remote Play da Sony recusa abrir app de mídia --
	 * YouTube, Netflix e afins não sobem no stream, só jogo. Não existe, no
	 * PS5 e por este caminho, imagem que já venha com dois olhos: um botão
	 * aqui seria uma opção que não faz nada em nenhum conteúdo que o usuário
	 * consiga pôr na tela.
	 *
	 * O mecanismo fica porque é exatamente o que o olho sintetizado vai usar:
	 * o sintetizador escreve os dois olhos lado a lado na mesma textura, e a
	 * submissão não precisa saber de onde eles vieram. Quando aquele modo
	 * existir, ele liga esta chave por dentro.
	 */
	var stereoMode: Int
		get() = prefs.getInt(KEY_STEREO, 0).coerceIn(0, 2)
		set(value) { prefs.edit().putInt(KEY_STEREO, value.coerceIn(0, 2)).apply() }

	/**
	 * Liga o olho sintetizado: o app inventa a profundidade e produz o segundo
	 * olho a partir dela.
	 *
	 * É emulação, e o nome disso na tela precisa dizer isso. O console manda
	 * imagem plana; a profundidade é um palpite feito das pistas que uma imagem
	 * 2D carrega, e vai errar em cena que não obedeça a elas. Existe porque um
	 * palpite razoável dá volume, e volume é o que se sente -- não a medida.
	 *
	 * Exige o caminho com shader, porque só ali há uma passada de GPU nossa
	 * onde a deformação caiba. Ligar isto liga aquele.
	 */
	var syntheticStereo: Boolean
		get() = prefs.getBoolean(KEY_SYNTH_3D, false)
		set(value) { prefs.edit().putBoolean(KEY_SYNTH_3D, value).apply() }

	/**
	 * Força do efeito, de 0 a 1, mapeada para a disparidade pedida.
	 *
	 * O que vale é o menor entre o pedido e o teto que a distância
	 * interpupilar impõe -- acima dele os olhos teriam de divergir. Por isso
	 * "força 100%" não é uma promessa de quanto vai saltar: é o pedido máximo
	 * dentro do que é fisicamente possível.
	 */
	var stereoStrength: Float
		get() = prefs.getFloat(KEY_3D_STRENGTH, 0.5f).coerceIn(0f, 1f)
		set(value) { prefs.edit().putFloat(KEY_3D_STRENGTH, value.coerceIn(0f, 1f)).apply() }

	/** Que profundidade fica no plano da tela, de 0 (tudo salta) a 1 (tudo afunda). */
	var stereoConvergence: Float
		get() = prefs.getFloat(KEY_3D_CONV, 0.35f).coerceIn(0f, 1f)
		set(value) { prefs.edit().putFloat(KEY_3D_CONV, value.coerceIn(0f, 1f)).apply() }

	/** Disparidade pedida ao shader, em fração da largura da imagem. */
	fun stereoDisparity(): Float = MAX_DISPARITY * stereoStrength

	/**
	 * Converte o vídeo na GPU em vez de entregá-lo direto ao compositor.
	 *
	 * É a única forma de ter 10 bits com cor correta neste hardware: o
	 * decodificador entrega PQ e ignora o pedido de mapeamento de tons, e o
	 * compositor não faz esse trabalho. O shader faz — PQ para SDR, BT.2020
	 * para BT.709 — ao custo de uma passada de GPU e do fim do caminho sem
	 * cópia, que é o que dá a este projeto a latência que ele tem.
	 *
	 * Desligado por padrão: quem não liga 10 bits não tem o que ganhar aqui,
	 * só a cópia a mais.
	 */
	var toneMapped: Boolean
		get() = prefs.getBoolean(KEY_TONE_MAP, false)
		set(value) { prefs.edit().putBoolean(KEY_TONE_MAP, value).apply() }

	/**
	 * Nitidez em seis degraus: 0 nenhuma, 1 leve, 2 média, 3 forte, 4 MQSR,
	 * 5 automática.
	 *
	 * O degrau 3 é exatamente o que havia antes — o `NORMAL_SHARPENING` do
	 * compositor, que ficou forte demais. Os degraus abaixo dele existem porque
	 * o bit não tem intensidade: no caminho direto eles saem de combiná-lo com o
	 * supersampling, que amacia; no caminho com shader, de uma máscara de
	 * nitidez com intensidade de verdade. Ver `Sharpness`, no lado nativo.
	 *
	 * Chave nova, e não a antiga reaproveitada: no valor velho o 1 significava
	 * "nitidez ligada", e nesta escala significa "leve". Reler o mesmo número
	 * numa escala diferente rebaixaria em silêncio a nitidez de quem já tinha
	 * escolhido.
	 *
	 * Os degraus 4 e 5 não são intensidade, são algoritmos:
	 *
	 * **MQSR** é o `QUALITY_SHARPENING` — desde a v55 do Horizon OS, o Meta
	 * Quest Super Resolution, que é o Snapdragon GSR com otimizações da Meta.
	 *
	 * Medido no Quest 3: funciona no caminho **com shader** e não no direto. A
	 * diferença entre os dois é o swapchain — com shader é um GL sRGB que nós
	 * criamos, no direto é um swapchain-Surface cujo formato o runtime escolhe
	 * para o MediaCodec escrever, e um filtro que espera textura RGB não tem o
	 * que fazer com aquilo.
	 *
	 * E, mesmo funcionando, ele é um **upscaler**: só tem o que fazer quando a
	 * tela pede mais pixels do que a fonte entrega. Com a tela "no ponto", não
	 * impressiona porque não há o que ampliar.
	 *
	 * **Automática** passa o bit `AUTO_LAYER_FILTER` com um conjunto de
	 * candidatos, e o compositor escolhe quadro a quadro. Ele sabe a pose da
	 * camada, a resolução do swapchain e a carga de GPU do momento; nós não
	 * sabemos nada disso daqui.
	 */
	var sharpness: Int
		get() = prefs.getInt(KEY_SHARPNESS, SHARPNESS_MEDIUM).coerceIn(0, 5)
		set(value) { prefs.edit().putInt(KEY_SHARPNESS, value.coerceIn(0, 5)).apply() }

	/** Intensidade correspondente ao degrau atual, para o caminho com shader. */
	val sharpenAmount: Float get() = SHARPEN_AMOUNT[sharpness]

	/**
	 * Áudio espacial em quatro degraus: 0 desligado, 1 sutil, 2 normal, 3 forte.
	 *
	 * Só tem efeito no modo imersivo: é lá que existe pose de cabeça para
	 * ancorar o som. No modo janela quem posiciona é o Horizon OS — e para que
	 * ele consiga, o stream de áudio sai por lá pelo mixer do sistema em vez do
	 * caminho direto ao hardware. Ver [AudioRoute].
	 */
	var spatialAudio: Int
		get() = prefs.getInt(KEY_SPATIAL, SPATIAL_NORMAL).coerceIn(0, 3)
		set(value) { prefs.edit().putInt(KEY_SPATIAL, value.coerceIn(0, 3)).apply() }

	/** Força correspondente ao degrau atual, de 0 a 1. */
	val spatialStrength: Float get() = SPATIAL_STRENGTH[spatialAudio]

	/**
	 * Brilho da tela em quatro degraus: 0 escura, 1 suave, 2 normal, 3 clara.
	 *
	 * Aplicado pelo compositor, por escala de cor na própria camada. Não custa
	 * passada de GPU nem cópia, e funciona no caminho direto — que por
	 * definição não tem shader onde mexer na imagem.
	 *
	 * Não substitui o mapeamento de tons dos 10 bits: escala é reta, e curva PQ
	 * não. Ela abaixa a imagem inteira em vez de comprimir só as altas luzes.
	 * Ameniza os brancos altos, e é o que dá para ter de graça.
	 */
	var brightness: Int
		get() = prefs.getInt(KEY_BRIGHTNESS, BRIGHTNESS_NORMAL).coerceIn(0, 3)
		set(value) { prefs.edit().putInt(KEY_BRIGHTNESS, value.coerceIn(0, 3)).apply() }

	/** Escala correspondente ao degrau atual. 1.0 não mexe em nada. */
	val brightnessScale: Float get() = BRIGHTNESS_SCALE[brightness]

	/**
	 * Quadros previstos entre os que o console manda.
	 *
	 * A fonte entrega 60 por segundo e o painel mostra 120: metade das passadas
	 * do frame loop não tem imagem nova e hoje redesenha a anterior. Com isto,
	 * essas passadas mostram um quadro **previsto** a partir dos dois últimos,
	 * pelo `GL_QCOM_frame_extrapolation` do Adreno.
	 *
	 * É extrapolação, não interpolação: nada é esperado, então **não há
	 * latência adicionada** — o preço é artefato no que for previsto errado,
	 * tipicamente em movimento rápido, transparência e elementos finos de HUD.
	 *
	 * Só existe no caminho com shader: é lá que há pipeline de GL para guardar
	 * o histórico. No caminho direto o decodificador escreve sozinho no
	 * swapchain e não há onde guardar nada.
	 *
	 * Desligado por padrão. É uma troca de gosto — imagem mais fluida contra
	 * imagem sempre verdadeira — e nenhuma das duas é obviamente certa.
	 */
	var frameExtrapolation: Boolean
		get() = prefs.getBoolean(KEY_EXTRAPOLATION, false)
		set(value) { prefs.edit().putBoolean(KEY_EXTRAPOLATION, value).apply() }

	/**
	 * Como o console deve mandar a vibração.
	 *
	 * ## O que a Sony serve, e por quê isso é uma escolha
	 *
	 * O Remote Play manda vibração de duas formas, e qual delas chega depende
	 * de como o cliente **se anuncia** ao console, no
	 * `ControllerConnectionPayload`:
	 *
	 *  - **DualShock 4** — o console faz ele mesmo a redução da háptica do jogo
	 *    para dois motores de massa excêntrica, esquerdo e direito, e manda o
	 *    resultado como pacote de tipo 7: três bytes, dois deles intensidade.
	 *    É a mesma redução que roda dentro do PS5 quando alguém joga com um
	 *    DualShock 4, feita por quem escreveu o jogo.
	 *  - **DualSense** — o console para de reduzir e manda a háptica **crua**,
	 *    como uma segunda trilha de áudio PCM, além dos efeitos de gatilho
	 *    adaptativo. É o sinal que move as bobinas de voz do controle.
	 *
	 * ## Por que o padrão é a clássica
	 *
	 * A trilha crua só serve para quem consegue entregá-la ao controle, e no
	 * Quest não conseguimos: o DualSense recebe háptica por relatório HID de
	 * saída pelo Bluetooth, e o Android não abre esse caminho a aplicativo
	 * nenhum -- não há API, com ou sem permissão. O mesmo vale para os gatilhos
	 * adaptativos. Anunciar-se como DualSense, então, custa a redução boa e
	 * compra duas coisas que não temos como entregar.
	 *
	 * O que sobra, no modo háptico, é adivinhar: pegar a envoltória da trilha e
	 * transformá-la em intensidade de motor. Foi o que este projeto fez
	 * enquanto nenhum pacote de tipo 7 aparecia, e o resultado é o que se
	 * esperaria de um palpite -- vibra em momentos em que o jogo não pediu
	 * vibração nenhuma, porque a trilha carrega passos, chuva e cliques de menu
	 * junto com o que era para sacudir.
	 *
	 * Fica aqui como escolha, e não como decisão minha, porque só quem está com
	 * o óculos na cabeça pode comparar as duas.
	 */
	var hapticRumble: Boolean
		get() = prefs.getBoolean(KEY_HAPTIC_RUMBLE, false)
		set(value) { prefs.edit().putBoolean(KEY_HAPTIC_RUMBLE, value).apply() }

	/** Opacidade do passthrough: abaixo de 1.0 escurece o quarto. */
	var passthroughOpacity: Float
		get() = prefs.getFloat(KEY_PT_OPACITY, 1.0f).coerceIn(0.1f, 1.0f)
		set(value) { prefs.edit().putFloat(KEY_PT_OPACITY, value.coerceIn(0.1f, 1.0f)).apply() }

	/** Perfil a enviar ao console. Sempre 1080p60: é o teto do protocolo. */
	fun videoProfile(): ConnectVideoProfile = ConnectVideoProfile(
		width = 1920,
		height = 1080,
		maxFPS = 60,
		bitrate = bitrateKbps,
		codec = if(tenBit) Codec.CODEC_H265_HDR else Codec.CODEC_H265
	)

	companion object
	{
		private const val KEY_BITRATE = "bitrate_kbps"
		private const val KEY_HDR = "hdr"
		private const val KEY_TONE_MAP = "tone_mapped"
		private const val KEY_SHARPNESS = "layer_sharpness"
		private const val KEY_PT_OPACITY = "passthrough_opacity"
		private const val KEY_HAPTIC_RUMBLE = "haptic_rumble"
		private const val KEY_STEREO = "stereo_mode"
		private const val KEY_SYNTH_3D = "synthetic_stereo"
		private const val KEY_3D_STRENGTH = "stereo_strength"
		private const val KEY_3D_CONV = "stereo_convergence"

		/**
		 * Disparidade pedida com força no máximo, em fração da largura.
		 *
		 * 2,5% é generoso de propósito para o teto da IPD ter o que cortar: é o
		 * limite físico que manda, e este número só decide o quanto do curso do
		 * botão fica útil antes de o teto assumir.
		 */
		const val MAX_DISPARITY = 0.025f

		/** Nome de cada modo, na ordem, para o lançador. */
		val STEREO_NAMES = listOf("off (2D)", "side by side", "over/under")
		private const val KEY_SPATIAL = "spatial_audio"
		private const val KEY_BRIGHTNESS = "video_brightness"
		private const val KEY_EXTRAPOLATION = "frame_extrapolation"

		const val DEFAULT_BITRATE = 25000

		const val SHARPNESS_OFF = 0
		const val SHARPNESS_LIGHT = 1
		const val SHARPNESS_MEDIUM = 2
		const val SHARPNESS_STRONG = 3

		/** Nome de cada degrau, na ordem, para o painel de ajuste. */
		const val SHARPNESS_MQSR = 4
		const val SHARPNESS_AUTO = 5

		val SHARPNESS_NAMES = listOf("off", "light", "medium", "strong",
			"MQSR", "automatic")

		/**
		 * Intensidade de cada degrau no caminho com shader.
		 *
		 * Não são lineares de propósito: a diferença entre nada e um pouco de
		 * realce salta muito mais aos olhos do que a diferença entre muito e um
		 * pouco mais, então o primeiro passo é o menor.
		 *
		 * A tabela vive aqui, e não no lado nativo, porque os dois caminhos com
		 * shader — o imersivo e o da janela — precisam dela e precisam dos
		 * mesmos números. Duas cópias acabariam divergindo, e "média" deixaria
		 * de significar a mesma coisa nos dois modos.
		 *
		 * Os dois últimos são zero: MQSR e automática são filtros do
		 * compositor, e o shader não deve afiar nada por baixo deles — seriam
		 * dois realces sobre a mesma imagem.
		 */
		/**
		 * Intensidade de cada degrau, para o caminho com shader.
		 *
		 * A escala mudou de significado quando a nitidez virou CAS: antes era o
		 * ganho de uma máscara de desfoque, sem teto natural, e passou a ser a
		 * dureza do CAS entre 0 e 1 — 0 é o realce contido, 1 o máximo que o
		 * filtro admite. Valor acima de 1 não faz mais nada, porque o shader
		 * satura; por isso o degrau forte é exatamente 1, e não 1,10.
		 *
		 * Os dois últimos são zero de propósito: MQSR e automático são do
		 * compositor, e ali não há shader nosso para intensificar.
		 */
		val SHARPEN_AMOUNT = floatArrayOf(0.0f, 0.35f, 0.65f, 1.0f, 0.0f, 0.0f)

		const val SPATIAL_OFF = 0
		const val SPATIAL_SUBTLE = 1
		const val SPATIAL_NORMAL = 2
		const val SPATIAL_STRONG = 3

		val SPATIAL_NAMES = listOf("off", "subtle", "normal", "strong")

		const val BRIGHTNESS_NORMAL = 2
		val BRIGHTNESS_NAMES = listOf("dark", "soft", "normal", "bright")
		val BRIGHTNESS_SCALE = floatArrayOf(0.70f, 0.85f, 1.00f, 1.15f)

		/**
		 * Força de cada degrau do áudio espacial.
		 *
		 * O zero é passagem exata, não uma aproximação: com força zero o
		 * atraso entre as orelhas some, o sombreamento some e a lei de pan
		 * devolve os canais intactos. O que sai é o que entrou.
		 */
		val SPATIAL_STRENGTH = floatArrayOf(0.0f, 0.40f, 0.70f, 1.0f)

		/** Framerate da fonte, usado para casar a taxa do painel. */
		const val SOURCE_FPS = 60
	}
}
