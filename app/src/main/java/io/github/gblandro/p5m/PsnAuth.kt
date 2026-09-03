// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.util.Base64
import android.util.Log
import com.metallic.chiaki.lib.ChiakiPsn
import com.metallic.chiaki.lib.PsnDeviceList
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Login PSN e credenciais da conexão remota.
 *
 * ## Por que isto existe
 *
 * A lista de consoles do app é descoberta por broadcast UDP na rede local.
 * Broadcast não atravessa a internet: fora de casa, o console nunca aparece, e
 * não há ajuste que mude isso. O caminho que resta é o mesmo que o app oficial
 * usa -- furar o NAT dos dois lados com a ajuda dos servidores da Sony --, e
 * para falar com esses servidores é preciso um token da conta PSN.
 *
 * Todo o protocolo já estava compilado dentro do APK (`lib/src/remote/`); a
 * porta Android do chiaki é que nunca expôs nada disso. Este arquivo cuida da
 * parte que é HTTP comum, e o resto é do lado nativo.
 *
 * ## Onde as credenciais ficam
 *
 * Em [SharedPreferences] privadas do app, e em lugar nenhum mais. Não vão para
 * o repositório, não entram no diário, não aparecem na tela de diagnóstico.
 * O token de acesso expira em cerca de uma hora e é renovado pelo
 * `refresh_token` sem novo login.
 *
 * ## O duid
 *
 * O token só serve para furar NAT se tiver sido **criado** com um `duid` na URL
 * de login -- um identificador deste cliente. Ele é gerado uma vez pela
 * libchiaki e guardado: trocá-lo depois invalidaria os tokens que dependem
 * dele.
 */
object PsnAuth
{
	private const val TAG = "P5MVR"
	private const val PREFS = "p5m_psn"

	private const val KEY_ACCESS = "access_token"
	private const val KEY_REFRESH = "refresh_token"
	private const val KEY_EXPIRY = "expiry_ms"
	private const val KEY_ACCOUNT = "account_id_b64"
	private const val KEY_DUID = "client_duid"

	private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
	private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
	private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
	const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"

	// Os quatro escopos são exigidos pela furação de NAT e estão listados no
	// cabeçalho de lib/include/chiaki/remote/holepunch.h. Vão com espaços
	// literais, como o chiaki-ng desktop manda: é a forma que se sabe que a
	// Sony aceita, e "consertar" a codificação aqui seria testar às cegas de
	// dentro de um headset, longe de casa.
	private const val SCOPE = "psn:clientapp referenceDataService:countryConfig.read " +
			"pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"

	// Uma renovação preventiva de um minuto: um token que expira no meio da
	// furação falha lá adiante, com uma mensagem que não diz "o token venceu".
	private const val EXPIRY_MARGIN_MS = 60_000L

	private fun prefs(context: Context) =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	/** Identificador deste cliente, estável entre logins. */
	fun clientDuid(context: Context): String?
	{
		val saved = prefs(context).getString(KEY_DUID, null)
		if(!saved.isNullOrEmpty())
			return saved
		val fresh = ChiakiPsn.clientDuid()
		if(fresh.isNullOrEmpty())
		{
			Log.e(TAG, "PSN: libchiaki did not generate the client duid")
			return null
		}
		prefs(context).edit().putString(KEY_DUID, fresh).apply()
		return fresh
	}

	fun loginUrl(context: Context): String?
	{
		val duid = clientDuid(context) ?: return null
		return "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
				"?service_entity=urn:service-entity:psn" +
				"&response_type=code" +
				"&client_id=$CLIENT_ID" +
				"&redirect_uri=$REDIRECT" +
				"&scope=$SCOPE" +
				"&request_locale=pt_BR" +
				"&ui=pr" +
				"&service_logo=ps" +
				"&layout_type=popup" +
				"&smcid=remoteplay" +
				"&prompt=always" +
				"&PlatformPrivacyWs1=minimal" +
				"&duid=$duid&"
	}

	fun isLoggedIn(context: Context) =
		!prefs(context).getString(KEY_REFRESH, null).isNullOrEmpty()
				&& !prefs(context).getString(KEY_ACCOUNT, null).isNullOrEmpty()

	fun forget(context: Context)
	{
		// O duid fica: ele identifica o aparelho, não a conta, e regenerá-lo a
		// cada saída faria a Sony ver um cliente novo toda vez.
		prefs(context).edit()
			.remove(KEY_ACCESS).remove(KEY_REFRESH)
			.remove(KEY_EXPIRY).remove(KEY_ACCOUNT)
			.apply()
	}

	/** Id da conta em 8 bytes, como o lado nativo espera. */
	fun accountId(context: Context): ByteArray?
	{
		val b64 = prefs(context).getString(KEY_ACCOUNT, null) ?: return null
		return try
		{
			Base64.decode(b64, Base64.NO_WRAP).takeIf { it.size == 8 }
		}
		catch(e: Exception)
		{
			Log.e(TAG, "PSN: the stored account id is corrupt", e)
			null
		}
	}

