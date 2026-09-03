// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Faz o login da PSN acontecer no celular, e traz só o resultado de volta.
 *
 * ## Por que o navegador embutido não basta
 *
 * A Sony **apaga a senha da conta** quando você cria uma passkey. Não é a
 * página escondendo o campo: a senha deixou de existir. O que sobra na tela de
 * login é escanear um QR com o app PlayStation, ou recuperação por e-mail.
 *
 * O QR até serve — quem escaneia é o celular, que já está autenticado, e o que
 * ele autoriza é a sessão que está na tela. O problema é geométrico: a tela
 * está dentro do headset, e não há como apontar o celular para ela.
 *
 * Passkey também não resolve de dentro: exige um gerenciador de credenciais e
 * um sensor biométrico que o Horizon OS não oferece a um WebView.
 *
 * ## O que este servidor faz
 *
 * Inverte os papéis. O headset publica uma página na rede local; o celular a
 * abre digitando só um endereço curto. Dali em diante o login acontece **no
 * celular**, onde a passkey funciona com um toque e o QR nem é preciso. No fim
 * a Sony redireciona para uma página que não carrega -- ela existe só para
 * carregar o código na URL --, o usuário copia essa URL e cola de volta na
 * nossa página. O código atravessa a rede local e cai no app.
 *
 * ## Sobre segurança
 *
 * O código de autorização trafega em HTTP simples dentro da rede local. É de
 * uso único, vale por segundos e vai para o aparelho do próprio dono. Em troca
 * disso, o servidor fica de pé **apenas enquanto a tela de login está aberta** e
 * cai no instante em que o código chega -- pelo mesmo motivo que o
 * [LogServer] não fica ligado sozinho: servidor esquecido num aparelho de uso
 * doméstico é risco sem contrapartida.
 *
 * Deliberadamente pequeno, como o [LogServer]: um socket, uma linha de
 * requisição, sem biblioteca. Só dois caminhos respondem.
 */
class PsnLoginRelay(private val context: Context)
{
	private var server: ServerSocket? = null
	private var worker: Thread? = null
	private var loginUrl: String = ""
	private var onCode: ((String) -> Unit)? = null

	val url: String?
		get() = server?.let { "http://${localAddress()}:${it.localPort}/" }

	/** Endereço sem o esquema, que é o que se digita no celular. */
	val shortAddress: String?
		get() = server?.let { "${localAddress()}:${it.localPort}" }

	fun start(loginUrl: String, onCode: (String) -> Unit): Boolean
	{
		if(server != null)
			return true
		this.loginUrl = loginUrl
		this.onCode = onCode
		return try
		{
			val socket = ServerSocket(PORT)
			server = socket
			worker = thread(name = "p5m-psn-relay", isDaemon = true) { serve(socket) }
			Log.i(TAG, "PSN: sign-in bridge at ${url}")
			true
		}
		catch(e: Exception)
		{
			Log.w(TAG, "PSN: could not open the sign-in bridge: ${e.message}")
			server = null
			false
		}
	}

	fun stop()
	{
		try { server?.close() } catch(e: Exception) { /* fechar e o suficiente */ }
		server = null
		worker = null
		onCode = null
	}

	private fun serve(socket: ServerSocket)
	{
		while(!socket.isClosed)
		{
			val client = try { socket.accept() } catch(e: Exception) { return }
			try { respond(client) }
			catch(e: Exception) { Log.w(TAG, "PSN: failed to answer on the bridge: ${e.message}") }
			finally { try { client.close() } catch(e: Exception) { } }
		}
	}

	private fun respond(client: Socket)
	{
		val request = client.getInputStream().bufferedReader().readLine().orEmpty()
		val path = request.split(" ").getOrNull(1) ?: "/"

		// Formulário por GET, e não por POST, de propósito: assim a requisição
		// inteira cabe na primeira linha e o servidor continua sendo um
		// readLine() só. Menos código é menos coisa para dar errado num
		// caminho que só existe para ser usado uma vez.
		if(path.startsWith("/code?"))
		{
			val colado = param(path, "url")
			val code = extractCode(colado)
			if(code.isEmpty())
			{
				write(client.getOutputStream(), pageError())
				return
			}
			write(client.getOutputStream(), pageDone())
			Log.i(TAG, "PSN: code received through the bridge")
			onCode?.invoke(code)
			return
		}
		write(client.getOutputStream(), pageHome())
	}

