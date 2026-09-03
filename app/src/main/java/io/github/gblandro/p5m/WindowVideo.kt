// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.metallic.chiaki.session.StreamSession

/**
 * Liga a sessão à superfície certa, no modo janela.
 *
 * O modo janela não tem camada nossa no compositor: quem compõe é o Horizon OS,
 * e o app é um painel comum. Os bits de nitidez do
 * `XR_FB_composition_layer_settings`, que servem o modo imersivo, não existem
 * aqui — nem o sharpening, nem o supersampling, nem a fixação de gamut. O que
 * sobra é fazer nós mesmos.
 *
 * A escolha acontece uma vez, na abertura:
 *
 * - Sem nada a fazer, o SurfaceView do chiaki continua como está e o
 *   decodificador escreve direto nele. É o caminho de sempre, sem uma cópia
 *   sequer a mais.
 * - Com nitidez pedida, ou com fonte de 10 bits a converter, entra a
 *   [SharpVideoView] no lugar dele: o decodificador passa a escrever numa
 *   SurfaceTexture e a imagem chega à janela por uma passada de shader.
 *
 * A troca custa uma passada de GPU e uma cópia. Não é grátis, e é por isso que
 * só acontece quando há de fato o que fazer — quem deixa a nitidez em "nenhuma"
 * não paga por ela.
 */
object WindowVideo
{
	private const val TAG = "P5MVR"

	fun attach(activity: Activity, surfaceView: SurfaceView, session: StreamSession)
	{
		val quality = StreamQualityPrefs(activity)
		val sharpen = quality.sharpenAmount
		val owner = activity as? LifecycleOwner

		// Dez bits obrigam o shader, tenha ou não sido pedida a conversão.
		//
		// Antes a condição era `tenBit && toneMapped`, e com os 10 bits ligados
		// sem conversão e sem nitidez a janela caía no caminho direto: o
		// decodificador escrevia PQ e BT.2020 numa superfície comum, que o
		// sistema trata como sRGB. Sai imagem, e sai com as cores deslocadas --
		// sem erro, sem log, e com a aparência de um ajuste de brilho ruim.
		//
		// No modo imersivo isso não acontecia porque lá o gamut é declarado ao
		// compositor; uma janela do Android não tem onde declarar nada.
		if((sharpen <= 0f && !quality.tenBit) || owner == null)
		{
			Log.i(TAG, "Window without shader: the decoder writes straight to the surface")
			session.attachToSurfaceView(surfaceView)
			return
		}

		// Um layout inesperado não pode custar a imagem: sem pai não há onde
		// inserir a nossa view, e o caminho de sempre continua servindo.
		val parent = surfaceView.parent as? ViewGroup
		if(parent == null)
		{
			Log.w(TAG, "SurfaceView has no parent; the window goes without the shader")
			session.attachToSurfaceView(surfaceView)
			return
		}

		// A fonte é PQ sempre que os 10 bits estiverem ligados, tenha ou não
		// sido pedida a conversão de tons: o decodificador entrega PQ de todo
		// jeito -- o pedido de mapeamento é ignorado, como o log mostrou --, e
		// tratar esses valores como SDR sairia com as cores deslocadas.
		val view = SharpVideoView(activity, quality.tenBit, sharpen) { surface ->
			session.attachToSurface(surface)
		}
		val index = parent.indexOfChild(surfaceView)
		parent.removeView(surfaceView)
		parent.addView(view, index, surfaceView.layoutParams)

		// O ciclo de vida no lugar de um campo estático: uma view guardada num
		// object sobrevive à activity e leva a activity junto.
		owner.lifecycle.addObserver(object: DefaultLifecycleObserver
		{
			override fun onPause(owner: LifecycleOwner)
			{
				// Soltar antes de parar a thread do GL, e não no onDestroy: com
				// ela parada os eventos na fila não rodam mais, e o que ficasse
				// por soltar só morreria junto com o processo. A GLSurfaceView
				// descarta o contexto ao pausar de qualquer modo -- o que vive
				// nele não sobreviveria à pausa nem se guardássemos.
				view.release()
				view.onPause()
			}

			override fun onResume(owner: LifecycleOwner)
			{
				// O contexto volta novo, e com ele o onSurfaceCreated do
				// renderer: é lá que a textura externa e a Surface renascem.
				view.onResume()
			}

			override fun onDestroy(owner: LifecycleOwner) = view.release()
		})

		Log.i(TAG, "Window with shader: sharpness ${quality.sharpness} "
				+ "(${StreamQualityPrefs.SHARPNESS_NAMES[quality.sharpness]})"
				+ (if(quality.tenBit) ", 10-bit PQ source" else ", 8-bit SDR source"))
	}
}
