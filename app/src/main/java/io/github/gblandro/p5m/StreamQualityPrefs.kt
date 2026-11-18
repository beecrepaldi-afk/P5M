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
	 *
	 * O teto era 50000, o mesmo que a interface Android do chiaki aceita. A
	 * dev.212 abre ate 100000 como experimento: o pedido vai inteiro ao console
	 * na launch spec, e a linha "P5M: bitrate 10s" do diario diz o que ele
	 * mirou e o que chegou -- e so por ela que se sabe se passou dos 50. Nada no
	 * protocolo limita abaixo disso; o limite de verdade é o Wi-Fi.
	 */
	var bitrateKbps: Int
		get() = prefs.getInt(KEY_BITRATE, DEFAULT_BITRATE).coerceIn(2000, BITRATE_MAX)
		set(value)
		{
			// Escolha da pessoa zera o que o automatico tinha baixado: o novo
			// teto vale inteiro, e a rede prova de novo se aguenta.
			prefs.edit().putInt(KEY_BITRATE, value.coerceIn(2000, BITRATE_MAX))
				.putInt(KEY_BITRATE_AUTO, 0).putInt(KEY_TETO_RUIM, 0)
				.putInt(KEY_SESSOES_LIMPAS, 0).apply()
		}

	/**
	 * Bitrate automatico: procura, partida a partida, o maior que a rede aguenta.
	 *
	 * O valor escolhido e o ponto de partida. Partida limpa (dois minutos ou
	 * mais sem perda) sobe um degrau para a proxima; partida que perdeu pacotes
	 * de forma continua desce um, e o degrau que perdeu fica marcado: o
	 * automatico so tenta de novo depois de cinco partidas limpas logo abaixo,
	 * em vez de bater nele toda vez. Teto em [BITRATE_MAX].
	 *
	 * Entre partidas, e nao durante: o bitrate e combinado com o console na
	 * conexao e nao muda ate o fim, e trocar no meio pediria reconectar. Dentro
	 * da partida quem reage e o proprio console, pelo relato de perda que o
	 * chiaki manda a cada 200 ms (ver AGENTS.md, dev.210).
	 *
	 * Ate 18/11/2026 o escolhido era teto e o automatico so descia; o dono pediu
	 * que ele tambem subisse enquanto nao houver perda.
	 */
	/**
	 * Tres modos, porque subir tem preco: a 100 Mbps o PS5 dobra os bytes por
	 * quadro e a decodificacao leva 3-4 ms a mais (medido em 18/11/2026). Quem
	 * quer a menor latencia fica em [AUTO_SO_DESCE] ou desliga.
	 *  - [AUTO_DESLIGADO]: toda partida no valor escolhido.
	 *  - [AUTO_SO_DESCE]: o escolhido e teto; perda desce, partidas limpas
	 *    voltam ate ele. O padrao.
	 *  - [AUTO_SOBE_E_DESCE]: o escolhido e ponto de partida e o automatico
	 *    sobe enquanto nao houver perda, ate [BITRATE_MAX].
	 */
	var autoBitrateMode: Int
		get()
		{
			if(prefs.contains(KEY_AUTO_MODE))
				return prefs.getInt(KEY_AUTO_MODE, AUTO_SO_DESCE).coerceIn(0, 2)
			// Antes era liga/desliga, e "ligado" era so descer.
			return if(prefs.getBoolean(KEY_AUTO_BITRATE, true)) AUTO_SO_DESCE else AUTO_DESLIGADO
		}
		set(value) { prefs.edit().putInt(KEY_AUTO_MODE, value.coerceIn(0, 2))
			.putInt(KEY_BITRATE_AUTO, 0).putInt(KEY_TETO_RUIM, 0)
			.putInt(KEY_SESSOES_LIMPAS, 0).apply() }

	val autoBitrate: Boolean get() = autoBitrateMode != AUTO_DESLIGADO

	/** O que vale na proxima partida: o escolhido, ou onde o automatico chegou. */
	val bitrateEfetivo: Int
		get()
		{
			val auto = prefs.getInt(KEY_BITRATE_AUTO, 0)
			return if(autoBitrate && auto > 0) auto.coerceAtMost(BITRATE_MAX) else bitrateKbps
		}

	/** O degrau que perdeu pacotes, ou 0. */
	val bitrateTetoRuim: Int get() = if(autoBitrate) prefs.getInt(KEY_TETO_RUIM, 0) else 0

	/**
	 * Fecha a conta de uma partida imersiva e decide o bitrate da proxima.
	 *
	 * Uma janela de dez segundos e ruim com 1% ou mais de pacotes perdidos: e a
	 * partir dai que o "FEC failed" aparece e a imagem quebra em blocos. Uma
	 * partida e ruim com tres janelas ruins e pelo menos um quinto delas -- um
	 * pico isolado, como micro-ondas ligado, nao derruba a seguinte. Com
	 * alguma perda mas sem chegar nisso, o bitrate fica onde esta.
	 *
	 * Devolve a linha do diario, ou null quando nada muda.
	 */
	fun avaliarSessao(segundos: Int, janelas: Int, janelasRuins: Int): String?
	{
		if(!autoBitrate || segundos < 60 || janelas == 0)
			return null
		val atual = bitrateEfetivo
		if(janelasRuins >= 3 && janelasRuins * 5 >= janelas)
		{
			val novo = BITRATE_STEPS.lastOrNull { it < atual }
				?: return "Auto bitrate: $janelasRuins of $janelas windows lost packets, " +
					"but ${atual / 1000} Mbps is already the lowest step"
			prefs.edit().putInt(KEY_BITRATE_AUTO, novo).putInt(KEY_TETO_RUIM, atual)
				.putInt(KEY_SESSOES_LIMPAS, 0).apply()
			return "Auto bitrate: $janelasRuins of $janelas windows lost packets at ${atual / 1000} Mbps; " +
					"next session at ${novo / 1000}"
		}
		if(janelasRuins > 0)
			return "Auto bitrate: some loss ($janelasRuins of $janelas windows); staying at ${atual / 1000} Mbps"
		if(segundos < 120)
			return null
		// So desce: sobe de volta, mas nunca alem do escolhido.
		val limite = if(autoBitrateMode == AUTO_SOBE_E_DESCE) BITRATE_MAX else bitrateKbps
		if(atual >= limite)
			return null
		val proximo = (BITRATE_STEPS.firstOrNull { it > atual } ?: limite).coerceAtMost(limite)
		val ruim = prefs.getInt(KEY_TETO_RUIM, 0)
		if(ruim in 1..proximo)
		{
			val limpas = prefs.getInt(KEY_SESSOES_LIMPAS, 0) + 1
			if(limpas < 5)
			{
				prefs.edit().putInt(KEY_SESSOES_LIMPAS, limpas).apply()
				return "Auto bitrate: clean session at ${atual / 1000} Mbps; ${ruim / 1000} lost packets " +
						"before, trying it again after ${5 - limpas} more clean sessions"
			}
		}
		prefs.edit().putInt(KEY_BITRATE_AUTO, proximo).putInt(KEY_SESSOES_LIMPAS, 0)
			.putInt(KEY_TETO_RUIM, if(ruim in 1..proximo) 0 else ruim).apply()
		return "Auto bitrate: clean session at ${atual / 1000} Mbps; next session at ${proximo / 1000}"
	}

	/**
	 * O próximo degrau de `BITRATE_STEPS`, dando a volta no fim.
	 *
	 * Parte do primeiro degrau acima do valor gravado, e não de um índice: quem
	 * tiver um valor fora da lista, gravado antes dela existir, cai no degrau
	 * seguinte em vez de pular para o começo.
	 */
	fun proximoBitrate(): Int =
		BITRATE_STEPS.firstOrNull { it > bitrateKbps } ?: BITRATE_STEPS.first()

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
	 * faixas.
	 *
	 * O decodificador do Quest 3 entrega P010 em PQ e BT.2020 e não faz
	 * mapeamento de tons (`color-transfer=6`, medido). Quem faz é o shader do
	 * `tone_mapper.cpp`, e por isso os 10 bits sempre ligam o caminho com shader,
	 * nas duas telas. A saída é de 8 bits, com pontilhado.
	 *
	 * Desligado por padrão: custa uma passada de GPU e o caminho sem cópia.
	 */
	var tenBit: Boolean
		get() = prefs.getBoolean(KEY_HDR, false)
		set(value) { prefs.edit().putBoolean(KEY_HDR, value).apply() }

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
	 * Força do efeito, de 0 a 1: fração do orçamento de disparidade.
	 *
	 * O orçamento é calculado no nativo, a partir da distância interpupilar e
	 * do tamanho da tela. Antes a força era um pedido que um teto cortava, e
	 * com a tela padrão o teto cortava já no primeiro degrau.
	 */
	var stereoStrength: Float
		get() = prefs.getFloat(KEY_3D_STRENGTH, 0.5f).coerceIn(0f, 1f)
		set(value) { prefs.edit().putFloat(KEY_3D_STRENGTH, value.coerceIn(0f, 1f)).apply() }

	/**
	 * Que profundidade fica no plano da tela, de 0 (tudo salta) a 1 (tudo afunda).
	 *
	 * O padrão é 1: a cena inteira atrás da tela, como vista por uma janela. É
	 * o único ponto em que nada briga com a moldura, e é onde a interface, que
	 * vai para o plano da convergência, fica na frente de tudo.
	 */
	var stereoConvergence: Float
		get() = prefs.getFloat(KEY_3D_CONV, 1.0f).coerceIn(0f, 1f)
		set(value) { prefs.edit().putFloat(KEY_3D_CONV, value.coerceIn(0f, 1f)).apply() }

	/**
	 * Pop-out (dev.214): o que esta perto salta para fora da tela, com uma
	 * moldura flutuante na frente para a borda nao brigar com o salto. O
	 * normal deixa a cena atras da tela, como uma janela -- o mais confortavel.
	 */
	var stereoPopOut: Boolean
		get() = prefs.getBoolean(KEY_3D_POP_OUT, false)
		set(value) { prefs.edit().putBoolean(KEY_3D_POP_OUT, value).apply() }

	/**
	 * De onde vem a profundidade do 3D emulado: da rede neural (ZipDepth, na
	 * GPU) ou das pistas da imagem, que eram a única fonte até aqui. Ligada
	 * por padrão; sem o modelo carregado, o 3D cai nas pistas sozinho.
	 */
	var neuralDepth: Boolean
		get() = prefs.getBoolean(KEY_3D_NEURAL, true)
		set(value) { prefs.edit().putBoolean(KEY_3D_NEURAL, value).apply() }

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
	 * Cinco escolhas visiveis, com os seis indices antigos preservados.
	 * O 4 (MQSR explicito) migra para 5 (automatico), inclusive ao ciclar.
	 * Renumerar o automatico mudaria preferencias persistidas e a fronteira JNI.
	 * O filtro explicito produziu um disco seguindo a cabeca na dev.168;
	 * agora MQSR so participa entre os candidatos do compositor no automatico.
	 * Os niveis 0..3 continuam sendo intensidade; o 5 escolhe um algoritmo.
	 */
	var sharpness: Int
		get() = normalizeSharpness(prefs.getInt(KEY_SHARPNESS, SHARPNESS_MEDIUM))
		set(value) { prefs.edit().putInt(KEY_SHARPNESS, normalizeSharpness(value)).apply() }

	/** Intensidade correspondente ao degrau atual, para o caminho com shader. */
	val sharpenAmount: Float get() = SHARPEN_AMOUNT[sharpness]

	// A janela nao tem os filtros do compositor OpenXR. Uma preferencia antiga
	// em automatico usa medium aqui, em vez de desligar a nitidez em silencio.
	val windowSharpness: Int get() = if(sharpness >= SHARPNESS_MQSR) SHARPNESS_MEDIUM else sharpness
	val windowSharpenAmount: Float get() = SHARPEN_AMOUNT_JANELA[windowSharpness]
	val windowUsesShader: Boolean get() = windowSharpenAmount > 0f || tenBit
	val immersiveUsesShader: Boolean get() = toneMapped || syntheticStereo || tenBit

	/**
	 * Áudio espacial em quatro degraus: 0 desligado, 1 sutil, 2 normal, 3 forte.
	 *
	 * Só tem efeito no modo imersivo: é lá que existe pose de cabeça para
	 * ancorar o som. No modo janela quem posiciona é o Horizon OS — e para que
	 * ele consiga, o stream de áudio sai por lá pelo mixer do sistema em vez do
	 * caminho direto ao hardware. Ver [AudioRoute].
	 */
	var spatialAudio: Int
		get() = prefs.getInt(KEY_SPATIAL, SPATIAL_NORMAL).coerceIn(0, SPATIAL_NAMES.size - 1)
		set(value) { prefs.edit().putInt(KEY_SPATIAL, value.coerceIn(0, SPATIAL_NAMES.size - 1)).apply() }

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
	 * Painel a 180 Hz em vez de 120 no imersivo. Os dois sao multiplos dos 60
	 * fps da fonte; 180 mostra cada quadro tres vezes e corta uns 3 ms entre o
	 * quadro chegar e aparecer, em troca de bateria e calor. 120 e o padrao do
	 * dono. So existe desde que o manifesto declara o Quest 3
	 * (`com.oculus.supportedDevices`, 18/11/2026): antes o runtime so dava 72.
	 */
	var panel180: Boolean
		get() = prefs.getBoolean(KEY_PANEL_180, false)
		set(value) { prefs.edit().putBoolean(KEY_PANEL_180, value).apply() }

	val panelMaxHz: Int get() = if(panel180) 180 else 120

	/**
	 * Framerate pedido ao console: sempre 60. Jogo travado em 30 e tratado
	 * sozinho pela previsao de quadros (repeticoes reconhecidas pelo tamanho),
	 * e o codificador do PS5 ja gasta quase nada nas repeticoes -- a banda
	 * fica para os quadros reais sem trocar o stream. Pedir 30 seria uma
	 * escolha manual por jogo, e errada em todo jogo de 60.
	 */
	val sourceFps: Int get() = 60

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
		get() = rumbleNaSessao == RUMBLE_BOBINA
		set(value) { rumbleMode = if(value) RUMBLE_BOBINA else RUMBLE_CLASSICO }

	/**
	 * Para onde vai a vibração, e o que o cliente pede ao console.
	 *
	 * Três caminhos, e a medição de 12/09 é o que justifica o terceiro:
	 *
	 * - `RUMBLE_CLASSICO`: anuncia DualShock 4, e o console faz ele mesmo a
	 *   redução para dois motores. No papel é o mais fiel, porque quem reduz é
	 *   quem escreveu o jogo. Na prática, o diário daquele dia mostrou **zero**
	 *   pacotes de tipo 7 em uma sessão inteira, com o jogo tremendo na tela.
	 * - `RUMBLE_BOBINA`: anuncia DualSense e entrega a trilha crua à bobina. É o
	 *   melhor quando funciona, e é o que hoje começa com segundos de atraso até
	 *   alguém religar o controle.
	 * - `RUMBLE_MOTOR`: anuncia DualSense -- então o console manda a trilha rica
	 *   -- e converte essa trilha em intensidade para os motores comuns, pela
	 *   envoltória que o patch 0009 calcula. Na mesma sessão em que o clássico
	 *   deu zero, esta conversão produziu de 15 a 73 eventos por dez segundos.
	 *   Não passa pela bobina, então não tem o atraso, e funciona em controle
	 *   que não é DualSense.
	 */
	var rumbleMode: Int
		get() = prefs.getInt(KEY_RUMBLE_MODE,
			// Migra a preferência antiga de quem já escolheu.
			if(prefs.getBoolean(KEY_HAPTIC_RUMBLE, false)) RUMBLE_BOBINA else RUMBLE_MOTOR)
			.coerceIn(0, RUMBLE_DESLIGADO)
		set(value) { prefs.edit().putInt(KEY_RUMBLE_MODE,
			value.coerceIn(0, RUMBLE_DESLIGADO)).apply() }

	/**
	 * Os gatilhos adaptativos: repassar ao controle o efeito que o jogo pede.
	 *
	 * Só chegam com o cliente anunciado como DualSense, então valem nos modos
	 * de vibração háptica, e no desligado (que passa a se anunciar como
	 * DualSense quando isto está ligado). No clássico não há como: o anúncio
	 * de DualShock 4 é o que faz o console reduzir a vibração, e um DualShock 4
	 * não tem gatilho adaptativo para o console mandar efeito.
	 */
	/**
	 * Giroscópio e acelerômetro do DualSense para o console. Desligado de
	 * início: pouco jogo usa, e o custo é de todo evento de sensor.
	 */
	var motionControls: Boolean
		get() = prefs.getBoolean(KEY_MOTION, false)
		set(value) { prefs.edit().putBoolean(KEY_MOTION, value).apply() }

	/** O microfone do Quest vai para o console (chat de voz). Desligado de início. */
	var microphone: Boolean
		get() = prefs.getBoolean(KEY_MICROPHONE, false)
		set(value) { prefs.edit().putBoolean(KEY_MICROPHONE, value).apply() }

	var adaptiveTriggers: Boolean
		get() = prefs.getBoolean(KEY_ADAPTIVE_TRIGGERS, true)
		set(value) { prefs.edit().putBoolean(KEY_ADAPTIVE_TRIGGERS, value).apply() }

	/**
	 * A barra de luz e os LEDs de jogador seguem o que o console manda, como no
	 * PS5: a cor que o jogo escolhe, e o número do jogador.
	 */
	var controllerLights: Boolean
		get() = prefs.getBoolean(KEY_CONTROLLER_LIGHTS, true)
		set(value) { prefs.edit().putBoolean(KEY_CONTROLLER_LIGHTS, value).apply() }

	/**
	 * Só os dois modos que usam a trilha do console pedem DualSense -- e o
	 * desligado, quando os gatilhos adaptativos estão ligados.
	 *
	 * Desligado sem gatilhos pede DualShock 4: sem anunciar DualSense o console
	 * nem abre a trilha háptica, então não se gasta banda com o que ninguém vai
	 * sentir. Com os gatilhos, o anúncio é o preço de recebê-los, e a trilha
	 * que vem junto é descartada.
	 */
	fun anunciarDualSense(): Boolean =
		rumbleNaSessao == RUMBLE_BOBINA || rumbleNaSessao == RUMBLE_MOTOR ||
			(rumbleNaSessao == RUMBLE_DESLIGADO && adaptiveTriggers)

	/**
	 * Qual acorde abre o painel de ajustes dentro do jogo.
	 *
	 * Virou escolha porque L3+R3 nao e um atalho livre: ha jogo que usa os dois
	 * cliques de analogico ao mesmo tempo, e nesse jogo o painel abriria no meio
	 * de uma acao. Nao existe combinacao que nenhum jogo use, entao o certo e
	 * deixar quem esta jogando decidir qual atrapalha menos no que ele joga.
	 */
	var tuningChord: Int
		get() = prefs.getInt(KEY_CHORD, CHORD_L3_R3_R1).coerceIn(0, CHORD_NAMES.size - 1)
		set(value) { prefs.edit().putInt(KEY_CHORD, value).apply() }

	/** Opacidade do passthrough: abaixo de 1.0 escurece o quarto. */
	var passthroughOpacity: Float
		get() = prefs.getFloat(KEY_PT_OPACITY, 1.0f).coerceIn(0.1f, 1.0f)
		set(value) { prefs.edit().putFloat(KEY_PT_OPACITY, value.coerceIn(0.1f, 1.0f)).apply() }

	/** Perfil a enviar ao console: 1080p60. */
	// 1440p foi tentado na dev.212 e o PS5 recusou: a sessao nem abre
	// ("Nagare did not init! AvCap failed to initialize video: [InitResult:-6]"),
	// com qualquer bitrate e com HDR ligado ou nao. O teto do Remote Play e 1080p
	// no proprio console, e nao no cliente. Ver AGENTS.md.

	fun videoProfile(): ConnectVideoProfile = ConnectVideoProfile(
		width = 1920,
		height = 1080,
		maxFPS = sourceFps,
		bitrate = bitrateEfetivo,
		codec = if(tenBitNaSessao) Codec.CODEC_H265_HDR else Codec.CODEC_H265
	)

	/**
	 * Os 10 bits valem so com PS5. O PS4 nao recebe o pedido de codec nem de
	 * HDR (o chiaki so os escreve para o PS5) e manda sempre H.264 de 8 bits;
	 * tratar essa imagem como PQ deixaria as cores erradas.
	 *
	 * E nao valem no 3D sintetizado. A passada que estima a profundidade le a
	 * imagem crua, antes de qualquer mapeamento, e as pistas dela -- cor viva
	 * contra lavada, detalhe, brilho -- foram acertadas em valores SDR. Em PQ
	 * BT.2020 as cores chegam comprimidas e menos saturadas, e o mapa muda. No
	 * 3D o que se ve e o volume; o degrau de gradiente que os 10 bits tiram
	 * pesa menos que um mapa pior. O dono pediu os 8 bits nativos ali (30/09).
	 */
	val tenBitNaSessao: Boolean get() = tenBit && !sessaoPs4 && !sessao3d

	/**
	 * O modo de vibracao que a sessao usa de fato. No PS4 a bobina vira motor:
	 * o PS4 nao manda a trilha de audio da haptica, so os dois motores, e a
	 * bobina ficaria esperando uma trilha que nunca chega.
	 */
	val rumbleNaSessao: Int
		get() = if(sessaoPs4 && rumbleMode == RUMBLE_BOBINA) RUMBLE_MOTOR else rumbleMode

	companion object
	{
		private const val KEY_BITRATE = "bitrate_kbps"
		private const val KEY_AUTO_BITRATE = "auto_bitrate"
		private const val KEY_AUTO_MODE = "auto_bitrate_mode"
		const val AUTO_DESLIGADO = 0
		const val AUTO_SO_DESCE = 1
		const val AUTO_SOBE_E_DESCE = 2
		val AUTO_NAMES = arrayOf("Off", "Only lowers", "Up and down")
		private const val KEY_BITRATE_AUTO = "bitrate_auto_kbps"
		private const val KEY_TETO_RUIM = "bitrate_teto_ruim_kbps"
		private const val KEY_SESSOES_LIMPAS = "bitrate_sessoes_limpas"

		/**
		 * O console da sessao aberta e um PS4. Escrito em DisplayMode, que e
		 * por onde passam as duas telas; os getters "NaSessao" leem daqui.
		 */
		@Volatile var sessaoPs4 = false

		/** A sessao aberta e imersiva com o 3D sintetizado. Ver tenBitNaSessao. */
		@Volatile var sessao3d = false
		private const val KEY_HDR = "hdr"
		private const val KEY_3D_POP_OUT = "stereo_pop_out"
		private const val KEY_3D_NEURAL = "stereo_neural_depth"
		private const val KEY_TONE_MAP = "tone_mapped"
		private const val KEY_SHARPNESS = "layer_sharpness"
		private const val KEY_PT_OPACITY = "passthrough_opacity"
		private const val KEY_CHORD = "tuning_chord"
		private const val KEY_HAPTIC_RUMBLE = "haptic_rumble"
		private const val KEY_RUMBLE_MODE = "rumble_mode"
		private const val KEY_ADAPTIVE_TRIGGERS = "adaptive_triggers"
		private const val KEY_MICROPHONE = "microphone"
		private const val KEY_MOTION = "motion-controls"
		private const val KEY_CONTROLLER_LIGHTS = "controller_lights"

		const val RUMBLE_CLASSICO = 0
		const val RUMBLE_BOBINA = 1
		const val RUMBLE_MOTOR = 2

		// No fim da lista de propósito: os três primeiros já estão gravados nas
		// preferências de quem testou, e renumerar trocaria a escolha de alguém
		// sem avisar.
		const val RUMBLE_DESLIGADO = 3

		/** Nome de cada caminho, na ordem, para o lançador. */
		val RUMBLE_NAMES = listOf("classic", "haptic coil", "haptic motor", "off")
		private const val KEY_SYNTH_3D = "synthetic_stereo"
		private const val KEY_3D_STRENGTH = "stereo_strength"
		// Chave nova porque o número mudou de sentido: cada lado da convergência
		// agora usa o orçamento inteiro, e o valor guardado com a regra antiga
		// cairia noutro lugar. Todo mundo recomeça no padrão novo.
		private const val KEY_3D_CONV = "stereo_convergence_v2"

		/** Nome de cada modo, na ordem, para o lançador. */
		private const val KEY_SPATIAL = "spatial_audio"
		private const val KEY_BRIGHTNESS = "video_brightness"
		private const val KEY_EXTRAPOLATION = "frame_extrapolation"
		private const val KEY_PANEL_180 = "panel_180hz"

		/** Os tres acordes possiveis para abrir o painel de ajustes. */
		const val CHORD_L3_R3 = 0
		const val CHORD_L3_R3_R1 = 1
		const val CHORD_HOLD_L3_R3 = 2
		val CHORD_NAMES = listOf("L3 + R3", "L3 + R3 + R1", "hold L3 + R3")

		/**
		 * Quanto tempo os dois analogicos precisam ficar apertados no acorde de
		 * segurar. Setecentos milissegundos: bem acima do clique acidental e de
		 * qualquer toque de jogo, e curto o bastante para nao parecer travado.
		 */
		const val CHORD_HOLD_MS = 700L

		const val DEFAULT_BITRATE = 25000

		/** Os degraus que o lançador oferece, em kbps. */
		val BITRATE_STEPS = listOf(10000, 15000, 20000, 25000, 30000, 40000, 50000,
				60000, 70000, 80000, 100000)
		/** Acima de 50000 e experimento: ver [bitrateKbps]. */
		const val BITRATE_MAX = 100000
		const val BITRATE_EXPERIMENTAL = 50000

		const val SHARPNESS_OFF = 0
		const val SHARPNESS_LIGHT = 1
		const val SHARPNESS_MEDIUM = 2
		const val SHARPNESS_STRONG = 3

		/** Nome de cada degrau, na ordem, para o painel de ajuste. */
		const val SHARPNESS_MQSR = 4
		const val SHARPNESS_AUTO = 5

		// Preserva os indices persistidos/JNI. O antigo 4 agora escolhe o
		// automatico: pedir MQSR sozinho produziu um disco seguindo a cabeca.
		private fun normalizeSharpness(value: Int): Int =
			value.coerceIn(0, 5).let { if(it == SHARPNESS_MQSR) SHARPNESS_AUTO else it }

		val SHARPNESS_NAMES = listOf("off", "light", "medium", "strong",
			"automatic", "automatic")

		/**
		 * Os degraus que se escolhem, em ordem (dev.220): quatro de intensidade
		 * e o automatico. O 4 continua existindo so como valor antigo que o
		 * setter converte; o lancador e o painel andam por esta lista, e nao
		 * mais pelo indice cru com um degrau fantasma para pular.
		 */
		val SHARPNESS_STEPS = intArrayOf(SHARPNESS_OFF, SHARPNESS_LIGHT, SHARPNESS_MEDIUM,
			SHARPNESS_STRONG, SHARPNESS_AUTO)

		/** Posicao de um valor na lista de degraus. */
		fun sharpnessStep(value: Int): Int =
			SHARPNESS_STEPS.indexOf(normalizeSharpness(value)).coerceAtLeast(0)

		/** O degrau vizinho, andando [delta] na lista dos primeiros [quantos]. */
		fun sharpnessNext(value: Int, delta: Int, quantos: Int = SHARPNESS_STEPS.size): Int
		{
			val i = sharpnessStep(value).coerceAtMost(quantos - 1)
			return SHARPNESS_STEPS[((i + delta) % quantos + quantos) % quantos]
		}

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
		// A janela reduz 1920 para uns 1600 pontos na tela e o compositor ainda
		// reamostra por cima: o mesmo 0,65 do imersivo ficava aspero la (o dono,
		// dev.207). Cerca de 60% da intensidade em cada degrau.
		val SHARPEN_AMOUNT_JANELA = floatArrayOf(0.0f, 0.2f, 0.4f, 0.6f, 0.0f, 0.0f)

		const val SPATIAL_OFF = 0
		const val SPATIAL_SUBTLE = 1
		const val SPATIAL_NORMAL = 2
		const val SPATIAL_STRONG = 3

		val SPATIAL_NAMES = listOf("off", "subtle", "normal", "strong", "room")

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
		// Acima de 1 e o modo sala do Spatializer (ver SetStrength no nativo).
		val SPATIAL_STRENGTH = floatArrayOf(0.0f, 0.40f, 0.70f, 1.0f, 2.0f)
	}
}
