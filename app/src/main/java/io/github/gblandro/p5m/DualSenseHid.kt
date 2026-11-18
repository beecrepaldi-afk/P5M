// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * Relatorio HID de saida ao DualSense por Bluetooth, num Quest 3. **Funciona.**
 *
 * Medido em hardware na dev.141, 03/09/2026: a lightbar varreu vermelho, verde
 * e azul, e voltou sozinha ao azul quando o fluxo parou. O AGENTS.md registrava
 * o contrario como fato pago; estava errado, e a entrada de la agora aponta
 * para ca.
 *
 * A receita tem quatro partes, e nenhuma sozinha basta. Custaram cinco builds,
 * e cada uma foi encontrada porque a anterior mudou o sintoma:
 *
 *  1. `BluetoothHidHost`, @SystemApi, alcancada por reflexao depois de furar a
 *     lista de bloqueio. A Horizon OS **nao** impoe BLUETOOTH_PRIVILEGED aqui,
 *     que era o que podia matar a ideia inteira.
 *  2. `sendData`, e nao `setReport`. O primeiro vira relatorio de saida no
 *     canal de interrupcao, que e por onde o driver do kernel manda o dele; o
 *     segundo vira SET_REPORT no canal de controle, e o controle ignora.
 *  3. Ler o relatorio de calibragem antes. Sem isso o controle fica no
 *     relatorio simples e descarta o 0x31 sem erro nenhum -- sintoma
 *     indistinguivel de "nao chegou", e por isso o mais caro de achar.
 *  4. Reescrever na cadencia do controle. Ele nao guarda estado: quando o fluxo
 *     para, volta ao azul sozinho.
 *
 * O layout do relatorio visual vem da `dualsense_output_report_bt` do
 * hid-playstation, cuja soma de campos fecha em 78 bytes exatos -- o tamanho do
 * relatorio. Nao vem do repositorio de referencia, que comeca o conteudo um
 * byte antes: com esse deslocamento o zero cai em `valid_flag1`, que quer dizer
 * "nao estou controlando a lightbar", e o controle apaga tudo obedecendo.
 *
 * Esta classe e a ponte, e nao a politica: ela abre o caminho e manda bytes.
 * Quem monta haptica e o `DualSenseHaptics`; quem varre cores e a sonda aqui
 * embaixo, que existe para diagnostico dentro do headset.
 */
