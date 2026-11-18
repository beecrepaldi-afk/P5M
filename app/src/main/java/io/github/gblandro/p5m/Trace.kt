// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Diário do app em arquivo, que sobrevive ao buffer do logcat.
 *
 * O logcat é um buffer circular pequeno, e o chiaki escreve centenas de linhas
 * por segundo em sessão — retransmissão, heartbeat, dump hexa. Na prática, o
 * que acontece durante o jogo já saiu do buffer quando a tela de diagnóstico
 * abre depois: um teste inteiro de input foi perdido exatamente assim, com os
 * botões registrados e o registro descartado antes de alguém poder ler.
 *
 * Aqui as linhas que importam ficam em disco.
 *
 * ## Como o arquivo é aparado
 *
 * A primeira versão aparava pelo começo e guardava o fim, com o argumento de
 * que "o fim é o que interessa". Estava errado, e o uso mostrou por quê: as
 * linhas que respondem quase toda pergunta — versão, extensões do runtime,
 * contadores, taxa do painel, rota de vibração — acontecem **uma vez, na
 * abertura**. Aparar pelo começo descarta exatamente elas, e sobra o meio de
 * uma sessão sem nada que diga em que condições ela rodou. O efeito prático foi
 * pior ainda: passou a valer a pena fazer só testes curtos, por medo de perder
 * a abertura — o diário estava moldando o teste em vez de registrá-lo.
 *
 * Agora o corte é **por sessão inteira**. Cada abertura de stream escreve um
 * marcador, e o aparo descarta as sessões mais antigas por completo, cortando
 * sempre num marcador. O que sobra são sessões inteiras, cada uma com sua
 * abertura.
 *
 * Se uma única sessão passar do teto sozinha, aí não há sessão velha a
 * descartar, e o corte guarda a abertura dela mais o fim, jogando fora o meio.
 * A abertura sobrevive em qualquer caso.
 */
object Trace
{
	private const val FILE_NAME = "p5m-trace.txt"
	private const val FILE_ANTERIOR = "p5m-trace-anterior.txt"
	// Tetos folgados de propósito. O arquivo vive no filesDir, meio mega não é
	// nada num headset de 128 GB, e cada byte a mais é um minuto a mais de
	// sessão que cabe sem aparar nada.
	private const val MAX_BYTES = 512 * 1024
	private const val KEEP_BYTES = 384 * 1024
	// Quanto da abertura preservar quando uma sessão sozinha estoura o teto.
	private const val HEAD_BYTES = 48 * 1024
	private const val PREFS_NAME = "p5m-diario"
	private const val KEY_VERSAO = "versao-do-diario"
	private const val SESSION_MARK = "=== session "
	private const val TAG = "P5MVR"
	// As linhas do [log] vao ao logcat com outra etiqueta (dev.220): com a
	// mesma, a copia do logcat trazia cada uma de novo, e o diario tinha tudo
	// em dobro. Elas ja estao no arquivo; a copia fica so para o nativo.
	private const val TAG_DO_APP = "P5M"
	private val HORA = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

	/**
	 * Diario detalhado (dev.220): as linhas periodicas e de uso intenso, que
	 * servem para investigar e nao para contar o que aconteceu. Ligado nas
	 * builds -dev e desligado na publica; o Diagnostico tem o interruptor, para
	 * um testador ligar quando pedirem.
	 */
	@Volatile var detalhado = false
		private set
	private const val KEY_DETALHADO = "detailed-diary"
	private val SO_DETALHADO = arrayOf("Video 10s", "Bench 10s", "Haptics 10s", "Haptics timing",
		"Input timing", "Controller motion 10s", "Controller mirror 10s", "Rumble events 10s",
		"Health 10s", "Neural depth 10s", "P5M: bitrate 10s", "[takion-v20] 10s", "[reorder] 10s",
		"[packets] 10s", "[frames] 10s", "State -> console", "Key received", "Unmapped gamepad key")

	/** Le o interruptor; sem escolha salva, vale o tipo da build. */
	fun iniciarDetalhe(context: Context)
	{
		val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		val versao = try
		{
			context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
		}
		catch(e: Exception) { "" }
		detalhado = prefs.getBoolean(KEY_DETALHADO, versao.contains("-dev"))
	}

