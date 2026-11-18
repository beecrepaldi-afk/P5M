// SPDX-License-Identifier: AGPL-3.0-only
#include "profundidade_neural.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace p5m {

namespace {

constexpr size_t kPixels = (size_t)ProfundidadeNeural::kLargura * ProfundidadeNeural::kAltura;

// A faixa e medida por percentil, e nao por minimo e maximo: meia duzia de
// pixels absurdos na borda (o modelo erra nas bordas, todo modelo erra) nao
// podem decidir o quanto o resto da cena afunda.
constexpr float kPercentilBaixo = 0.02f;
constexpr float kPercentilAlto = 0.98f;
// Um em cada tres pixels: ~49 mil amostras para um percentil que nao muda na
// terceira casa. O nth_element nelas fica abaixo de meio milissegundo.
constexpr size_t kPassoAmostra = 3;
// Quanto da faixa nova entra a cada mapa. A passada de faixa do shader ja
// suaviza de novo depois; aqui so se tira o tremor de um mapa para o outro,
// que no ritmo de 20 mapas por segundo ainda seria visivel.
constexpr float kAlfaFaixa = 0.35f;

} // namespace

ProfundidadeNeural &ProfundidadeNeural::Instance()
{
	static ProfundidadeNeural instancia;
	return instancia;
}

void ProfundidadeNeural::Ligar()
{
	std::lock_guard<std::mutex> lock(mutex_);
	ligada_ = true;
	geracao_++;
	entrada_.assign(kPixels * 4, 0);
	saida_.assign(kPixels, 0);
	entrada_cheia_ = saida_cheia_ = saida_valida_ = false;
	faixa_valida_ = false;
}

void ProfundidadeNeural::Desligar()
{
	{
		std::lock_guard<std::mutex> lock(mutex_);
		ligada_ = false;
		geracao_++;
		entrada_cheia_ = saida_cheia_ = saida_valida_ = false;
	}
	cv_.notify_all();
}

bool ProfundidadeNeural::Ligada()
{
	std::lock_guard<std::mutex> lock(mutex_);
	return ligada_;
}

uint32_t ProfundidadeNeural::Geracao()
{
	std::lock_guard<std::mutex> lock(mutex_);
	return geracao_;
}

bool ProfundidadeNeural::EntregarEntrada(const uint8_t *rgba, bool inverter, uint32_t geracao)
{
	{
		std::lock_guard<std::mutex> lock(mutex_);
		if(!ligada_ || geracao != geracao_ || entrada_cheia_)
			return false;
		// Copia crua, sem conversao: 590 KB num memcpy, e o que a thread de
		// quadros pode pagar. A conversao para float fica para a thread da
		// rede, que tem tempo sobrando.
		memcpy(entrada_.data(), rgba, kPixels * 4);
		inverter_ = inverter;
		entrada_cheia_ = true;
	}
	cv_.notify_all();
	return true;
}

bool ProfundidadeNeural::EsperarEntrada(float *destino)
{
	bool inverter;
	{
		std::unique_lock<std::mutex> lock(mutex_);
		cv_.wait(lock, [this] { return !ligada_ || entrada_cheia_; });
		if(!ligada_)
			return false;
		// Copia e solta: a conversao e mais lenta que a copia, e a thread de
		// quadros nao deve esperar por ela no TomarSaida.
		copia_.resize(kPixels * 4);
		memcpy(copia_.data(), entrada_.data(), kPixels * 4);
		inverter = inverter_;
		entrada_cheia_ = false;
		geracao_em_voo_ = geracao_;
	}

	const float escala = 1.0f / 255.0f;
	for(int linha = 0; linha < kAltura; linha++)
	{
		const int origem = inverter ? kAltura - 1 - linha : linha;
		const uint8_t *p = copia_.data() + (size_t)origem * kLargura * 4;
		float *q = destino + (size_t)linha * kLargura * 3;
		for(int x = 0; x < kLargura; x++)
		{
			q[0] = p[0] * escala;
			q[1] = p[1] * escala;
			q[2] = p[2] * escala;
			p += 4;
			q += 3;
		}
	}
	return true;
}

void ProfundidadeNeural::PublicarSaida(const float *origem, bool ok)
{
	if(ok)
	{
		amostras_.clear();
		amostras_.reserve(kPixels / kPassoAmostra + 1);
		for(size_t i = 0; i < kPixels; i += kPassoAmostra)
		{
			const float v = origem[i];
			if(std::isfinite(v))
				amostras_.push_back(v);
		}
		ok = amostras_.size() > 64;
	}
	if(ok)
	{
		const size_t n = amostras_.size();
		const size_t ib = (size_t)(kPercentilBaixo * (float)(n - 1));
		const size_t ia = (size_t)(kPercentilAlto * (float)(n - 1));
		std::nth_element(amostras_.begin(), amostras_.begin() + ib, amostras_.end());
		const float baixo = amostras_[ib];
		std::nth_element(amostras_.begin() + ib, amostras_.begin() + ia, amostras_.end());
		const float alto = amostras_[ia];

		if(!faixa_valida_)
		{
			faixa_baixo_ = baixo;
			faixa_alto_ = alto;
			faixa_valida_ = true;
		}
		else
		{
			faixa_baixo_ += (baixo - faixa_baixo_) * kAlfaFaixa;
			faixa_alto_ += (alto - faixa_alto_) * kAlfaFaixa;
		}

		// Uma cena sem profundidade nenhuma (menu chapado, tela preta) tem
		// faixa quase zero, e esticar o ruido dela ate 0..1 inventaria relevo.
		// O piso da faixa o deixa onde esta.
		const float largura = std::max(faixa_alto_ - faixa_baixo_, 1e-4f);
		const float inv = 1.0f / largura;
		normalizada_.resize(kPixels);
		for(size_t i = 0; i < kPixels; i++)
		{
			float v = origem[i];
			v = std::isfinite(v) ? (v - faixa_baixo_) * inv : 0.5f;
			v = v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
			normalizada_[i] = (uint8_t)(v * 255.0f + 0.5f);
		}
	}

	std::lock_guard<std::mutex> lock(mutex_);
	if(!ligada_ || geracao_em_voo_ != geracao_)
		return;
	if(ok)
		saida_.swap(normalizada_);
	saida_valida_ = ok;
	saida_cheia_ = true;
}

bool ProfundidadeNeural::TomarSaida(uint8_t *destino, uint32_t geracao, bool *devolvida)
{
	std::lock_guard<std::mutex> lock(mutex_);
	*devolvida = false;
	if(geracao != geracao_ || !saida_cheia_)
		return false;
	*devolvida = true;
	saida_cheia_ = false;
	if(!saida_valida_)
		return false;
	// A saida esta na ordem do modelo (linha de cima primeiro); a textura
	// volta para a ordem do framebuffer, a mesma da captura, e assim o mapa
	// cai exatamente sobre o quadro que o gerou.
	for(int linha = 0; linha < kAltura; linha++)
	{
		const int fonte = inverter_ ? kAltura - 1 - linha : linha;
		memcpy(destino + (size_t)linha * kLargura,
				saida_.data() + (size_t)fonte * kLargura, kLargura);
	}
	return true;
}

} // namespace p5m