class DualSenseHid(private val context: Context, private val nomeAlvo: String? = null)
{
	companion object
	{
		// BluetoothProfile.HID_HOST, que e @SystemApi e nao esta no SDK.
		private const val HID_HOST = 4

		private const val REPORT_SIZE = 78
		private const val CRC_OFFSET = 74

		// Posicoes do relatorio 0x31, conferidas contra o driver do kernel.
		private const val TAG = 2
		private const val VALID0 = 3
		private const val VALID1 = 4

		// O bloco de audio do relatorio 0x31. Fica onde a struct do kernel diz
		// `reserved[4]`, entre os motores e o LED do microfone -- reservado para
		// o driver do Linux, que nao usa audio, e nao para o controle.
		private const val AUDIO_FONE = 7
		private const val AUDIO_ALTOFALANTE = 8
		private const val AUDIO_MIC = 9
		private const val AUDIO_CONTROLE = 10

		// O resto da SetStateData, nas posicoes do relatorio 0x31.
		//
		// A struct documentada conta a partir do inicio do conteudo, que no 0x31
		// e o byte 3 -- e com esse deslocamento ela encaixa exatamente na
		// `dualsense_output_report_bt` do kernel, campo por campo, ate a cor da
		// lightbar. Duas descricoes independentes que fecham nao costumam estar
		// as duas erradas.
		private const val SILENCIAMENTO = 12      // power save e mute, por bloco
		// Os dois gatilhos, onze bytes cada (modo e dez de parametro), direito
		// primeiro: 10 e 21 da struct comum, mais os tres do cabecalho BT.
		private const val GATILHOS = 13
		private const val V0_GATILHO_DIREITO = 0x04
		private const val V0_GATILHO_ESQUERDO = 0x08
		private const val V0_SELECAO_HAPTICA = 0x02
		private const val V2_VIBRACAO_COMPATIVEL = 0x04
		private const val MOTOR_DIREITO = 5
		private const val MOTOR_ESQUERDO = 6
		private const val POTENCIA_MOTORES = 39   // reducao em passos de 12,5%
		private const val FILTRO_HAPTICO = 42     // passa-baixa na bobina

		// valid_flag1: as licencas para escrever os campos acima.
		private const val V1_AUDIO_MUTE = 0x02
		private const val V1_FILTRO_HAPTICO = 0x20
		private const val V1_POTENCIA_MOTORES = 0x40
		private const val VALID2 = 41
		private const val LIGHTBAR_SETUP = 44
		private const val LED_BRILHO = 45
		private const val LEDS_JOGADOR = 46
		private const val COR = 47

		private const val ROTULO_SAIDA: Byte = 0x10
		private const val V1_LIGHTBAR = 0x04
		private const val V1_JOGADOR = 0x10
		private const val V2_LIGHTBAR_SETUP = 0x02
		private const val V2_BRILHO_LEDS = 0x01
		// O que o driver do kernel manda ao conectar para a barra deixar de ser
		// do firmware: "light out", o bit 1 do lightbar_setup.
		private const val SETUP_APAGAR_FIRMWARE = 0x02
		// valid_flag1 bit 3: "release LEDs", tambem do driver do kernel.
		private const val V1_LIBERAR_LEDS = 0x08
		private const val MODO_SIMPLES = 0
		private const val MODO_LIBERAR = 1

		private const val CADENCIA_MS = 50L

		// Intervalo minimo entre duas procuras pelo controle depois de uma
		// recusa. Quem chama e o laco da haptica, noventa e tantas vezes por
		// segundo, e cada procura e uma chamada ao servico de Bluetooth.
		private const val PROCURA_MS = 1_000L
		private const val COR_MS = 1200L

		private val HEX = "0123456789ABCDEF".toCharArray()

		/**
		 * A lista inteira de isencoes do app. Inteira porque a chamada
		 * SUBSTITUI a lista, e nao soma: o P5MApp poe a mesma na abertura, e
		 * esta ponte a repete sem apagar as tres de entrada que o menu usa para
		 * saber em que tela chega cada evento (MenuController.displayOf).
		 */
		val ISENCOES = arrayOf(
			"Landroid/bluetooth/BluetoothHidHost;",
			"Landroid/bluetooth/BluetoothAdapter;",
			"Landroid/bluetooth/BluetoothDevice;")

		fun disponivel(context: Context): Boolean =
			context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
				PackageManager.PERMISSION_GRANTED
	}

	private val linhas = StringBuilder()
	// Os dois volateis porque o listener do perfil os reescreve na thread
	// principal enquanto a thread da haptica ou do keepalive os le: sem isso a
	// queda do perfil podia nao ser vista, e o envio seguia no proxy morto.
	@Volatile private var proxy: BluetoothProfile? = null
	@Volatile private var alvo: BluetoothDevice? = null
	private var ultimaProcura = 0L
	private var procurasVazias = 0
	private var sendDataHex: Method? = null
	private var setReportHex: Method? = null
	@Volatile private var pronto = false

	private fun anotar(linha: String)
	{
		synchronized(linhas) { linhas.append(linha).append('\n') }
		Trace.log(context, "DSHID: $linha")
	}

	fun texto(): String = synchronized(linhas) { linhas.toString() }

