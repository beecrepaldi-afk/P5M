// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Atualizar em um toque: baixa o APK da [Atualizacao.Oferta] para o cache e
 * abre a tela de instalação do sistema, que só pede "Update".
 *
 * Antes era o navegador do Quest: baixar, achar o arquivo, abrir, instalar.
 * Aqui o app faz o meio do caminho e o Android continua decidindo a parte que
 * é dele -- a confirmação e a assinatura. Uma assinatura diferente da
 * instalada é recusada pelo próprio instalador, sem tocar nos dados.
 *
 * A primeira vez pede a licença "instalar apps desconhecidos" para o P5M; o
 * Android leva o usuário à tela certa e ele volta e toca de novo.
 */
object InstaladorDeAtualizacao
{
	private const val AUTORIDADE = "io.github.gblandro.p5m.diagnostics"

	@Volatile private var baixando = false

	/**
	 * `progresso` recebe 0-100 (ou -1 sem tamanho conhecido) e `fim` recebe
	 * null no sucesso ou o motivo da falha, os dois na thread principal.
	 */
	fun atualizar(activity: Activity, oferta: Atualizacao.Oferta,
			progresso: (Int) -> Unit, fim: (String?) -> Unit)
	{
		if(baixando)
			return
		if(!oferta.url.endsWith(".apk"))
		{
			// Release sem APK anexado: só resta a página.
			activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(oferta.url)))
			fim(null)
			return
		}
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
				!activity.packageManager.canRequestPackageInstalls())
		{
			Trace.log(activity, "Update: asking for permission to install apps")
			activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
					Uri.parse("package:${activity.packageName}")))
			fim("Allow P5M to install apps, then select Update again.")
			return
		}
		baixando = true
		val app = activity.applicationContext
		thread(name = "p5m-update-download", isDaemon = true) {
			val motivo: String? = try
			{
				val pasta = File(app.cacheDir, "updates").apply { mkdirs() }
				pasta.listFiles()?.forEach { it.delete() }
				val apk = File(pasta, "p5m-${oferta.versao}.apk")
				baixar(oferta.url, apk) { p -> activity.runOnUiThread { progresso(p) } }
				conferir(app, apk, oferta.versao)
				Trace.log(app, "Update: ${oferta.versao} downloaded (${apk.length() / 1024} KB); opening the installer")
				val uri = FileProvider.getUriForFile(app, AUTORIDADE, apk)
				activity.runOnUiThread {
					activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
						setDataAndType(uri, "application/vnd.android.package-archive")
						addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
					})
				}
				null
			}
			catch(e: Exception)
			{
				Trace.log(app, "Update: download of ${oferta.versao} failed: ${e.javaClass.simpleName}: ${e.message}")
				"Download failed: ${e.message ?: e.javaClass.simpleName}"
			}
			finally { baixando = false }
			activity.runOnUiThread { fim(motivo) }
		}
	}

	private fun baixar(url: String, destino: File, progresso: (Int) -> Unit)
	{
		var endereco = URL(url)
		// O GitHub redireciona o asset para outro host; seguir na mão deixa o
		// motivo claro se algum salto falhar.
		repeat(5) {
			val c = endereco.openConnection() as HttpURLConnection
			try
			{
				c.instanceFollowRedirects = false
				c.connectTimeout = 15_000
				c.readTimeout = 30_000
				c.setRequestProperty("User-Agent", "P5M-Quest")
				val codigo = c.responseCode
				if(codigo in 300..399)
				{
					val proximo = c.getHeaderField("Location")
						?: throw java.io.IOException("redirect without a location")
					endereco = URL(endereco, proximo)
					return@repeat
				}
				if(codigo != 200)
					throw java.io.IOException("the server answered HTTP $codigo")
				val total = c.contentLengthLong
				var lido = 0L
				var ultimo = -2
				c.inputStream.use { entrada ->
					destino.outputStream().use { saida ->
						val buf = ByteArray(64 * 1024)
						while(true)
						{
							val n = entrada.read(buf)
							if(n < 0)
								break
							saida.write(buf, 0, n)
							lido += n
							val pct = if(total > 0) (lido * 100 / total).toInt() else -1
							if(pct != ultimo)
							{
								ultimo = pct
								progresso(pct)
							}
						}
					}
				}
				if(total > 0 && lido != total)
					throw java.io.IOException("the download stopped at $lido of $total bytes")
				return
			}
			finally { c.disconnect() }
		}
		throw java.io.IOException("too many redirects")
	}

	/** O arquivo tem de ser o P5M, e da versão anunciada. */
	private fun conferir(app: android.content.Context, apk: File, versao: String)
	{
		val info = app.packageManager.getPackageArchiveInfo(apk.path, 0)
			?: throw java.io.IOException("the file is not a valid APK")
		if(info.packageName != app.packageName)
			throw java.io.IOException("the APK is ${info.packageName}, not P5M")
		val nome = info.versionName ?: ""
		if(Atualizacao.comparar(nome, versao) != 0)
			Trace.log(app, "Update: the APK says $nome, the release says $versao; installing anyway")
	}
}