	/**
	 * Aceita a URL inteira de redirecionamento ou só o código.
	 *
	 * Pedir "copie a URL da barra de endereços" é o que se consegue explicar sem
	 * ambiguidade; pedir "copie o pedaço depois de code=" é pedir para errar. Se
	 * vier só o código, também serve.
	 */
	private fun extractCode(texto: String): String
	{
		val limpo = texto.trim()
		if(limpo.isEmpty())
			return ""
		val i = limpo.indexOf("code=")
		if(i < 0)
			return if(limpo.contains(" ") || limpo.contains("/")) "" else limpo
		val resto = limpo.substring(i + 5)
		val fim = resto.indexOfFirst { it == '&' || it == '#' }
		return if(fim < 0) resto else resto.substring(0, fim)
	}

	private fun param(path: String, name: String): String
	{
		val query = path.substringAfter('?', "")
		for(par in query.split("&"))
		{
			val i = par.indexOf('=')
			if(i > 0 && par.substring(0, i) == name)
				return try
				{
					URLDecoder.decode(par.substring(i + 1), "UTF-8")
				}
				catch(e: Exception)
				{
					par.substring(i + 1)
				}
		}
		return ""
	}

	// ------------------------------------------------------------- as páginas

	private fun pageHome() = page("""
		<h1>Sign in to PSN</h1>
		<p class="sub">You are on your phone. The sign-in happens here, where your
		passkey works with one tap, and P5M receives only the result.</p>

		<div class="passo">
			<span class="n">1</span>
			<div>
				<a class="botao" href="$loginUrl" target="_blank" rel="noopener">
					Open Sony's sign-in page</a>
				<p class="dica">It opens in another tab. Leave this one open —
				you will come back to it.</p>
			</div>
		</div>

		<div class="passo">
			<span class="n">2</span>
			<div>
				<p>Sign in as usual. At the end Sony sends you to a page that
				<strong>does not load</strong> — that is expected. It exists only
				to carry the code in its address.</p>
			</div>
		</div>

		<div class="passo">
			<span class="n">3</span>
			<div>
				<p>Copy the whole address of that page that did not load and paste
				it below.</p>
				<form action="/code" method="get">
					<input name="url" placeholder="https://remoteplay.dl.playstation.net/..."
						autocomplete="off" autocapitalize="off" spellcheck="false">
					<button type="submit">Send to the headset</button>
				</form>
			</div>
		</div>
	""")

	private fun pageDone() = page("""
		<h1>Done</h1>
		<p class="sub">The code reached the headset. You can close this page and
		put the headset back on — the console list appears on its own.</p>
	""")

	private fun pageError() = page("""
		<h1>No code found</h1>
		<p class="sub">What you pasted has no <code>code=</code> in it. Check that
		you copied the address of the page that did not load, and not the one of
		the sign-in page.</p>
		<p><a class="botao" href="/">Try again</a></p>
	""")

	private fun page(corpo: String) = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>P5M — sign in to PSN</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; padding:24px; background:#101014; color:#e8eaed;
         font:16px/1.5 system-ui, -apple-system, sans-serif; }
  h1 { font-size:24px; margin:0 0 8px; }
  .sub { color:#9aa0a6; margin:0 0 24px; }
  .passo { display:flex; gap:14px; margin:0 0 24px; align-items:flex-start; }
  .n { flex:0 0 28px; height:28px; border-radius:14px; background:#2a2a35;
       display:flex; align-items:center; justify-content:center;
       font-weight:600; font-size:14px; }
  .passo p { margin:0 0 8px; }
  .dica { color:#9aa0a6; font-size:14px; }
  .botao { display:inline-block; background:#3d6fd6; color:#fff;
           text-decoration:none; padding:12px 18px; border-radius:8px;
           font-weight:600; }
  input { width:100%; box-sizing:border-box; padding:12px; border-radius:8px;
          border:1px solid #3a3a46; background:#1a1a22; color:#e8eaed;
          font-size:16px; margin:0 0 10px; }
  button { width:100%; padding:12px; border-radius:8px; border:0;
           background:#3d6fd6; color:#fff; font-size:16px; font-weight:600; }
  code { background:#1a1a22; padding:2px 5px; border-radius:4px; }
</style></head><body>$corpo</body></html>"""

	private fun write(out: OutputStream, body: String)
	{
		val bytes = body.toByteArray(Charsets.UTF_8)
		val header = "HTTP/1.1 200 OK\r\n" +
				"Content-Type: text/html; charset=utf-8\r\n" +
				"Content-Length: ${bytes.size}\r\n" +
				"Connection: close\r\n\r\n"
		out.write(header.toByteArray(Charsets.UTF_8))
		out.write(bytes)
		out.flush()
	}

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

	private companion object
	{
		const val TAG = "P5MVR"
		// 8787 é do LogServer; os dois podem estar de pé ao mesmo tempo.
		const val PORT = 8788
	}
}