	/**
	 * Troca o código do redirecionamento por tokens e descobre o id da conta.
	 *
	 * Faz rede: chame de uma thread. Devolve null em caso de sucesso, ou a
	 * mensagem do erro.
	 */
	fun finishLogin(context: Context, code: String): String?
	{
		val body = "grant_type=authorization_code&code=$code&scope=$SCOPE" +
				"&redirect_uri=$REDIRECT&"
		val json = try
		{
			post(TOKEN_URL, body)
		}
		catch(e: Exception)
		{
			return "A PSN recusou o login: ${e.message}"
		}
		storeToken(context, json)

		val access = json.optString("access_token")
		val userId = try
		{
			get("$TOKEN_URL/$access").optString("user_id")
		}
		catch(e: Exception)
		{
			return "Got the token, but the account id did not come with it: ${e.message}"
		}
		val numeric = userId.toLongOrNull()
			?: return "PSN returned an account id that is not numeric"

		// Oito bytes em little endian, que é a forma que o protocolo do Remote
		// Play espera -- não o número decimal nem o texto.
		val bytes = ByteArray(8)
		var v = numeric
		for(i in 0 until 8)
		{
			bytes[i] = (v and 0xff).toByte()
			v = v ushr 8
		}
		prefs(context).edit()
			.putString(KEY_ACCOUNT, Base64.encodeToString(bytes, Base64.NO_WRAP))
			.apply()
		Log.i(TAG, "PSN: sign-in complete, account id stored")
		return null
	}

	/**
	 * Token de acesso válido, renovando se estiver perto de vencer.
	 *
	 * Faz rede quando renova: chame de uma thread.
	 */
	fun validToken(context: Context): String?
	{
		val p = prefs(context)
		val access = p.getString(KEY_ACCESS, null)
		val expiry = p.getLong(KEY_EXPIRY, 0L)
		if(!access.isNullOrEmpty() && System.currentTimeMillis() + EXPIRY_MARGIN_MS < expiry)
			return access

		val refresh = p.getString(KEY_REFRESH, null)
		if(refresh.isNullOrEmpty())
			return null
		return try
		{
			val json = post(TOKEN_URL,
				"grant_type=refresh_token&refresh_token=$refresh&scope=$SCOPE" +
						"&redirect_uri=$REDIRECT&")
			storeToken(context, json)
			Log.i(TAG, "PSN: token refreshed without a new sign-in")
			json.optString("access_token").takeIf { it.isNotEmpty() }
		}
		catch(e: Exception)
		{
			Log.e(TAG, "PSN: failed to refresh the token", e)
			null
		}
	}

	/**
	 * Consoles da conta. Faz rede: chame de uma thread.
	 *
	 * Devolve o motivo em vez de uma lista vazia quando a falha é nossa —
	 * token vencido, servidor fora. Lista vazia sem erro quer dizer que a
	 * conta respondeu e não tem console visível, que é outra conversa.
	 */
	fun devices(context: Context): PsnDeviceList
	{
		// Antes da primeira conversa da libchiaki com a PSN. Faz rede, e já
		// estamos numa thread aqui; barato depois da primeira vez.
		CaBundle.ensureNetwork(context)
		val token = validToken(context)
			?: return PsnDeviceList("the token expired and could not be refreshed", emptyList())
		return ChiakiPsn.listDevices(token)
	}

	private fun storeToken(context: Context, json: JSONObject)
	{
		val expiresIn = json.optInt("expires_in", 3600).toLong()
		val edit = prefs(context).edit()
			.putString(KEY_ACCESS, json.optString("access_token"))
			.putLong(KEY_EXPIRY, System.currentTimeMillis() + expiresIn * 1000L)
		// Numa renovação a resposta pode não trazer refresh_token novo; nesse
		// caso o antigo continua valendo e sobrescrevê-lo com vazio derrubaria
		// o login inteiro.
		val refresh = json.optString("refresh_token")
		if(refresh.isNotEmpty())
			edit.putString(KEY_REFRESH, refresh)
		edit.apply()
	}

	private fun basicAuth(): String
	{
		val raw = "$CLIENT_ID:$CLIENT_SECRET".toByteArray(Charsets.UTF_8)
		return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
	}

	private fun post(url: String, body: String): JSONObject
	{
		val conn = URL(url).openConnection() as HttpURLConnection
		return try
		{
			conn.requestMethod = "POST"
			conn.doOutput = true
			conn.connectTimeout = 15_000
			conn.readTimeout = 20_000
			conn.setRequestProperty("Authorization", basicAuth())
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
			conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
			readJson(conn)
		}
		finally
		{
			conn.disconnect()
		}
	}

	private fun get(url: String): JSONObject
	{
		val conn = URL(url).openConnection() as HttpURLConnection
		return try
		{
			conn.requestMethod = "GET"
			conn.connectTimeout = 15_000
			conn.readTimeout = 20_000
			conn.setRequestProperty("Authorization", basicAuth())
			conn.setRequestProperty("Accept", "application/json")
			readJson(conn)
		}
		finally
		{
			conn.disconnect()
		}
	}

	private fun readJson(conn: HttpURLConnection): JSONObject
	{
		val code = conn.responseCode
		val stream = if(code in 200..299) conn.inputStream else conn.errorStream
		val text = stream?.let {
			BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
		} ?: ""
		if(code !in 200..299)
		{
			// O corpo do erro pode conter o token numa mensagem de eco; por isso
			// só o código e o campo de erro entram no diário, nunca o corpo
			// inteiro.
			val reason = try
			{
				JSONObject(text).optString("error_description").ifEmpty {
					JSONObject(text).optString("error")
				}
			}
			catch(e: Exception)
			{
				""
			}
			throw IllegalStateException("HTTP $code${if(reason.isEmpty()) "" else " ($reason)"}")
		}
		return JSONObject(text)
	}
}
