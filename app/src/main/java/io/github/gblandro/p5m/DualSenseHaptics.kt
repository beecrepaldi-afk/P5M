// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import com.metallic.chiaki.lib.Session

/**
 * Leva a haptica crua do console ao DualSense, pelo relatorio 0x32.
 *
 * O console manda a haptica de duas formas exclusivas, e quem escolhe e o jeito
 * como o cliente se anuncia. Anunciado como DualShock 4, ele reduz a haptica do
 * jogo a dois motores; anunciado como DualSense, manda a trilha crua, que e o
 * que a bobina do controle foi feita para receber. Ate agora so a primeira
 * chegava aqui, porque nao havia como entregar a segunda -- agora ha, e este
 * arquivo e a entrega.
 *
 * O desenho e um consumidor com relogio proprio, e nao um empurrao por quadro.
 * A trilha chega na thread do takion e vai para um anel no lado nativo; esta
 * thread acorda a cada 10,67 ms, tira o que couber num relatorio e manda. Duas
 * razoes: a thread do takion e a mesma que entrega video e nao pode pagar
 * fronteira de JNI cem vezes por segundo, e o controle quer cadencia constante,
 * que so quem tem relogio proprio consegue dar.
 *
 * **Silencio longo nao e mandado, e isso e o oposto do que este comentario dizia
 * antes.** A comparacao com a lightbar estava errada: la o relatorio carrega um
 * estado, que o controle esquece se ninguem repetir; aqui ele carrega um trecho
 * de audio, que o controle enfileira. Mandar silencio para sempre nao mantem
 * nada aceso -- enche a fila dele de nada.
 *
 * E encher a fila dele custa caro, porque nao ha como ler o quanto ela tem. O
 * nosso relogio manda 3000 amostras por segundo e o dele consome no cristal
 * dele; qualquer diferenca, mesmo de fracao de por cento, vira folga que se
 * acumula enquanto o fluxo nao para. Foi assim que uma sessao de Spider-Man
 * chegou a dois segundos de atraso com a nossa fila em dezenas de bytes -- a
 * fila que crescia era a de la, que este lado nao enxerga.
 *
 * Entao o fluxo para depois de uns 250 ms de silencio e recomeca quando ha
 * trilha. A fila do controle esvazia sozinha nas pausas, e cada evento novo
 * comeca do zero em vez de entrar atras do que se acumulou.
 *
 * O caminho atual usa PCM de 8 bits COM sinal, repouso zero, como a conversao
 * explicita do DS5Dongle. A antiga conclusao baseada no `audio.format=u8` do
 * README do SAxense ficou desatualizada; nao mudar o repouso so por ela.
 * A conversao nativa e este empacotador precisam usar o mesmo formato.
 */