	/**
	 * Abre a ponte e devolve se da para mandar relatorio.
	 *
	 * **Bloqueia, e por isso nao pode ser chamada na thread principal**: o proxy
	 * do perfil chega por callback nela, e esperar por ele de dentro dela seria
	 * esperar por si mesmo. Quem chama e a thread de entrega, que ja e propria.
	 */
	fun conectar(): Boolean
	{
		if(pronto)
			return true

		synchronized(linhas) { linhas.setLength(0) }

		if(!disponivel(context))
		{
			anotar("BLUETOOTH_CONNECT not granted")
			return false
		}
		if(!furarListaDeBloqueio())
			return false

		val adapter = BluetoothAdapter.getDefaultAdapter()
		if(adapter == null || !adapter.isEnabled)
		{
			anotar("Bluetooth adapter absent or off")
			return false
		}

		val chegou = CountDownLatch(1)
		// Quem desistiu de esperar (prazo ou interrupt) nao quer mais este
		// proxy: chegando tarde, ele e fechado ali mesmo, em vez de se gravar
		// por cima de um fechar() ja feito ou de uma segunda tentativa.
		val abandonado = java.util.concurrent.atomic.AtomicBoolean(false)
		val listener = object: BluetoothProfile.ServiceListener
		{
			override fun onServiceConnected(profile: Int, p: BluetoothProfile)
			{
				try
				{
					if(abandonado.get())
					{
						anotar("HID host proxy arrived after the wait was given up; closing it")
						runCatching { BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(profile, p) }
						return
					}
					proxy = p
					anotar("HID host proxy: ${p.javaClass.name}")
					resolverMetodos(p)
					alvo = escolherControle(p)
				}
				catch(e: Throwable)
				{
					// Na thread principal: uma exceção aqui derrubava o app, e sem
					// o countDown a ponte esperava o prazo inteiro por nada.
					anotar("HID host proxy setup threw: ${e.javaClass.simpleName}: ${e.message}")
				}
				finally
				{
					chegou.countDown()
				}
			}

			override fun onServiceDisconnected(profile: Int)
			{
				proxy = null
				pronto = false
			}
		}

		val pedido = try
		{
			adapter.getProfileProxy(context, listener, HID_HOST)
		}
		catch(e: Throwable)
		{
			anotar("getProfileProxy(HID_HOST) threw: ${e.javaClass.simpleName}: ${e.message}")
			false
		}
		if(!pedido)
		{
			anotar("getProfileProxy(HID_HOST) refused")
			return false
		}

		val aTempo = try
		{
			chegou.await(4, TimeUnit.SECONDS)
		}
		catch(e: InterruptedException)
		{
			abandonado.set(true)
			throw e
		}
		if(!aTempo)
		{
			abandonado.set(true)
			anotar("HID host proxy never arrived")
			return false
		}

		val p = proxy
		val a = alvo
		if(p == null || a == null)
		{
			anotar(if(nomeAlvo == null) "No DualSense among the connected HID devices"
				else "'$nomeAlvo' is not among the connected HID devices")
			return false
		}
		if(sendDataHex == null && setReportHex == null)
		{
			anotar("No usable transport on this build")
			return false
		}

		// Sem ler a calibragem: e o experimento da dev.179. No diario da dev.178
		// o atraso da vibracao existia quando esta leitura acontecia (controle
		// ligado antes da sessao) e sumia quando nao acontecia (controle
		// religado no meio dela, com a vibracao perfeita ate o fim). O driver
		// do kernel ja le a calibragem ao conectar, e le-la de novo por aqui
		// deixa o enlace lento e a vibracao atrasada ate o controle ser
		// religado (dev.179, dev.202-206). Nunca ler.
		pronto = true
		return true
	}

	/**
	 * Manda um relatorio ja montado. Devolve se a chamada foi aceita.
	 *
	 * Aceita nao e o mesmo que entregue: o retorno diz que o relatorio entrou na
	 * fila do Bluetooth, e nada mais. Foi essa distincao que sustentou tres
	 * builds de log limpo com o controle apagado -- quem responde de verdade e o
	 * controle, olhando.
	 */
	fun mandar(relatorio: ByteArray): Boolean
	{
		val p = proxy ?: return false
		val a = alvo ?: return false
		val metodo = sendDataHex ?: setReportHex ?: return false
		return try
		{
			synchronized(DualSenseOutputSequence)
			{
				// Numerar e enviar ficam na mesma ordem mesmo na curta troca em
				// que a thread da sessao anterior ainda esta encerrando.
				DualSenseOutputSequence.preparar(relatorio)
				val hex = paraHex(relatorio)
				val resposta = if(metodo === sendDataHex)
					metodo.invoke(p, a, hex)
				else
					metodo.invoke(p, a, 2.toByte(), hex)
				resposta == true
			}
		}
		catch(e: Throwable)
		{
			false
		}
	}

	/**
	 * Procura o controle de novo quando um envio foi recusado com a ponte de pe.
	 *
	 * Existe por causa do diario da dev.177. Desligar o Bluetooth do Quest
	 * derruba o controle junto; ao religar, o sistema reconecta o proxy do
	 * perfil sozinho e o listener refaz a escolha -- mas isso aconteceu 3 s
	 * antes de o controle voltar, achou zero dispositivos, e ninguem procurou
	 * outra vez. A sessao ficou com a vibracao morta ate o fim.
	 *
	 * Nao rele a calibragem: o controle religado no meio da sessao da dev.178
	 * tocou perfeito sem ela.
	 */
	/**
	 * Se o proxy do perfil ainda existe. Quando o sistema o derruba
	 * (`onServiceDisconnected`), `recuperar` nao tem com o que procurar, e so
	 * `fechar` seguido de `conectar` reabre a ponte.
	 */
	val ponteAberta: Boolean get() = proxy != null

