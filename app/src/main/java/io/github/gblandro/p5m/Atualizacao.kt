// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Aviso de versão nova (dev.243), como o Mac e o Windows já fazem.
 *
 * Consulta os releases públicos do P5M no GitHub e, se houver um mais novo que
 * o instalado e que o usuário não pulou, o lançador mostra um cartão com
 * "Update" e "Skip this version". Desde a dev.245 o Update baixa o APK e abre
 * o instalador do sistema ([InstaladorDeAtualizacao]); antes ia pelo navegador.
 *
 * Só a build pública oferece versão: o PSMeta é privado e não responde sem
 * credencial, e uma build -dev é sempre mais nova que o último beta público.
 * Na -dev a consulta roda do mesmo jeito e só vai ao diário, para o caminho
 * poder ser conferido no headset de quem testa.
 *
 * Uma consulta a cada 6 h no máximo, na abertura do lançador: o GitHub aceita
 * 60 por hora sem conta, e versão nova sai de semanas em semanas.
 */
object Atualizacao
{
	data class Oferta(val versao: String, val url: String)

	private const val API = "https://api.github.com/repos/beecrepaldi-afk/P5M/releases?per_page=10"
	private const val PAGINA = "https://github.com/beecrepaldi-afk/P5M/releases/latest"
	private const val PREFS = "p5m-update"
	private const val KEY_PULADA = "skipped-version"
	private const val KEY_ULTIMA = "last-check-ms"
	private const val KEY_OFERTA = "offered-version"
	private const val KEY_URL = "offered-url"
	private const val KEY_LIGADO = "check-for-updates"
	private const val INTERVALO_MS = 6 * 60 * 60 * 1000L

	@Volatile private var consultando = false

	fun ligado(context: Context) = prefs(context).getBoolean(KEY_LIGADO, true)

	fun definirLigado(context: Context, ligado: Boolean)
	{
		prefs(context).edit().putBoolean(KEY_LIGADO, ligado).apply()
		if(ligado)
			prefs(context).edit().remove(KEY_ULTIMA).apply()
	}

	/** O que o cartão deve mostrar agora, da última consulta guardada. */
	fun oferta(context: Context): Oferta?
	{
		if(!ligado(context) || publica(versaoInstalada(context)) == false)
			return null
		val p = prefs(context)
		val versao = p.getString(KEY_OFERTA, null) ?: return null
		if(versao == p.getString(KEY_PULADA, null))
			return null
		// Instalou por cima: a oferta guardada pode ter deixado de ser nova.
		if(comparar(versao, versaoInstalada(context)) <= 0)
			return null
		return Oferta(versao, p.getString(KEY_URL, null) ?: PAGINA)
	}

	fun pular(context: Context, versao: String)
	{
		prefs(context).edit().putString(KEY_PULADA, versao).apply()
		Trace.log(context, "Update: version $versao skipped")
	}

	/**
	 * Consulta em segundo plano, se já passou o intervalo. [pronto] roda na
	 * thread da consulta quando ela muda o que [oferta] devolve.
	 */
	fun consultar(context: Context, pronto: () -> Unit)
	{
		val app = context.applicationContext
		if(!ligado(app) || consultando)
			return
		val p = prefs(app)
		if(System.currentTimeMillis() - p.getLong(KEY_ULTIMA, 0) < INTERVALO_MS)
			return
		consultando = true
		thread(name = "p5m-update-check", isDaemon = true) {
			try
			{
				val instalada = versaoInstalada(app)
				val melhor = buscarMaisNova()
				p.edit().putLong(KEY_ULTIMA, System.currentTimeMillis()).apply()
				when
				{
					melhor == null ->
						Trace.log(app, "Update check: no public release found")
					publica(instalada) == false ->
						Trace.log(app, "Update check: latest public release is ${melhor.versao}; " +
								"development build $instalada does not offer it")
					comparar(melhor.versao, instalada) > 0 ->
					{
						p.edit().putString(KEY_OFERTA, melhor.versao).putString(KEY_URL, melhor.url).apply()
						Trace.log(app, "Update check: ${melhor.versao} is available (installed $instalada)" +
								if(melhor.versao == p.getString(KEY_PULADA, null)) ", skipped by the user" else "")
						pronto()
					}
					else ->
						Trace.log(app, "Update check: $instalada is up to date (latest ${melhor.versao})")
				}
			}
			catch(e: Exception)
			{
				// O motivo, não o sintoma: sem rede, GitHub fora e limite de
				// consultas aparecem diferentes aqui.
				Trace.log(app, "Update check failed: ${e.javaClass.simpleName}: ${e.message}")
			}
			finally { consultando = false }
		}
	}

