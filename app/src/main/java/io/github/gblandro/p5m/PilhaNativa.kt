// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Pilha de chamada de uma queda nativa, capturada pelo próprio app.
 *
 * O `CrashCatcher` pega o que o Java lança; um `SIGSEGV` mata o processo sem
 * passar por ele. O histórico de saídas do Android diz **que** houve queda, mas
 * não onde. O tombstone diz onde, em offsets — e offset só vira nome com o `.so`
 * não removido e um `addr2line`, ou seja, com um PC. Este projeto não tem PC.
 *
 * Então o app resolve os símbolos sozinho, na hora da queda, com `dladdr`. O
 * resultado é aproximado: para uma função `static` ele devolve a exportada
 * anterior. Mas a diferença entre `offset 0x1c2000` e
 * `perto de chiaki_audio_receiver_...` é a diferença entre não saber e saber por
 * onde começar.
 *
 * Não substitui o tombstone: o handler anterior continua sendo chamado, e o
 * sistema registra tudo o que registrava.
 */
object PilhaNativa
{
	private const val ARQUIVO = "ultima_pilha_nativa.txt"
	private const val ARQUIVO_ANTERIOR = "ultima_pilha_nativa_anterior.txt"

	private fun arquivo(context: Context) = File(context.filesDir, ARQUIVO)
	private fun arquivoAnterior(context: Context) = File(context.filesDir, ARQUIVO_ANTERIOR)

	/** Instala os handlers. Chamado uma vez, na abertura do processo. */
	fun instalar(context: Context)
	{
		try
		{
			System.loadLibrary("p5m-vr")
			nativeInstalar(arquivo(context).absolutePath)
		}
		catch(e: Throwable)
		{
			Log.w("P5MVR", "Could not install the stack capture: ${e.message}")
		}
	}

	/** A pilha da última queda, ou null se não houve nenhuma nesta versão. */
	fun ultima(context: Context): String? =
		arquivo(context).takeIf { it.exists() }?.runCatching { readText() }
			?.getOrNull()?.takeIf { it.isNotBlank() }

	/** A pilha guardada na última troca de APK. */
	fun anterior(context: Context): String? =
		arquivoAnterior(context).takeIf { it.exists() }?.runCatching { readText() }
			?.getOrNull()?.takeIf { it.isNotBlank() }

	/**
	 * Move a pilha para o lugar de "anterior", na troca de versão.
	 *
	 * Mesmo motivo do diário e do crash: quem acabou de voltar para um APK
	 * antigo está atrás justamente do que a versão nova deixou.
	 */
	fun guardarAnterior(context: Context)
	{
		val atual = arquivo(context)
		val anterior = arquivoAnterior(context)
		if(atual.exists())
		{
			anterior.delete()
			if(!atual.renameTo(anterior))
				atual.delete()
		}
	}

	private external fun nativeInstalar(caminho: String): Boolean
}
