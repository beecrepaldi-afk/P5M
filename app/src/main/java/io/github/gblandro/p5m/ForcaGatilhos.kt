// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import kotlin.math.roundToInt

/**
 * A intensidade dos gatilhos do PS5 (forte, media, fraca) aplicada dentro do
 * proprio efeito.
 *
 * O chiaki-ng faz isso pelo byte 39 do relatorio, a reducao de potencia dos
 * motores. Aqui ele nao entra: as fontes discordam sobre qual metade do byte e
 * dos gatilhos e qual e da vibracao (o chiaki-ng diz alta para os gatilhos, a
 * tabela do wiki diz o contrario), e na dev.199 -- PS5 em gatilhos "media",
 * 0x60 no byte -- o dono sentiu a vibracao pior, que e o que se espera se a
 * metade alta for a da vibracao. Mexer na forca que o proprio efeito carrega
 * nao tem essa duvida: o formato de cada modo e conhecido (tabela do Nielk1) e
 * so o gatilho le.
 *
 * Os fatores sao os que o chiaki-ng usa para a vibracao: metade e um terco.
 * Modo desconhecido passa como veio. So reduz, nunca aumenta.
 */
object ForcaGatilhos
{
	const val FORTE = 1f
	const val MEDIA = 0.5f
	const val FRACA = 0.33f

	/** A intensidade do PS5 (0 off, 1 forte, 2 media, 3 fraca) em fator. */
	fun fator(intensidade: Int): Float = when(intensidade)
	{
		0 -> 0f
		2 -> MEDIA
		3 -> FRACA
		else -> FORTE
	}

	/** Os 22 bytes do espelho (direito e esquerdo, 11 cada), escalados. */
	fun escalar(efeito: ByteArray, fator: Float): ByteArray
	{
		if(fator >= 1f || efeito.size < 22)
			return efeito
		val saida = efeito.copyOf()
		escalarUm(saida, 0, fator)
		escalarUm(saida, 11, fator)
		return saida
	}

	private fun escalarUm(b: ByteArray, i: Int, f: Float)
	{
		fun u(k: Int) = b[i + k].toInt() and 0xFF
		fun byte(k: Int, maximo: Int) {
			val v = u(k)
			if(v > 0)
				b[i + k] = maxOf(1, minOf(maximo, (v * f).roundToInt())).toByte()
		}
		when(u(0))
		{
			0x01 -> byte(2, 255)                // Simple_Feedback: inicio, forca
			0x02 -> byte(3, 255)                // Simple_Weapon: inicio, fim, forca
			0x06 -> byte(2, 255)                // Simple_Vibration: frequencia, forca, inicio
			0x11 -> byte(2, 10)                 // Limited_Feedback: inicio, forca 0-10
			0x12 -> byte(3, 10)                 // Limited_Weapon: inicio, fim, forca 0-10
			0x21, 0x26 -> tresBits(b, i + 3, 4, 10, f, menosUm = true)  // Feedback, Vibration: forca-1 por zona
			0x22 -> tresBits(b, i + 3, 2, 2, f, menosUm = true)         // Bow: forca-1 e estalo-1
			0x25 -> tresBits(b, i + 3, 1, 1, f, menosUm = true)         // Weapon: forca-1
			0x27 -> tresBits(b, i + 3, 1, 2, f, menosUm = false)        // Machine: amplitudes A e B
		}
	}

	/**
	 * `quantos` campos de 3 bits, em little-endian a partir de `inicio`
	 * (`bytes` de largura). Com `menosUm` o campo guarda a forca menos um, e
	 * a forca escalada nunca cai abaixo de um -- zona ativa continua ativa.
	 */
	private fun tresBits(b: ByteArray, inicio: Int, bytes: Int, quantos: Int, f: Float, menosUm: Boolean)
	{
		var v = 0L
		for(k in 0 until bytes)
			v = v or ((b[inicio + k].toLong() and 0xFF) shl (8 * k))
		for(c in 0 until quantos)
		{
			val campo = ((v shr (3 * c)) and 7).toInt()
			val novo = if(menosUm) maxOf(1, ((campo + 1) * f).roundToInt()) - 1
				else if(campo == 0) 0 else maxOf(1, (campo * f).roundToInt())
			v = (v and (7L shl (3 * c)).inv()) or (novo.toLong() shl (3 * c))
		}
		for(k in 0 until bytes)
			b[inicio + k] = ((v shr (8 * k)) and 0xFF).toByte()
	}
}
