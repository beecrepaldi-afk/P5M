// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice

/**
 * Vibracao por Bluetooth para controles que nao expoem motor ao Android.
 *
 * O 8BitDo Ultimate 2 Wireless respondeu "sem motor" a varredura do `Rumble`
 * no diario da dev.231: o driver HID generico do Quest nao registra vibracao
 * para ele. O motor existe e obedece a um relatorio de saida do fabricante, e a
 * ponte HID que ja fala com o DualSense manda relatorio a qualquer controle
 * conectado -- basta escolher o controle pelo nome.
 *
 * Os formatos sao os que o SDL usa por Bluetooth nesses controles
 * (`src/joystick/hidapi`, drivers 8bitdo e xboxone):
 *
 *  - 8BitDo: `05 grave agudo gatilhoEsq gatilhoDir`, de 0 a 255. No Ultimate 2
 *    Wireless o SDL so liga vibracao a partir do firmware 1.03; no 1.02 o
 *    relatorio e aceito e nada vibra.
 *  - Xbox One S e Series: `03 0F gatilhoEsq gatilhoDir grave agudo FF 00 EB`,
 *    intensidades de 0 a 100. O FF/EB e "2,55 s, repetido 235 vezes": o
 *    controle segura o valor ate o proximo relatorio, e por isso o zero tem de
 *    ser mandado de verdade.
 *
 * Os motores dos gatilhos do Xbox ficam em zero: o console nao manda nada que
 * corresponda a eles.
 *
 * Conectar a ponte espera um callback de ate uns segundos, e cada envio e um
 * binder ao servico de Bluetooth. Por isso tudo roda numa thread propria, e so
 * o valor mais novo sai -- os eventos do console chegam dezenas por segundo.
 */
internal class PadHid(context: Context, val nome: String, val formato: Formato)
{
	enum class Formato(val rotulo: String) { OITO_BITDO("8BitDo"), XBOX("Xbox") }

	private val hid = DualSenseHid(context, nome)
	private val thread = HandlerThread("p5m-pad-hid").also { it.start() }
	private val fila = Handler(thread.looper)

	// Escritos por quem chama e lidos na fila; a trava e esta instancia.
	private var esquerdo = 0
	private var direito = 0
	// Sobe a cada pedido novo. O fim da buzina so zera se ninguem pediu nada
	// depois dela.
	private var geracao = 0

	// So a fila mexe nestes.
	private var ultimaFalhaMs = 0L
	private var falhas = 0
	private var enviouAlgum = false
	private var recusou = false

	private val envio = Runnable { mandarAtual() }

	/** Intensidades de 0 a 255. Nao bloqueia. */
	fun vibrar(esq: Int, dir: Int)
	{
		synchronized(this)
		{
			esquerdo = esq.coerceIn(0, 255)
			direito = dir.coerceIn(0, 255)
			geracao++
		}
		fila.removeCallbacks(envio)
		fila.post(envio)
	}

	/**
	 * Um toque que acaba sozinho: a buzina de teste do `Rumble`.
	 *
	 * O fim e contado depois do envio, e nao de quem pediu. A primeira conexao
	 * da ponte leva ate uns segundos, e um zero agendado de fora chegaria a fila
	 * antes dela terminar -- o valor mais novo venceria e a buzina nunca sairia.
	 */
	fun tocar(esq: Int, dir: Int, duracaoMs: Long)
	{
		val minha: Int
		synchronized(this)
		{
			esquerdo = esq.coerceIn(0, 255)
			direito = dir.coerceIn(0, 255)
			minha = ++geracao
		}
		fila.removeCallbacks(envio)
		fila.post {
			mandarAtual()
			fila.postDelayed({
				val ainda = synchronized(this) { geracao == minha }
				if(ainda)
					vibrar(0, 0)
			}, duracaoMs)
		}
	}

	/** Solta o motor e fecha a ponte. Para o fim da sessao. */
	fun fechar()
	{
		fila.removeCallbacksAndMessages(null)
		synchronized(this)
		{
			esquerdo = 0
			direito = 0
			geracao++
		}
		fila.post {
			if(enviouAlgum)
				mandar(0, 0)
			hid.fechar()
		}
		thread.quitSafely()
	}

	private fun mandarAtual()
	{
		val l: Int
		val r: Int
		synchronized(this)
		{
			l = esquerdo
			r = direito
		}

		// O controle pode ter desligado ou nunca ter estado no Bluetooth (pelo
		// cabo ele nao passa pela ponte). Tentar de novo a cada pedido seria
		// uma chamada ao servico por evento do console.
		val agora = SystemClock.uptimeMillis()
		if(falhas > 0 && agora - ultimaFalhaMs < NOVA_TENTATIVA_MS)
			return
		if(!hid.conectar())
		{
			if(falhas++ == 0)
				Log.w(TAG, "Rumble: the Bluetooth HID bridge did not reach '$nome'; " +
						"trying again at most every ${NOVA_TENTATIVA_MS / 1000} s")
			ultimaFalhaMs = agora
			hid.fechar()
			return
		}
		if(falhas > 0)
		{
			Log.i(TAG, "Rumble: the Bluetooth HID bridge reached '$nome' after $falhas failed attempt(s)")
			falhas = 0
		}

		if(!mandar(l, r) && hid.recuperar())
			mandar(l, r)

		// Reforco enquanto vibra. Nenhum dos dois formatos diz que o controle
		// solta sozinho, mas um relatorio perdido no enlace deixaria o motor
		// parado ou preso ate o proximo evento do console.
		if(l > 0 || r > 0)
			fila.postDelayed(envio, REFORCO_MS)
	}

	private fun mandar(l: Int, r: Int): Boolean
	{
		val relatorio = when(formato)
		{
			Formato.OITO_BITDO -> byteArrayOf(0x05, l.toByte(), r.toByte(), 0, 0)
			Formato.XBOX -> byteArrayOf(0x03, 0x0F, 0, 0,
				(l * 100 / 255).toByte(), (r * 100 / 255).toByte(),
				0xFF.toByte(), 0x00, 0xEB.toByte())
		}
		val aceito = hid.mandar(relatorio)
		if(aceito && !enviouAlgum)
		{
			enviouAlgum = true
			Log.i(TAG, "Rumble: first ${formato.rotulo} report accepted by the Bluetooth HID " +
					"bridge for '$nome'. Accepted is not felt: if nothing vibrated, the " +
					"controller ignores this report")
		}
		else if(!aceito && !recusou)
		{
			recusou = true
			Log.w(TAG, "Rumble: the Bluetooth HID bridge refused the ${formato.rotulo} report for '$nome'")
		}
		return aceito
	}

	companion object
	{
		private const val TAG = "P5MVR"
		private const val REFORCO_MS = 1_000L
		private const val NOVA_TENTATIVA_MS = 2_000L

		private const val VENDOR_8BITDO = 0x2DC8
		private const val VENDOR_MICROSOFT = 0x045E

		/** O formato que este controle entende, ou null se nao conhecemos. */
		fun formato(device: InputDevice): Formato?
		{
			val nome = device.name.lowercase()
			return when
			{
				device.vendorId == VENDOR_8BITDO || nome.contains("8bitdo") -> Formato.OITO_BITDO
				device.vendorId == VENDOR_MICROSOFT || nome.contains("xbox") -> Formato.XBOX
				else -> null
			}
		}
	}
}