class DualSenseHaptics(private val context: Context, private val hid: DualSenseHid)
{
	companion object
	{
		// Relatorio 0x32: 142 bytes, 32 amostras estereo de 8 bits a 3 kHz.
		private const val REPORT_SIZE = 142
		private const val AMOSTRAS_OFFSET = 13
		private const val AMOSTRAS_BYTES = 64
		private const val CRC_OFFSET = 138

		// Repouso da amostra. Zero, porque as amostras sao 8 bits COM sinal.
		//
		// Isto ja foi 0x80, e a troca veio de uma linha de configuracao do
		// PipeWire no README do SAxense que declara `audio.format=u8`. O
		// DS5Dongle, que e um bridge de hardware em producao para exatamente
		// isto, escreve `int8_t` e escala float[-1,1] por 127 com corte em
		// [-128,127] -- codigo explicito, e nao uma linha de configuracao. Vale
		// mais, e por isso o repouso voltou a zero.
		//
		// A bancada compara os dois em hardware, porque as duas fontes nao podem
		// estar certas e nenhum sintoma distingue uma da outra: mandar o formato
		// trocado poe o repouso no fim de curso, e o resultado e o mesmo dos dois
		// lados -- quase nada se sente.
		private const val SILENCIO: Byte = 0x00

		// Bytes 5 a 9 do relatorio: a profundidade da fila de audio DENTRO do
		// controle, em quadros.
		//
		// Estes cinco bytes iam zerados, seguindo o SAxense, que e um POC. O
		// DS5Dongle os expoe como ajuste ao usuario, entre 16 e 128, com padrao
		// 32 -- e o ajuste existe la justamente para trocar latencia por
		// resistencia a tranco. Mandando zero, o controle nao guarda nada, e
		// qualquer atraso nosso vira buraco na onda imediatamente.
		//
		// Dezesseis, que e o minimo do DS5Dongle, e nao os 32 do padrao dele.
		// A folga la nao e de graca como eu escrevi antes: ela nao custa
		// trabalho nosso, mas custa atraso igual, porque o controle so comeca a
		// tocar depois de encher. Com os furos ja em seis por dez segundos, o
		// que falta cortar e atraso, e nao tranco.
		private const val FILA_DO_CONTROLE: Byte = 16

		// 32 amostras a 3 kHz, com uns 0,3% a mais de proposito.
		//
		// O periodo exato seria 10.666.666 ns. O que se manda com ele e o que o
		// controle consome, na media, e "na media" nao existe entre dois
		// cristais: o que sobrar de diferenca vai para a fila do controle, que
		// nao se le e que so esvazia quando o fluxo para.
		//
		// Entao a diferenca e escolhida, e escolhida para o lado que nao
		// acumula. Este periodo entrega cerca de 2991,2 quadros estereo/s:
		// faltam cerca de 8,8 por segundo em relacao aos 3000 nominais.
		// O efeito no controle ainda precisa ser medido; esta margem nao prova
		// que a fila remota fica vazia nem que as lacunas sejam imperceptiveis.
		private const val PERIODO_NANOS = 10_698_000L

		private const val RELATORIO_MS = 10_000L

		// De quanto em quanto tempo a configuracao de potencia e reafirmada.
		//
		// Uma vez no comeco deveria bastar, porque o controle guarda esses
		// campos. "Deveria" nao e bom o suficiente aqui: o console tambem fala
		// com o mesmo controle pelo Remote Play, e se ele reduzir a potencia no
		// meio da sessao ninguem aqui fica sabendo. Um relatorio a cada dois
		// segundos custa nada perto dos noventa e quatro por segundo da trilha.
		private const val POTENCIA_MS = 2_000L

		// Depois de tantos relatorios seguidos de silencio, para de mandar.
		//
		// Vinte e quatro sao uns 250 ms: longo o bastante para nao piscar entre
		// dois trancos de um mesmo efeito, curto o bastante para a fila do
		// controle esvaziar antes do proximo evento.
		private const val VOLTAS_ATE_ESTACIONAR = 24

		// A fila que segura a trilha entre o que chega e o que sai.
		//
		// O console entrega 30 amostras estereo por pacote e o relatorio leva
		// 32. As duas taxas batem na media -- 3000 amostras por segundo dos dois
		// lados --, mas o tamanho do pedaco nao bate, e o pacote vem quando a
		// rede deixa. Sem uma fila no meio, a maioria das voltas encontra menos
		// de um relatorio de trilha e completa o resto com zeros: a onda sai
		// picada, e o que se sente e chiado em vez de vibracao.
		// O vetor tem de ser maior que o teto: uma leitura inteira precisa caber
		// acima dele, senao o teto nunca e alcancado e quem limita a fila passa
		// a ser a condicao de leitura, calada, um lugar antes.
		private const val FILA_BYTES = 1024

		// Quanto se tira do anel por volta. Menor que a fila de proposito: so se
		// le quando cabe uma leitura inteira, senao o que passasse do fim seria
		// dado ja tirado do anel e jogado fora -- um buraco no meio da onda, que
		// e pior do que deixa-lo esperando do outro lado.
		private const val LEITURA_BYTES = 256

		// Nao comeca a entregar antes de ter isto guardado: um relatorio, uns
		// 10,7 ms.
		//
		// Ja foram 128 bytes, e 128 tinham um efeito colateral que nao aparece
		// em contador nenhum: um efeito curto -- um toque, um passo -- que
		// coubesse em menos de 21 ms de trilha nunca alcancava o piso, e so saia
		// quando o esvaziamento por inatividade o soltava, dezenas de
		// milissegundos depois. Era ao mesmo tempo o "as vezes nao vibrava" e
		// parte do atraso.
		//
		// Um relatorio de piso e o minimo que ainda permite entregar quadro
		// inteiro. O que segurava a fila contra o tranco da rede passa a ser a
		// fila do proprio controle, que e onde essa folga custa menos.
		private const val ENCHER_ANTES = 64

		// Teto da fila, uns 32 ms. Nao e o atraso normal: e o quanto cabe num
		// tranco de rede sem perder amostra.
		//
		// Era 512, com o relogio adaptativo encarregado de trazer o nivel de
		// volta. Sem ele, o teto e o unico limite do atraso, e 512 bytes seriam
		// 85 ms parados na fila sempre que um tranco a enchesse. Cortar o mais
		// velho custa um salto na onda; guardar custa atraso em tudo que vem
		// depois, e o segundo e o que se sente.
		//
		// Com teto apertado a fila estourava para os dois lados no mesmo
		// intervalo -- a dev.150 marcou 110 furos e 49 estouros em dez segundos,
		// com o pico colado no teto o tempo todo. Entre o piso e o teto havia um
		// relatorio de folga, e qualquer oscilacao da rede saia como salto na
		// onda ou silencio no meio dela. Dezesseis descontinuidades por segundo
		// nao sao uma vibracao mais fraca: sao outra coisa, aspera.
		private const val TETO_DA_FILA = 192

		// A bancada agora tem um proposito so: mostrar o teto.
		//
		// O formato ja foi decidido, e as filas ja foram medidas. O que continua
		// sem resposta e se o teto da bobina fica acima ou abaixo do que se quer
		// sentir num jogo -- e essa pergunta nao se responde em jogo, onde o que
		// chega e o conteudo que o console mandar. Um tom de escala cheia
		// responde: se ELE for forte, o teto esta alto e o que falta e levantar o
		// corpo da trilha; se ELE ja parecer fraco, o teto e este e nao ha o que
		// espremer daqui.
		//
		// Tres niveis, do cheio ao terco, para dar referencia em vez de um sim ou
		// nao isolado.
		private const val ENSAIO_HZ = 90.0
		private const val ENSAIO_MS = 1500L
		private val ENSAIO_NIVEIS = listOf(120.0 to "full", 80.0 to "two thirds", 40.0 to "one third")

		// Depois de tantas voltas sem nada novo, o que sobrou na fila sai
		// completado com silencio. E o fim de uma rajada: segurar meia dezena de
		// amostras esperando companhia colaria o fim de um efeito no comeco do
		// proximo, uns segundos depois.
		//
		// Duas voltas, e nao cinco. Cinco eram 53 ms de espera antes de soltar o
		// rabo de um efeito, e efeito curto e quase so rabo -- o atraso que se
		// sentia nos toques rapidos nascia aqui.
		private const val VOLTAS_ATE_ESVAZIAR = 2
	}

