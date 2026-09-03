// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Application
import android.os.Handler
import android.os.Bundle
import android.app.Activity
import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guarda em arquivo a exceção que derrubou o app.
 *
 * Existe porque o Quest é usado longe do PC: sem isto, um crash na
 * inicialização aparece apenas como uma janela em branco, e descobrir a causa
 * exige cabo e `adb logcat`. Com o arquivo, a [DiagnosticActivity] mostra o
 * stack trace dentro do próprio headset.
 */
class P5MApp: Application()
{
	override fun onCreate()
	{
		super.onCreate()
		// Antes de startTraceCapture, e antes de qualquer linha escrita nesta
		// execucao: quem zera o diario tem de fazer isso com o diario ainda
		// parado, senao apaga o comeco da propria sessao que esta abrindo.
		// A captura de pilha vem primeiro de todas: ela e a unica coisa aqui que
		// serve se o proximo passo cair.
		PilhaNativa.instalar(this)
		Trace.rotateOnNewVersion(this)
		registrarMortesAnteriores(this)
		startTraceCapture()
		// Antes de qualquer coisa que fale HTTPS pelo curl. O libcurl que vai
		// dentro deste APK nasce sem nenhuma raiz em que confiar, e sem isto
		// toda chamada da libchiaki à PSN é recusada no aperto de mão. Fica no
		// ciclo de vida do processo, e não numa activity, porque a furação de
		// NAT também usa curl e roda na activity de stream.
		try
		{
			CaBundle.ensure(this)
		}
		catch(e: Throwable)
		{
			Log.e("P5MVR", "Failed to prepare the trust roots", e)
		}
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			try
			{
				saveCrash(this, thread.name, error)
			}
			catch(e: Throwable)
			{
				Log.e("P5MVR", "Failed to write the crash", e)
			}
			// Encadeia no handler original: o processo ainda precisa morrer
			// como morreria normalmente.
			previous?.uncaughtException(thread, error)
		}
	}

	/**
	 * Copia as linhas do app para o diário enquanto houver tela em primeiro
	 * plano, em qualquer modo.
	 *
	 * Antes isto vivia na activity imersiva, o que deixou o modo janela sem
	 * diagnóstico nenhum -- e janela é o padrão. O ciclo de vida do processo é o
	 * lugar certo: não depende de qual activity está aberta, e o modo janela usa
	 * a StreamActivity do chiaki-ng, onde não há código nosso para instrumentar.
	 *
	 * Só roda com activity visível: capturar com o app em segundo plano gastaria
	 * bateria para registrar o que não está acontecendo.
	 */
	private fun startTraceCapture()
	{
		// Thread propria, e nao a principal.
		//
		// O tick executa `logcat -d` -- um processo externo -- e le o buffer
		// inteiro. Na thread principal isso e dezenas de milissegundos de
		// bloqueio a cada dez segundos, no meio da sessao de video.
		//
		// Isto nao e precaucao: foi medido. Numa fonte de cadencia perfeita, com
		// o tick na thread principal, uma janela de dez segundos teve seis
		// entregas atrasadas e pico de 39,9 ms; com o tick fora, tres janelas
		// seguidas sairam com 600 de 600 no ritmo e pico de 18,9 ms. O maximo da
		// decodificacao caiu de 24,7 para 6,8 ms -- o decodificador nunca esteve
		// lento, estava esperando CPU junto com todo o resto.
		val ticker = android.os.HandlerThread("p5m-diario").also { it.start() }
		val handler = Handler(ticker.looper)
		val tick = object: Runnable {
			override fun run()
			{
				Trace.captureNativeLines(this@P5MApp)
				handler.postDelayed(this, TRACE_INTERVAL_MS)
			}
		}

		registerActivityLifecycleCallbacks(object: Application.ActivityLifecycleCallbacks
		{
			private var visible = 0

			override fun onActivityStarted(activity: Activity)
			{
				if(visible++ == 0)
					handler.post(tick)
			}

			override fun onActivityStopped(activity: Activity)
			{
				if(--visible <= 0)
				{
					visible = 0
					handler.removeCallbacks(tick)
					// Uma última passagem: o fim da sessão é justamente onde
					// estão as linhas que explicam por que ela terminou.
					Trace.captureNativeLines(this@P5MApp)
				}
			}

			override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
			override fun onActivityResumed(activity: Activity) = Unit
			override fun onActivityPaused(activity: Activity) = Unit
			override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
			override fun onActivityDestroyed(activity: Activity) = Unit
		})
	}

	companion object
	{
		/**
		 * Escreve no diário por que os processos anteriores morreram.
		 *
		 * Existe porque o diagnóstico estava mentindo. O handler de exceção
		 * abaixo pega o que o Java lança, e só isso: um `SIGSEGV` mata o
		 * processo sem passar por ele. Um diário com cinco quedas nativas na
		 * mesma noite continuava dizendo "nenhum crash registrado desde a
		 * instalação", e essa frase valia mais do que a verdade -- quem lê para
		 * de procurar.
		 *
		 * O Android guarda esse histórico desde a API 30, e ninguém precisa de
		 * PC para lê-lo. É a diferença entre "o app fechou sozinho" e "o app
		 * morreu com sinal fatal às 23:59".
		 *
		 * Só o motivo, sem a pilha: a pilha vem em protobuf e desempacotá-la
		 * custaria mais do que vale. Quem precisar dela pega o tombstone
		 * completo pelo logcat, que a captura de linhas nativas já traz.
		 */
		fun registrarMortesAnteriores(context: Context)
		{
			try
			{
				val am = context.getSystemService(Context.ACTIVITY_SERVICE)
						as android.app.ActivityManager
				val saidas = am.getHistoricalProcessExitReasons(context.packageName, 0, 6)
				if(saidas.isEmpty())
					return
				val formato = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
				for(s in saidas)
				{
					// Saída normal não é notícia; ocupa espaço e ensina a
					// ignorar as linhas vizinhas.
					if(s.reason == android.app.ApplicationExitInfo.REASON_EXIT_SELF ||
							s.reason == android.app.ApplicationExitInfo.REASON_USER_REQUESTED)
						continue
					val motivo = when(s.reason)
					{
						// SIGNALED e não CRASH_NATIVE: é assim que o Horizon OS
						// relata um SIGSEGV nosso. A primeira versão desta
						// função esperava CRASH_NATIVE e imprimiu "motivo 2"
						// para cinco quedas seguidas -- número cru, que é
						// exatamente o tipo de linha que não ajuda ninguém.
						android.app.ApplicationExitInfo.REASON_SIGNALED,
						android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ->
							"NATIVE CRASH (killed by signal)"
						android.app.ApplicationExitInfo.REASON_CRASH ->
							"unhandled Java exception"
						android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
						android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
						android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
							"initialization failure"
						android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
							"excessive resource use"
						android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED ->
							"a dependency died"
						android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE ->
							"permission change"
						// Rotina do sistema, e não notícia: reinstalação do APK
						// e faxina de processos vazios acontecem o tempo todo.
						// Ficam com o nome porque some-las esconderia a razão de
						// uma sessão ter acabado do nada.
						android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED ->
							"APK reinstalled"
						android.app.ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE ->
							"package changed"
						android.app.ApplicationExitInfo.REASON_OTHER -> "system housekeeping"
						android.app.ApplicationExitInfo.REASON_FREEZER -> "frozen"
						else -> "reason ${s.reason} (no known name)"
					}
					Trace.log(context, "Previous process (pid ${s.pid}) ended at " +
							"${formato.format(Date(s.timestamp))}: $motivo" +
							(s.description?.let { " -- $it" } ?: ""))
				}
			}
			catch(e: Throwable)
			{
				Log.w("P5MVR", "Could not read the exit history: ${e.message}")
			}
		}

		private const val TRACE_INTERVAL_MS = 10_000L
		private const val FILE_NAME = "ultimo_crash.txt"
		private const val FILE_ANTERIOR = "ultimo_crash_anterior.txt"

		private fun file(context: Context) = File(context.filesDir, FILE_NAME)
		private fun fileAnterior(context: Context) = File(context.filesDir, FILE_ANTERIOR)

		/** Crash da versão anterior, guardado na última troca de APK. */
		fun lastCrashAnterior(context: Context): String? =
			fileAnterior(context).takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

		/**
		 * Guarda o crash da versão que sai, em vez de apagá-lo.
		 *
		 * Um stack trace da versão passada exibido sob o cabeçalho da versão
		 * nova é a mentira mais convincente que este diário pode contar -- por
		 * isso ele sai da vista. Mas quem acabou de voltar para um APK antigo
		 * está atrás justamente dele, então some da vista sem sumir do disco.
		 */
		fun guardarAnterior(context: Context)
		{
			val atual = file(context)
			val anterior = fileAnterior(context)
			if(atual.exists())
			{
				anterior.delete()
				if(!atual.renameTo(anterior))
					atual.delete()
			}
		}

		fun saveCrash(context: Context, threadName: String, error: Throwable)
		{
			val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
			val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
			file(context).writeText(Trace.redact(
				"$timestamp\nthread: $threadName\n\n$stack"
			))
		}

		/**
		 * Último crash **Java** gravado, ou null se não houve nesta versão.
		 *
		 * Queda nativa nao passa por aqui: ela mata o processo sem dar chance ao
		 * handler. Ver registrarMortesAnteriores.
		 *
		 * "Nesta versão" e não "desde a instalação": o arquivo sobrevive a uma
		 * instalação por cima, e é o [Trace.rotateOnNewVersion] que o descarta
		 * quando o APK muda.
		 */
		fun lastCrash(context: Context): String? =
			file(context).takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

		fun clear(context: Context)
		{
			file(context).delete()
		}
	}
}
