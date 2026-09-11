// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import java.util.zip.CRC32

/**
 * Numera todos os relatorios Bluetooth como um unico fluxo para o DualSense.
 *
 * O contador do cabecalho pertence ao transporte, nao a uma tela ou sessao.
 * Reinicia-lo ao alternar entre 0x31 e 0x32, ou ao abrir outro stream, faz o
 * controle receber pacotes antigos com numeros novos enquanto a conexao HID
 * continua a mesma. O contador do bloco de audio tambem atravessa sessoes.
 */
internal object DualSenseOutputSequence
{
	private var relatorio = 0
	private var audio = 0

	@Synchronized
	fun preparar(bytes: ByteArray)
	{
		if(bytes.size != 78 && bytes.size != 142)
			return
		val id = bytes[0].toInt() and 0xFF
		if(id != 0x31 && id != 0x32)
			return

		bytes[1] = (((relatorio and 0x0F) shl 4) or
			(bytes[1].toInt() and 0x0F)).toByte()
		relatorio = (relatorio + 1) and 0x0F
		if(id == 0x32)
		{
			bytes[10] = audio.toByte()
			audio = (audio + 1) and 0xFF
		}

		val crcOffset = bytes.size - 4
		val crc = CRC32()
		crc.update(0xA2)
		crc.update(bytes, 0, crcOffset)
		val valor = crc.value
		bytes[crcOffset] = (valor and 0xFF).toByte()
		bytes[crcOffset + 1] = ((valor shr 8) and 0xFF).toByte()
		bytes[crcOffset + 2] = ((valor shr 16) and 0xFF).toByte()
		bytes[crcOffset + 3] = ((valor shr 24) and 0xFF).toByte()
	}
}
