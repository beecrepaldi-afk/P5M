// SPDX-License-Identifier: AGPL-3.0-only
#ifndef P5M_SPATIALIZER_H
#define P5M_SPATIALIZER_H

#include <atomic>
#include <stddef.h>
#include <stdint.h>

namespace p5m {

/**
 * Alto-falantes virtuais presos a tela.
 *
 * O Remote Play manda dois canais de Opus e mais nada -- nao ha 5.1, nao ha
 * ambisonico, nao ha objetos. Entao nao ha o que "espacializar" no sentido de
 * descobrir onde cada som estava: o que existe e um par estereo, e o que se
 * pode fazer com ele e ancora-lo na tela.
 *
 * E o que isto faz. Os dois canais viram dois alto-falantes na largura e na
 * distancia da tela que o usuario posicionou. Quando a cabeca gira, eles ficam
 * onde estao, e o palco sonoro deixa de acompanhar a cabeca -- que e o unico
 * incomodo real de ouvir estereo comum dentro de um headset.
 *
 * Nao e simulacao de sala: nao ha reflexoes, nao ha elevacao, nao ha frente
 * contra tras. Isso pede HRTF de verdade, e HRTF pede biblioteca e um bloco de
 * latencia. Aqui sao tres coisas so, todas por amostra e sem buffer nenhum:
 *
 *  - diferenca de tempo entre as orelhas (Woodworth), que e a pista dominante
 *    de direcao abaixo de 1,5 kHz e a que faz a imagem ficar parada;
 *  - a rotacao da imagem em si, por uma lei de pan de potencia constante presa
 *    aos angulos dos alto-falantes;
 *  - um sombreamento suave da orelha distante, so como diferenca em relacao ao
 *    que ja havia com a cabeca de frente.
 *
 * As tres degradam para identidade quando a forca e zero: com o efeito
 * desligado o que sai e exatamente o que entrou, e nao uma aproximacao dele.
 *
 * A geometria e publicada pelo frame loop a cada quadro; o processamento roda
 * na thread de audio do Oboe. Entre as duas so ha atomicos -- nenhuma trava no
 * caminho de um callback de tempo real.
 */
class Spatializer
{
public:
	static Spatializer &Instance();

	/**
	 * 0 desliga (passagem exata), 1 e o efeito cheio. Acima de 1 liga o modo
	 * sala ([ProcessRoom]) com o efeito cheio -- assim o nivel novo do menu
	 * passa pela mesma chamada JNI.
	 */
	void SetStrength(float strength);

	/**
	 * Onde estao os alto-falantes, em relacao a cabeca.
	 *
	 * @param yawToScreen azimute do centro da tela visto da cabeca, em
	 *        radianos: zero com a tela bem a frente, positivo quando ela esta a
	 *        direita do olhar
	 * @param halfAngle metade do angulo que a tela subtende, tambem em radianos
	 */
	void SetGeometry(float yawToScreen, float halfAngle);

	/** Esquece a geometria: sem sessao imersiva nao ha cabeca para rastrear. */
	void Clear();

	/** Chamado da thread de audio. Silencioso e sem alocacao. */
	void Process(int16_t *frames, size_t frameCount, uint32_t channels, uint32_t rate);

private:
	Spatializer() = default;

	/**
	 * Modo sala: alto-falantes virtuais de verdade, e nao so ancorados.
	 *
	 * Cada alto-falante chega as duas orelhas (com o modo normal, de frente
	 * para a tela, o canal esquerdo so chega a orelha esquerda -- e o que deixa
	 * o som dentro da cabeca), com o atraso interaural de Woodworth e a sombra
	 * da cabeca do modelo estrutural de Brown e Duda (1998): um polo e um zero
	 * que viram prateleira de agudos conforme o angulo entre a fonte e a
	 * orelha. Mais seis reflexoes de uma sala pequena presa ao mundo (chao,
	 * teto, paredes, fundo, atras da tela) e uma cauda curta de quatro linhas.
	 * Tudo por amostra: nenhuma latencia de bloco.
	 */
	void ProcessRoom(int16_t *frames, size_t frameCount, uint32_t rate);

	// Estado da thread de audio. Nada aqui e tocado de fora.
	// Potencia de dois: mascara em vez de resto. 2048 cabe a reflexao mais
	// distante do modo sala (~16 ms) com o atraso interaural e a folga.
	static constexpr size_t kLineSize = 2048;
	static constexpr size_t kLineMask = kLineSize - 1;
	float line_[2][kLineSize] = {};
	size_t write_ = 0;
	float lowpass_[2][2] = {};
	// Valores correntes, que deslizam ate o alvo ao longo do bloco. Sem isto
	// cada mudanca de angulo entraria como degrau, e degrau numa linha de atraso
	// e ruido de zipper.
	float delay_[2][2] = {};
	float gain_[2][2] = {};
	float damp_[2][2] = {};
	bool primed_ = false;

	// Modo sala. Sombra da cabeca por fonte direta e orelha (estado do filtro).
	float shadowX_[2][2] = {};
	float shadowY_[2][2] = {};
	static constexpr int kReflections = 6;
	float reflDelay_[2][kReflections][2] = {};
	float reflGain_[2][kReflections][2] = {};
	float reflLowpass_[2] = {};
	// Cauda: quatro linhas de realimentacao com amortecimento.
	static constexpr int kTailLines = 4;
	static constexpr size_t kTailSize = 2048;
	float tail_[kTailLines][kTailSize] = {};
	float tailDamp_[kTailLines] = {};
	size_t tailWrite_ = 0;
	bool roomPrimed_ = false;

	std::atomic<bool> room_{false};
	std::atomic<float> strength_{0.0f};
	std::atomic<float> yaw_{0.0f};
	std::atomic<float> halfAngle_{0.35f};
	std::atomic<bool> active_{false};
};

/** Ver p5m_audio_prefers_system_mixer. */
void SetPrefersSystemMixer(bool prefers);

} // namespace p5m

/**
 * Gancho chamado pela saida de audio do chiaki, do outro lado da fronteira de
 * biblioteca. Resolvido por dlsym, entao o nome importa e nao pode ser
 * decorado.
 */
extern "C" __attribute__((visibility("default")))
void p5m_audio_filter(int16_t *frames, size_t frameCount, uint32_t channels, uint32_t rate);

/**
 * Se a saida de audio deve passar pelo mixer do sistema em vez de ir direto ao
 * hardware.
 *
 * Um stream em SharingMode::Exclusive fala com o hardware sem passar pelo
 * mixer, e o que nao passa pelo mixer nao pode ser processado por ele -- o
 * espacializador do Horizon OS incluido. E por isso que uma janela do navegador
 * tem audio posicionado no painel e a nossa nao tinha: nao e recurso que falte
 * ao sistema, e o nosso stream que passa por fora dele.
 *
 * No modo imersivo continua valendo o caminho direto: la a espacializacao e
 * nossa, e a latencia do mixer seria paga a troco de nada.
 */
extern "C" __attribute__((visibility("default")))
int p5m_audio_prefers_system_mixer(void);

#endif // P5M_SPATIALIZER_H
