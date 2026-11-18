// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * O visual das telas 2D, num lugar so (dev.213).
 *
 * Antes cada tela tinha a sua copia das cores, e elas divergiram: a conexao
 * pela PSN nem contorno de foco tinha, e com o controle nao se sabia o que
 * estava selecionado. Aqui ficam as cores, os tamanhos e as pecas que todas
 * usam.
 *
 * Inspirado no PS5, a pedido do dono, para o app combinar com o console que ele
 * espelha: fundo azul-marinho quase preto com degrade, titulos grandes e finos,
 * cartoes translucidos, o selecionado ganhando contorno branco, e o botao
 * principal que fica branco com texto preto quando o foco chega -- o gesto mais
 * reconhecivel da interface do console. O azul e o da PlayStation. Os
 * cartoes sao de vidro, uma pitada do Liquid Glass da Apple: ver [vidro].
 */
object Estilo
{
	// Fundo: o degrade do menu do PS5, de quase preto a azul-marinho.
	val FUNDO_TOPO = Color.parseColor("#0A1428")
	val FUNDO = Color.parseColor("#05070D")
	val TEXTO = Color.parseColor("#FFFFFF")
	/** Texto de apoio: 7:1 sobre o fundo, legivel a um metro e meio. */
	val SECUNDARIO = Color.parseColor("#A9B1BF")
	/** Rotulo de secao: mais apagado, mas ainda 5:1. */
	val SECAO = Color.parseColor("#8B95A6")
	val AZUL = Color.parseColor("#0070D1")
	val AZUL_FOCO = Color.parseColor("#1A86E8")
	/** Valor ativo e marcador aceso. */
	val AZUL_CLARO = Color.parseColor("#6FB2FF")
	val CONTORNO_FOCO = Color.WHITE
	val ALERTA = Color.parseColor("#FF7A7A")
	val ALERTA_FUNDO = Color.argb(90, 180, 40, 60)
	val DESTAQUE = Color.parseColor("#8CC4FF")
	val DESTAQUE_FUNDO = Color.argb(80, 0, 112, 209)

	// Quatro degraus de texto, e so. Eram sete, e os de baixo nao se liam no
	// painel flutuante.
	const val TAM_MARCA = 30f
	const val TAM_TITULO = 28f
	const val TAM_ITEM = 18f
	const val TAM_APOIO = 15f
	const val TAM_SECAO = 13f

	const val RAIO = 18
	const val FOCO_TRACO = 3

	val FINA: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
	val MEDIA: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
	val NEGRITO: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

	fun dp(context: Context, valor: Int): Int =
		(valor * context.resources.displayMetrics.density).toInt()