	fun definirDetalhe(context: Context, ligado: Boolean)
	{
		detalhado = ligado
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
			.putBoolean(KEY_DETALHADO, ligado).apply()
	}

	private fun soDetalhe(linha: String) = !detalhado && SO_DETALHADO.any { linha.contains(it) }

	private val lock = Any()
	private var lastCapturedStamp: String? = null

	/**
	 * A escrita em disco, numa thread só dela.
	 *
	 * O [log] é chamado da thread principal (cada botão apertado, com o diário
	 * detalhado), da háptica e da entrega de áudio. Escrever ali mesmo era abrir
	 * e fechar o arquivo, passar doze expressões regulares e, a cada 128 KB,
	 * reler e regravar meio mega -- tudo com a trava do diário presa, no meio do
	 * caminho de um botão até o console. Aqui a chamada só enfileira a linha,
	 * já com a hora dela; a ordem é a da fila.
	 *
	 * O que ainda está na fila quando o processo morre de queda nativa se
	 * perde: são milissegundos, e o [flush] cobre a queda Java e quem vai ler.
	 */
	private val escritor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
		Thread(r, "p5m-diary-writer").apply { isDaemon = true }
	}

	/**
	 * Espera a fila esvaziar (até um segundo). Para quem vai ler o arquivo ou
	 * mexer nele por fora, e para o fim do processo numa exceção.
	 */
	fun flush()
	{
		if(Thread.currentThread().name == "p5m-diary-writer")
			return
		try { escritor.submit {}.get(1, java.util.concurrent.TimeUnit.SECONDS) }
		catch(e: Exception) { Log.w(TAG, "Diary flush: ${e.javaClass.simpleName}") }
	}

	/**
	 * Pares de "o que esconder" e "o que pôr no lugar".
	 *
	 * O nome do campo fica; só o valor sai. "access_token=<redacted>" continua
	 * dizendo que houve um token ali, que é a informação útil da linha; o token
	 * em si não acrescenta nada a quem for ler o log.
	 */
	private val REDACOES: List<Pair<Regex, String>> = listOf(
		// Credenciais e identificadores, no formato chave=valor, chave: valor
		// ou "chave": "valor" -- as três formas aparecem, porque as linhas vêm
		// de JSON, de log do chiaki e das nossas.
		Regex("""(?i)\b(rp_regist_key|rp_key|regist_key|registkey|morning|access_token|refresh_token|id_token|npsso|user_credential|duid|account_?id|online_?id|psn_?token)\b(\s*["']?\s*[:=]\s*["']?)([A-Za-z0-9+/=_.\-]{6,})""")
				to "\$1\$2<redacted>",
		// O nome do console. Sai numa categoria propria porque nao e credencial
		// nenhuma e mesmo assim e o vazamento mais provavel de todos: quem
		// batiza um PS5 poe o proprio nome nele, e a linha de descoberta do
		// chiaki -- "hostName=PS5 Fulano" -- vai inteira para o diario. Foi o
		// que sobrou de pe no primeiro log depois de a redacao existir.
		Regex("""(?i)\b(host_?name|nickname|device_?name|console_?name)(\s*["']?\s*[:=]\s*["']?)([^,"'\]}\s][^,"'\]}]*)""")
				to "\$1\$2<name>",
		// O código de autorização da PSN, que viaja na URL de redirecionamento
		// e vale um token se alguém o pegar antes de expirar.
		Regex("""(?i)\bcode=[A-Za-z0-9._\-]{6,}""") to "code=<redacted>",
		Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-]{8,}""") to "Bearer <redacted>",
		// Blocos longos de hexa contínuo: é a forma que uma chave assume quando
		// o chiaki a imprime sem rótulo nenhum. Os dumps hexa dele saem
		// separados por espaço e não caem aqui.
		Regex("""\b[0-9a-fA-F]{32,}\b""") to "<hex>",
		Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b""") to "<email>",
		// IPv6 e MAC. Quatro dois-pontos no mínimo, e não três: com três, o
		// horário de cada linha do logcat (12:34:56) virava endereço, e o
		// diário saía sem hora nenhuma.
		Regex("""\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){4,}\b""") to "<addr>",
		// MAC com hífen, que a regra acima (dois-pontos) não pega.
		Regex("""\b(?:[0-9A-Fa-f]{2}-){5}[0-9A-Fa-f]{2}\b""") to "<addr>",
		// O id do console na descoberta do chiaki é o MAC dele em doze hexas
		// corridos ("detected new host with id ..."), curto demais para a regra
		// dos blocos longos.
		Regex("""(?i)\b(with id|host[_ ]?id)(\s*[:=]?\s*)[0-9A-Fa-f]{12}\b""") to "\$1\$2<id>",
		// O apelido do console, na linha do registro -- sem rótulo nenhum
		// na frente, então a regra dos nomes não o vê.
		Regex("""([:\]]\s)[^:\]\n]+?( successfully registered for Remote Play)""") to "\$1<name>\$2")

	private val IPV4 = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")

	private fun file(context: Context) = File(context.filesDir, FILE_NAME)
	private fun fileAnterior(context: Context) = File(context.filesDir, FILE_ANTERIOR)

	/** Diário da versão anterior, guardado na última troca de APK. */
	fun readAnterior(context: Context): String? =
		fileAnterior(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

	/**
	 * Registra no logcat e no arquivo.
	 *
	 * Nos dois de propósito: o logcat é imediato para quem está com cabo, e o
	 * arquivo é o que ainda existe meia hora depois.
	 */
	fun log(context: Context, message: String)
	{
		Log.i(TAG_DO_APP, message)
		if(soDetalhe(message))
			return
		val hora = synchronized(HORA) { HORA.format(java.util.Date()) }
		append(context, "$hora I/$TAG_DO_APP ${android.os.Process.myPid()}: $message")
	}

	/**
	 * Tira do texto o que identifica quem está usando o app.
	 *
	 * Existe por causa do beta. Um testador que abre o diagnóstico, toca em
	 * "Copy all" e cola o resultado num fórum está colando, sem saber, a chave
	 * de registro do console dele, o token da conta PSN e o endereço público de
	 * onde ele joga. Nada disso ajuda a responder a pergunta que ele veio
	 * fazer, e a chave de registro em particular é a credencial que autoriza
	 * um Remote Play -- é a coisa que menos se quer num post público.
	 *
	 * Aplicada na **escrita**, e não na exibição: assim vale de uma vez para a
	 * tela, para o "Copy all", para o arquivo compartilhado e para a página
	 * que o [LogServer] publica na rede. Redigir em cada saída seria quatro
	 * lugares para esquecer um.
	 *
	 * O que fica: endereços de rede local, nomes de controle, números de
	 * quadro, códigos de erro. Sem eles o diário não responderia mais nada, e
	 * um IP 192.168 não diz quem é ninguém.
	 */
	fun redact(text: String): String
	{
		var saida = text
		for((padrao, troca) in REDACOES)
			saida = padrao.replace(saida, troca)
		// IPv4 público é caso à parte: o mesmo formato serve para o roteador de
		// casa, que interessa, e para o endereço de onde a pessoa está, que
		// não. Só dá para separar olhando o valor.
		return IPV4.replace(saida) { m ->
			if(enderecoLocal(m.value)) m.value else "<ip>"
		}
	}

	/** true para as faixas privadas, o loopback e o link-local. */
	private fun enderecoLocal(ip: String): Boolean
	{
		val p = ip.split(".").map { it.toIntOrNull() ?: return false }
		if(p.size != 4 || p.any { it > 255 })
			return false
		return when
		{
			p[0] == 10 || p[0] == 127 || p[0] == 0 -> true
			p[0] == 192 && p[1] == 168 -> true
			p[0] == 172 && p[1] in 16..31 -> true
			p[0] == 169 && p[1] == 254 -> true
			p[0] >= 224 -> true
			else -> false
		}
	}

	/**
	 * Diário ao vivo (dev.242): as últimas linhas já redigidas, numeradas, para
	 * o [LogServer] entregar em `/follow` assim que são gravadas. Antes, quem
	 * acompanhava um teste esperava o dono terminar, ligar o servidor e mandar
	 * o diário; agora o computador da mesma rede lê enquanto ele joga.
	 *
	 * Só memória e só as recentes: o arquivo continua sendo o registro, isto
	 * é a janela de quem está olhando agora.
	 */
	private val vivo = ArrayDeque<String>()
	private var vivoUltima = 0L
	private val vivoTrava = Object()
	private const val VIVO_MAX = 2000

	private fun publicarAoVivo(linhas: List<String>)
	{
		synchronized(vivoTrava)
		{
			for(l in linhas)
			{
				vivo.addLast(l)
				vivoUltima++
				if(vivo.size > VIVO_MAX)
					vivo.removeFirst()
			}
			vivoTrava.notifyAll()
		}
	}

	/**
	 * As linhas depois da número [depois], esperando até [esperaMs] se não
	 * houver nenhuma. Devolve também o número da última, para a próxima
	 * chamada. Um [depois] maior que o contador (o processo reabriu) entrega
	 * tudo o que há.
	 */
	fun aoVivoDepois(depois: Long, esperaMs: Long): Pair<Long, List<String>>
	{
		synchronized(vivoTrava)
		{
			if(vivoUltima == depois && esperaMs > 0)
				vivoTrava.wait(esperaMs)
			val novas = if(depois > vivoUltima) vivo.size
				else (vivoUltima - depois).coerceAtMost(vivo.size.toLong()).toInt()
			return vivoUltima to vivo.toList().takeLast(novas)
		}
	}

	/** O número da última linha publicada, para começar a seguir dali. */
	fun aoVivoUltima(): Long = synchronized(vivoTrava) { vivoUltima }

	private fun append(context: Context, line: String)
	{
		val app = context.applicationContext ?: context
		escritor.execute {
			synchronized<Unit>(lock)
			{
				try
				{
					val f = file(app)
					val redigida = redact(line)
					f.appendText(redigida + "\n")
					publicarAoVivo(listOf(redigida))
					if(f.length() > MAX_BYTES)
						trim(f)
				}
				catch(e: Exception)
				{
					Log.w(TAG, "Failed to write to the diary: ${e.message}")
				}
			}
		}
	}

	/**
	 * Troca o conteúdo do arquivo de uma vez: grava ao lado e renomeia. Um
	 * processo morto no meio do `writeText` direto deixava o diário truncado --
	 * e o aparo acontece justamente nas sessões longas, as que mais importam.
	 */
	private fun regravar(f: File, texto: String)
	{
		val tmp = File(f.parentFile, f.name + ".tmp")
		tmp.writeText(texto)
		if(!tmp.renameTo(f))
		{
			f.writeText(texto)
			tmp.delete()
		}
	}

	/**
	 * Marca o começo de uma sessão.
	 *
	 * É por estes marcadores que o aparo corta: sem eles ele teria de escolher
	 * um byte qualquer, e byte qualquer cai no meio da abertura de alguma
	 * sessão tanto quanto em qualquer outro lugar.
	 */
	fun beginSession(context: Context, label: String)
	{
		append(context, "")
		append(context, "$SESSION_MARK$label ===")
	}

	/**
	 * Apara descartando sessões inteiras, das mais antigas para as mais novas.
	 *
	 * Nunca corta no meio de uma: o arquivo resultante começa sempre num
	 * marcador, e portanto toda sessão que sobrou está inteira, com a abertura
	 * que diz em que condições ela rodou.
	 */
	private fun trim(f: File)
	{
		val text = f.readText()
		val needle = "\n$SESSION_MARK"
		val lastMark = text.lastIndexOf(needle)

		// A sessão em curso é a que começa no último marcador. Ela é intocável:
		// é a que está sendo testada agora, e é a abertura dela que responde as
		// perguntas.
		if(lastMark >= 0 && text.length - lastMark <= KEEP_BYTES)
		{
			// Ela cabe inteira. Então dá para ser generoso e guardar também as
			// sessões anteriores que couberem — cortando na primeira que caiba
			// por completo, nunca no meio de uma.
			val cut = (text.length - KEEP_BYTES).coerceAtLeast(0)
			val mark = text.indexOf(needle, cut).let { if(it in 0..lastMark) it else lastMark }
			regravar(f, "(older sessions dropped for size)\n"
					+ text.substring(mark + 1))
			return
		}

		// Aqui ou não há marcador nenhum (diário de uma versão antiga), ou a
		// sessão em curso sozinha já passou do teto. Nos dois casos guarda a
		// abertura e o fim, e joga fora o meio: perder o meio de uma sessão
		// longa custa muito menos do que perder a abertura dela.
		val headStart = if(lastMark >= 0) lastMark + 1 else 0
		val headEnd = (headStart + HEAD_BYTES).coerceAtMost(text.length)
		val head = text.substring(headStart, headEnd).substringBeforeLast('\n', "")
		val tailStart = (text.length - (KEEP_BYTES - HEAD_BYTES)).coerceIn(headEnd, text.length)
		val tail = text.substring(tailStart).substringAfter('\n', "")
		regravar(f, head + "\n(middle of the session dropped for size; "
				+ "the opening and the end are kept)\n" + tail)
	}

	/**
	 * Copia para o arquivo o que o lado nativo escreveu no logcat.
	 *
	 * As linhas do C++ não passam por [log], e reescrever aquilo tudo para
	 * gravar em arquivo custaria mais do que vale. Uma cópia periódica do que
	 * ainda está no buffer resolve, desde que aconteça antes de o buffer girar.
	 */
	fun captureNativeLines(context: Context)
	{
		try
		{
			// Duas tags, com severidades diferentes de proposito: tudo que o
			// app registra, e do chiaki so os erros. Os info do chiaki sao
			// centenas por segundo em sessao e afogariam o arquivo; os erros
			// sao onde aparecem perda de pacote, falha de FEC e frame
			// corrompido -- o que explica imagem travando.
			val process = Runtime.getRuntime().exec(
				arrayOf("logcat", "-d", "-v", "time", "-s", "$TAG:I", "Chiaki:E"))
			val lines = BufferedReader(InputStreamReader(process.inputStream))
				.use { it.readLines() }

			// Tudo sob a trava, a marca inclusive: três threads chamam isto (o
			// tick, o Diagnóstico e o banco de ensaio), e duas lendo a mesma
			// marca gravavam as mesmas linhas duas vezes.
			synchronized<Unit>(lock)
			{
				// Só o que é mais novo que a última captura, senão cada passagem
				// duplicaria tudo que já está gravado.
				val previous = lastCapturedStamp
				val fresh = if(previous == null) lines
					else lines.filter { it.length > 18 && it.substring(0, 18) > previous }
				if(fresh.isEmpty())
					return
				lastCapturedStamp = fresh.last().take(18)
				val guardar = if(detalhado) fresh else fresh.filter { !soDetalhe(it) }
				if(guardar.isEmpty())
					return

				// Pela redação também: estas linhas vêm do chiaki, que imprime o
				// apelido do console e o id dele (o MAC) sem passar por nós.
				val f = file(context)
				val redigidas = collapseRepeats(guardar).map { redact(it) }
				f.appendText(redigidas.joinToString("\n") + "\n")
				publicarAoVivo(redigidas)
				if(f.length() > MAX_BYTES)
					trim(f)
			}
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Failed to capture native lines: ${e.message}")
		}
	}

	/**
	 * Colapsa linhas idênticas consecutivas numa só, com a contagem.
	 *
	 * Um erro dentro do loop de frame vira centenas de linhas iguais por
	 * segundo, e o arquivo tem tamanho fixo: o que sobra é o fim do despejo, e o
	 * começo da sessão — que é onde está a causa — sai pela borda. Aconteceu com
	 * 1668 cópias de "Framebuffer incompleto", que apagaram as linhas de abertura
	 * do próprio diagnóstico que as explicaria.
	 *
	 * Compara ignorando o timestamp e o PID, que mudam a cada linha e fariam
	 * repetição nenhuma parecer única.
	 */
	private fun collapseRepeats(lines: List<String>): List<String>
	{
		fun body(line: String) = line.substringAfter("): ", line)

		val out = mutableListOf<String>()
		var last: String? = null
		var count = 0

		fun flush()
		{
			val previous = last ?: return
			if(count > 1)
				out.add("$previous    [repetida ${count}x]")
			else
				out.add(previous)
		}

		for(line in lines)
		{
			if(last != null && body(line) == body(last!!))
			{
				count++
				continue
			}
			flush()
			last = line
			count = 1
		}
		flush()
		return out
	}

	fun read(context: Context): String?
	{
		flush()
		return file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
	}

	fun clear(context: Context)
	{
		flush()
		synchronized<Unit>(lock)
		{
			file(context).delete()
			lastCapturedStamp = null
		}
	}

	/**
	 * Comeca um diario novo quando o APK instalado muda.
	 *
	 * O `filesDir` sobrevive a uma instalacao por cima -- e ainda bem, porque e
	 * isso que preserva o login da PSN entre builds. O efeito colateral e que o
	 * diario atravessa versoes: depois de sideload de uma dev nova, o
	 * "Copiar tudo" trazia linhas de duas ou tres versoes misturadas, sem nada
	 * que as separasse a olho. Ja aconteceu de um sintoma de uma versao ser lido
	 * como se fosse da outra, e o tempo perdido nisso e o motivo deste metodo.
	 *
	 * Um botao resolveria se o dono lembrasse de aperta-lo. Uma vez esquecido, o
	 * diario mente -- e o unico jeito de descobrir e desconfiando dele, que e
	 * exatamente o que um diario nao pode pedir. Entao a troca de versao, que e
	 * o evento real, dispara a limpeza sozinha.
	 *
	 * Zera tambem o ultimo crash e os logs de sessao do chiaki, pelo mesmo
	 * motivo: um crash da versao passada exibido sob a versao nova e a mesma
	 * mentira, so que mais convincente.
	 *
	 * Se a leitura da versao falhar, o rotulo e o mesmo em toda execucao e nada
	 * e apagado. A falha por omissao aqui custa um diario grande; a falha pelo
	 * outro lado custaria um diario apagado sem motivo.
	 */
	fun rotateOnNewVersion(context: Context)
	{
		val atual = versionLabel(context)
		val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		val anterior = prefs.getString(KEY_VERSAO, null)
		if(anterior == atual)
			return

		val tinhaDiario = file(context).exists()
		guardarAnterior(context)
		P5MApp.guardarAnterior(context)
		PilhaNativa.guardarAnterior(context)
		clearSessionLogs(context)
		prefs.edit().putString(KEY_VERSAO, atual).apply()

		// A primeira linha do diario novo diz o que foi jogado fora. Sem ela a
		// limpeza automatica seria indistinguivel de um diario que nunca gravou.
		val de = anterior
			?: (if(tinhaDiario) "an earlier version (unmarked)" else "a fresh install")
		append(context, "=== diary cleared on version change: $de -> $atual ===")
	}

	private fun versionLabel(context: Context): String = try
	{
		val info = context.packageManager.getPackageInfo(context.packageName, 0)
		"${info.versionName} (${info.longVersionCode})"
	}
	catch(e: Exception)
	{
		Log.w(TAG, "Could not read the installed version: ${e.message}")
		"?"
	}

	/**
	 * Move o diário para o lugar de "anterior" em vez de apagá-lo.
	 *
	 * Apagar era o comportamento da primeira versão desta limpeza, e estava
	 * errado num caso que é justamente o mais importante: quando algo quebra e
	 * a saída é instalar um APK de volta, a troca de versão apagaria o diário da
	 * versão que quebrou -- a prova sumia exatamente quando ela era necessária.
	 *
	 * Só um nível de história. Guardar mais seria acumular sem fim, e a segunda
	 * versão atrás nunca foi útil neste projeto.
	 */
	private fun guardarAnterior(context: Context)
	{
		flush()
		synchronized<Unit>(lock)
		{
			val atual = file(context)
			val anterior = fileAnterior(context)
			if(atual.exists())
			{
				anterior.delete()
				// Se o renomear falhar, apagar é melhor do que deixar o diário
				// da versão passada se misturar ao da nova.
				if(!atual.renameTo(anterior))
					atual.delete()
			}
			lastCapturedStamp = null
		}
	}

	/**
	 * Os logs de sessao do chiaki, que o LogManager guarda de cinco em cinco.
	 *
	 * O nome do diretorio esta escrito aqui porque o do submodulo e privado do
	 * arquivo dele. Se um dia mudar la, some silenciosamente daqui -- e o custo
	 * disso e nao apagar cinco arquivos, nao um defeito.
	 */
	private fun clearSessionLogs(context: Context)
	{
		try
		{
			File(context.filesDir, "session_logs").listFiles()?.forEach { it.delete() }
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Could not delete the session logs: ${e.message}")
		}
	}
}
