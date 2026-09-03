// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.sqrt

/**
 * Calibração dos analógicos: onde cada um descansa, e quanto ele treme ali.
 *
 * ## Por que não basta uma zona morta
 *
 * A versão anterior tinha um número só, 10%, escolhido na mão: o pior repouso
 * medido num controle foi 7,4%, e arredondou-se para cima. Isso funciona
 * enquanto o controle é aquele. Num controle gasto o repouso passa de 10% e o
 * personagem anda sozinho; num controle novo, que treme 1%, os 10% jogam fora
 * um décimo do curso por precaução contra um problema que ele não tem.
 *
 * E há um caso que zona morta nenhuma resolve bem: um analógico gasto costuma
 * **descansar fora do centro**, não só tremer em torno dele. Um que descansa 8%
 * para a esquerda precisaria de 16% de zona morta para ficar quieto — e aí
 * perde 16% do curso em todas as direções, inclusive nas que estavam boas. O
 * conserto certo é subtrair o repouso e deixar a zona morta cobrir só o tremor,
 * que é o que esta classe guarda.
 *
 * ## O que fica guardado
 *
 * Por analógico: o ponto de repouso (`centerX`, `centerY`) e o raio do tremor
 * em torno dele. Ambos em fração do curso, de 0 a 1, medidos pela
 * [StickCalibrationActivity] com o controle largado.
 *
 * O padrão continua sendo centro no zero e 10% de raio — quem nunca calibrar
 * fica exatamente com o comportamento que já tinha, e nada muda por baixo de
 * ninguém.
 */
class StickPrefs(context: Context)
{
	private val prefs: SharedPreferences =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	/** Repouso e tremor de um analógico, já em fração do curso. */
	data class Calibragem(
		val centerX: Float,
		val centerY: Float,
		val deadzone: Float)
	{
		/** true quando isto é o padrão de fábrica, e não uma medição. */
		val medido: Boolean
			get() = centerX != 0f || centerY != 0f || deadzone != StickPrefs.DEFAULT_DEADZONE
	}

	var left: Calibragem
		get() = ler("l")
		set(value) = gravar("l", value)

	var right: Calibragem
		get() = ler("r")
		set(value) = gravar("r", value)

	/** Devolve os dois ao padrão, para quem calibrou com a mão no analógico. */
	fun reset()
	{
		prefs.edit().clear().apply()
	}

	private fun ler(lado: String) = Calibragem(
		prefs.getFloat("$lado$KEY_CX", 0f).coerceIn(-MAX_CENTER, MAX_CENTER),
		prefs.getFloat("$lado$KEY_CY", 0f).coerceIn(-MAX_CENTER, MAX_CENTER),
		prefs.getFloat("$lado$KEY_DZ", DEFAULT_DEADZONE).coerceIn(0f, MAX_DEADZONE))

	private fun gravar(lado: String, c: Calibragem)
	{
		prefs.edit()
			.putFloat("$lado$KEY_CX", c.centerX.coerceIn(-MAX_CENTER, MAX_CENTER))
			.putFloat("$lado$KEY_CY", c.centerY.coerceIn(-MAX_CENTER, MAX_CENTER))
			.putFloat("$lado$KEY_DZ", c.deadzone.coerceIn(0f, MAX_DEADZONE))
			.apply()
	}

	companion object
	{
		private const val PREFS = "p5m_sticks"
		private const val KEY_CX = "_cx"
		private const val KEY_CY = "_cy"
		private const val KEY_DZ = "_dz"

		/** Curso máximo de um eixo do Android, em unidades de [ControllerState]. */
		const val AXIS_MAX = 32767f

		/**
		 * O que valia antes desta tela existir, e o que continua valendo para
		 * quem não calibrar. Sair de 10% sem medir nada seria trocar um número
		 * escolhido a dedo por outro.
		 */
		const val DEFAULT_DEADZONE = 0.10f

		/**
		 * Tetos de sanidade, e não limites de gosto.
		 *
		 * Um repouso de 25% ou um tremor de 40% não são um controle gasto: são
		 * uma medição feita com o dedo no analógico. Guardar isso deixaria o
		 * controle sem metade do curso e a causa escondida numa tela que
		 * ninguém abre duas vezes. A calibração recusa antes de chegar aqui;
		 * estes tetos são a última rede, para o caso de um valor antigo ou
		 * escrito à mão.
		 */
		const val MAX_CENTER = 0.25f
		const val MAX_DEADZONE = 0.40f

		/**
		 * Aplica a calibração a um par de eixos crus.
		 *
		 * Vive aqui, e não na activity imersiva, porque os dois modos precisam
		 * dela: o imersivo tinha zona morta e o modo janela não tinha nenhuma,
		 * e um ajuste que só valesse em um deles seria pior do que não existir.
		 *
		 * A ordem é subtrair o repouso, depois cortar o tremor, depois
		 * reescalar. Reescalar é o que impede a zona morta de roubar curso: sem
		 * isso, 10% de zona morta viram 10% de alcance total perdido, e o
		 * personagem nunca corre no máximo.
		 *
		 * Radial e não por eixo: cortar X e Y separadamente deixaria a borda da
		 * zona quadrada, e o movimento na diagonal começaria antes do reto.
		 */
		fun aplicar(x: Short, y: Short, c: Calibragem): Pair<Short, Short>
		{
			// O repouso sai antes de tudo. O que sobra é deslocamento de
			// verdade, e não a soma do que a pessoa fez com o quanto o
			// analógico já estava torto parado.
			var fx = x.toFloat() / AXIS_MAX - c.centerX
			var fy = y.toFloat() / AXIS_MAX - c.centerY

			// Subtrair o centro pode empurrar a ponta do curso para fora do
			// círculo unitário do lado oposto ao desvio. Sem este corte, um
			// analógico torto mandaria valor acima do máximo em metade das
			// direções, que o console lê como fundo de curso antes da hora.
			val bruto = sqrt(fx * fx + fy * fy)
			if(bruto > 1f)
			{
				fx /= bruto
				fy /= bruto
			}

			val magnitude = minOf(bruto, 1f)
			if(magnitude <= c.deadzone)
				return Pair(0.toShort(), 0.toShort())

			val escala = ((magnitude - c.deadzone) / (1f - c.deadzone)).coerceAtMost(1f)
			val fator = escala / magnitude
			return Pair(
				(fx * fator * AXIS_MAX).toInt().coerceIn(-32767, 32767).toShort(),
				(fy * fator * AXIS_MAX).toInt().coerceIn(-32767, 32767).toShort())
		}
	}
}