	/** O release público mais novo (tag `vX.Y.Z[-pre]`), com o link do APK. */
	private fun buscarMaisNova(): Oferta?
	{
		val conexao = URL(API).openConnection() as HttpURLConnection
		try
		{
			conexao.connectTimeout = 15_000
			conexao.readTimeout = 15_000
			conexao.setRequestProperty("Accept", "application/vnd.github+json")
			conexao.setRequestProperty("User-Agent", "P5M-Quest")
			val codigo = conexao.responseCode
			if(codigo != 200)
				throw java.io.IOException("GitHub answered HTTP $codigo")
			val texto = conexao.inputStream.bufferedReader().use { it.readText() }
			val lista = JSONArray(texto)
			var melhor: Oferta? = null
			for(i in 0 until lista.length())
			{
				val r = lista.getJSONObject(i)
				val tag = r.optString("tag_name")
				// O release rolante "dev" do P5M é antigo e não é versão.
				if(r.optBoolean("draft") || !Regex("""^v\d+\.\d+""").containsMatchIn(tag))
					continue
				val versao = tag.removePrefix("v")
				val assets = r.optJSONArray("assets") ?: JSONArray()
				var apk: String? = null
				for(j in 0 until assets.length())
				{
					val a = assets.getJSONObject(j)
					if(a.optString("name").endsWith(".apk"))
						apk = a.optString("browser_download_url")
				}
				val url = apk ?: r.optString("html_url").ifEmpty { PAGINA }
				if(melhor == null || comparar(versao, melhor.versao) > 0)
					melhor = Oferta(versao, url)
			}
			return melhor
		}
		finally { conexao.disconnect() }
	}

	/** null quando não dá para saber; false nas builds -dev e locais. */
	private fun publica(versao: String): Boolean? =
		if(versao.isEmpty()) null else !(versao.contains("-dev") || versao.endsWith("-local"))

	private fun versaoInstalada(context: Context): String = try
	{
		context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
	}
	catch(e: Exception) { "" }

	/**
	 * Compara versões no formato do Windows (commit "updater version compare"):
	 * tira `v` e metadados depois de `+`, números do núcleo um a um (faltando
	 * vale 0), versão final vence pré-versão, e identificadores numéricos da
	 * pré-versão comparam como números ("beta.10" > "beta.9").
	 */
	internal fun comparar(a: String, b: String): Int
	{
		fun partes(v: String): Pair<List<Int>, List<String>>
		{
			val limpa = v.trim().removePrefix("v").substringBefore('+')
			val nucleo = limpa.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
			val pre = if(limpa.contains('-')) limpa.substringAfter('-').split('.') else emptyList()
			return nucleo to pre
		}
		val (na, pa) = partes(a)
		val (nb, pb) = partes(b)
		for(i in 0 until maxOf(na.size, nb.size))
		{
			val d = na.getOrElse(i) { 0 }.compareTo(nb.getOrElse(i) { 0 })
			if(d != 0) return d
		}
		if(pa.isEmpty() || pb.isEmpty())
			return pb.size.coerceAtMost(1) - pa.size.coerceAtMost(1)
		for(i in 0 until maxOf(pa.size, pb.size))
		{
			val x = pa.getOrNull(i) ?: return -1
			val y = pb.getOrNull(i) ?: return 1
			val xn = x.toIntOrNull()
			val yn = y.toIntOrNull()
			val d = if(xn != null && yn != null) xn.compareTo(yn)
				else if(xn != null) -1
				else if(yn != null) 1
				else x.compareTo(y, ignoreCase = true)
			if(d != 0) return d
		}
		return 0
	}

	private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