	fun recuperar(): Boolean
	{
		val p = proxy ?: return false
		val agora = System.currentTimeMillis()
		if(agora - ultimaProcura < PROCURA_MS)
			return false
		ultimaProcura = agora

		// A procura silenciosa vem primeiro: `escolherControle` anota a lista
		// inteira, e uma vez por segundo com o controle desligado entulharia o
		// diario. Anota so a primeira procura vazia e a que encontrar.
		val conectados = try { p.connectedDevices } catch(e: Throwable) { emptyList<BluetoothDevice>() }
		if(conectados.isEmpty())
		{
			if(procurasVazias++ == 0)
				anotar("Send refused and no HID device connected; looking again every second")
			return false
		}
		val a = escolherControle(p) ?: return false
		alvo = a
		anotar("${nomeAlvo ?: "DualSense"} found again after $procurasVazias empty look(s)")
		procurasVazias = 0
		return true
	}

	fun fechar()
	{
		val p = proxy ?: return
		proxy = null
		alvo = null
		pronto = false
		try
		{
			BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(HID_HOST, p)
		}
		catch(e: Throwable)
		{
			anotar("closeProfileProxy threw: ${e.javaClass.simpleName}")
		}
	}

	/**
	 * Tira as tres classes de Bluetooth da lista de bloqueio de API escondida.
	 *
	 * A isencao e nominal de proposito. Uma isencao aberta -- "L", que libera
	 * tudo -- resolveria a mesma coisa e deixaria o app inteiro sem a rede de
	 * protecao que a lista e, inclusive nos caminhos que nao tem nada a ver com
	 * isto aqui.
	 */
	private fun furarListaDeBloqueio(): Boolean = try
	{
		HiddenApiBypass.addHiddenApiExemptions(*ISENCOES)
		anotar("Hidden API exemptions installed")
		true
	}
	catch(e: Throwable)
	{
		anotar("Hidden API exemptions failed: ${e.javaClass.simpleName}: ${e.message}")
		false
	}

	/**
	 * Procura as formas de mandar um relatorio que esta build da Horizon tem.
	 *
	 * A assinatura mudou de versao para versao do AOSP -- o relatorio ja foi
	 * String hexadecimal e ja foi ByteArray. Nesta build so as hexadecimais
	 * existem, mas procurar as duas continua valendo: uma atualizacao do sistema
	 * pode trocar isso, e a alternativa e descobrir por build queimada.
	 */
	private fun resolverMetodos(p: BluetoothProfile)
	{
		val classes = mutableListOf<Class<*>>(p.javaClass)
		try
		{
			classes.add(Class.forName("android.bluetooth.BluetoothHidHost"))
		}
		catch(e: Throwable)
		{
			anotar("android.bluetooth.BluetoothHidHost not found by name")
		}

		sendDataHex = procurar(classes, "sendData") { t ->
			t.size == 2 && BluetoothDevice::class.java.isAssignableFrom(t[0]) &&
				t[1] == String::class.java
		}
		setReportHex = procurar(classes, "setReport") { t ->
			t.size == 3 && BluetoothDevice::class.java.isAssignableFrom(t[0]) &&
				t[2] == String::class.java
		}

		anotar("sendData(hex)=${sendDataHex != null} setReport(hex)=${setReportHex != null}")
	}

	private fun procurar(classes: List<Class<*>>, nome: String,
		combina: (Array<Class<*>>) -> Boolean): Method?
	{
		for(clazz in classes)
		{
			var atual: Class<*>? = clazz
			while(atual != null)
			{
				val achado = try
				{
					atual.declaredMethods.firstOrNull { it.name == nome && combina(it.parameterTypes) }
				}
				catch(e: Throwable)
				{
					null
				}
				if(achado != null)
				{
					achado.isAccessible = true
					return achado
				}
				atual = atual.superclass
			}
		}
		return null
	}