	@Volatile private var rodando = false
	private var thread: Thread? = null

	@Volatile var mandados = 0L
		private set
	@Volatile var mudos = 0L
		private set
	@Volatile var recusados = 0L
		private set
	@Volatile var furos = 0L
		private set
	@Volatile var atrasados = 0L
		private set
	@Volatile var estacionadas = 0L
		private set

	/**
	 * Se a trilha crua esta mesmo saindo para o controle.
	 *
	 * Existe para o `Rumble` calar a boca. As duas saidas ficam ligadas de
	 * proposito -- se a ponte nao subir, a envoltoria no motor e o que salva a
	 * sessao --, mas enquanto as duas funcionam quem se sente e o motor, que e
	 * grosseiro e muito mais forte que a bobina. O resultado e uma vibracao que
	 * parece a de sempre com um chiado por baixo, e nao a do PS5.
	 */
	@Volatile var entregando = false
		private set

	/**
	 * Bancada: toca um tom conhecido na bobina, variando como a rota e armada.
	 *
	 * Existe porque a pergunta "a bobina toca?" nao pode depender de um jogo. Em
	 * jogo o que se sente e a soma de tudo, o motor abafa a bobina, e a resposta
	 * chega como "achei que estava estranho" -- que nao decide nada. Aqui o
	 * sinal e um tom de 90 Hz quase na escala cheia, e a unica coisa que muda
	 * entre as passadas e o relatorio que arma a rota antes.
	 *
	 * Quatro passadas, uma por hipotese, separadas por silencio: sem armar
	 * nenhuma rota, e com tres valores de `valid_flag0`. As fontes publicas
	 * discordam sobre quais bits abrem o audio -- uma chama de "use rumble not
	 * haptics" o bit que o kernel chama de "haptics select" --, e uma passada em
	 * hardware resolve o que a leitura nao resolve.
	 *
	 * **Bloqueia.** Quem chama roda numa thread propria.
	 */
	fun ensaiar(): String
	{
		val saida = StringBuilder()
		if(!hid.conectar())
			return "DualSense bridge did not open.\n" + hid.texto()

		// Potencia cheia tambem aqui: sem isto a bancada mediria o teto que o
		// ultimo dono da conexao deixou, e nao o do controle.
		val cheia = hid.potenciaCheia()
		saida.append("full motor power ").append(if(cheia) "accepted" else "refused").append("\n")
		dormir(200)

		for((nivel, nome) in ENSAIO_NIVEIS)
		{
			val mandados = tocarTom(nivel, false)
			saida.append("$nome (${nivel.toInt()} of 127): $mandados reports\n")
			Trace.log(context, "Haptics bench: $nome, $mandados reports")
			dormir(1200)
		}

		hid.fechar()
		return saida.toString()
	}

