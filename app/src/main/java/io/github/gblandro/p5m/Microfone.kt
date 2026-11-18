// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.metallic.chiaki.lib.Session

/**
 * O microfone do Quest vai para o console, para o chat de voz.
 *
 * A fonte é a de chamada de voz, e não a crua: o som do jogo sai pelos
 * alto-falantes do headset, a dois centímetros do microfone, e sem o
 * cancelamento de eco quem está no grupo ouviria o próprio jogo de volta.
 *
 * O console espera estéreo em 48 kHz, em blocos de 10 ms; o microfone é mono,
 * então cada amostra vai nos dois canais.
 */
class Microfone(private val context: Context)
{
	@Volatile private var rodando = false
	private var thread: Thread? = null
	private var sessao: Session? = null

	fun start(session: Session)
	{
		sessao = session
		if(rodando)
			return
		if(!permitido(context))
		{
			Trace.log(context, "Microphone: permission missing; the console gets no voice")
			return
		}
		rodando = true
		thread = Thread({ laco(session) }, "p5m-microphone").also { it.start() }
	}

	/** Para e espera a thread: depois disto ninguém mais fala com a sessão nativa. */
	fun stop()
	{
		val ativa = rodando
		rodando = false
		thread?.join(500)
		thread = null
		if(ativa)
			sessao?.micMute(true)
	}

	/**
	 * Com o stream pausado -- headset fora da cabeça, app em segundo plano --, o
	 * microfone para de captar e o console vê o microfone mudo. A dev.225
	 * mandou voz por 2 h 24 min seguidas, a maior parte delas com o headset
	 * na mesa.
	 */
	fun pausar()
	{
		if(!rodando)
			return
		stop()
		Trace.log(context, "Microphone: paused with the stream")
	}

	fun retomar()
	{
		val s = sessao ?: return
		if(!rodando)
			start(s)
	}

	private fun laco(session: Session)
	{
		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
		val minimo = AudioRecord.getMinBufferSize(TAXA, AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT)
		val gravador = try
		{
			@Suppress("MissingPermission")
			AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, TAXA,
					AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
					maxOf(minimo, AMOSTRAS * 2 * 4))
		}
		catch(e: Exception)
		{
			Trace.log(context, "Microphone: could not open (${e.message})")
			rodando = false
			return
		}
		var eco: AcousticEchoCanceler? = null
		var ruido: NoiseSuppressor? = null
		try
		{
			// Dentro do try: um `enabled = true` que lance deixava o gravador
			// aberto, segurando o microfone do headset.
			eco = if(AcousticEchoCanceler.isAvailable())
				AcousticEchoCanceler.create(gravador.audioSessionId)?.also { it.enabled = true } else null
			ruido = if(NoiseSuppressor.isAvailable())
				NoiseSuppressor.create(gravador.audioSessionId)?.also { it.enabled = true } else null
			if(gravador.state != AudioRecord.STATE_INITIALIZED || !session.micStart())
			{
				Trace.log(context, "Microphone: did not start (recorder state ${gravador.state})")
				// Sem thread de pé, o estado diz que não roda: o próximo
				// retomar tenta de novo, em vez de achar que já está ligado.
				rodando = false
				return
			}
			session.micMute(false)
			gravador.startRecording()
			Trace.log(context, "Microphone: on (echo canceller ${if(eco != null) "on" else "unavailable"}, " +
					"noise suppressor ${if(ruido != null) "on" else "unavailable"})")
			val mono = ShortArray(AMOSTRAS)
			val estereo = ShortArray(AMOSTRAS * 2)
			var blocos = 0L
			var erro = 0
			while(rodando && erro == 0)
			{
				var lidas = 0
				while(lidas < AMOSTRAS && rodando)
				{
					val r = gravador.read(mono, lidas, AMOSTRAS - lidas)
					if(r < 0)
					{
						// Erro do gravador (serviço de áudio caiu, objeto morto)
						// não passa sozinho. Antes o laço voltava direto a ler e
						// girava sem pausa, com prioridade de áudio, até o fim
						// da sessão: um núcleo inteiro por nada.
						erro = r
						break
					}
					if(r == 0)
					{
						Thread.sleep(5)
						continue
					}
					lidas += r
				}
				if(lidas < AMOSTRAS)
					continue
				for(i in 0 until AMOSTRAS)
				{
					estereo[2 * i] = mono[i]
					estereo[2 * i + 1] = mono[i]
				}
				session.micFrame(estereo)
				blocos++
			}
			if(erro != 0)
			{
				Trace.log(context, "Microphone: the recorder failed (AudioRecord error $erro) after " +
						"${blocos / 100} s of voice; off until the stream resumes")
				rodando = false
				session.micMute(true)
			}
			else
				Trace.log(context, "Microphone: off after ${blocos / 100} s of voice sent")
		}
		finally
		{
			try { gravador.stop() } catch(e: Exception) { }
			gravador.release()
			eco?.release()
			ruido?.release()
		}
	}

	companion object
	{
		private const val TAXA = 48000
		private const val AMOSTRAS = 480

		fun permitido(context: Context) =
			context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
					PackageManager.PERMISSION_GRANTED
	}
}
