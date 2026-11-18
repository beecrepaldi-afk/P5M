package io.github.gblandro.p5m

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Desenho do painel de ajustes (L3+R3 e variantes), numa faixa larga e baixa.
 *
 * O painel antigo era um quadro de 1024x768 no meio da vista, com treze linhas
 * de texto do mesmo peso: o botao em amarelo, e o valor escondido no fim de uma
 * frase. O dono achou feio e, principalmente, achou que tapava o jogo. Este e o
 * redesenho da dev.210, aprovado numa previa: cinco colunas de tres linhas,
 * logo abaixo da tela do jogo.
 *
 * - Os botoes aparecem como na mao: as quatro formas do PlayStation nas cores
 *   delas, o direcional desenhado, e o resto em pilula com o nome.
 * - Rotulo pequeno em cima, valor grande embaixo: quem abre o painel quer saber
 *   em que estado esta cada coisa, e isso tem de ser o que salta.
 * - Ajuste de degraus ganha pontinhos, para ver de longe onde se esta no ciclo.
 * - O que acabou de mudar fica realcado por um instante: confirma que o botao
 *   pegou sem precisar achar a linha.
 * - Medidores numa coluna propria, com uma bolinha verde, amarela ou vermelha.
 *
 * So desenha: quem sabe o estado e a atividade, que monta as [Linha]s.
 */
object PainelDeAjuste
{
	sealed class Botao
	{
		object DirecionalVertical: Botao()
		object DirecionalHorizontal: Botao()
		object Cruz: Botao()
		object Circulo: Botao()
		object Quadrado: Botao()
		object Triangulo: Botao()
		data class Nome(val texto: String): Botao()
	}

	sealed class Extra
	{
		object Nada: Extra()
		/** Degrau [atual] de [total], de zero. */
		data class Degraus(val total: Int, val atual: Int): Extra()
		data class Etiqueta(val texto: String, val estado: Estado): Extra()
		data class Chave(val ligada: Boolean): Extra()
	}

	enum class Estado { BOM, ATENCAO, RUIM }

	data class Linha(val botao: Botao, val rotulo: String, val valor: String,
			val extra: Extra = Extra.Nada, val realce: Boolean = false)

	data class Medidor(val nome: String, val valor: String, val detalhe: String, val estado: Estado)

	private val TEXTO = Color.rgb(238, 241, 247)
	private val SUAVE = Color.rgb(142, 152, 173)
	private val CABECALHO = Color.rgb(125, 135, 156)
	private val DESTAQUE = Color.rgb(140, 188, 255)
	private val AVISO_TEXTO = Color.rgb(217, 184, 112)
	private val COR_CRUZ = Color.rgb(127, 180, 238)
	private val COR_CIRCULO = Color.rgb(255, 122, 122)
	private val COR_QUADRADO = Color.rgb(230, 144, 207)
	private val COR_TRIANGULO = Color.rgb(67, 214, 178)

	fun cor(estado: Estado) = when(estado)
	{
		Estado.BOM -> Color.rgb(95, 211, 141)
		Estado.ATENCAO -> Color.rgb(255, 199, 102)
		Estado.RUIM -> Color.rgb(255, 110, 110)
	}

	private const val MARGEM = 52f
	private const val VAO = 44f
	private const val COLUNAS = 5
	/** Topo de cada uma das tres linhas. */
	private val TOPOS = floatArrayOf(170f, 250f, 330f)

