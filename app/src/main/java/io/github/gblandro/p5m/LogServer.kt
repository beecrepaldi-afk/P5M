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
 * Deliberadamente pequeno: um socket, uma resposta, sem biblioteca. Fica de pé
 * apenas enquanto a tela de diagnóstico está aberta e só responde a dois
 * caminhos, porque um servidor esquecido ligado num aparelho de uso doméstico é
 * risco sem contrapartida.
 */
class LogServer(private val context: Context)
{
	private var server: ServerSocket? = null
	private var worker: Thread? = null

	val url: String?
		get() = server?.let { "http://${localAddress()}:${it.localPort}/" }

	fun start(): Boolean
	{
		if(server != null)
			return true
		return try
		{
			val socket = ServerSocket(PORT)
			server = socket
			worker = thread(name = "p5m-log-server", isDaemon = true) { serve(socket) }
			Log.i(TAG, "Diagnostics available at ${url}")
			true
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Could not open the log server: ${e.message}")
			server = null
			false
		}
	}

	fun stop()
	{
		try { server?.close() } catch(e: Exception) { /* fechar e o suficiente */ }
		server = null
		worker = null
	}

	private fun serve(socket: ServerSocket)
	{
		while(!socket.isClosed)
		{
			val client = try { socket.accept() } catch(e: Exception) { return }
			try { respond(client) }
			catch(e: Exception) { Log.w(TAG, "Failed to answer: ${e.message}") }
			finally { try { client.close() } catch(e: Exception) { } }
		}
	}

	private fun respond(client: Socket)
	{
		val request = client.getInputStream().bufferedReader().readLine().orEmpty()
		val path = request.split(" ").getOrNull(1) ?: "/"

		val body = when
		{
			path.startsWith("/crash") -> P5MApp.lastCrash(context) ?: "(no crash recorded)"
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

	private companion object
	{
		const val TAG = "P5MVR"
		const val PORT = 8787
	}
}
