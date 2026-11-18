// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Serve o diagnóstico por HTTP na rede local, para ser lido de outro aparelho.
 *
 * O compartilhamento de arquivos do Horizon OS não entrega o `.txt` em lugar
 * nenhum, e colar um log longo pelo teclado do headset é inviável. O que sobra é
 * tirar o texto do aparelho por onde ele já fala: a própria rede.
 *
 * Com isto, o celular ou o PC abrem um endereço e recebem o log como texto —
 * onde copiar, salvar e enviar funcionam normalmente.
 *
 * Deliberadamente pequeno: um socket, uma resposta, sem biblioteca. Na build
 * pública fica de pé apenas enquanto a tela de diagnóstico está aberta, porque
 * um servidor esquecido ligado num aparelho de uso doméstico é risco sem
 * contrapartida.
 *
 * Diário ao vivo (dev.242): nas builds -dev ele sobe com o processo, se
 * anuncia na rede por mDNS (`_p5m._tcp`) e serve `/follow`, que entrega cada
 * linha nova enquanto o dono joga. O computador de quem acompanha acha o
 * headset sozinho e lê o teste sem o dono parar para mandar o diário. O que
 * sai é o mesmo diário redigido do arquivo; o interruptor fica no Diagnóstico.
 */
class LogServer(context: Context)
{
	// O do aplicativo, e nao o da tela: o socket vive mais que a tela que o abriu.
	private val context = context.applicationContext

	val url: String?
		get() = server?.let { "http://${localAddress()}:${it.localPort}/" }

	/** Por que o ultimo [start] falhou, para a tela mostrar. */
	val falha: String?
		get() = ultimaFalha

	/** Uma tela de diagnostico abriu; so a ultima a fechar derruba o servidor. */
	fun abrir() = synchronized(LogServer) { telas++ }

	/** Uma tela de diagnostico fechou. */
	fun fechar() = synchronized(LogServer)
	{
		telas = (telas - 1).coerceAtLeast(0)
		if(telas == 0 && !aoVivo)
			stop()
	}

	fun start(): Boolean = synchronized(LogServer)
	{
		if(server != null)
			return true
		try
		{
			val socket = ServerSocket().apply {
				reuseAddress = true
				bind(java.net.InetSocketAddress(PORT))
			}
			server = socket
			ultimaFalha = null
			thread(name = "p5m-log-server", isDaemon = true) { serve(socket) }
			Log.i(TAG, "Diagnostics available at ${url}")
			true
		}
		catch(e: Exception)
		{
			ultimaFalha = "${e::class.java.simpleName}: ${e.message}"
			Log.w(TAG, "Could not open the log server: $ultimaFalha")
			false
		}
	}

	fun stop() = synchronized(LogServer)
	{
		aoVivo = false
		desanunciar(context)
		try { server?.close() } catch(e: Exception) { /* fechar e o suficiente */ }
		server = null
	}

	private fun serve(socket: ServerSocket)
	{
		while(!socket.isClosed)
		{
			val client = try { socket.accept() } catch(e: Exception) { return }
			var seguir = false
			try
			{
				val path = primeiraLinhaHttp(client).split(" ").getOrNull(1) ?: "/"
				// O /follow fica aberto enquanto quem le quiser; numa thread
				// propria, senao o servidor (que atende um de cada vez) parava.
				if(path.startsWith("/follow"))
				{
					seguir = true
					thread(name = "p5m-log-follow", isDaemon = true) { seguirAoVivo(client, path) }
				}
				else
					respond(client, path)
			}
			catch(e: Exception) { Log.w(TAG, "Failed to answer: ${e.message}") }
			finally { if(!seguir) try { client.close() } catch(e: Exception) { } }
		}
	}

