// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.X509Certificate

/**
 * Dá ao libcurl uma lista de autoridades certificadoras em que confiar.
 *
 * ## O defeito que isto conserta
 *
 * O primeiro login remoto de verdade morreu assim:
 *
 * ```
 * chiaki_holepunch_list_devices: ... failed with CURL error
 * SSL peer certificate or SSH remote key was not OK
 * ```
 *
 * Não era certificado inválido do lado da Sony. Era o nosso libcurl **sem nada
 * em que confiar**. O `CMakeLists.txt` do curl só procura um pacote de
 * certificados no sistema quando não está compilando cruzado
 * (`if(NOT CMAKE_CROSSCOMPILING ...)`), e compilar para Android é exatamente
 * isso — então `CURL_CA_BUNDLE` e `CURL_CA_PATH` saem indefinidos. O recurso de
 * último caso, `CURL_CA_FALLBACK`, vem desligado. Resultado: a biblioteca sobe
 * sem nenhuma raiz e recusa toda conexão HTTPS.
 *
 * Foi por isso que o login funcionou e a listagem não: o login é HTTP do lado
 * Kotlin, que usa a pilha TLS do Android e a loja de confiança do sistema. Só
 * as chamadas de dentro da libchiaki passam pelo curl.
 *
 * ## Por que exportar em vez de embutir
 *
 * Um `cacert.pem` embutido no APK envelhece: raízes são revogadas e adicionadas,
 * e um arquivo congelado em 2026 vira uma bomba-relógio que só explode longe de
 * casa. Aqui as raízes saem do `AndroidCAStore` — a mesma loja que o navegador
 * e o resto do sistema usam. O que o Android confia, nós confiamos, e nem uma
 * a mais.
 *
 * ## O caminho fixo
 *
 * `CURL_CA_BUNDLE` é macro de compilação, não variável de ambiente: o valor tem
 * de ser decidido no `build.gradle`. Por isso o arquivo mora num caminho
 * determinístico derivado do `applicationId`, e [ensure] confere que o caminho
 * onde escreveu é o mesmo que foi compilado — se um dia o pacote for renomeado
 * e só um dos dois lados mudar, a linha no diário diz isso em vez de o TLS
 * falhar sem explicação.
 */
object CaBundle
{
	private const val TAG = "P5MVR"
	private const val FILE_NAME = "p5m-ca.pem"
	private const val INTER_NAME = "p5m-intermediarios.pem"

	/**
	 * Intermediários baixados em tempo de build e embarcados no APK.
	 *
	 * São **candidatos**, não certificados confiados: cada um ainda passa pela
	 * mesma prova de [confiavel] no aparelho antes de entrar no pacote. Assim o
	 * download da CI é só uma conveniência -- tira a dependência de a rede onde
	 * o headset está alcançar o CDN da autoridade --, e a decisão de confiança
	 * continua sendo tomada aqui, contra as raízes deste aparelho.
	 */
	private const val ASSET_NAME = "ca-intermediarios.pem"

	/**
	 * Intermediários que os servidores da Sony não mandam.
	 *
	 * O diagnóstico do dev.85 mostrou o problema exato: o host de notificações
	 * manda **um certificado só** -- a folha, emitida pela "COMODO RSA Domain
	 * Validation Secure Server CA" -- e não manda essa autoridade junto. O
	 * OpenSSL então não tem como ligar a folha à raiz que temos, e recusa
	 * (código X509 20). O Android não se importa porque a pilha dele guarda
	 * intermediários e busca os que faltam; o OpenSSL não faz nem uma coisa nem
	 * outra.
	 *
	 * A URL é a que o próprio certificado da folha aponta na extensão AIA --
	 * "vá buscar meu emissor aqui". Buscar em vez de embutir é o mesmo
	 * princípio das raízes: um intermediário embutido envelhece, e a Sectigo
	 * roda os dela periodicamente.
	 */
	private val INTERMEDIARIOS = listOf(
		Intermediario(
			nome = "COMODO RSA Domain Validation Secure Server CA",
			urls = listOf(
				"http://crt.comodoca.com/COMODORSADomainValidationSecureServerCA.crt",
				"http://crt.sectigo.com/COMODORSADomainValidationSecureServerCA.crt")))

	private data class Intermediario(val nome: String, val urls: List<String>)

	/** Precisa bater com o -DCURL_CA_BUNDLE do build.gradle. */
	const val COMPILED_PATH = "/data/user/0/io.github.gblandro.p5m/files/$FILE_NAME"

	// Reescrito quando passa disto. As raízes mudam devagar, e reescrever a cada
	// abertura seria gastar disco e tempo de partida à toa.
	private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

