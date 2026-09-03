// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

/**
 * Por onde o som sai: direto ao hardware, ou pelo mixer do sistema.
 *
 * O chiaki abre o stream do Oboe em `SharingMode::Exclusive`, que fala com o
 * hardware sem passar pelo mixer. É o caminho de menor latência, e é o certo
 * para o modo imersivo — lá a espacialização é nossa e o mixer só cobraria
 * latência sem dar nada em troca.
 *
 * No modo janela é o contrário, e foi um teste em hardware que mostrou. Uma
 * janela do navegador tem o áudio posicionado no painel pelo Horizon OS; a
 * nossa não tinha. Não é recurso que falte ao sistema: **o que não passa pelo
 * mixer não pode ser processado por ele**, e o espacializador vive lá dentro.
 * Um stream exclusivo é invisível para ele.
 *
 * Então o caminho passa a seguir o modo de exibição. Janela pede o mixer, com
 * atributos de mídia e de filme, que é o que o espacializador procura. Imersivo
 * continua direto.
 */
object AudioRoute
{
	init { System.loadLibrary("p5m-vr") }

	/**
	 * Precisa ser chamado **antes** de o stream abrir.
	 *
	 * Quem lê a preferência é a saída de áudio do chiaki, no momento em que
	 * monta o `AudioStreamBuilder`. Depois disso o stream já está aberto e o
	 * modo de compartilhamento não muda mais.
	 */
	fun setPrefersSystemMixer(prefers: Boolean) = nativeSetPrefersSystemMixer(prefers)

	private external fun nativeSetPrefersSystemMixer(prefers: Boolean)
}