	/**
	 * Entrega as linhas novas do diário conforme são gravadas, até quem lê
	 * fechar. `/follow` começa pelas últimas 30; `/follow/all`, por todas as
	 * que ainda estão na memória (até 2000).
	 *
	 * Uma linha vazia a cada 15 s sem novidade: sem escrever nada não há como
	 * perceber que o outro lado foi embora, e a thread ficaria presa.
	 */
	private fun seguirAoVivo(client: Socket, path: String)
	{
		if(seguidores.incrementAndGet() > MAX_SEGUIDORES)
		{
			seguidores.decrementAndGet()
			try { write(client.getOutputStream(), "(too many followers)\n") } catch(e: Exception) { }
			try { client.close() } catch(e: Exception) { }
			return
		}
		try
		{
			val out = client.getOutputStream()
			out.write(("HTTP/1.1 200 OK\r\n" +
					"Content-Type: text/plain; charset=utf-8\r\n" +
					"Cache-Control: no-cache\r\n" +
					"Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
			Trace.flush()
			val agora = Trace.aoVivoUltima()
			var ultima = if(path.startsWith("/follow/all")) 0L else (agora - 30).coerceAtLeast(0)
			while(server != null && !client.isClosed)
			{
				val (nova, linhas) = Trace.aoVivoDepois(ultima, 15_000)
				ultima = nova
				val texto = if(linhas.isEmpty()) "\n" else linhas.joinToString("\n", postfix = "\n")
				out.write(texto.toByteArray(Charsets.UTF_8))
				out.flush()
			}
		}
		catch(e: Exception) { /* quem lia fechou */ }
		finally
		{
			seguidores.decrementAndGet()
			try { client.close() } catch(e: Exception) { }
		}
	}

	private fun respond(client: Socket, path: String)
	{
		val body = when
		{
			path.startsWith("/crash") -> P5MApp.lastCrash(context) ?: "(no crash recorded)"

			// Rotas de recorte. O diario inteiro passa de cem mil caracteres, e
			// quem esta caçando um defeito quase sempre quer uma familia de
			// linhas -- as da haptica, as do video -- ou so o fim. Puxar tudo
			// para achar vinte linhas e caro de todo jeito, e por rede de
			// terceiro e pior ainda.
			//
			// O termo vem do proprio caminho, sem parametro de consulta: e uma
			// linha de requisicao lida a mao, e um caminho e mais facil de
			// digitar num navegador do que uma consulta com escape.
			path.startsWith("/grep/") -> filtrar(context, path.removePrefix("/grep/"))
			path.startsWith("/haptics") -> filtrar(context, "aptics")
			path.startsWith("/video") -> filtrar(context, "Video 10s")
			// As duas medidas de tempo do enlace, lado a lado: envio da háptica
			// e chegada da entrada. Uma sem a outra não separa as hipóteses.
			path.startsWith("/timing") -> filtrar(context, "timing:")
			path.startsWith("/tail") -> ultimas(context, 120)
			else -> buildString {
				append("=== P5M — diagnostics ===\n\n")
				P5MApp.lastCrash(context)?.let {
					append("--- last crash ---\n").append(it).append("\n\n")
				}
				append("--- diary ---\n")
				append(Trace.read(context) ?: "(diary empty)")
			}
		}
		write(client.getOutputStream(), body)
	}

	/**
	 * As linhas do diario que contem o termo, sem diferenciar maiuscula.
	 *
	 * Sem expressao regular de proposito: quem le isto esta com o headset na
	 * cabeca ou digitando num navegador de celular, e "contem" resolve o caso
	 * real. Uma expressao mal formada devolveria vazio e pareceria diario vazio.
	 */
	private fun filtrar(context: Context, termo: String): String
	{
		val diario = Trace.read(context) ?: return "(diary empty)"
		val alvo = termo.trim('/').lowercase()
		if(alvo.isEmpty())
			return diario
		val achadas = diario.lineSequence().filter { it.lowercase().contains(alvo) }.toList()
		return if(achadas.isEmpty()) "(no line contains '$alvo')"
			else achadas.joinToString("\n")
	}

	private fun ultimas(context: Context, quantas: Int): String
	{
		val linhas = (Trace.read(context) ?: return "(diary empty)").lines()
		return linhas.takeLast(quantas).joinToString("\n")
	}

	private fun write(out: OutputStream, body: String)
	{
		val bytes = body.toByteArray(Charsets.UTF_8)
		// text/plain para o navegador mostrar em vez de baixar, que no celular
		// e o que permite selecionar e copiar.
		val header = "HTTP/1.1 200 OK\r\n" +
				"Content-Type: text/plain; charset=utf-8\r\n" +
				"Content-Length: ${bytes.size}\r\n" +
				"Connection: close\r\n\r\n"
		out.write(header.toByteArray(Charsets.UTF_8))
		out.write(bytes)
		out.flush()
	}

	/** Endereço na rede local, para compor a URL que o usuário vai digitar. */
	private fun localAddress(): String
	{
		return try
		{
			val wifi = context.applicationContext
				.getSystemService(Context.WIFI_SERVICE) as? WifiManager
			@Suppress("DEPRECATION")
			val ip = wifi?.connectionInfo?.ipAddress ?: 0
			if(ip != 0)
				InetAddress.getByAddress(
					byteArrayOf(
						(ip and 0xff).toByte(),
						(ip shr 8 and 0xff).toByte(),
						(ip shr 16 and 0xff).toByte(),
						(ip shr 24 and 0xff).toByte()
					)
				).hostAddress ?: "?"
			else "?"
		}
		catch(e: Exception) { "?" }
	}

	/**
	 * O servidor e um so por processo. Quando ele pertencia a cada tela, uma
	 * tela de diagnostico que o Horizon deixou viva em segundo plano segurava a
	 * porta, e a tela nova nao conseguia abri-la ("Could not start the server",
	 * dev.220).
	 */
	companion object
	{
		private const val TAG = "P5MVR"
		private const val PORT = 8787
		@Volatile private var server: ServerSocket? = null
		private var telas = 0
		private var ultimaFalha: String? = null
		@Volatile private var aoVivo = false
		private const val MAX_SEGUIDORES = 3
		private val seguidores = java.util.concurrent.atomic.AtomicInteger(0)
		private var anuncio: android.net.nsd.NsdManager.RegistrationListener? = null
		private const val PREFS = "p5m-live-diary"
		private const val KEY = "live-diary"

		/** Alguém está lendo o /follow agora. */
		fun seguindo() = seguidores.get() > 0

		/** Sem escolha salva, ligado nas builds -dev e desligado na pública. */
		fun aoVivoEscolhido(context: Context): Boolean
		{
			val versao = try
			{
				context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
			}
			catch(e: Exception) { "" }
			return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
				.getBoolean(KEY, versao.contains("-dev"))
		}

		/** Na abertura do processo: sobe o servidor se o diário ao vivo estiver ligado. */
		fun iniciarAoVivo(context: Context)
		{
			if(aoVivoEscolhido(context))
				ligarAoVivo(context)
		}

		fun definirAoVivo(context: Context, ligado: Boolean)
		{
			context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
				.putBoolean(KEY, ligado).apply()
			if(ligado)
				ligarAoVivo(context)
			else synchronized(LogServer)
			{
				val servidor = LogServer(context)
				if(telas == 0)
					servidor.stop()
				else
				{
					// A tela do Diagnostico ainda usa o servidor; so o anuncio sai.
					aoVivo = false
					servidor.desanunciar(context)
				}
			}
		}

		private fun ligarAoVivo(context: Context)
		{
			val servidor = LogServer(context)
			synchronized(LogServer)
			{
				if(!servidor.start())
				{
					Trace.log(context, "Live diary: could not open port $PORT (${ultimaFalha})")
					return
				}
				aoVivo = true
			}
			servidor.anunciar(context)
			Trace.log(context, "Live diary: serving ${servidor.url}follow, announced as _p5m._tcp")
		}
	}

	/**
	 * Anuncia o servidor por mDNS, para o computador achar o headset sem o
	 * dono ler e digitar o endereço (`dns-sd -B _p5m._tcp` no Mac).
	 */
	private fun anunciar(context: Context) = synchronized(LogServer)
	{
		if(anuncio != null)
			return
		val nsd = context.getSystemService(Context.NSD_SERVICE) as? android.net.nsd.NsdManager ?: return
		val info = android.net.nsd.NsdServiceInfo().apply {
			serviceName = "P5M Quest"
			serviceType = "_p5m._tcp"
			port = PORT
		}
		val ouvinte = object: android.net.nsd.NsdManager.RegistrationListener {
			override fun onServiceRegistered(i: android.net.nsd.NsdServiceInfo) {}
			override fun onServiceUnregistered(i: android.net.nsd.NsdServiceInfo) {}
			override fun onRegistrationFailed(i: android.net.nsd.NsdServiceInfo, erro: Int)
			{
				Trace.log(context, "Live diary: mDNS announcement failed (error $erro); the address still works")
			}
			override fun onUnregistrationFailed(i: android.net.nsd.NsdServiceInfo, erro: Int) {}
		}
		try
		{
			nsd.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, ouvinte)
			anuncio = ouvinte
		}
		catch(e: Exception)
		{
			Trace.log(context, "Live diary: mDNS announcement refused (${e.javaClass.simpleName}); the address still works")
		}
	}

	private fun desanunciar(context: Context) = synchronized(LogServer)
	{
		val ouvinte = anuncio ?: return
		anuncio = null
		val nsd = context.getSystemService(Context.NSD_SERVICE) as? android.net.nsd.NsdManager ?: return
		try { nsd.unregisterService(ouvinte) } catch(e: Exception) { }
	}
}

/**
 * A linha da requisição, com prazo e com teto.
 *
 * O servidor atende um de cada vez. Sem prazo, um aparelho da rede que
 * conectasse e não mandasse nada travava o diário para todo mundo; sem
 * teto, uma linha sem fim estourava a memória -- e um OutOfMemoryError
 * não é Exception, passa pelo catch e derruba o app, stream inclusive.
 */
internal fun primeiraLinhaHttp(client: Socket, teto: Int = 8192): String
{
	client.soTimeout = 3000
	val entrada = client.getInputStream()
	val linha = StringBuilder()
	while(linha.length < teto)
	{
		val b = entrada.read()
		if(b < 0 || b == '\n'.code)
			break
		if(b != '\r'.code)
			linha.append(b.toChar())
	}
	descartarCabecalho(client, entrada, teto)
	return linha.toString()
}

/**
 * Lê e joga fora o resto do cabeçalho, até a linha vazia.
 *
 * Não é faxina. Fechar um socket com bytes recebidos e não lidos faz o
 * sistema mandar RST em vez de encerrar a conexão, e o RST descarta do lado
 * do cliente o que ainda não tinha sido entregue: o diário chegava cortado
 * em uns 13 KB no navegador e no curl (dev.237). O prazo é curto porque
 * cliente de verdade manda o cabeçalho junto com a primeira linha; quem não
 * manda (um `nc` com a linha só) não espera por nada.
 */
private fun descartarCabecalho(client: Socket, entrada: java.io.InputStream, teto: Int)
{
	client.soTimeout = 200
	var lidos = 0
	var naLinha = 0
	try
	{
		while(lidos < teto)
		{
			val b = entrada.read()
			if(b < 0)
				return
			lidos++
			if(b == '\n'.code)
			{
				if(naLinha == 0)
					return
				naLinha = 0
			}
			else if(b != '\r'.code)
				naLinha++
		}
	}
	catch(e: java.net.SocketTimeoutException)
	{
		// Sem cabeçalho, ou incompleto: não há o que esperar.
	}
}
