// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.PsnDevice
import com.metallic.chiaki.lib.PsnDeviceList
import java.net.URI

/**
 * Conexão remota: jogar de fora de casa.
 *
 * Três telas numa só, porque são três passos de uma coisa só e separá-los em
 * activities faria o usuário voltar e avançar sem motivo:
 *
 *  1. **Entrar**: um WebView com a página de login da própria Sony. A senha é
 *     digitada lá, no domínio dela, e nunca passa por este app -- o que volta é
 *     um código de uso único na URL de redirecionamento.
 *  2. **Escolher**: a lista de consoles da conta, que vem dos servidores da PSN
 *     e não da rede local. É por isso que ela funciona de qualquer lugar.
 *  3. **Conectar**: monta o [ConnectInfo] com o duid do console em vez de um
 *     endereço IP e entrega ao [DisplayMode], que abre o stream no modo
 *     escolhido. Dali para a frente é a mesma sessão de sempre.
 *
 * Sem AppCompat e sem layout XML, como as outras telas nossas: menos peças
 * entre o toque e o que ele faz.
 */
class PsnRemoteActivity: Activity()
{
	private lateinit var root: LinearLayout
	private lateinit var scroll: ScrollView
	private lateinit var status: TextView
	private var web: WebView? = null
	private var relay: PsnLoginRelay? = null
	private val ui = Handler(Looper.getMainLooper())
	private var busy = false

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
			setBackgroundColor(BACKGROUND)
			setPadding(64, 64, 64, 64)
		}
		scroll = ScrollView(this).apply {
			setBackgroundColor(BACKGROUND)
			addView(root)
		}
		showHome()
	}

	override fun onBackPressed()
	{
		// Com o WebView aberto, voltar significa desistir do login e não sair da
		// tela -- que é o que o botão de voltar do Quest faria por padrão.
		if(web != null)
			showHome()
		else
			@Suppress("DEPRECATION") super.onBackPressed()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		// A ponte fica de pé só enquanto esta tela existe. Um servidor esquecido
		// ligado num aparelho de uso doméstico é risco sem contrapartida.
		relay?.stop()
		relay = null
	}

	// ---------------------------------------------------------------- telas

	private fun showHome()
	{
		web = null
		root.removeAllViews()
		setContentView(scroll)

		root.addView(title("Remote connection"))
		root.addView(hint(
				"The app's normal list is discovered by broadcast on the local " +
				"network, and broadcast does not cross the internet: away from " +
				"home the console will never show up there. Here it is addressed " +
				"through your PSN account, which works from anywhere."))

		status = TextView(this).apply {
			setTextColor(Color.parseColor("#9aa0a6"))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			gravity = Gravity.CENTER
			setPadding(0, 16, 0, 16)
		}
		root.addView(status)

		if(!PsnAuth.isLoggedIn(this))
		{
			status.text = "No account signed in."

			root.addView(bigButton("Sign in from your phone") { showPhoneLogin() })
			root.addView(hint(
					"The path that works when the account has a passkey — and Sony " +
					"deletes the password once you create one. The sign-in page " +
					"then has nowhere to type a password: all that is left is " +
					"scanning a QR code, and you cannot point a phone at a screen " +
					"that is inside the headset. Here the sign-in happens on the " +
					"phone and only the result crosses over."))

			root.addView(bigButton("Sign in right here") { showLogin() })
			root.addView(hint(
					"Opens Sony's page inside the headset. Only works if your " +
					"account still has a password."))
			return
		}

		status.text = "Looking for the consoles on this account…"
		root.addView(bigButton("Sign out") {
			PsnAuth.forget(this)
			CookieManager.getInstance().removeAllCookies(null)
			showHome()
		})
		loadDevices()
	}

	private fun showPhoneLogin()
	{
		root.removeAllViews()
		setContentView(scroll)
		root.addView(title("Sign in from your phone"))

		val loginUrl = PsnAuth.loginUrl(this)
		if(loginUrl == null)
		{
			root.addView(hint("Could not generate this device's identifier. " +
					"Without it the sign-in is no use for NAT traversal."))
			root.addView(bigButton("Back") { showHome() })
			return
		}

		val ponte = relay ?: PsnLoginRelay(this).also { relay = it }
		val dePe = ponte.start(loginUrl) { code -> ui.post { concludeLogin(code) } }
		val endereco = ponte.shortAddress

		status = TextView(this).apply {
			setTextColor(Color.parseColor("#9aa0a6"))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			gravity = Gravity.CENTER
			setPadding(0, 8, 0, 8)
		}

		if(dePe && endereco != null && !endereco.startsWith("?"))
		{
			root.addView(hint("In your phone's browser, type:"))
			root.addView(TextView(this).apply {
				text = endereco
				setTextColor(Color.WHITE)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
				gravity = Gravity.CENTER
				setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
				setPadding(0, 8, 0, 8)
			})
			root.addView(hint("The page opens with step-by-step instructions. " +
					"The phone has to be on the same network as the headset — if " +
					"the network isolates devices from each other, put the headset " +
					"on the phone's own hotspot."))
			status.text = "Waiting for the code to arrive…"
		}
		else
		{
			status.text = "Could not publish the page on the network. Use the " +
					"field below, or try the other path."
		}
		root.addView(status)

		// Último recurso, para quando a rede isola os aparelhos: o login
		// acontece no celular do mesmo jeito, e só o código é digitado aqui.
		// São umas poucas dezenas de caracteres no teclado do headset -- ruim,
		// mas é a diferença entre difícil e impossível.
		root.addView(hint("Or paste here the address Sony redirected to " +
				"(that page which does not load):"))
		val campo = EditText(this).apply {
			// setHint, e nao a propriedade: esta classe tem um metodo `hint`
			// proprio, e um `hint = ...` dentro de apply e ambiguidade a toa.
			setHint("https://remoteplay.dl.playstation.net/...")
			setTextColor(Color.WHITE)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT)
		}
		root.addView(campo)
		root.addView(bigButton("Use this address") {
			val texto = campo.text.toString()
			val code = queryParam(texto, "code") ?: texto.trim().takeIf {
				it.isNotEmpty() && !it.contains(' ') && !it.contains('/')
			}
			if(code.isNullOrEmpty())
				status.text = "I could not find a code= in there."
			else
				concludeLogin(code)
		})

		root.addView(bigButton("Back") {
			relay?.stop()
			relay = null
			showHome()
		})
	}

	/** Fecha o login com o código, venha ele da ponte ou do campo. */
	private fun concludeLogin(code: String)
	{
		relay?.stop()
		relay = null
		root.removeAllViews()
		setContentView(scroll)
		root.addView(title("Remote connection"))
		status = TextView(this).apply {
			text = "Finishing the sign-in…"
			setTextColor(Color.parseColor("#9aa0a6"))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			gravity = Gravity.CENTER
		}
		root.addView(status)
		Thread {
			val erro = PsnAuth.finishLogin(this, code)
			ui.post {
				showHome()
				if(erro != null)
					status.text = erro
			}
		}.start()
	}

	private fun showLogin()
	{
		root.removeAllViews()
		val url = PsnAuth.loginUrl(this)
		if(url == null)
		{
			setContentView(scroll)
			root.addView(title("Remote connection"))
			root.addView(hint("Could not generate this device's identifier. " +
					"Without it the sign-in is no use for NAT traversal."))
			root.addView(bigButton("Back") { showHome() })
			return
		}

		// A página de login vai direto na tela, e não dentro do ScrollView das
		// outras: um WebView de altura MATCH_PARENT dentro de um ScrollView
		// mede zero e não aparece nada. É a mesma armadilha de sempre — o
		// contêiner rolável dá altura infinita ao filho, e o filho que a pediu
		// fica com nada.
		val view = WebView(this)
		view.settings.javaScriptEnabled = true
		view.settings.domStorageEnabled = true
		// Sem o "; wv" da identificação padrão do WebView.
		//
		// Não é firula. O chiaki-ng no desktop carrega um interceptador que só
		// existe para forjar o cabeçalho Sec-Ch-Ua e fazer o QtWebEngine passar
		// por Chrome ou Edge -- ninguém escreve isso por acaso, escreve depois
		// de a página de login ter recusado o cliente. A identificação de um
		// WebView Android carrega "; wv", que é a marca de navegador embutido, e
		// é exatamente o que uma página de login costuma barrar.
		//
		// Tirar a marca é o mínimo, e é reversível: se o login funcionar do
		// mesmo jeito, esta linha não custou nada; se sem ela a página recusar,
		// a causa estaria escondida atrás de uma tela genérica de erro da Sony,
		// longe de casa.
		view.settings.userAgentString = view.settings.userAgentString
			?.replace("; wv", "")
		CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
		view.webViewClient = object: WebViewClient()
		{
			override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean
			{
				val alvo = request?.url?.toString() ?: return false
				return intercept(alvo)
			}

			@Deprecated("Required by older WebView versions")
			override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean
			{
				// Corpo em bloco, e não expressão: o `?: return false` de uma
				// linha só não compila -- Kotlin não aceita return dentro de
				// corpo-expressão.
				if(url == null)
					return false
				return intercept(url)
			}
		}
		setContentView(view)
		web = view
		view.loadUrl(url)
	}

	/**
	 * Devolve true quando a navegação era o redirecionamento final.
	 *
	 * A página de destino não existe para ser carregada: ela só carrega o
	 * código na query. Deixar o WebView segui-la mostraria um erro de rede em
	 * cima de um login que deu certo.
	 */
	private fun intercept(url: String): Boolean
	{
		if(!url.startsWith(PsnAuth.REDIRECT))
			return false
		val code = queryParam(url, "code") ?: ""
		if(code.isEmpty())
		{
			Log.e(TAG, "PSN: redirect with no code")
			ui.post {
				showHome()
				status.text = "PSN redirected without a code. Try signing in again."
			}
			return true
		}
		Log.i(TAG, "PSN: authorization code received")
		web = null
		concludeLogin(code)
		return true
	}

	private fun loadDevices()
	{
		Thread {
			val lista = try
			{
				PsnAuth.devices(this)
			}
			catch(e: Exception)
			{
				Log.e(TAG, "PSN: failed to list consoles", e)
				PsnDeviceList(e.message ?: e::class.java.simpleName, emptyList())
			}
			ui.post { showDevices(lista) }
		}.start()
	}

	private fun showDevices(resultado: PsnDeviceList)
	{
		// Três desfechos diferentes, três textos diferentes. Antes os três
		// viravam "nenhum console apareceu", e a primeira falha de verdade não
		// disse nada -- que é o pior momento possível para uma tela ser vaga,
		// porque acontece longe de casa.
		if(resultado.error != null)
		{
			status.text = "The PSN query failed: ${resultado.error}.\n\n" +
					"This is a problem between the headset and Sony, not with the " +
					"console. The diary has the full line."
			root.addView(bigButton("Sign out and sign in again") {
				PsnAuth.forget(this)
				CookieManager.getInstance().removeAllCookies(null)
				showHome()
			})
			return
		}
		val lista = resultado.devices
		if(lista.isEmpty())
		{
			status.text = "PSN answered, and the account has no console visible " +
					"for remote connection.\n\n" +
					"This is not a sign-in failure: the token worked. A console " +
					"only shows up in this list while it is on, or in rest mode " +
					"with \"Stay connected to the internet\" enabled in the power " +
					"saving options. Unplugged, it does not exist as far as Sony " +
					"is concerned."
			return
		}
		status.text = "${lista.size} console(s) on this account."
		for(device in lista)
		{
			val etiqueta = device.name +
					(if(device.remotePlayEnabled) "" else "  (Remote Play off)")
			root.addView(bigButton(etiqueta) { connect(device) })
		}
		root.addView(hint(
				"The console has to be on, or in rest mode with Remote Play " +
				"enabled. Opening takes longer than at home: that is Sony's " +
				"servers putting the two sides in touch before any picture."))
	}

	// ------------------------------------------------------------- conectar

	private fun connect(device: PsnDevice)
	{
		if(busy)
			return
		busy = true
		status.text = "Preparing the connection to ${device.name}…"
		Thread {
			val token = PsnAuth.validToken(this)
			val account = PsnAuth.accountId(this)
			ui.post {
				busy = false
				if(token.isNullOrEmpty() || account == null)
				{
					status.text = "The account token expired and could not be " +
							"refreshed. Sign out and sign in again."
					return@post
				}
				val info = ConnectInfo(
					ps5 = device.ps5,
					// Sem endereço, de propósito: na conexão remota quem
					// endereça é o duid, e a libchiaki só descobre o IP depois
					// de a furação de NAT terminar.
					host = "",
					registKey = ByteArray(0),
					morning = ByteArray(0),
					videoProfile = StreamQualityPrefs(this).videoProfile(),
					duid = device.duid,
					psnToken = token,
					psnAccountId = account)
				// Sem o nome: quem batiza um console costuma pôr o próprio nome
				// nele, e esta linha vai para o diário que o testador cola.
				Log.i(TAG, "PSN: opening remote stream to a "
						+ (if(device.ps5) "PS5" else "PS4"))
				DisplayMode.startStream(this, info)
				finish()
			}
		}.start()
	}

	// -------------------------------------------------------------- pedaços

	private fun queryParam(url: String, name: String): String?
	{
		val query: String = try
		{
			URI(url).rawQuery ?: ""
		}
		catch(e: Exception)
		{
			// URL malformada ainda pode trazer a query: o que interessa vem
			// depois da interrogação, e o parser é que é exigente demais.
			url.substringAfter('?', "")
		}
		for(par in query.split("&"))
		{
			val i = par.indexOf('=')
			if(i > 0 && par.substring(0, i) == name)
				return par.substring(i + 1)
		}
		return null
	}

	private fun title(text: String) = TextView(this).apply {
		this.text = text
		setTextColor(Color.WHITE)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
		gravity = Gravity.CENTER
		setTypeface(typeface, Typeface.BOLD)
		setPadding(0, 0, 0, 16)
	}

	private fun hint(text: String) = TextView(this).apply {
		this.text = text
		setTextColor(Color.parseColor("#9aa0a6"))
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
		gravity = Gravity.CENTER
		setPadding(0, 8, 0, 24)
	}

	private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
		text = label
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
		setOnClickListener { onClick() }
		layoutParams = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT,
			LinearLayout.LayoutParams.WRAP_CONTENT
		).apply { setMargins(0, 12, 0, 12) }
	}

	companion object
	{
		private const val TAG = "P5MVR"
		private val BACKGROUND = Color.parseColor("#101014")
	}
}
