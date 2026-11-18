// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Calibra os dois analógicos olhando para eles.
 *
 * ## Por que uma tela, e não um número na preferência
 *
 * Zona morta é o único ajuste do app cujo valor certo depende do exemplar de
 * hardware, não do gosto de quem joga. Dois 8BitDo da mesma caixa pedem números
 * diferentes, e o mesmo controle pede outro seis meses depois. Um menu de
 * "leve/média/forte" seria pedir para adivinhar; aqui o app mede.
 *
 * ## O que a medição faz
 *
 * Com o controle largado, amostra os dois analógicos por alguns segundos. O
 * ponto de repouso é a média do que chegou; o tremor é a maior distância que
 * uma amostra ficou dessa média. A zona morta sai do tremor com uma folga,
 * porque um tremor medido em cinco segundos não é o pior tremor que existe.
 *
 * Isto é o mesmo que subir a zona morta de ponto em ponto até o drift parar,
 * só que resolvido de uma vez: o menor valor que cobre todas as amostras **é**
 * o ponto em que a subida pararia. A diferença é que a busca pararia cedo se
 * tivesse a sorte de olhar num instante quieto, e a medição não.
 *
 * ## A armadilha que esta tela precisa evitar
 *
 * **Analógico parado não gera evento.** O Android só manda `MotionEvent`
 * quando um eixo muda, então um analógico perfeito fica cinco segundos em
 * silêncio absoluto. Se a medição contasse "nenhuma amostra" como "medi e deu
 * zero", um controle que trava e para de reportar sairia daqui com zona morta
 * mínima e um veredito de aprovação. Por isso silêncio total é resposta
 * separada: mantém o valor que já havia e diz que não leu nada.
 *
 * ## A volta ao centro (dev.226)
 *
 * O tremor parado não basta: o analógico não volta sempre ao mesmo ponto
 * quando é solto, e a diferença entre uma soltura e outra passava da zona
 * morta -- o boneco seguia andando. Por isso a medição tem uma segunda fase:
 * empurrar os analógicos até a borda e soltar, várias vezes. Cada ponto onde o
 * analógico assenta vira um repouso; o centro é a média deles e a zona morta
 * cobre o mais distante.
 */
class StickCalibrationActivity: Activity()
{
	/** Acumulador de uma medição, de um analógico. */
	private class Amostras
	{
		var n = 0
		var somaX = 0f
		var somaY = 0f
		val xs = ArrayList<Float>(512)
		val ys = ArrayList<Float>(512)

		fun add(x: Float, y: Float)
		{
			n++
			somaX += x
			somaY += y
			// Guardadas porque o desvio máximo só pode ser calculado depois de
			// a média existir, e a média só existe no fim.
			if(xs.size < 4096)
			{
				xs.add(x)
				ys.add(y)
			}
		}
	}

	private lateinit var desenho: Mira
	private lateinit var leitura: TextView
	private lateinit var veredito: TextView
	private lateinit var botaoMedir: TextView
	private lateinit var rotuloEsq: TextView
	private lateinit var rotuloDir: TextView

	private val prefs by lazy { StickPrefs(this) }
	private val ui = Handler(Looper.getMainLooper())

	// Último valor cru visto, em fração do curso. Começa em NaN para separar
	// "descansa no zero" de "nunca mandou nada".
	private var lx = Float.NaN
	private var ly = Float.NaN
	private var rx = Float.NaN
	private var ry = Float.NaN

	private var medindo = false

	/**
	 * Os pontos onde um analógico assentou depois de solto, na segunda fase.
	 *
	 * Assentar é ficar abaixo de [ASSENTADO] por [ASSENTAR_MS] depois de ter
	 * passado de [EMPURRADO]. O tempo é contado pelo tique, e não por evento:
	 * um analógico parado não manda evento nenhum, e esperar o próximo para
	 * decidir que ele parou seria esperar para sempre.
	 */
	private class Solturas
	{
		var empurrado = false
		var assentandoDesde = 0L
		var ultimoX = 0f
		var ultimoY = 0f
		val pontos = ArrayList<Pair<Float, Float>>()

		fun amostra(x: Float, y: Float, agora: Long)
		{
			ultimoX = x
			ultimoY = y
			val m = sqrt(x * x + y * y)
			when
			{
				m > EMPURRADO -> { empurrado = true; assentandoDesde = 0L }
				m >= ASSENTADO -> assentandoDesde = 0L
				empurrado && assentandoDesde == 0L -> assentandoDesde = agora
			}
		}