	/**
	 * O DualSense entre os dispositivos HID conectados.
	 *
	 * O nome anunciado varia com o firmware e com o pareamento, entao a busca e
	 * por qualquer um dos dois nomes conhecidos, e o primeiro que casar vence.
	 */
	private fun escolherControle(p: BluetoothProfile): BluetoothDevice?
	{
		val conectados = try
		{
			p.connectedDevices
		}
		catch(e: Throwable)
		{
			anotar("getConnectedDevices threw: ${e.javaClass.simpleName}: ${e.message}")
			return null
		}

		anotar("Connected HID devices: ${conectados.size}")
		for(d in conectados)
			anotar("  device: ${nomeMinusculo(d).ifEmpty { "?" }}")

		// Com um nome pedido, so ele serve: quem pede nome e a vibracao de um
		// controle que nao e DualSense, e cair no primeiro da lista mandaria o
		// relatorio de um fabricante ao controle de outro.
		if(nomeAlvo != null)
			return conectados.firstOrNull { nomeMinusculo(it) == nomeAlvo.lowercase() }
		return conectados.firstOrNull {
			val nome = nomeMinusculo(it)
			ehNomeDeDualSense(nome)
		} ?: conectados.firstOrNull()
	}

	// "Wireless Controller" por inteiro, e nao contido: o controle do Xbox se
	// anuncia como "Xbox Wireless Controller", e casar com ele mandaria
	// relatorio de DualSense a um controle que nao e.
	private fun ehNomeDeDualSense(nome: String): Boolean =
		nome.contains("dualsense") || nome == "wireless controller"

	private fun nomeMinusculo(device: BluetoothDevice): String =
		(try { device.name } catch(e: Throwable) { null } ?: "").lowercase()

	/**
	 * Arma a rota de audio do controle, que e o que a haptica crua precisa.
	 *
	 * A bobina do DualSense tem dois modos. Num deles ela imita motor comum e
	 * obedece a intensidade que o relatorio manda; no outro ela toca a trilha de
	 * audio que chega pelo relatorio 0x32. Sao exclusivos, e o controle nao
	 * volta sozinho de um para o outro: quem manda intensidade o poe em modo
	 * motor, e a partir dali a trilha e recebida e descartada em silencio.
	 *
	 * Era o que faltava. O `Rumble` daqui manda uma buzina de teste ao abrir a
	 * sessao, pelo caminho de vibracao do proprio Android -- e essa buzina, alem
	 * de ser sentida, trocava o modo do controle bem na hora em que a entrega
	 * crua comecava. O diario da dev.146 mostra as duas coisas na mesma linha do
	 * tempo, e o que se sentiu foi so a buzina.
	 *
	 * Quais bits de `valid_flag0` abrem a rota e o que esta em teste: as fontes
	 * publicas discordam entre si, e uma delas chama de "use rumble not haptics"
	 * exatamente o bit que o driver do kernel chama de "haptics select". Por isso
	 * o valor e parametro, e nao constante, e a bancada aqui embaixo experimenta
	 * as variantes numa passada so.
	 */
	fun armarAudio(validFlag0: Int, validFlag1: Int = 0x00): Boolean
	{
		val r = ByteArray(REPORT_SIZE)
		r[0] = 0x31
		r[TAG] = ROTULO_SAIDA
		r[VALID0] = validFlag0.toByte()
		r[VALID1] = validFlag1.toByte()
		r[AUDIO_FONE] = 0x00
		r[AUDIO_ALTOFALANTE] = 0x00
		r[AUDIO_MIC] = 0x00
		r[AUDIO_CONTROLE] = 0x05
		assinar(r)
		return mandar(r)
	}

