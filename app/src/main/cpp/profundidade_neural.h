// SPDX-License-Identifier: AGPL-3.0-only
#ifndef P5M_PROFUNDIDADE_NEURAL_H
#define P5M_PROFUNDIDADE_NEURAL_H

#include <condition_variable>
#include <mutex>
#include <stdint.h>
#include <vector>

namespace p5m {

/**
 * A ponte entre a thread de quadros e a thread da rede de profundidade.
 *
 * A rede (ZipDepth, rodando no LiteRT pelo Kotlin) leva mais que um quadro do
 * painel, entao ela nao pode morar na thread de quadros. Fica numa thread
 * propria, e as duas so se falam por aqui:
 *
 *  - a thread de quadros reduz o video a 512x288, le de volta por um PBO sem
 *    esperar a GPU, e entrega os bytes com EntregarEntrada;
 *  - a thread da rede espera em EsperarEntrada, que converte os bytes para o
 *    formato que o modelo quer, roda o modelo e devolve com PublicarSaida, que
 *    normaliza a saida para 0..1 em 8 bits;
 *  - a thread de quadros pega o mapa com TomarSaida e o sobe numa textura, que
 *    a passada de profundidade passa a ler no lugar das pistas.
 *
 * Um mapa so em voo de cada vez: a ida e volta inteira (leitura, rede,
 * subida) fica abaixo de 40 ms, e o ritmo pedido e de um mapa a cada 50 ms.
 * Com dois em voo o ganho seria pouco e a contabilidade dobraria.
 *
 * Nenhuma chamada de GL mora aqui -- isso e da ToneMapper. Aqui so ha bytes e
 * a sincronizacao entre as duas threads, o que deixa tudo isto fora do
 * contexto de GL e testavel no host.
 */
class ProfundidadeNeural
{
public:
	// O tamanho que o modelo foi exportado: 16:9, sem esticar o quadro.
	static constexpr int kLargura = 512;
	static constexpr int kAltura = 288;

	static ProfundidadeNeural &Instance();

	// --- Thread da rede (pelo JNI) ---

	// O modelo carregou: a thread de quadros pode comecar a capturar.
	void Ligar();
	// A thread da rede vai embora; quem estiver esperando acorda.
	void Desligar();
	// Bloqueia ate haver um quadro capturado, e o escreve em `destino` como
	// RGB float 0..1, linha de cima primeiro (kLargura * kAltura * 3).
	// Falso quando foi desligada.
	bool EsperarEntrada(float *destino);
	// A saida do modelo, profundidade inversa relativa (maior = mais perto),
	// kLargura * kAltura floats. `ok` falso devolve a vez sem mapa.
	void PublicarSaida(const float *origem, bool ok);

	// --- Thread de quadros ---

	bool Ligada();
	// Muda a cada Ligar/Desligar: um mapa de uma rodada anterior nunca entra
	// na seguinte.
	uint32_t Geracao();
	// RGBA8 lido do framebuffer, linha de baixo primeiro. `inverter` diz se a
	// linha de baixo do framebuffer e a de baixo da imagem.
	bool EntregarEntrada(const uint8_t *rgba, bool inverter, uint32_t geracao);
	// O mapa pronto, em 8 bits, ja na ordem de linhas do framebuffer. Falso
	// enquanto nao ha mapa. `devolvida` diz se a rede devolveu a vez (com ou
	// sem mapa), que e o que libera a proxima captura.
	bool TomarSaida(uint8_t *destino, uint32_t geracao, bool *devolvida);

private:
	ProfundidadeNeural() = default;

	std::mutex mutex_;
	std::condition_variable cv_;
	bool ligada_ = false;
	uint32_t geracao_ = 0;

	std::vector<uint8_t> entrada_;
	bool entrada_cheia_ = false;
	bool inverter_ = true;

	std::vector<uint8_t> saida_;
	bool saida_cheia_ = false;
	bool saida_valida_ = false;

	// A geracao do quadro que a rede esta rodando: se mudou no meio, a saida
	// e de uma rodada que ja acabou e nao e publicada.
	uint32_t geracao_em_voo_ = 0;

	// So da thread da rede: a faixa suavizada entre mapas.
	float faixa_baixo_ = 0.0f;
	float faixa_alto_ = 1.0f;
	bool faixa_valida_ = false;
	std::vector<uint8_t> copia_;
	std::vector<float> amostras_;
	std::vector<uint8_t> normalizada_;
};

} // namespace p5m

#endif
