// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Teclado numerico para a senha de login do PS5, dentro do modo imersivo.
 *
 * Nao e a PIN de registro. E a senha que o console cobra a cada sessao quando
 * o usuario do PS5 tem uma, e ela chega como `LoginPinRequestEvent` antes do
 * `ConnectedEvent`. A sessao fica parada sem prazo esperando a resposta. Ate
 * aqui o modo imersivo so encerrava, e o README mandava "registrar pelo painel
 * 2D", o que nao resolve: a senha e pedida de novo toda vez.
 *
 * Desenha na mesma camada do painel de ajustes, que ja existe antes de haver
 * video, e e navegado pelo controle: nao ha superficie 2D nem teclado do
 * sistema nesta activity. D-pad ou analogico move, Cross aperta a tecla,
 * Circle apaga, e segurar Circle desiste. O quarto digito envia sozinho, como
 * no proprio PS5: a senha tem sempre quatro.
 *
 * Desistir e segurar, e nao um toque com o campo vazio, porque Circle e o
 * "voltar" do PlayStation: quem apaga rapido da um toque a mais, e aqui um
 * toque a mais custava a sessao. E o mesmo gesto do painel de ajustes.
 *
 * A logica fica separada do desenho para que a activity so repasse teclas.
 */
class TecladoPin(private val incorreto: Boolean)
{
	companion object
	{
		const val DIGITOS = 4

		// "Del" e nao o simbolo de apagar: a fonte padrao do Android nao
		// garante o glifo, e um quadrado vazio so apareceria no headset.
		private const val APAGAR = "Del"
		private const val VAZIA = ""
		private val TECLAS = listOf(
			listOf("1", "2", "3"),
			listOf("4", "5", "6"),
			listOf("7", "8", "9"),
			listOf(VAZIA, "0", APAGAR))
	}

	/** O que a activity deve fazer depois de uma tecla. */
	sealed class Acao
	{
		object Nada: Acao()
		object Redesenhar: Acao()
		data class Enviar(val pin: String): Acao()
	}

	private val digitos = StringBuilder()
	private var linha = 0
	private var coluna = 0

	fun mover(dx: Int, dy: Int): Acao
	{
		// Da a volta nas bordas, e pula a casa vazia seguindo na mesma direcao:
		// parar nela seria um cursor que nao aponta para nada.
		do
		{
			linha = Math.floorMod(linha + dy, TECLAS.size)
			coluna = Math.floorMod(coluna + dx, TECLAS[0].size)
		}
		while(TECLAS[linha][coluna] == VAZIA)
		return Acao.Redesenhar
	}

	fun apertar(): Acao = when(val tecla = TECLAS[linha][coluna])
	{
		APAGAR -> apagar()
		VAZIA -> Acao.Nada
		else -> digitar(tecla.toInt())
	}

	/** Um digito, da grade ou de teclado fisico. O quarto envia. */
	fun digitar(d: Int): Acao
	{
		digitos.append(d)
		if(digitos.length < DIGITOS)
			return Acao.Redesenhar
		val pin = digitos.toString()
		digitos.setLength(0)
		return Acao.Enviar(pin)
	}

	/** Com o campo vazio nao faz nada: desistir e segurar Circle. */
	fun apagar(): Acao
	{
		if(digitos.isEmpty())
			return Acao.Nada
		digitos.setLength(digitos.length - 1)
		return Acao.Redesenhar
	}

	/**
	 * Deitado, e nao em pe (dev.210): a camada virou uma faixa larga de 464 px
	 * de altura para o painel de ajustes caber embaixo da tela do jogo. O texto
	 * vai a esquerda e a grade a direita, lado a lado.
	 */
	fun desenhar(bitmap: Bitmap)
	{
		val canvas = Canvas(bitmap)
		canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
		val w = bitmap.width.toFloat()
		val h = bitmap.height.toFloat()
		val erro = Color.rgb(255, 130, 120)

		val larguraTecla = 150f
		val alturaTecla = 80f
		val folga = 14f
		val larguraGrade = 3 * larguraTecla + 2 * folga
		val alturaGrade = TECLAS.size * alturaTecla + (TECLAS.size - 1) * folga
		val larguraTexto = 760f
		val vao = 110f
		val esquerda = (w - (larguraTexto + vao + larguraGrade)) / 2f

		// Mesma moldura do painel de ajustes: e a mesma camada, e parecer outra
		// coisa so faria a pessoa desconfiar.
		PainelDeAjuste.fundo(canvas, RectF(esquerda - 56f, 8f, esquerda + larguraTexto + vao + larguraGrade + 56f, h - 8f))

		// O erro vai no titulo, e nao so no subtitulo: a um metro e meio, uma
		// linha pequena em vermelho passa batida.
		val titulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = if(incorreto) erro else Color.rgb(238, 241, 247)
			textSize = 44f
			typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
		}
		canvas.drawText(if(incorreto) "Wrong passcode" else "PS5 login passcode", esquerda, 118f, titulo)

		val aviso = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			textSize = 28f
			color = Color.rgb(142, 152, 173)
		}
		canvas.drawText(if(incorreto) "Enter it again. The console asks for it every session."
			else "The console asks for it every session.", esquerda, 164f, aviso)

		// Os digitos mascarados. Contorno vermelho na volta de uma senha errada.
		val cheio = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(238, 241, 247) }
		val vazio = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.STROKE
			strokeWidth = 3f
			color = if(incorreto) erro else Color.argb(150, 200, 210, 230)
		}
		var cx = esquerda + 18f
		for(i in 0 until DIGITOS)
		{
			canvas.drawCircle(cx, 244f, 18f, if(i < digitos.length) cheio else vazio)
			cx += 60f
		}

		val ajuda = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.rgb(142, 152, 173)
			textSize = 26f
		}
		canvas.drawText("D-pad or left stick: move  ·  Cross: press", esquerda, h - 96f, ajuda)
		canvas.drawText("Circle: delete  ·  hold Circle: leave", esquerda, h - 58f, ajuda)

		// A grade.
		val x0 = esquerda + larguraTexto + vao
		val topo = (h - alturaGrade) / 2f
		val tecla = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 40, 46, 60) }
		val marcada = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 140, 230) }
		val rotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.rgb(240, 242, 248)
			textSize = 42f
			textAlign = Paint.Align.CENTER
			typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
		}
		for((l, fileira) in TECLAS.withIndex())
			for((c, texto) in fileira.withIndex())
			{
				if(texto == VAZIA)
					continue
				val x = x0 + c * (larguraTecla + folga)
				val y = topo + l * (alturaTecla + folga)
				val r = RectF(x, y, x + larguraTecla, y + alturaTecla)
				canvas.drawRoundRect(r, 16f, 16f, if(l == linha && c == coluna) marcada else tecla)
				rotulo.textSize = if(texto == APAGAR) 34f else 42f
				canvas.drawText(texto, r.centerX(), r.centerY() + 14f, rotulo)
			}
	}
}