	/**
	 * Um tom senoidal na bobina, na mesma cadencia da entrega de verdade.
	 *
	 * A fase corre pelo indice absoluto da amostra, e nao reinicia a cada
	 * relatorio: reiniciar poria um degrau na onda a cada 10,67 ms, e o que se
	 * sentiria seria esse degrau, a 94 Hz, e nao o tom de 90.
	 */
	private fun tocarTom(amplitude: Double, semSinal: Boolean): Int
	{
		val amostras = ByteArray(AMOSTRAS_BYTES)
		val relatorio = ByteArray(REPORT_SIZE)
		var quadro = 0
		var mandados = 0
		var proximo = System.nanoTime()
		val fim = System.currentTimeMillis() + ENSAIO_MS

		while(System.currentTimeMillis() < fim)
		{
			proximo += PERIODO_NANOS
			for(i in 0 until AMOSTRAS_BYTES / 2)
			{
				val t = (quadro + i).toDouble() / 3000.0
				val onda = kotlin.math.sin(2.0 * Math.PI * ENSAIO_HZ * t) * amplitude
				val v = if(semSinal) (128.0 + onda).toInt().coerceIn(0, 255)
					else onda.toInt().coerceIn(-128, 127)
				amostras[i * 2] = v.toByte()
				amostras[i * 2 + 1] = v.toByte()
			}
			quadro += AMOSTRAS_BYTES / 2

			montar(relatorio, amostras, AMOSTRAS_BYTES)
			if(hid.mandar(relatorio))
				mandados++

			val falta = proximo - System.nanoTime()
			if(falta > 0)
				try { Thread.sleep(falta / 1_000_000L, (falta % 1_000_000L).toInt()) }
				catch(e: InterruptedException) { return mandados }
			else
				proximo = System.nanoTime()
		}
		return mandados
	}

	private fun dormir(ms: Long)
	{
		try { Thread.sleep(ms) } catch(e: InterruptedException) { }
	}

	/**
	 * Comeca a entregar. Nao bloqueia.
	 *
	 * A ponte com o controle sobe dentro da propria thread de entrega, e nao
	 * antes dela: abrir o perfil de HID espera um callback que chega na thread
	 * principal, e esperar por ele de dentro dela seria esperar por si mesmo.
	 * Como efeito colateral util, a tela de stream nao segura nada enquanto o
	 * Bluetooth se decide.
	 *
	 * A thread e dona da ponte do comeco ao fim, e e ela que a fecha. Poderia
	 * ser o `stop()`, e seria errado: subir a ponte leva ate uns segundos, entao
	 * existe uma janela em que `stop()` chega com a thread ainda no meio da
	 * conexao. Se os dois lados mexessem na ponte, essa janela seria uma corrida
	 * -- e o `stop()` nao pode simplesmente esperar, porque quem o chama e o
	 * encerramento da tela.
	 *
	 * Se a ponte nao abrir, a thread encerra sozinha e a vibracao classica
	 * continua valendo: ela nunca deixou de estar ligada.
	 */
	fun start(session: Session, aoFalhar: () -> Unit = {})
	{
		if(rodando)
			return
		rodando = true
		thread = Thread({
			try
			{
				// O segundo teste nao e redundante. Entre o pedido e a ponte de
				// pe cabe uma sessao inteira ser encerrada, e entrar no laco
				// depois disso seria ler um ponteiro nativo ja liberado.
				if(hid.conectar() && rodando)
				{
					// Potencia cheia antes da primeira amostra. Sem isto a
					// bobina pode estar recebendo um oitavo do que mandamos, e
					// com um filtro passa-baixa na frente -- os dois campos ficam
					// com o valor que o ultimo dono da conexao deixou.
					val cheia = hid.potenciaCheia()
					Trace.log(context, "Haptics: full motor power " +
						(if(cheia) "accepted" else "refused"))

					// Nao arma rota nenhuma, e isso e resultado de medida.
					//
					// A bancada da dev.147 tocou o mesmo tom em quatro passadas,
					// variando so como a rota era armada antes, e as quatro
					// foram sentidas -- inclusive a que nao armava nada. Entao a
					// bobina ja atende o relatorio 0x32 depois da leitura do
					// relatorio de calibragem, que a ponte faz ao conectar, e
					// mandar um 0x31 antes so acrescentaria um passo que pode
					// falhar sem acrescentar nada que funcione.
					Trace.log(context, "Haptics: raw DualSense haptics started")
					entregando = true
					try
					{
						laco(session)
					}
					finally
					{
						entregando = false
					}
				}
				else if(rodando)
				{
					Trace.log(context,
						"Haptics: DualSense bridge did not open; falling back to classic rumble")
					aoFalhar()
				}
			}
			finally
			{
				encerrarPonte()
			}
			rodando = false
		}, "p5m-haptics").also {
			// Acima do normal, abaixo do audio. Um relatorio atrasado e um
			// tranco sentido na mao, mas nao vale disputar com o video.
			it.priority = Thread.NORM_PRIORITY + 2
			it.start()
		}
	}