	/**
	 * Poe a bobina em potencia cheia e tira o filtro da frente dela.
	 *
	 * Tres campos que nunca escrevemos, e que estavam com o valor que o ultimo
	 * dono da conexao tivesse deixado -- o console, o Android, ou o pareamento.
	 * Enquanto o sinal saia errado nao dava para notar; agora que ele sai em
	 * escala cheia e ainda parece fraco, sao eles que sobram.
	 *
	 *  - **Reducao de potencia dos motores** (byte 39). Um nibble por motor, em
	 *    passos de 12,5%, ate sete passos. Se estiver em 7, a bobina esta
	 *    recebendo 12,5% do que mandamos -- e nada do que se faca do lado de ca
	 *    conserta isso.
	 *  - **Filtro passa-baixa da haptica** (byte 42). Um bit. Ligado, ele come o
	 *    que da nitidez a um estalo e deixa so o corpo grave -- que descreve bem
	 *    "anemico, sem definicao".
	 *  - **Economia e mudo da haptica** (byte 12, dois bits do mesmo bloco).
	 *
	 * Os tres so sao aceitos com a licenca correspondente em `valid_flag1`, e e
	 * por isso que escrever o valor sem a licenca nao faz nada -- o controle
	 * ignora o campo inteiro em silencio.
	 *
	 * Nao mexe nos dois bits de emulacao de motor do `valid_flag0`: ligar
	 * qualquer um deles tira a bobina do modo de audio, que e onde a trilha
	 * toca.
	 */
	fun potenciaCheia(): Boolean
	{
		val r = ByteArray(REPORT_SIZE)
		r[0] = 0x31
		r[TAG] = ROTULO_SAIDA
		r[VALID0] = 0x00
		r[VALID1] = (V1_AUDIO_MUTE or V1_FILTRO_HAPTICO or V1_POTENCIA_MOTORES).toByte()
		r[SILENCIAMENTO] = 0x00     // nem economia de energia nem mudo
		r[POTENCIA_MOTORES] = 0x00  // zero passos de reducao nos dois motores
		// dev.208: passa-baixa LIGADO. Desligado ele deixava passar o degrau de
		// cada amostra de 8 bits e os saltos da onda -- a vibracao "aspera,
		// ruidosa" que o dono comparou com a do console, que e arredondada.
		r[FILTRO_HAPTICO] = 0x01
		assinar(r)
		return mandar(r)
	}

	/**
	 * Se o controle escolhido e mesmo um DualSense, e nao o primeiro HID que
	 * sobrou. `escolherControle` cai no primeiro da lista quando nenhum nome
	 * casa, o que serve a sonda e nao serve a quem vai mandar relatorio sem
	 * ninguem olhando: um 0x31 num 8BitDo nao e inocuo, e so desconhecido.
	 */
	fun alvoEDualSense(): Boolean
	{
		val a = alvo ?: return false
		val nome = nomeMinusculo(a)
		return ehNomeDeDualSense(nome)
	}

	/**
	 * Um 0x31 com o estado que o console pediu para o controle.
	 *
	 * Cada parte so entra com a sua licenca, e as que ficam de fora nao mudam
	 * no controle: `gatilhos` nulo nao toca nos gatilhos, `cor` negativa nao
	 * toca na barra, e assim por diante. E essa regra que deixa o espelho
	 * reescrever a barra periodicamente sem reiniciar o efeito do gatilho.
	 *
	 * `gatilhos` sao 22 bytes, direito e depois esquerdo; `cor` e 0xRRGGBB;
	 * `ledsJogador` e o padrao dos cinco LEDs; `reducao` e o byte 39;
	 * `brilho` e o dos LEDs de jogador, 0 claro, 1 medio, 2 escuro.
	 */
	fun espelhar(gatilhos: ByteArray?, cor: Int, ledsJogador: Int, reducao: Int,
		brilho: Int = -1, motores: Int = -1): Boolean
	{
		val r = ByteArray(REPORT_SIZE)
		r[0] = 0x31
		r[TAG] = ROTULO_SAIDA
		var v0 = 0
		var v1 = 0
		var v2 = 0
		// Vibracao classica (os dois motores emulados pelas bobinas), no mesmo
		// relatorio que barra e gatilhos. `motores` e (esquerdo shl 8) or
		// direito, 0-255 cada. HAPTICS_SELECT em valid0 e a vibracao
		// compativel v2 em valid2, como o SDL e o kernel fazem com firmware
		// novo. Um escritor so: com o driver do Android mandando a vibracao e
		// o espelho mandando a barra, um desfazia o outro (dev.207).
		if(motores >= 0)
		{
			v0 = v0 or V0_SELECAO_HAPTICA
			v2 = v2 or V2_VIBRACAO_COMPATIVEL
			r[MOTOR_DIREITO] = (motores and 0xFF).toByte()
			r[MOTOR_ESQUERDO] = ((motores shr 8) and 0xFF).toByte()
		}
		if(gatilhos != null && gatilhos.size == 22)
		{
			v0 = v0 or V0_GATILHO_DIREITO or V0_GATILHO_ESQUERDO
			System.arraycopy(gatilhos, 0, r, GATILHOS, 22)
		}
		if(cor >= 0)
		{
			v1 = v1 or V1_LIGHTBAR
			r[COR] = ((cor shr 16) and 0xFF).toByte()
			r[COR + 1] = ((cor shr 8) and 0xFF).toByte()
			r[COR + 2] = (cor and 0xFF).toByte()
		}
		if(ledsJogador >= 0)
		{
			v1 = v1 or V1_JOGADOR
			r[LEDS_JOGADOR] = ledsJogador.toByte()
		}
		if(reducao >= 0)
		{
			v1 = v1 or V1_POTENCIA_MOTORES
			r[POTENCIA_MOTORES] = reducao.toByte()
		}
		if(brilho >= 0)
		{
			v2 = v2 or V2_BRILHO_LEDS
			r[LED_BRILHO] = brilho.coerceIn(0, 2).toByte()
		}
		r[VALID0] = v0.toByte()
		r[VALID1] = v1.toByte()
		r[VALID2] = v2.toByte()
		assinar(r)
		return mandar(r)
	}