	/**
	 * O fundo: o degrade azul do PS5 e dois brilhos difusos. Os brilhos sao o
	 * que o vidro dos cartoes "refrata" -- sobre um fundo liso, um cartao
	 * translucido so pareceria cinza.
	 */
	fun fundoDaTela(): Drawable
	{
		val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(FUNDO_TOPO, FUNDO))
		val brilhoAzul = GradientDrawable().apply {
			gradientType = GradientDrawable.RADIAL_GRADIENT
			colors = intArrayOf(Color.argb(110, 30, 90, 210), Color.TRANSPARENT)
			gradientRadius = 900f
			setGradientCenter(0.15f, 0.05f)
		}
		val brilhoRoxo = GradientDrawable().apply {
			gradientType = GradientDrawable.RADIAL_GRADIENT
			colors = intArrayOf(Color.argb(70, 90, 60, 200), Color.TRANSPARENT)
			gradientRadius = 800f
			setGradientCenter(0.9f, 0.95f)
		}
		return android.graphics.drawable.LayerDrawable(arrayOf(base, brilhoAzul, brilhoRoxo))
	}

	fun arredondado(context: Context, cor: Int, raio: Int = RAIO): GradientDrawable =
		GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadius = dp(context, raio).toFloat()
			setColor(cor)
		}

	/**
	 * Vidro, a pitada de Liquid Glass que o dono pediu (dev.213).
	 *
	 * O Android do Quest nao desfoca o que esta atras de uma view sem custo, entao
	 * o efeito e imitado com o que da a leitura de vidro: preenchimento branco
	 * translucido, mais claro em cima que embaixo (o reflexo), e uma borda fina de
	 * luz. [intensidade] 1 e o cartao parado; 2, com foco.
	 */
	fun vidro(context: Context, intensidade: Int = 1, raio: Int = RAIO): Drawable
	{
		val (topo, base, borda) = when(intensidade)
		{
			0 -> Triple(14, 6, 26)
			1 -> Triple(30, 12, 46)
			else -> Triple(62, 30, 110)
		}
		return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
				intArrayOf(Color.argb(topo, 255, 255, 255), Color.argb(base, 255, 255, 255))).apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadius = dp(context, raio).toFloat()
			setStroke(maxOf(1, dp(context, 1)), Color.argb(borda, 255, 255, 255))
		}
	}

	/**
	 * Fundo com estado de foco, ponteiro e toque, em vidro.
	 *
	 * Dentro do headset o raio dos Touch da *hover*, e o gamepad da *foco*;
	 * sem retorno visual a tela parece travada. O contorno branco do foco vem
	 * do [MenuController], igual em todas as telas -- aqui o vidro clareia, para
	 * o ponteiro tambem ter resposta.
	 */
	fun tocavel(context: Context, raio: Int = RAIO): StateListDrawable = StateListDrawable().apply {
		addState(intArrayOf(android.R.attr.state_pressed), vidro(context, 2, raio))
		addState(intArrayOf(android.R.attr.state_focused), vidro(context, 2, raio))
		addState(intArrayOf(android.R.attr.state_hovered), vidro(context, 2, raio))
		addState(intArrayOf(android.R.attr.state_selected), vidro(context, 2, raio))
		addState(intArrayOf(), vidro(context, 1, raio))
	}

	/** O contorno de foco que o [MenuController] poe por cima do item. */
	fun contornoDeFoco(context: Context): GradientDrawable = GradientDrawable().apply {
		setColor(Color.TRANSPARENT)
		cornerRadius = dp(context, RAIO).toFloat()
		setStroke(dp(context, FOCO_TRACO), CONTORNO_FOCO)
	}

	fun titulo(context: Context, texto: String) = TextView(context).apply {
		text = texto
		setTextColor(TEXTO)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, TAM_TITULO)
		typeface = FINA
	}

	fun apoio(context: Context, texto: String) = TextView(context).apply {
		text = texto
		setTextColor(SECUNDARIO)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, TAM_APOIO)
		setLineSpacing(dp(context, 3).toFloat(), 1f)
	}

	/** Cabecalho de secao: separa sem pesar -- pequeno, espacado, apagado. */
	fun secao(context: Context, texto: String) = TextView(context).apply {
		text = texto.uppercase()
		setTextColor(SECAO)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, TAM_SECAO)
		typeface = MEDIA
		letterSpacing = 0.14f
		setPadding(dp(context, 6), dp(context, 20), dp(context, 6), dp(context, 10))
	}

	/** Cartao que dispara uma acao: o botao comum de todas as telas. */
	fun botao(context: Context, rotulo: String, onClick: () -> Unit) = TextView(context).apply {
		text = rotulo
		setTextColor(TEXTO)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, TAM_ITEM)
		typeface = MEDIA
		gravity = Gravity.CENTER_VERTICAL or Gravity.START
		setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14))
		minHeight = dp(context, 52)
		background = tocavel(context)
		isFocusable = true
		isClickable = true
		setOnClickListener { onClick() }
	}

	/**
	 * O botao principal: azul PlayStation parado, branco com texto preto com o
	 * foco -- como o "Jogar" do console.
	 */
	fun botaoPrincipal(context: Context, rotulo: String, onClick: () -> Unit) = TextView(context).apply {
		text = rotulo
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
		typeface = MEDIA
		gravity = Gravity.CENTER
		setPadding(dp(context, 28), dp(context, 16), dp(context, 28), dp(context, 16))
		minWidth = dp(context, 200)
		val estados = arrayOf(intArrayOf(android.R.attr.state_focused),
				intArrayOf(android.R.attr.state_hovered), intArrayOf(android.R.attr.state_pressed), intArrayOf())
		setTextColor(ColorStateList(estados, intArrayOf(Color.BLACK, Color.BLACK, Color.BLACK, Color.WHITE)))
		background = StateListDrawable().apply {
			addState(intArrayOf(android.R.attr.state_focused), arredondado(context, Color.WHITE, 28))
			addState(intArrayOf(android.R.attr.state_hovered), arredondado(context, Color.WHITE, 28))
			addState(intArrayOf(android.R.attr.state_pressed), arredondado(context, Color.parseColor("#DDE3EC"), 28))
			addState(intArrayOf(), arredondado(context, AZUL, 28))
		}
		isFocusable = true
		isClickable = true
		setOnClickListener { onClick() }
	}

	/**
	 * A marca: o icone do app e "P5M" em negrito, como no proprio icone, com o
	 * nome da tela ao lado em fino. Vai no topo de toda tela, sempre no mesmo
	 * lugar -- o dono pediu para o app aparecer como app, e nao como uma
	 * colecao de telas do chiaki (dev.213).
	 */
	fun marca(context: Context, tela: String? = null, tamanho: Float = TAM_MARCA) = LinearLayout(context).apply {
		orientation = LinearLayout.HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
		val lado = dp(context, (tamanho * 1.45f).toInt())
		addView(android.widget.ImageView(context).apply {
			setImageResource(com.metallic.chiaki.R.mipmap.ic_p5m)
			clipToOutline = true
			background = arredondado(context, Color.TRANSPARENT, 10)
			outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
		}, LinearLayout.LayoutParams(lado, lado).apply { rightMargin = dp(context, 14) })
		addView(TextView(context).apply {
			text = "P5M"
			setTextColor(TEXTO)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, tamanho)
			typeface = NEGRITO
			letterSpacing = 0.04f
		})
		if(tela != null)
		{
			addView(View(context).apply { setBackgroundColor(Color.argb(70, 255, 255, 255)) },
					LinearLayout.LayoutParams(dp(context, 1), dp(context, (tamanho * 0.9f).toInt())).apply {
						leftMargin = dp(context, 16); rightMargin = dp(context, 16)
					})
			addView(TextView(context).apply {
				text = tela
				setTextColor(SECUNDARIO)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, tamanho * 0.75f)
				typeface = FINA
			})
		}
	}

	fun margem(context: Context, topo: Int = 0, base: Int = 8) = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
		setMargins(0, dp(context, topo), 0, dp(context, base))
	}

	/** Linha de ajuda dos botoes do controle, no pe das telas. */
	fun ajudaDoControle(context: Context, texto: String =
			"D-pad  Move      Cross  Select      Circle  Back") = TextView(context).apply {
		text = texto
		setTextColor(SECAO)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
		setPadding(dp(context, 4), dp(context, 12), 0, 0)
	}

}