	/**
	 * Pede para parar e espera pouco.
	 *
	 * O pouco e de proposito: quem chama isto e o encerramento da tela, e
	 * segurar o encerramento por segundos para esperar o Bluetooth seria trocar
	 * um defeito invisivel por um visivel. Quando a espera estoura, a thread
	 * ainda esta subindo a ponte -- e ao terminar de subir ela vai ver que nao
	 * ha mais o que fazer, fechar o que abriu e sair sem tocar na sessao.
	 */
	fun stop()
	{
		if(!rodando)
			return
		rodando = false
		thread?.join(800)
		thread = null
		Trace.log(context, "Haptics: stopped after $mandados reports " +
			"($mudos silent, $furos gaps, $atrasados late, $recusados refused, " +
			"$estacionadas parked)")
	}

	/**
	 * Silencio e fecha, na thread que abriu.
	 *
	 * O relatorio de zeros existe porque a bobina segura a ultima vibracao ate
	 * o controle decidir sozinho que acabou -- sair sem ele deixa um zumbido na
	 * mao depois que a tela ja fechou.
	 */
	private fun encerrarPonte()
	{
		try
		{
			val silencio = ByteArray(REPORT_SIZE)
			val mudo = ByteArray(AMOSTRAS_BYTES)
			java.util.Arrays.fill(mudo, SILENCIO)
			montar(silencio, mudo, AMOSTRAS_BYTES)
			hid.mandar(silencio)
		}
		catch(e: Throwable)
		{
			// Nao ha a quem reclamar: a tela ja esta indo embora.
		}
		hid.fechar()
	}