	/**
	 * Um 0x31 que nao muda nada no controle (as tres valid_flag zeradas). Serve
	 * so para haver trafego no enlace: ver [EnlaceAtivo].
	 */
	fun manterAcordado(): Boolean
	{
		val r = ByteArray(REPORT_SIZE)
		r[0] = 0x31
		r[TAG] = ROTULO_SAIDA
		assinar(r)
		return mandar(r)
	}

	/**
	 * Sonda de diagnostico: varre a lightbar por vermelho, verde e azul.
	 *
	 * Existe para responder, dentro do headset e sem cabo, se o caminho inteiro
	 * esta de pe. O alvo e a barra e nao a vibracao porque a vibracao ja chega
	 * pelo caminho do `Rumble`, e acertar por ela seria acertar pelo motivo
	 * errado.
	 *
	 * **Bloqueia.** Quem chama roda numa thread propria.
	 */
	fun sondar(): String
	{
		if(!conectar())
			return texto()
		if(!alvoEDualSense())
		{
			fechar()
			return "The controller on the HID bridge is not a DualSense; nothing was sent.\n" + texto()
		}

		// Quatro passadas, uma por hipotese, na ordem em que nao se atrapalham:
		// a leitura da calibragem fica por ultimo porque muda o estado do
		// controle para as seguintes. O dono conta qual passada acendeu.
		//
		// A dev.201 mostrou o console mandando cor, o app mandando 190 relatorios
		// por 10 s sem recusa, e a barra parada -- e esta mesma sonda, que
		// acendeu na dev.141, tambem parada. O que mudou desde la foi a leitura
		// da calibragem, que saiu na dev.179 por atrasar a haptica.
		anotar("Pass 1: plain (what the session sends)")
		passada("plain", MODO_SIMPLES)
		anotar("Pass 2: lightbar setup 'light out' first (what the kernel driver sends at connect)")
		mandar(relatorioDeSetup(SETUP_APAGAR_FIRMWARE))
		dormir(300)
		passada("after light-out", MODO_SIMPLES)
		// A passada com a licenca "release LEDs" (valid_flag1 0x08) saiu na
		// dev.206: na dev.202 ela foi a unica que deixou a barra apagada --
		// a licenca devolve os LEDs, e nao os acende. A sessao nunca a manda.
		// A quarta passada, depois de ler a calibragem, saiu na dev.206: a
		// leitura deixa o enlace do controle no ritmo lento (~11 ms) ate ele
		// ser desligado, e a haptica atrasa em todas as sessoes seguintes. Foi
		// o que as dev.202-204 herdaram desta sonda.
		return texto()
	}

	private fun passada(nome: String, modo: Int)
	{
		for((cor, nomeCor) in listOf(Triple(255, 0, 0) to "red",
				Triple(0, 255, 0) to "green", Triple(0, 0, 255) to "blue"))
			sustentar("$nome $nomeCor", cor.first, cor.second, cor.third, modo)
		dormir(1200)
	}