	/**
	 * Garante o arquivo no lugar onde o curl vai procurar.
	 *
	 * Barato quando já existe. Chamado do [P5MApp], para valer em qualquer
	 * porta de entrada: a furação de NAT também fala com a PSN por curl, e ela
	 * roda dentro da activity de stream, não desta tela.
	 */
	fun ensure(context: Context)
	{
		val file = File(context.filesDir, FILE_NAME)
		if(file.absolutePath != COMPILED_PATH)
		{
			// Não é fatal: o curl vai procurar no caminho compilado, e se este
			// aparelho põe os dados noutro lugar, escrevemos nos dois.
			Log.w(TAG, "CA: the real path (${file.absolutePath}) is not the compiled " +
					"one ($COMPILED_PATH); writing to both")
		}
		val fresh = file.isFile && file.length() > 0 &&
				System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
		if(fresh)
			return

		val pem = try
		{
			build(context)
		}
		catch(e: Exception)
		{
			Log.e(TAG, "CA: failed to read the Android trust store", e)
			return
		}
		if(pem.isEmpty())
		{
			Log.e(TAG, "CA: the Android trust store came back empty; " +
					"curl will keep refusing HTTPS")
			return
		}

		write(file, pem)
		if(file.absolutePath != COMPILED_PATH)
			write(File(COMPILED_PATH), pem)
	}

	private fun write(file: File, pem: String)
	{
		try
		{
			file.parentFile?.mkdirs()
			file.writeText(pem)
			val raizes = pem.split("-----BEGIN CERTIFICATE-----").size - 1
			Log.i(TAG, "CA: $raizes roots written to ${file.absolutePath}")
		}
		catch(e: Exception)
		{
			Log.e(TAG, "CA: could not write to ${file.absolutePath}", e)
		}
	}

	/**
	 * Busca os intermediários que faltam e reescreve o pacote se achar algum.
	 *
	 * **Faz rede: nunca chame da thread principal.** Barato depois da primeira
	 * vez -- só relê um arquivo. Chamado das threads que já existem antes de
	 * qualquer conversa da libchiaki com a PSN.
	 */
	fun ensureNetwork(context: Context)
	{
		val arquivo = File(context.filesDir, INTER_NAME)
		val guardados = if(arquivo.isFile) arquivo.readText() else ""
		val faltando = INTERMEDIARIOS.filter { !guardados.contains(it.nome) }
		if(faltando.isEmpty())
			return

		val novos = StringBuilder(guardados)
		var achou = false
		for(inter in faltando)
		{
			// Primeiro o que veio dentro do APK: não depende de esta rede
			// alcançar o CDN da autoridade. Só se não vier de lá é que se
			// busca -- e os dois caminhos passam pela mesma prova.
			val pem = doApk(context, inter) ?: buscar(inter) ?: continue
			novos.append("# ").append(inter.nome).append('\n').append(pem)
			achou = true
		}
		if(!achou)
			return

		try
		{
			arquivo.writeText(novos.toString())
		}
		catch(e: Exception)
		{
			Log.e(TAG, "CA: could not store the intermediates", e)
			return
		}
		// O pacote precisa ser reescrito para o curl enxergar o que chegou.
		reescrever(context)
	}

	/** O intermediário que veio no APK, se ele existir e se provar confiável. */
	private fun doApk(context: Context, inter: Intermediario): String?
	{
		val candidatos = try
		{
			context.assets.open(ASSET_NAME).use {
				CertificateFactory.getInstance("X.509").generateCertificates(it)
			}
		}
		catch(e: Exception)
		{
			// Asset ausente é normal: a CI pode não ter conseguido baixar.
			return null
		}
		for(qualquer in candidatos)
		{
			val cert = qualquer as? X509Certificate ?: continue
			if(!cert.subjectX500Principal.name.contains(inter.nome))
				continue
			if(!confiavel(cert))
			{
				Log.w(TAG, "CA: the intermediate shipped in the APK does not " +
						"validate on this device; trying to download it")
				return null
			}
			Log.i(TAG, "CA: intermediate '${inter.nome}' shipped in the APK and validated")
			return paraPem(cert)
		}
		return null
	}

	/**
	 * Baixa, confere e converte um intermediário.
	 *
	 * A conferência é o que torna isto seguro: o certificado baixado só entra
	 * no pacote se **o próprio Android já confiar em quem o assinou**. Sem
	 * isso, seria buscar um certificado por HTTP e passar a confiar nele — que
	 * é exatamente o que não se pode fazer. Com isso, o pior caso de um
	 * download adulterado é não validar e ser descartado.
	 */
	private fun buscar(inter: Intermediario): String?
	{
		for(url in inter.urls)
		{
			// O `continue` mora fora do try de propósito: usá-lo como valor de
			// uma expressão try é terreno que eu não quero descobrir numa volta
			// de CI, e um null explícito diz a mesma coisa.
			val bytes = try
			{
				baixar(url)
			}
			catch(e: Exception)
			{
				Log.w(TAG, "CA: ${inter.nome} did not come from $url: ${e.message}")
				null
			}
			if(bytes == null)
				continue

			val cert = try
			{
				CertificateFactory.getInstance("X.509")
					.generateCertificate(bytes.inputStream()) as? X509Certificate
			}
			catch(e: Exception)
			{
				Log.w(TAG, "CA: what came from $url is not a certificate", e)
				null
			}
			if(cert == null)
			{
				Log.w(TAG, "CA: $url did not return an X.509 certificate")
				continue
			}
			if(!cert.subjectX500Principal.name.contains(inter.nome))
			{
				Log.w(TAG, "CA: $url returned '${cert.subjectX500Principal.name}', " +
						"not '${inter.nome}'")
				continue
			}
			if(!confiavel(cert))
			{
				Log.e(TAG, "CA: '${inter.nome}' does not validate against the Android " +
						"roots; discarded")
				continue
			}
			Log.i(TAG, "CA: intermediate '${inter.nome}' fetched and validated")
			return paraPem(cert)
		}
		Log.e(TAG, "CA: could not obtain the intermediate '${inter.nome}'; " +
				"the remote connection will keep being refused at TLS")
		return null
	}