	/**
	 * O laco de entrega, com relogio de deadline e fila no meio.
	 *
	 * Duas coisas que a primeira versao errava, e que so apareceram com o
	 * controle na mao.
	 *
	 * A primeira e o relogio. Dormir um periodo fixo depois de cada volta soma o
	 * tempo do trabalho ao intervalo, e a cadencia derrapa devagar ate o
	 * controle sentir. Aqui cada volta tem um instante-alvo proprio, e o que se
	 * dorme e o que falta para ele.
	 *
	 * A segunda e o enquadramento, e era a que fazia a vibracao sair esquisita.
	 * O console entrega 30 amostras estereo por pacote; o relatorio leva 32. As
	 * medias batem, os pedacos nao, e o pacote chega quando a rede deixa. A
	 * versao anterior mandava o que tivesse e completava o resto com zeros --
	 * entao numa vibracao continua a maioria dos relatorios saia com um naco de
	 * onda seguido de silencio, cem vezes por segundo. Isso nao e a vibracao
	 * mais fraca: e outra coisa, com uma componente de 94 Hz que o jogo nunca
	 * pediu. Agora o relatorio so leva trilha quando ha trilha inteira para ele,
	 * e o resto espera na fila em vez de virar buraco.
	 */
	private fun laco(session: Session)
	{
		val chegando = ByteArray(LEITURA_BYTES)
		val fila = ByteArray(FILA_BYTES)
		var naFila = 0
		val relatorio = ByteArray(REPORT_SIZE)
		val amostras = ByteArray(AMOSTRAS_BYTES)
		var fluindo = false
		var voltasVazias = 0
		var voltasMudas = 0
		var estacionado = false
		var picoDaFila = 0
		var somaDaFila = 0L
		var voltasContadas = 0L
		var proximo = System.nanoTime()
		var ultimoRelato = System.nanoTime()
		var ultimaPotencia = System.currentTimeMillis()
		var somaQuadradosSaida = 0.0
		var amostrasSaida = 0L
		var picoSaida = 0
		var enviadosComSinal = 0L
		var enviadosMudos = 0L
		var voltasEstacionadas = 0L
		var recusadosNoIntervalo = 0L
		var bytesLidos = 0L
		var bytesConsumidos = 0L
		var bytesDescartados = 0L
		var prazosPerdidos = 0L
		var picoLeituraNs = 0L
		var picoEnvioNs = 0L
		// O pico sozinho nao separa as duas explicacoes do `send peak` de 21 ms:
		// esperar o enlace agrupa os tempos em multiplos do intervalo dele,
		// ruido de agendamento os espalha. A dev.177 mostrou espalhado, com
		// pico unico em ~2,5 ms -- o que diz que este cronometro nao enxerga o
		// enlace, porque o `sendData` so entrega o relatorio a pilha.
		val temposEnvio = TimingHistogram()
		var iniciosSemEspera = 0L

		while(rodando)
		{
			// Cadencia fixa. O relogio adaptativo que estava aqui perseguia o
			// nivel da NOSSA fila encurtando o periodo -- ou seja, mandando mais
			// de 3000 amostras por segundo para quem consome 3000. Ele nunca
			// drenou nada: mudava o atraso de uma fila que eu meco para a fila
			// do controle, que eu nao leio e que so anda quando o fluxo para.
			// Era exatamente o sintoma dos dez pulos seguidos.
			proximo += PERIODO_NANOS

			val inicioLeitura = System.nanoTime()
			val lidos = try
			{
				if(FILA_BYTES - naFila >= LEITURA_BYTES)
					session.hapticsRead(chegando)
				else
				{
					// Fila cheia: o que sobra fica no anel, que tem o proprio
					// teto de atraso e sabe pular para a frente sozinho.
					atrasados++
					0
				}
			}
			catch(e: Throwable)
			{
				// A sessao pode ter sido liberada entre a checagem e a leitura.
				// Isso encerra a entrega, e nao e defeito: e o fim do stream.
				Trace.log(context, "Haptics: read failed, ending delivery")
				break
			}

			picoLeituraNs = maxOf(picoLeituraNs, System.nanoTime() - inicioLeitura)
			bytesLidos += lidos
			if(lidos > 0)
			{
				voltasVazias = 0
				System.arraycopy(chegando, 0, fila, naFila, lidos)
				naFila += lidos

				// Descarta o mais velho, e nao o mais novo. O que interessa e o
				// agora: um efeito de meio segundo atras nao vale a pena ser
				// sentido, e mante-lo empurraria todo o resto para tras.
				if(naFila > TETO_DA_FILA)
				{
					val sobra = naFila - TETO_DA_FILA
					bytesDescartados += sobra
					System.arraycopy(fila, sobra, fila, 0, TETO_DA_FILA)
					naFila = TETO_DA_FILA
					atrasados++
				}

				if(!fluindo && naFila >= ENCHER_ANTES)
					fluindo = true
			}
			else
				voltasVazias++

			if(naFila > picoDaFila)
				picoDaFila = naFila
			somaDaFila += naFila
			voltasContadas++

			// Fim de rajada: o que sobrou sai completado, e a fila zera. Sem
			// isto o rabo de um efeito ficaria preso esperando o proximo, e
			// sairia colado nele.
			// Um efeito isolado pode terminar antes de atingir ENCHER_ANTES.
			// Exigir `fluindo` aqui deixava esse efeito preso ate outro chegar.
			val esvaziando = naFila in 1 until AMOSTRAS_BYTES &&
					voltasVazias >= VOLTAS_ATE_ESVAZIAR
			// Acordar de repouso nao precisa do prebuffer usado no fluxo continuo.
			// Um pacote isolado traz 60 bytes, menos que os 64 do relatorio; esperar
			// duas voltas acrescentava ~21 ms ao primeiro toque. Completa so essa
			// entrada com zero; depois mantem o enquadramento e a cadencia normais.
			val acordando = estacionado && !fluindo && naFila in 1 until AMOSTRAS_BYTES

			val leva = when
			{
				fluindo && naFila >= AMOSTRAS_BYTES -> AMOSTRAS_BYTES
				acordando -> naFila
				esvaziando -> naFila
				else -> 0
			}

			bytesConsumidos += leva
			if(acordando) {
				fluindo = true
				iniciosSemEspera++
			}
			if(leva > 0)
			{
				System.arraycopy(fila, 0, amostras, 0, leva)
				if(leva < AMOSTRAS_BYTES)
					java.util.Arrays.fill(amostras, leva, AMOSTRAS_BYTES, SILENCIO)
				naFila -= leva
				if(naFila > 0)
					System.arraycopy(fila, leva, fila, 0, naFila)
			}
			else
			{
				java.util.Arrays.fill(amostras, SILENCIO)
				// Furo e diferente de silencio: e a fila secando no meio de uma
				// vibracao. Se este numero subir em jogo, o que falta e mais
				// folga em ENCHER_ANTES, e nao mais ganho.
				if(fluindo)
					furos++
			}

			if(naFila == 0 && voltasVazias >= VOLTAS_ATE_ESVAZIAR)
				fluindo = false

			// Estaciona depois de silencio prolongado, e volta na primeira
			// trilha. Enquanto estacionado o laco continua girando: ele ainda le,
			// ainda mede e ainda mantem a cadencia -- so nao alimenta a fila do
			// controle com nada.
			// Receber PCM nao significa receber vibracao: o console pode manter
			// a trilha aberta com amostras zero. Olhamos o conteudo, sem limiar
			// de amplitude, para nao apagar os detalhes mais fracos do jogo.
			val temSinal = amostras.any { it != SILENCIO }
			if(!temSinal)
				mudos++
			if(temSinal)
			{
				voltasMudas = 0
				estacionado = false
			}
			else if(!estacionado && ++voltasMudas >= VOLTAS_ATE_ESTACIONAR)
			{
				estacionado = true
				estacionadas++
			}

			if(!estacionado)
			{
				montar(relatorio, amostras, AMOSTRAS_BYTES)

				val inicioEnvio = System.nanoTime()
				val aceito = hid.mandar(relatorio)
				val duracaoEnvio = System.nanoTime() - inicioEnvio
				picoEnvioNs = maxOf(picoEnvioNs, duracaoEnvio)
				temposEnvio.add(duracaoEnvio)
				if(aceito)
				{
					mandados++
					if(temSinal) enviadosComSinal++ else enviadosMudos++
					// Mede o PCM entregue ao transporte, incluindo silencio, e
					// nao a curva aplicada ao RMS de entrada: com ganho nao linear
					// essas duas contas nao sao equivalentes.
					for(amostra in amostras)
					{
						val v = amostra.toInt()
						somaQuadradosSaida += v * v
						picoSaida = maxOf(picoSaida, kotlin.math.abs(v))
					}
					amostrasSaida += AMOSTRAS_BYTES
				}
				else
				{
					recusados++
					recusadosNoIntervalo++
					// Recusa quase sempre e o controle fora do enlace. Se ele
					// voltar no meio da sessao, alguem tem de procura-lo de novo:
					// a dev.177 perdeu a vibracao de uma sessao inteira porque a
					// unica procura aconteceu 3 s antes de o controle reconectar.
					hid.recuperar()
				}
			}
			else
				voltasEstacionadas++

			val agora = System.currentTimeMillis()
			if(agora - ultimaPotencia >= POTENCIA_MS)
			{
				ultimaPotencia = agora
				hid.potenciaCheia()
			}
			val agoraNanos = System.nanoTime()
			if(agoraNanos - ultimoRelato >= RELATORIO_MS * 1_000_000L)
			{
				val intervaloMs = (agoraNanos - ultimoRelato) / 1_000_000L
				ultimoRelato = agoraNanos
				// O pico da fila e o atraso que nos acrescentamos, e e a unica
				// parte da latencia que da para medir daqui: 64 bytes sao um
				// relatorio, uns 10,7 ms. O resto do caminho -- rede, console,
				// fila do proprio controle -- nao se ve deste lado.
				Trace.log(context, "Haptics 10s: $mandados sent, $mudos silent, " +
					"$furos gaps, $atrasados late, $recusados refused, " +
					"$estacionadas parked, " +
					"queue ${if(voltasContadas > 0) somaDaFila / voltasContadas else 0} B avg, " +
					"$picoDaFila B peak, output RMS " +
					String.format(java.util.Locale.ROOT, "%.2f",
						if(amostrasSaida > 0) kotlin.math.sqrt(somaQuadradosSaida / amostrasSaida) else 0.0) +
					", output peak $picoSaida (signed 8-bit, transport accepted)")
				// Particao sem sobreposicao: as quatro categorias somam as voltas.
				// `sent` e `silent` acima sao cumulativos e se sobrepoem; somar os
				// dois parecia provar uma cadencia que nunca foi medida.
				Trace.log(context, "Haptics timing: $intervaloMs ms, $voltasContadas loops, " +
					"$enviadosComSinal active sent, $enviadosMudos zero sent, " +
					"$voltasEstacionadas idle, $recusadosNoIntervalo refused; " +
					"source $bytesLidos B, consumed $bytesConsumidos B, dropped $bytesDescartados B; " +
					"$prazosPerdidos missed deadlines, read peak ${picoLeituraNs / 1000} us, " +
					"send peak ${picoEnvioNs / 1000} us; $iniciosSemEspera early onsets; " +
					"send ms histogram ${TimingHistogram.TITULO}${temposEnvio.format()}")
				enviadosComSinal = 0L
				enviadosMudos = 0L
				voltasEstacionadas = 0L
				recusadosNoIntervalo = 0L
				bytesLidos = 0L
				bytesConsumidos = 0L
				bytesDescartados = 0L
				prazosPerdidos = 0L
				picoLeituraNs = 0L
				picoEnvioNs = 0L
				temposEnvio.clear()
				iniciosSemEspera = 0L
				somaQuadradosSaida = 0.0
				amostrasSaida = 0L
				picoSaida = 0
				picoDaFila = naFila
				somaDaFila = 0L
				voltasContadas = 0L
			}

			val falta = proximo - System.nanoTime()
			if(falta > 0)
			{
				try
				{
					Thread.sleep(falta / 1_000_000L, (falta % 1_000_000L).toInt())
				}
				catch(e: InterruptedException)
				{
					break
				}
			}
			else
			{
				// Ficou para tras a ponto de nao adiantar correr atras: mandar
				// tres relatorios seguidos so entulharia a fila do controle com
				// passado. Recomeca a contagem daqui.
				proximo = System.nanoTime()
				prazosPerdidos++
			}
		}
	}

