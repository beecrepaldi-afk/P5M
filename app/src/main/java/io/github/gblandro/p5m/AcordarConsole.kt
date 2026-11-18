// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.lib.DiscoveryHost

/**
 * Acordar o console e conectar, num clique so (dev.216).
 *
 * O chiaki perguntava, com tres botoes, se era para mandar o pacote de
 * acordar; mandava e nao dizia mais nada, e a pessoa tinha de adivinhar
 * quando clicar de novo. Aqui o pacote sai, a espera aparece com o tempo
 * correndo, e a propria busca de consoles da lista diz quando ele acordou --
 * ai a conexao segue sozinha.
 *
 * O pacote e repetido: e UDP, e um perdido no Wi-Fi era o "nao acordou" mais
 * comum. Trinta segundos cobrem com folga o PS5 saindo do repouso.
 *
 * Janela propria no visual do P5M (dev.217): as caixas do Material destoavam
 * do resto, e o dono achou feias. Uma janela so, que troca de estado.
 */
class AcordarConsole(
	private val activity: AppCompatActivity,
	private val alvo: DiscoveredDisplayHost,
	private val hosts: LiveData<List<DisplayHost>>,
	private val acordar: () -> Unit,
	private val conectar: () -> Unit)
{
	private val principal = Handler(Looper.getMainLooper())
	private var inicio = 0L
	private var esperando = false
	private val nome = alvo.name ?: alvo.host

	private val janela = Dialog(activity).apply {
		requestWindowFeature(Window.FEATURE_NO_TITLE)
		window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
		setCanceledOnTouchOutside(false)
		setOnCancelListener { encerrar("cancelled") }
	}
	private val titulo = Estilo.titulo(activity, "").apply { setPadding(0, dp(22), 0, 0) }
	private val estado = Estilo.apoio(activity, "").apply {
		setTextSize(TypedValue.COMPLEX_UNIT_SP, Estilo.TAM_APOIO + 1f)
		setPadding(0, dp(10), 0, 0)
	}
	private val barra = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
		max = LIMITE_S * 10
		progressTintList = ColorStateList.valueOf(Estilo.AZUL_CLARO)
		progressBackgroundTintList = ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
	}
	private val botoes = LinearLayout(activity).apply {
		orientation = LinearLayout.HORIZONTAL
		setPadding(0, dp(24), 0, 0)
	}

	init
	{
		val corpo = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(32), dp(28), dp(32), dp(28))
			background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
					intArrayOf(Color.parseColor("#F0142240"), Color.parseColor("#F205070D"))).apply {
				cornerRadius = dp(Estilo.RAIO + 6).toFloat()
				setStroke(dp(1).coerceAtLeast(1), Color.argb(70, 255, 255, 255))
			}
			addView(Estilo.marca(activity, "Wake up", 22f))
			addView(titulo)
			addView(estado)
			addView(barra, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(18) })
			addView(botoes)
		}
		janela.setContentView(corpo, android.view.ViewGroup.LayoutParams(dp(560), -2))
	}

	private fun dp(v: Int) = Estilo.dp(activity, v)

	private val observador = Observer<List<DisplayHost>> { lista ->
		val acordado = lista.filterIsInstance<DiscoveredDisplayHost>().any {
			mesmoConsole(it) && it.discoveredHost.state == DiscoveryHost.State.READY
		}
		if(acordado && esperando)
			acordou()
	}

	private val relogio = object: Runnable
	{
		private var ultimoReenvio = 0
		override fun run()
		{
			if(!esperando) return
			val ms = SystemClock.uptimeMillis() - inicio
			val s = (ms / 1000L).toInt()
			if(s >= LIMITE_S)
			{
				naoAcordou()
				return
			}
			if(s > 0 && s % REENVIO_S == 0 && s != ultimoReenvio)
			{
				ultimoReenvio = s
				acordar()
			}
			barra.progress = (ms / 100L).toInt()
			estado.text = "Waiting for it to answer · $s s\nIt connects on its own as soon as it is awake."
			principal.postDelayed(this, 100L)
		}
	}

	fun iniciar()
	{
		esperando = true
		inicio = SystemClock.uptimeMillis()
		Trace.log(activity, "Wake: sending wakeup to the console and waiting for it")
		acordar()
		titulo.text = "Waking up $nome"
		estado.setTextColor(Estilo.SECUNDARIO)
		barra.visibility = View.VISIBLE
		barra.isIndeterminate = false
		barra.progress = 0
		mostrarBotoes(listOf("Cancel" to { encerrar("cancelled") }), principalPrimeiro = false)
		hosts.observe(activity, observador)
		principal.post(relogio)
		if(!janela.isShowing) janela.show()
	}

	private fun mesmoConsole(h: DiscoveredDisplayHost): Boolean
	{
		val id = alvo.discoveredHost.hostId
		return if(id != null) h.discoveredHost.hostId == id else h.host == alvo.host
	}

	private fun acordou()
	{
		val s = (SystemClock.uptimeMillis() - inicio) / 1000L
		pararDeEsperar()
		Trace.log(activity, "Wake: awake after $s s")
		// O servico de Remote Play sobe um instante depois de o console
		// responder a busca; conectar no mesmo segundo costuma ser recusado.
		titulo.text = "$nome is awake"
		estado.setTextColor(Estilo.AZUL_CLARO)
		estado.text = "Connecting…"
		barra.isIndeterminate = true
		botoes.removeAllViews()
		principal.postDelayed({
			fechar()
			if(!activity.isFinishing) conectar()
		}, ESPERA_SERVICO_MS)
	}

	private fun naoAcordou()
	{
		pararDeEsperar()
		Trace.log(activity, "Wake: no answer in $LIMITE_S s")
		titulo.text = "$nome didn't wake up"
		estado.setTextColor(Estilo.SECUNDARIO)
		estado.text = "On the PS5, open Settings › System › Power Saving › Features Available in Rest " +
				"Mode, and turn on \"Stay Connected to the Internet\" and \"Enable Turning On PS5 from Network\"."
		barra.visibility = View.GONE
		mostrarBotoes(listOf("Try again" to { iniciar() }, "Close" to { fechar() }), principalPrimeiro = true)
	}

	/** O primeiro vira o botao azul quando [principalPrimeiro]; o foco vai nele. */
	private fun mostrarBotoes(lista: List<Pair<String, () -> Unit>>, principalPrimeiro: Boolean)
	{
		botoes.removeAllViews()
		var primeiro: View? = null
		for((i, par) in lista.withIndex())
		{
			val (rotulo, acao) = par
			val b: TextView = if(i == 0 && principalPrimeiro) Estilo.botaoPrincipal(activity, rotulo, acao)
				else Estilo.botao(activity, rotulo, acao).apply { gravity = android.view.Gravity.CENTER }
			botoes.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if(i > 0) leftMargin = dp(12) })
			if(primeiro == null) primeiro = b
		}
		primeiro?.requestFocus()
	}

	private fun pararDeEsperar()
	{
		esperando = false
		principal.removeCallbacks(relogio)
		hosts.removeObserver(observador)
	}

	private fun encerrar(motivo: String)
	{
		if(esperando)
			Trace.log(activity, "Wake: $motivo")
		pararDeEsperar()
		fechar()
	}

	private fun fechar()
	{
		if(janela.isShowing) janela.dismiss()
	}

	private companion object
	{
		const val LIMITE_S = 30
		const val REENVIO_S = 5
		const val ESPERA_SERVICO_MS = 2500L
	}
}