	/**
	 * Tira a barra de luz do firmware, como o driver do kernel faz ao conectar.
	 *
	 * Na dev.201 os LEDs de jogador e o brilho obedeceram e a barra nao, no
	 * mesmo relatorio: o controle aceita o 0x31, e o que recusa e so a barra.
	 * O suspeito e o firmware ainda ser o dono dela -- a animacao azul do
	 * pareamento --, e "light out" e o gesto que passa o controle para quem
	 * manda. Uma vez por sessao, antes da primeira cor.
	 */
	fun soltarBarraDoFirmware(): Boolean = mandar(relatorioDeSetup(SETUP_APAGAR_FIRMWARE))

	/** So o lightbar_setup, com a licenca em valid_flag2. */
	private fun relatorioDeSetup(setup: Int): ByteArray
	{
		val r = ByteArray(REPORT_SIZE)
		r[0] = 0x31
		r[TAG] = ROTULO_SAIDA
		r[VALID2] = V2_LIGHTBAR_SETUP.toByte()
		r[LIGHTBAR_SETUP] = setup.toByte()
		assinar(r)
		return r
	}

	/**
	 * Segura uma cor por um tempo visivel, reescrevendo na cadencia do controle.
	 *
	 * Uma unica falha nao interrompe a passada: num fluxo de vinte e quatro
	 * relatorios o que interessa e quantos passaram, e nao qual foi o primeiro a
	 * cair.
	 */
	private fun sustentar(nome: String, vermelho: Int, verde: Int, azul: Int,
		modo: Int)
	{
		var passaram = 0
		var falharam = 0
		val fim = System.currentTimeMillis() + COR_MS
		while(System.currentTimeMillis() < fim)
		{
			if(mandar(relatorioDeCor(vermelho, verde, azul, modo)))
				passaram++
			else
				falharam++
			dormir(CADENCIA_MS)
		}
		anotar("$nome: $passaram sent, $falharam failed")
	}

	private fun dormir(ms: Long)
	{
		try { Thread.sleep(ms) } catch(e: InterruptedException) { }
	}

	/**
	 * Relatorio 0x31 com a lightbar numa cor e o LED de jogador central aceso.
	 *
	 * Os campos que ficam em zero ficam de proposito: `valid_flag0` em zero diz
	 * que este relatorio nao mexe em vibracao, e nao mexer e o certo aqui --
	 * quem cuida disso e o `Rumble`, e sobrescrever de dois lugares daria um
	 * defeito dificil de ler depois.
	 */
	private fun relatorioDeCor(vermelho: Int, verde: Int, azul: Int,
		modo: Int): ByteArray
	{
		val r = ByteArray(REPORT_SIZE)
		r[0] = 0x31
		r[TAG] = ROTULO_SAIDA
		r[VALID1] = (V1_LIGHTBAR or V1_JOGADOR or
				(if(modo == MODO_LIBERAR) V1_LIBERAR_LEDS else 0)).toByte()

		r[LED_BRILHO] = 0x00
		r[LEDS_JOGADOR] = 0x04
		r[COR] = vermelho.toByte()
		r[COR + 1] = verde.toByte()
		r[COR + 2] = azul.toByte()
		assinar(r)
		return r
	}

	/**
	 * CRC-32 sobre 0xA2 seguido do conteudo, em little-endian.
	 *
	 * O 0xA2 nao esta no relatorio: e o rotulo do canal HID, que o controle
	 * inclui na conta e o transporte nao carrega. Sem ele o CRC fecha aqui e
	 * falha la, e o controle descarta em silencio -- o modo de falha mais caro
	 * possivel, porque parece que o relatorio nem chegou.
	 */
	private fun assinar(r: ByteArray)
	{
		val crc = CRC32()
		crc.update(0xA2)
		crc.update(r, 0, CRC_OFFSET)
		val v = crc.value
		r[CRC_OFFSET] = (v and 0xFF).toByte()
		r[CRC_OFFSET + 1] = ((v shr 8) and 0xFF).toByte()
		r[CRC_OFFSET + 2] = ((v shr 16) and 0xFF).toByte()
		r[CRC_OFFSET + 3] = ((v shr 24) and 0xFF).toByte()
	}

	private fun paraHex(bytes: ByteArray): String
	{
		val sb = StringBuilder(bytes.size * 2)
		for(b in bytes)
		{
			val v = b.toInt() and 0xFF
			sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
		}
		return sb.toString()
	}
}