	/**
	 * Fundo e moldura do painel e das faixas de aviso: a mesma camada, o mesmo
	 * visual, para nada parecer que veio de outro app.
	 */
	fun fundo(canvas: Canvas, r: RectF)
	{
		// O vidro das telas do app (Estilo.vidro, dev.213), com fundo quase
		// opaco: aqui ele fica por cima do jogo, e texto sobre cena clara
		// precisa de base firme. Azul-marinho do PS5 em cima, reflexo claro
		// descendo, e a borda fina de luz.
		canvas.drawRoundRect(r, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
			shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom,
					Color.argb(240, 14, 26, 50), Color.argb(240, 6, 9, 16), android.graphics.Shader.TileMode.CLAMP)
		})
		canvas.drawRoundRect(r, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
			shader = android.graphics.LinearGradient(0f, r.top, 0f, r.top + (r.height() * 0.5f),
					Color.argb(26, 255, 255, 255), Color.argb(0, 255, 255, 255), android.graphics.Shader.TileMode.CLAMP)
		})
		canvas.drawRoundRect(r, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.STROKE
			strokeWidth = 2f
			color = Color.argb(60, 255, 255, 255)
		})
	}

	/**
	 * [tela] tem seis linhas (duas colunas), [imagem] tres, [outros] tres
	 * (3D, gatilhos, touchpad), e [medidores] ate cinco.
	 */
	fun desenhar(bitmap: Bitmap, atalho: String, tela: List<Linha>, imagem: List<Linha>,
			outros: List<Linha>, medidores: List<Medidor>)
	{
		val canvas = Canvas(bitmap)
		canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
		val w = bitmap.width.toFloat()
		val h = bitmap.height.toFloat()
		fundo(canvas, RectF(8f, 8f, w - 8f, h - 8f))

		cabecalho(canvas, w, atalho)

		val largura = (w - 2 * MARGEM - (COLUNAS - 1) * VAO) / COLUNAS
		fun coluna(i: Int) = (MARGEM + i * (largura + VAO)).let { it to it + largura }

		secao(canvas, coluna(0).first, coluna(1).second, 146f, "SCREEN")
		secao(canvas, coluna(2).first, coluna(3).second, 146f, "PICTURE & CONTROLLER")
		secao(canvas, coluna(4).first, coluna(4).second, 146f, "LIVE")

		val colunas = listOf(tela.take(3), tela.drop(3).take(3), imagem.take(3), outros.take(3))
		for((i, linhas) in colunas.withIndex())
		{
			val (x0, x1) = coluna(i)
			for((j, linha) in linhas.withIndex())
				linha(canvas, x0, x1, TOPOS[j], linha)
		}

		// Divisoria antes dos medidores: sao outra natureza de coisa, leitura e
		// nao ajuste.
		val xDiv = coluna(4).first - VAO / 2f
		canvas.drawRect(xDiv, 160f, xDiv + 1f, h - 30f, Paint().apply { color = Color.argb(18, 255, 255, 255) })
		medidores(canvas, coluna(4).first, coluna(4).second, medidores)
	}

	private fun cabecalho(canvas: Canvas, w: Float, atalho: String)
	{
		val titulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = TEXTO
			textSize = 36f
			typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
		}
		// A marca antes do nome da tela, como no topo das telas do app.
		canvas.drawText("P5M", MARGEM, 78f, titulo)
		var x = MARGEM + titulo.measureText("P5M") + 18f
		canvas.drawRect(x, 50f, x + 2f, 82f, Paint().apply { color = Color.argb(70, 255, 255, 255) })
		x += 20f
		val nomeDaTela = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = SUAVE
			textSize = 34f
			typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
		}
		canvas.drawText("Screen settings", x, 78f, nomeDaTela)
		x += nomeDaTela.measureText("Screen settings") + 32f
		canvas.drawCircle(x + 7f, 66f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cor(Estado.ATENCAO) })
		// O jogo nao pausa: so os botoes do painel deixam de ir ao console, e os
		// analogicos ficam mudos. Dizer "pausado" seria prometer o que nao ha.
		canvas.drawText("The game gets no input while this is open", x + 24f, 74f,
				Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AVISO_TEXTO; textSize = 22f })

		// Da direita para a esquerda: segurar Circle, Circle, o acorde.
		val dica = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SUAVE; textSize = 22f; textAlign = Paint.Align.RIGHT }
		x = w - MARGEM
		canvas.drawText("hold to end the session", x, 74f, dica)
		x -= dica.measureText("hold to end the session") + 14f
		forma(canvas, Botao.Circulo, x - 16f, 66f, 15f)
		x -= 32f + 26f
		canvas.drawText("close  ·", x, 74f, dica)
		x -= dica.measureText("close  ·") + 14f
		forma(canvas, Botao.Circulo, x - 16f, 66f, 15f)
		x -= 32f + 16f
		canvas.drawText("or", x, 74f, dica)
		x -= dica.measureText("or") + 14f
		val larg = pilula(canvas, 0f, 0f, atalho, 19f, medir = true)
		pilula(canvas, x - larg / 2f, 66f, atalho, 19f)

		canvas.drawRect(MARGEM, 112f, w - MARGEM, 113f, Paint().apply { color = Color.argb(18, 255, 255, 255) })
	}

	private fun secao(canvas: Canvas, x0: Float, x1: Float, y: Float, texto: String)
	{
		val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = CABECALHO
			textSize = 17f
			letterSpacing = 0.15f
			typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
		}
		canvas.drawText(texto, x0, y, p)
		val fim = x0 + p.measureText(texto) + 16f
		if(fim < x1)
			canvas.drawRect(fim, y - 6f, x1, y - 5f, Paint().apply { color = Color.argb(18, 255, 255, 255) })
	}

	private fun linha(canvas: Canvas, x0: Float, x1: Float, y: Float, l: Linha)
	{
		if(l.realce)
			canvas.drawRoundRect(RectF(x0 - 12f, y + 2f, x1 + 12f, y + 76f), 14f, 14f,
					Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(33, 140, 188, 255) })

		val cx = x0 + 34f
		val cy = y + 38f
		when(val b = l.botao)
		{
			is Botao.Nome -> pilula(canvas, cx, cy, b.texto, 19f)
			Botao.DirecionalVertical, Botao.DirecionalHorizontal -> direcional(canvas, cx, cy, b == Botao.DirecionalVertical)
			else ->
			{
				canvas.drawCircle(cx, cy, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(18, 255, 255, 255) })
				forma(canvas, b, cx, cy, 16f)
			}
		}

		val texto0 = x0 + 86f
		canvas.drawText(l.rotulo, texto0, y + 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SUAVE; textSize = 21f })
		val valor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = TEXTO
			textSize = 29f
			typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
		}
		// O extra a direita tem prioridade; o valor encolhe antes de invadir.
		val limite = x1 - larguraDoExtra(l.extra) - 12f
		var texto = l.valor
		while(texto.length > 3 && texto0 + valor.measureText(texto) > limite)
			texto = texto.dropLast(2) + "…"
		canvas.drawText(texto, texto0, y + 66f, valor)

		when(val e = l.extra)
		{
			is Extra.Degraus -> degraus(canvas, x1, y, e.total, e.atual)
			is Extra.Etiqueta -> etiqueta(canvas, x1, y, e.texto, cor(e.estado))
			is Extra.Chave -> chave(canvas, x1, y, e.ligada)
			Extra.Nada -> {}
		}
	}

	private fun larguraDoExtra(e: Extra): Float = when(e)
	{
		is Extra.Degraus -> e.total * 11f + (e.total - 1) * 7f
		is Extra.Etiqueta -> Paint().apply { textSize = 18f }.measureText(e.texto) + 20f
		is Extra.Chave -> 56f
		Extra.Nada -> 0f
	}

	private fun medidores(canvas: Canvas, x0: Float, x1: Float, lista: List<Medidor>)
	{
		val nome = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SUAVE; textSize = 21f }
		val valor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = TEXTO
			textSize = 25f
			textAlign = Paint.Align.RIGHT
			typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
		}
		val detalhe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CABECALHO; textSize = 17f }
		if(lista.isEmpty())
		{
			canvas.drawText("No readings from this runtime", x0, TOPOS[0] + 40f, nome)
			return
		}
		// Cinco cabem com o passo um pouco mais curto: o do controle (dev.216)
		// entra sem tirar o termico.
		val passo = if(lista.size > 4) 50f else 60f
		var y = TOPOS[0] - 16f
		for(m in lista.take(5))
		{
			canvas.drawCircle(x0 + 8f, y + 42f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cor(m.estado) })
			canvas.drawText(m.nome, x0 + 26f, y + 49f, nome)
			canvas.drawText(m.valor, x1, y + 49f, valor)
			canvas.drawText(m.detalhe, x0 + 26f, y + 70f, detalhe)
			y += passo
		}
	}

	/** Pilula com o nome do botao. Com [medir], so devolve a largura. */
	private fun pilula(canvas: Canvas, cx: Float, cy: Float, texto: String, tamanho: Float,
			medir: Boolean = false): Float
	{
		val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = TEXTO
			textSize = tamanho
			textAlign = Paint.Align.CENTER
			typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
		}
		val largura = maxOf(44f, p.measureText(texto) + 22f)
		if(medir)
			return largura
		canvas.drawRoundRect(RectF(cx - largura / 2f, cy - 18f, cx + largura / 2f, cy + 18f), 10f, 10f,
				Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(26, 255, 255, 255) })
		canvas.drawText(texto, cx, cy + 7f, p)
		return largura
	}

	private fun forma(canvas: Canvas, b: Botao, cx: Float, cy: Float, r: Float)
	{
		val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.STROKE
			strokeWidth = 3.5f
			strokeCap = Paint.Cap.ROUND
			strokeJoin = Paint.Join.ROUND
		}
		when(b)
		{
			Botao.Circulo ->
			{
				p.color = COR_CIRCULO
				canvas.drawCircle(cx, cy, r * 0.8f, p)
			}
			Botao.Cruz ->
			{
				p.color = COR_CRUZ
				val d = r * 0.7f
				canvas.drawLine(cx - d, cy - d, cx + d, cy + d, p)
				canvas.drawLine(cx + d, cy - d, cx - d, cy + d, p)
			}
			Botao.Quadrado ->
			{
				p.color = COR_QUADRADO
				val d = r * 0.65f
				canvas.drawRect(cx - d, cy - d, cx + d, cy + d, p)
			}
			Botao.Triangulo ->
			{
				p.color = COR_TRIANGULO
				val path = Path().apply {
					moveTo(cx, cy - r * 0.8f)
					lineTo(cx + r * 0.8f, cy + r * 0.55f)
					lineTo(cx - r * 0.8f, cy + r * 0.55f)
					close()
				}
				canvas.drawPath(path, p)
			}
			else -> {}
		}
	}

	/** O direcional, com os dois bracos do eixo que o ajuste usa acesos. */
	private fun direcional(canvas: Canvas, cx: Float, cy: Float, vertical: Boolean)
	{
		val a = 8f
		val b = 22f
		val apagado = Paint().apply { color = Color.argb(56, 255, 255, 255) }
		canvas.drawRect(cx - a, cy - b, cx + a, cy + b, apagado)
		canvas.drawRect(cx - b, cy - a, cx + b, cy + a, apagado)
		val aceso = Paint().apply { color = TEXTO }
		if(vertical)
		{
			canvas.drawRect(cx - a, cy - b, cx + a, cy - a, aceso)
			canvas.drawRect(cx - a, cy + a, cx + a, cy + b, aceso)
		}
		else
		{
			canvas.drawRect(cx - b, cy - a, cx - a, cy + a, aceso)
			canvas.drawRect(cx + a, cy - a, cx + b, cy + a, aceso)
		}
	}

	private fun degraus(canvas: Canvas, xDireita: Float, y: Float, total: Int, atual: Int)
	{
		val d = 11f
		val g = 7f
		var x = xDireita - (total * d + (total - 1) * g)
		val aceso = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DESTAQUE }
		val apagado = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 255, 255, 255) }
		for(k in 0 until total)
		{
			canvas.drawRoundRect(RectF(x, y + 50f, x + d, y + 50f + d), 3f, 3f, if(k <= atual) aceso else apagado)
			x += d + g
		}
	}

	private fun etiqueta(canvas: Canvas, xDireita: Float, y: Float, texto: String, cor: Int)
	{
		val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = cor
			textSize = 18f
			textAlign = Paint.Align.CENTER
			typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
		}
		val largura = p.measureText(texto) + 20f
		canvas.drawRoundRect(RectF(xDireita - largura, y + 36f, xDireita, y + 66f), 15f, 15f,
				Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(51, Color.red(cor), Color.green(cor), Color.blue(cor)) })
		canvas.drawText(texto, xDireita - largura / 2f, y + 57f, p)
	}

	private fun chave(canvas: Canvas, xDireita: Float, y: Float, ligada: Boolean)
	{
		canvas.drawRoundRect(RectF(xDireita - 56f, y + 37f, xDireita, y + 67f), 15f, 15f,
				Paint(Paint.ANTI_ALIAS_FLAG).apply {
					color = if(ligada) Color.rgb(60, 127, 224) else Color.argb(41, 255, 255, 255)
				})
		canvas.drawCircle(if(ligada) xDireita - 15f else xDireita - 41f, y + 52f, 11f,
				Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
	}

	/**
	 * Faixa de aviso curta, no alto da camada, com o resto transparente. Serve
	 * ao lembrete do atalho e ao aviso de controle lento.
	 */
	fun faixa(bitmap: Bitmap, titulo: String, corTitulo: Int, linha1: String, linha2: String,
			pilulaTitulo: Boolean = false)
	{
		val canvas = Canvas(bitmap)
		canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
		val w = bitmap.width.toFloat()
		val r = RectF(w / 2f - 720f, 8f, w / 2f + 720f, 200f)
		fundo(canvas, r)
		val x = r.left + 48f
		var depois = x
		if(pilulaTitulo)
		{
			val larg = pilula(canvas, 0f, 0f, titulo, 24f, medir = true)
			pilula(canvas, x + larg / 2f, r.top + 58f, titulo, 24f)
			depois = x + larg + 20f
		}
		else
		{
			val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
				color = corTitulo
				textSize = 38f
				typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
			}
			canvas.drawText(titulo, x, r.top + 72f, p)
			depois = r.right
		}
		val detalhe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SUAVE; textSize = 27f }
		if(pilulaTitulo)
		{
			// Lembrete: o acorde em pilula e a frase ao lado, na mesma linha.
			canvas.drawText(linha1, depois, r.top + 68f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
				color = TEXTO
				textSize = 34f
				typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
			})
			canvas.drawText(linha2, x, r.top + 132f, detalhe)
		}
		else
		{
			canvas.drawText(linha1, x, r.top + 120f, detalhe)
			canvas.drawText(linha2, x, r.top + 158f, detalhe)
		}
	}
}