	/**
	 * O certificado encadeia até uma raiz que o próprio Android confia?
	 *
	 * Duas tentativas, e as duas provam a mesma coisa. A validação PKIX é a
	 * completa, mas pode recusar por formalidade um certificado de autoridade
	 * posto como alvo do caminho -- ela foi feita pensando em folhas. Quando
	 * isso acontece, a conferência que realmente importa ainda pode ser feita
	 * direto: achar entre as raízes do Android aquela cujo titular é o emissor
	 * deste certificado, e verificar a assinatura com a chave dela.
	 *
	 * A segunda não é um afrouxamento da primeira: continua sendo "assinado por
	 * uma raiz em que este aparelho confia", que é exatamente a propriedade que
	 * nos autoriza a acrescentá-lo. O que ela dispensa são as regras de
	 * política e uso que não se aplicam a um intermediário isolado.
	 */
	private fun confiavel(cert: X509Certificate): Boolean
	{
		val store = try
		{
			KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
		}
		catch(e: Exception)
		{
			Log.e(TAG, "CA: could not open the Android store to validate", e)
			return false
		}

		try
		{
			val caminho = CertificateFactory.getInstance("X.509")
				.generateCertPath(listOf(cert))
			val params = PKIXParameters(store).apply {
				// Sem consulta de revogação: ela precisaria de rede que pode não
				// existir, e a assinatura pela raiz é o que estamos conferindo.
				isRevocationEnabled = false
			}
			CertPathValidator.getInstance("PKIX").validate(caminho, params)
			return true
		}
		catch(e: Exception)
		{
			Log.i(TAG, "CA: PKIX did not validate the intermediate (${e.message}); " +
					"falling back to a direct signature check")
		}

		val emissor = cert.issuerX500Principal
		for(alias in store.aliases())
		{
			val raiz = store.getCertificate(alias) as? X509Certificate ?: continue
			if(raiz.subjectX500Principal != emissor)
				continue
			try
			{
				cert.verify(raiz.publicKey)
				cert.checkValidity()
				Log.i(TAG, "CA: signature checked against root '$alias'")
				return true
			}
			catch(e: Exception)
			{
				// Titular igual e assinatura diferente acontece: raízes com o
				// mesmo nome e chaves distintas convivem na loja. Segue a busca.
			}
		}
		Log.e(TAG, "CA: no Android root signs '${cert.subjectX500Principal.name}'")
		return false
	}

	private fun baixar(url: String): ByteArray
	{
		val conn = URL(url).openConnection() as HttpURLConnection
		return try
		{
			conn.connectTimeout = 15_000
			conn.readTimeout = 20_000
			val code = conn.responseCode
			if(code !in 200..299)
				throw IllegalStateException("HTTP $code")
			conn.inputStream.readBytes()
		}
		finally
		{
			conn.disconnect()
		}
	}

	/** Reescreve o pacote a partir do que existe hoje. */
	private fun reescrever(context: Context)
	{
		val pem = try
		{
			build(context)
		}
		catch(e: Exception)
		{
			Log.e(TAG, "CA: failed to rebuild the bundle", e)
			return
		}
		if(pem.isEmpty())
			return
		write(File(context.filesDir, FILE_NAME), pem)
		if(File(context.filesDir, FILE_NAME).absolutePath != COMPILED_PATH)
			write(File(COMPILED_PATH), pem)
	}

	/** Um certificado em PEM, com as 64 colunas que o OpenSSL espera. */
	private fun paraPem(cert: X509Certificate): String
	{
		val b64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
		val out = StringBuilder("-----BEGIN CERTIFICATE-----\n")
		var i = 0
		while(i < b64.length)
		{
			val fim = minOf(i + 64, b64.length)
			out.append(b64, i, fim).append('\n')
			i = fim
		}
		return out.append("-----END CERTIFICATE-----\n").toString()
	}

	/** As raízes do sistema mais os intermediários já obtidos. */
	private fun build(context: Context): String
	{
		val store = KeyStore.getInstance("AndroidCAStore")
		store.load(null, null)
		val out = StringBuilder()
		for(alias in store.aliases())
		{
			val cert = store.getCertificate(alias) as? X509Certificate ?: continue
			out.append(paraPem(cert))
		}
		val inter = File(context.filesDir, INTER_NAME)
		if(inter.isFile)
			out.append(inter.readText())
		return out.toString()
	}
}
