// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.content.SharedPreferences

/**
 * Geometria da tela virtual, persistida entre sessoes.
 *
 * Guardamos em um arquivo proprio para nao misturar com as preferencias do
 * chiaki-ng, que vem do submodulo e podem mudar de chave a qualquer atualizacao.
 */
class ScreenPrefs(context: Context)
{
	private val prefs: SharedPreferences =
		context.getSharedPreferences("p5m_vr_screen", Context.MODE_PRIVATE)

	/** Distancia da tela em metros. */
	var radius: Float
		get() = prefs.getFloat(KEY_RADIUS, DEFAULT_RADIUS).coerceIn(MIN_RADIUS, MAX_RADIUS)
		set(value) { prefs.edit().putFloat(KEY_RADIUS, value.coerceIn(MIN_RADIUS, MAX_RADIUS)).apply() }

	/** Arco horizontal em radianos: quanto do campo de visao a tela ocupa. */
	var centralAngle: Float
		get() = prefs.getFloat(KEY_ANGLE, DEFAULT_ANGLE).coerceIn(MIN_ANGLE, MAX_ANGLE)
		set(value) { prefs.edit().putFloat(KEY_ANGLE, value.coerceIn(MIN_ANGLE, MAX_ANGLE)).apply() }

	/** Altura da tela em metros, relativa ao ponto de recentragem. */
	var heightOffset: Float
		get() = prefs.getFloat(KEY_HEIGHT, 0f).coerceIn(-1.5f, 1.5f)
		set(value) { prefs.edit().putFloat(KEY_HEIGHT, value.coerceIn(-1.5f, 1.5f)).apply() }

	/**
	 * Passthrough ligado por padrao: jogar vendo o quarto em volta e o modo
	 * preferido, nao a excecao. A tela do console fica opaca sobre o ambiente,
	 * e o que sobra do campo de visao mostra o mundo real.
	 */
	var passthroughEnabled: Boolean
		get() = prefs.getBoolean(KEY_PASSTHROUGH, true)
		set(value) { prefs.edit().putBoolean(KEY_PASSTHROUGH, value).apply() }

	/**
	 * Modo cinema: 0 desligado, 1 suave, 2 médio, 3 forte.
	 *
	 * Escurece e dessatura o passthrough sem tocar na tela. Não é opacidade —
	 * baixar a opacidade deixa o quarto translúcido, o que embaralha a imagem
	 * em vez de apagá-la. Isto age no brilho, no contraste e na saturação da
	 * própria câmera: a sala fica escura e sem cor, e a tela salta dela.
	 *
	 * Vive no mesmo botão do passthrough, e não num seu: é tudo "quanto do
	 * quarto eu quero ver", e o Triângulo já era esse botão.
	 */
	var cinema: Int
		get() = prefs.getInt(KEY_CINEMA, 0).coerceIn(0, 3)
		set(value) { prefs.edit().putInt(KEY_CINEMA, value.coerceIn(0, 3)).apply() }

	/**
	 * Forma da tela: 0 = cilindro (curva), 1 = quad (plana).
	 *
	 * Plana por padrão, por preferência de uso. L2 alterna em jogo.
	 */
	var layerShape: Int
		get() = prefs.getInt(KEY_SHAPE, SHAPE_QUAD).coerceIn(0, 1)
		set(value) { prefs.edit().putInt(KEY_SHAPE, value.coerceIn(0, 1)).apply() }

	/**
	 * Quanto a tela curva, de [CURVATURE_MIN] (quase reta) a 1 (máxima).
	 *
	 * Com 1, o olho fica no centro do cilindro e toda a superfície equidista —
	 * é a curva mais fechada possível, e abraça demais quem assiste. Valores
	 * menores recuam o eixo do cilindro para trás do olho: a tela continua à
	 * mesma distância, mas descreve um arco mais aberto.
	 */
	var curvature: Float
		get() = prefs.getFloat(KEY_CURVATURE, 0.35f).coerceIn(CURVATURE_MIN, 1f)
		set(value) { prefs.edit().putFloat(KEY_CURVATURE, value.coerceIn(CURVATURE_MIN, 1f)).apply() }

	/**
	 * Espelhamento vertical da imagem, corrigido pelo compositor.
	 *
	 * Ligado por padrão: o BufferQueue do Android entrega a imagem com a origem
	 * no canto oposto ao que o compositor assume, e sem isto o vídeo aparece de
	 * cabeça para baixo. Vale só no caminho direto — com shader, a matriz da
	 * SurfaceTexture já orienta a imagem.
	 */
	var verticalFlip: Boolean
		get() = prefs.getBoolean(KEY_FLIP, true)
		set(value) { prefs.edit().putBoolean(KEY_FLIP, value).apply() }

	companion object
	{
		/** Nome de cada degrau do modo cinema, na ordem. */
		val CINEMA_NAMES = listOf("normal", "soft", "medium", "strong")

		/**
		 * Brilho, contraste e saturação de cada degrau.
		 *
		 * Neutro é (0, 1, 1) — nesses valores o elo nem entra no encadeamento
		 * do estilo do passthrough. Os degraus escurecem e tiram cor juntos, e
		 * sobem um pouco o contraste: reduzir só o brilho deixaria a sala
		 * cinzenta e chapada em vez de escura.
		 */
		val CINEMA_GRADE = arrayOf(
			floatArrayOf(0.0f, 1.00f, 1.00f),
			floatArrayOf(-0.15f, 1.05f, 0.70f),
			floatArrayOf(-0.32f, 1.10f, 0.45f),
			floatArrayOf(-0.52f, 1.15f, 0.20f)
		)

		private const val KEY_SHAPE = "layer_shape"
		private const val KEY_FLIP = "vertical_flip"
		private const val KEY_CURVATURE = "curvature"

		// Abaixo disto o raio do arco fica grande demais para o compositor
		// tratar bem, e a diferenca para a tela plana ja nao se percebe.
		const val CURVATURE_MIN = 0.2f

		const val SHAPE_CYLINDER = 0
		const val SHAPE_QUAD = 1

		private const val KEY_RADIUS = "radius"
		private const val KEY_ANGLE = "central_angle"
		private const val KEY_HEIGHT = "height_offset"
		private const val KEY_PASSTHROUGH = "passthrough"
		private const val KEY_CINEMA = "cinema"

		// 4 m e ~70 graus: tela grande sem forcar convergencia, confortavel por horas.
		const val DEFAULT_RADIUS = 4.0f
		const val DEFAULT_ANGLE = 1.22f

		const val MIN_RADIUS = 1.5f
		const val MAX_RADIUS = 12.0f
		const val MIN_ANGLE = 0.35f   // ~20 graus
		const val MAX_ANGLE = 2.60f   // ~150 graus

		const val RADIUS_STEP = 0.25f
		const val ANGLE_STEP = 0.05f
		const val HEIGHT_STEP = 0.05f
	}
}