		fun tique(agora: Long)
		{
			if(assentandoDesde != 0L && agora - assentandoDesde >= ASSENTAR_MS)
			{
				pontos.add(ultimoX to ultimoY)
				empurrado = false
				assentandoDesde = 0L
			}
		}
	}

	private var soltando = false
	private var solturasEsq = Solturas()
	private var solturasDir = Solturas()

	override fun onDestroy()
	{
		super.onDestroy()
		// O tique da medicao se reagenda sozinho; sair no meio dela o deixava
		// rodando sobre views soltas ate o prazo acabar.
		ui.removeCallbacksAndMessages(null)
	}
	private var esq = Amostras()
	private var dir = Amostras()
	private var fimEm = 0L

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		// O visual de todas as telas, em Estilo (dev.213).
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(24), dp(20), dp(24), dp(16))
		}

		root.addView(Estilo.marca(this, "Stick calibration", 26f).apply { setPadding(0, 0, 0, dp(12)) })

		root.addView(hint("The right deadzone depends on your controller, not on "
				+ "taste. Press Measure. First keep your hands off the sticks; then "
				+ "push each stick to the edge in different directions and let go, "
				+ "again and again, until it finishes."))

		desenho = Mira(this)
		root.addView(desenho, LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))

		val linhaRotulos = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
		}
		rotuloEsq = colunaTexto()
		rotuloDir = colunaTexto()
		linhaRotulos.addView(rotuloEsq)
		linhaRotulos.addView(rotuloDir)
		root.addView(linhaRotulos)

		leitura = TextView(this).apply {
			setTextColor(Estilo.SECUNDARIO)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			gravity = Gravity.CENTER
			typeface = Typeface.MONOSPACE
			setPadding(0, dp(8), 0, dp(4))
		}
		root.addView(leitura)

		veredito = TextView(this).apply {
			setTextColor(Estilo.SECUNDARIO)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			gravity = Gravity.CENTER
			setPadding(0, dp(4), 0, dp(8))
		}
		root.addView(veredito)

		botaoMedir = bigButton("Measure (${(MEDICAO_MS + SOLTURAS_MS) / 1000} s)") { comecarMedicao() }
		root.addView(botaoMedir)

		// Ajuste na mão por cima da medição: um analógico que treme só de vez
		// em quando pode passar limpo por cinco segundos, e aí a medição está
		// certa sobre o que viu e errada sobre o controle.
		root.addView(hint("A stick that only twitches now and then can pass a clean "
				+ "measurement. If it still drifts in game, raise it by hand."))

		val linhaEsq = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
		linhaEsq.addView(smallButton("Left −") { ajustar(esquerdo = true, delta = -PASSO) })
		linhaEsq.addView(smallButton("Left +") { ajustar(esquerdo = true, delta = PASSO) })
		linhaEsq.addView(smallButton("Right −") { ajustar(esquerdo = false, delta = -PASSO) })
		linhaEsq.addView(smallButton("Right +") { ajustar(esquerdo = false, delta = PASSO) })
		root.addView(linhaEsq)

		root.addView(bigButton("Reset to default") {
			prefs.reset()
			veredito.text = "Back to the default: centered, ${pct(StickPrefs.DEFAULT_DEADZONE)} deadzone."
			atualizarRotulos()
			desenho.invalidate()
		})

		// Sem botao "Back": Circle volta, como em todas as outras telas.
		root.addView(Estilo.ajudaDoControle(this))

		setContentView(ScrollView(this).apply {
			background = Estilo.fundoDaTela()
			addView(root, LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT))
		})

		atualizarRotulos()
		atualizarLeitura()
		Trace.log(this, "Stick calibration opened")
	}

	// ------------------------------------------------------------- os eixos

	/**
	 * Eixos crus, sem passar pela calibração.
	 *
	 * De propósito: esta tela existe para medir o hardware, e medir através do
	 * próprio conserto não mediria nada. O que ela desenha é o que o console
	 * receberia se não houvesse calibração nenhuma.
	 */
	override fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		val ehJoystick =
			event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
			event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
		if(!ehJoystick || event.action != MotionEvent.ACTION_MOVE)
			return super.onGenericMotionEvent(event)

		// As amostras históricas entram uma a uma. É onde mora o tremor: o
		// Android junta várias leituras num evento só, e olhar apenas a última
		// jogaria fora justamente os picos que decidem a zona morta.
		for(h in 0 until event.historySize)
			amostrar(
				event.getHistoricalAxisValue(MotionEvent.AXIS_X, h),
				event.getHistoricalAxisValue(MotionEvent.AXIS_Y, h),
				event.getHistoricalAxisValue(MotionEvent.AXIS_Z, h),
				event.getHistoricalAxisValue(MotionEvent.AXIS_RZ, h))
		amostrar(
			event.getAxisValue(MotionEvent.AXIS_X),
			event.getAxisValue(MotionEvent.AXIS_Y),
			event.getAxisValue(MotionEvent.AXIS_Z),
			event.getAxisValue(MotionEvent.AXIS_RZ))

		atualizarLeitura()
		desenho.invalidate()
		return true
	}

	private fun amostrar(x: Float, y: Float, z: Float, rz: Float)
	{
		lx = x; ly = y; rx = z; ry = rz
		if(medindo)
		{
			esq.add(x, y)
			dir.add(z, rz)
		}
		if(soltando)
		{
			val agora = SystemClock.uptimeMillis()
			solturasEsq.amostra(x, y, agora)
			solturasDir.amostra(z, rz, agora)
		}
	}

	// ---------------------------------------------------------- a medicao

	private fun comecarMedicao()
	{
		medindo = true
		esq = Amostras()
		dir = Amostras()
		solturasEsq = Solturas()
		solturasDir = Solturas()
		fimEm = System.currentTimeMillis() + MEDICAO_MS
		botaoMedir.isEnabled = false
		veredito.text = ""
		tique()
	}

	private fun tique()
	{
		val faltam = fimEm - System.currentTimeMillis()
		if(soltando)
		{
			val agora = SystemClock.uptimeMillis()
			solturasEsq.tique(agora)
			solturasDir.tique(agora)
		}
		if(faltam <= 0)
		{
			if(medindo)
			{
				// Primeira fase feita: agora as solturas.
				medindo = false
				soltando = true
				fimEm = System.currentTimeMillis() + SOLTURAS_MS
				ui.postDelayed({ tique() }, 100)
				return
			}
			terminarMedicao()
			return
		}
		botaoMedir.text = if(medindo) "Measuring… ${(faltam / 1000) + 1} s — hands off"
			else "Push each stick to the edge and let go, again and again… " +
					"${(faltam / 1000) + 1} s  (L ${solturasEsq.pontos.size}, R ${solturasDir.pontos.size})"
		ui.postDelayed({ tique() }, 100)
	}

	private fun terminarMedicao()
	{
		medindo = false
		soltando = false
		botaoMedir.isEnabled = true
		botaoMedir.text = "Measure (${(MEDICAO_MS + SOLTURAS_MS) / 1000} s)"

		val recadoEsq = concluir("Left", esq, solturasEsq) { prefs.left = it }
		val recadoDir = concluir("Right", dir, solturasDir) { prefs.right = it }
		veredito.text = "$recadoEsq\n$recadoDir"
		atualizarRotulos()
		desenho.invalidate()
	}

	/**
	 * Transforma as amostras de um analógico numa calibragem, ou recusa.
	 *
	 * Recusa em dois casos, e os dois importam:
	 *
	 * - **Nenhuma amostra.** Não é um analógico perfeito, é um analógico que
	 *   não falou. Pode estar perfeitamente parado (o caso bom) ou pode ter
	 *   parado de reportar (o caso ruim), e daqui não dá para saber qual. O
	 *   valor que já existia fica.
	 * - **Valores altos demais.** Um repouso de 25% ou um tremor de 40% é
	 *   praticamente sempre um dedo no analógico durante a medição. Gravar
	 *   isso deixaria o controle sem metade do curso, e a causa ficaria
	 *   escondida numa tela que ninguém abre duas vezes.
	 */
	private fun concluir(lado: String, a: Amostras, s: Solturas,
			gravar: (StickPrefs.Calibragem) -> Unit): String
	{
		if(a.n == 0)
			return "$lado: no reading — the stick sent nothing. Either it is "
					.plus("perfectly still, or it stopped reporting. Kept as it was.")

		val parado = a.somaX / a.n to a.somaY / a.n
		var pior = 0f
		for(i in a.xs.indices)
		{
			val dx = a.xs[i] - parado.first
			val dy = a.ys[i] - parado.second
			val d = sqrt(dx * dx + dy * dy)
			if(d > pior)
				pior = d
		}

		// O centro é a média de todos os repousos: o da fase parada e cada
		// ponto onde o analógico assentou depois de solto.
		val repousos = s.pontos + parado
		val cx = repousos.sumOf { it.first.toDouble() }.toFloat() / repousos.size
		val cy = repousos.sumOf { it.second.toDouble() }.toFloat() / repousos.size
		val espalhamento = repousos.maxOf { sqrt((it.first - cx) * (it.first - cx) +
				(it.second - cy) * (it.second - cy)) }

		// Folga sobre o pior visto: alguns segundos não são a vida inteira do
		// controle, e uma zona morta que passa raspando pelo medido falha no
		// primeiro tremor ou na primeira soltura um pouco maior.
		val zona = (maxOf(pior, espalhamento) * FOLGA + MARGEM).coerceAtLeast(StickPrefs.MIN_DEADZONE)

		if(abs(cx) > StickPrefs.MAX_CENTER || abs(cy) > StickPrefs.MAX_CENTER ||
				zona > StickPrefs.MAX_DEADZONE)
			return "$lado: refused — rest at ${pct(sqrt(cx * cx + cy * cy))}, jitter "
					.plus("${pct(pior)}. That is a finger on the stick, not wear. ")
					.plus("Let go and measure again.")

		gravar(StickPrefs.Calibragem(cx, cy, zona))
		val desvio = sqrt(cx * cx + cy * cy)
		Trace.log(this, "Stick calibration $lado: rest ${pct(desvio)}, "
				+ "jitter ${pct(pior)}, return spread ${pct(espalhamento)} over ${s.pontos.size} "
				+ "release(s), deadzone ${pct(zona)} (${a.n} samples)")
		val poucas = if(s.pontos.size < 3) "\n  Only ${s.pontos.size} release(s) seen; " +
				"measure again, pushing and letting go more times." else ""
		return "$lado: rest ${pct(desvio)} off center, jitter ${pct(pior)}, returns within "
				.plus("${pct(espalhamento)} → deadzone ${pct(zona)}$poucas")
	}

	private fun ajustar(esquerdo: Boolean, delta: Float)
	{
		val atual = if(esquerdo) prefs.left else prefs.right
		val novo = atual.copy(
			deadzone = (atual.deadzone + delta).coerceIn(StickPrefs.MIN_DEADZONE, StickPrefs.MAX_DEADZONE))
		if(esquerdo) prefs.left = novo else prefs.right = novo
		veredito.text = "${if(esquerdo) "Left" else "Right"} deadzone set by hand to "
				.plus(pct(novo.deadzone))
		atualizarRotulos()
		desenho.invalidate()
	}

	// ------------------------------------------------------------- a tela

	private fun atualizarRotulos()
	{
		rotuloEsq.text = descricao("Left", prefs.left)
		rotuloDir.text = descricao("Right", prefs.right)
	}

	private fun descricao(lado: String, c: StickPrefs.Calibragem): String
	{
		val desvio = sqrt(c.centerX * c.centerX + c.centerY * c.centerY)
		return "$lado\ndeadzone ${pct(c.deadzone)}\n" +
				(if(c.medido) "center ${pct(desvio)} off" else "not measured yet")
	}

	private fun atualizarLeitura()
	{
		leitura.text = "raw  L ${leituraDe(lx, ly)}   R ${leituraDe(rx, ry)}"
	}

	private fun leituraDe(x: Float, y: Float): String =
		if(x.isNaN()) "—" else pct(sqrt(x * x + y * y))

	/**
	 * Os dois analógicos desenhados: onde estão, e o que o app corta.
	 *
	 * O ponto é a leitura crua e o círculo é a zona morta gravada. Ver o ponto
	 * passear para fora do círculo enquanto ninguém encosta é o diagnóstico
	 * inteiro, sem número nenhum: é literalmente o drift que chegaria ao jogo.
	 */
	private inner class Mira(context: android.content.Context): View(context)
	{
		// Cores da mesma paleta do lancador: cartao para o fundo do aro,
		// pressionado para a borda, destaque para a zona morta.
		private val fundo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.argb(34, 255, 255, 255)
		}
		private val borda = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.STROKE
			strokeWidth = 2f
			color = Color.argb(80, 255, 255, 255)
		}
		private val anel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.STROKE
			strokeWidth = 4f
			color = Estilo.DESTAQUE
		}
		private val centro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.STROKE
			strokeWidth = 3f
			color = Estilo.AZUL_CLARO
		}
		private val ponto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Estilo.TEXTO
		}

		override fun onDraw(canvas: Canvas)
		{
			val h = height.toFloat()
			val raio = (minOf(width / 2f, h) / 2f) - 24f
			desenhar(canvas, width * 0.25f, h / 2f, raio, lx, ly, prefs.left)
			desenhar(canvas, width * 0.75f, h / 2f, raio, rx, ry, prefs.right)
		}

		private fun desenhar(canvas: Canvas, cx: Float, cy: Float, raio: Float,
				x: Float, y: Float, cal: StickPrefs.Calibragem)
		{
			canvas.drawCircle(cx, cy, raio, fundo)
			canvas.drawCircle(cx, cy, raio, borda)
			// Cruz do centro geométrico, para o repouso medido ter contra o que
			// ser comparado.
			canvas.drawLine(cx - 12f, cy, cx + 12f, cy, borda)
			canvas.drawLine(cx, cy - 12f, cx, cy + 12f, borda)

			// A zona morta desenhada em volta do repouso medido, que é onde ela
			// age de verdade -- não em volta do centro da tela.
			val zx = cx + cal.centerX * raio
			val zy = cy + cal.centerY * raio
			canvas.drawCircle(zx, zy, cal.deadzone * raio, anel)
			canvas.drawCircle(zx, zy, 5f, centro)

			if(!x.isNaN())
				canvas.drawCircle(cx + x * raio, cy + y * raio, 10f, ponto)
		}
	}

	// ------------------------------------------------------------- pedaços

	/**
	 * Fração do curso em porcentagem, com uma casa.
	 *
	 * Uma casa porque a diferença entre 2,4% e 3,1% decide se o analógico anda
	 * sozinho, e "2%" contra "3%" esconderia exatamente essa diferença. Locale
	 * fixo em US para o separador ser sempre ponto: o mesmo número aparece na
	 * tela e no diário, e duas grafias do mesmo valor atrapalhariam a leitura
	 * de um log colado.
	 */
	private fun pct(v: Float) = "%.1f%%".format(java.util.Locale.US, v * 100f)

	private fun colunaTexto() = TextView(this).apply {
		setTextColor(Estilo.SECUNDARIO)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, Estilo.TAM_APOIO)
		gravity = Gravity.CENTER
		layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
			.apply { weight = 1f }
	}

	private fun hint(texto: String) = Estilo.apoio(this, texto).apply {
		setPadding(dp(4), dp(4), dp(4), dp(10))
	}

	/** Cartao de linha inteira, o mesmo de todas as telas. */
	private fun bigButton(label: String, onClick: () -> Unit) = Estilo.botao(this, label, onClick).apply {
		gravity = Gravity.CENTER
		layoutParams = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.WRAP_CONTENT
		).apply { setMargins(0, dp(6), 0, dp(6)) }
	}

	/** Cartao menor, para os quatro ajustes finos numa fileira. */
	private fun smallButton(label: String, onClick: () -> Unit) = Estilo.botao(this, label, onClick).apply {
		gravity = Gravity.CENTER
		layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
			.apply { weight = 1f; marginStart = dp(4); marginEnd = dp(4) }
	}

	private fun dp(valor: Int): Int = Estilo.dp(this, valor)

	private companion object
	{
		const val MEDICAO_MS = 5_000L

		/** Segunda fase: empurrar e soltar, várias vezes. */
		const val SOLTURAS_MS = 10_000L

		// Empurrado é passar da metade do curso; assentado é ficar abaixo de
		// um quarto dele por 300 ms. O quarto é generoso de propósito: o
		// repouso de um controle gasto passa de 20%, e ele tem de contar.
		const val EMPURRADO = 0.5f
		const val ASSENTADO = 0.25f
		const val ASSENTAR_MS = 300L

		/** Um passo do ajuste manual: 1% do curso. */
		const val PASSO = 0.01f

		// Folga sobre o pior tremor visto. 1,3 e mais um ponto fixo: o fator
		// cobre o controle que treme proporcionalmente mais quando esquenta, e
		// a parcela fixa cobre o controle que mal treme, onde 30% de quase nada
		// continua sendo quase nada.
		const val FOLGA = 1.3f
		const val MARGEM = 0.01f

	}
}