	/**
	 * Monta o relatorio 0x32 em cima do mesmo vetor, sem alocar por volta.
	 *
	 * Os bytes fixos do cabecalho sao o modo de audio do controle: rota, ganho
	 * e o marcador de bloco de amostras. Nao sao escolha nossa e nao tem
	 * significado nosso -- vem do formato, e mexer neles e como mexer no
	 * cabecalho de um WAV.
	 */
	private fun montar(r: ByteArray, amostras: ByteArray, tamanho: Int)
	{
		java.util.Arrays.fill(r, 0)
		r[0] = 0x32
		// Os contadores sao preenchidos pelo transporte. Assim 0x31 e 0x32
		// formam um fluxo unico mesmo quando uma sessao termina e outra comeca.
		r[2] = 0x91.toByte()
		r[3] = 0x07
		r[4] = 0xFE.toByte()
		r[5] = FILA_DO_CONTROLE
		r[6] = FILA_DO_CONTROLE
		r[7] = FILA_DO_CONTROLE
		r[8] = FILA_DO_CONTROLE
		r[9] = FILA_DO_CONTROLE
		r[11] = 0x92.toByte()
		r[12] = 0x40
		System.arraycopy(amostras, 0, r, AMOSTRAS_OFFSET, tamanho)

		val crc = java.util.zip.CRC32()
		crc.update(0xA2)
		crc.update(r, 0, CRC_OFFSET)
		val v = crc.value
		r[CRC_OFFSET] = (v and 0xFF).toByte()
		r[CRC_OFFSET + 1] = ((v shr 8) and 0xFF).toByte()
		r[CRC_OFFSET + 2] = ((v shr 16) and 0xFF).toByte()
		r[CRC_OFFSET + 3] = ((v shr 24) and 0xFF).toByte()
	}
}
