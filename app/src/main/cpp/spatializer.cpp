// SPDX-License-Identifier: AGPL-3.0-only
#include "spatializer.h"

#include <algorithm>
#include <cmath>

namespace p5m {
namespace {

// Cabeca media e velocidade do som: so aparecem no calculo do atraso entre as
// orelhas, e sao os numeros de sempre da literatura.
constexpr float kHeadRadius = 0.0875f;
constexpr float kSoundSpeed = 343.0f;

// Atraso comum aos dois ouvidos, para o atraso interaural caber dos dois lados
// sem passar do inicio da linha. O maximo teorico do modelo de Woodworth e
// ~0,66 ms, ou 32 amostras a 48 kHz; 48 deixa folga e custa 1 ms de latencia
// constante, que ninguem percebe e nada acumula.
constexpr float kBaseDelay = 48.0f;

// Limites do afastamento dos alto-falantes. Uma tela muito estreita nao deve
// colar os dois canais no centro, e uma muito larga nao deve joga-los para os
// lados da cabeca: nos dois extremos a imagem estereo se desfaz.
constexpr float kMinHalfAngle = 0.26f;   // ~15 graus
constexpr float kMaxHalfAngle = 0.70f;   // ~40 graus

/** Atraso interaural de Woodworth, em segundos. Positivo: fonte a direita. */
inline float Itd(float azimuth)
{
	float a = std::max(-1.5707963f, std::min(1.5707963f, azimuth));
	return (kHeadRadius / kSoundSpeed) * (a + std::sin(a));
}

/**
 * Quanto a cabeca esconde esta fonte desta orelha, de 0 a 1.
 *
 * `earSign` e -1 para a esquerda e +1 para a direita. Com a fonte do lado da
 * orelha o resultado e zero; do lado oposto, um.
 */
inline float Shade(float azimuth, float earSign)
{
	return (1.0f - std::sin(azimuth) * earSign) * 0.5f;
}

} // namespace

Spatializer &Spatializer::Instance()
{
	static Spatializer instance;
	return instance;
}

void Spatializer::SetStrength(float strength)
{
	strength_.store(std::max(0.0f, std::min(1.0f, strength)));
}

void Spatializer::SetGeometry(float yawToScreen, float halfAngle)
{
	yaw_.store(yawToScreen);
	halfAngle_.store(std::max(kMinHalfAngle, std::min(kMaxHalfAngle, halfAngle)));
	active_.store(true);
}

void Spatializer::Clear()
{
	active_.store(false);
}

void Spatializer::Process(int16_t *frames, size_t frameCount, uint32_t channels, uint32_t rate)
{
	const float strength = strength_.load();

	// Sem sessao imersiva nao ha pose de cabeca, e sem cabeca nao ha o que
	// ancorar. No modo janela quem posiciona o audio do painel e o proprio
	// Horizon OS -- passar por aqui so tiraria dele o que ele ja faz.
	if(!active_.load() || strength <= 0.0f || channels != 2 || frameCount == 0
			|| rate == 0)
	{
		primed_ = false;
		return;
	}

	// Angulos de referencia: onde os alto-falantes estao com a cabeca de frente
	// para a tela. Sao eles que definem o que "nao mexer em nada" significa.
	const float half = halfAngle_.load();
	const float ref[2] = { -half, half };
	const float yaw = yaw_.load();

	float targetDelay[2][2];
	float targetGain[2][2];
	float targetDamp[2][2];

	for(int s = 0; s < 2; s++)
	{
		// A forca interpola entre o angulo de referencia e o real: em zero a
		// fonte fica onde sempre esteve e todo o resto vira identidade.
		const float az = ref[s] + strength * yaw;

		// Lei de pan de potencia constante, ancorada nos alto-falantes: no
		// angulo de referencia ela devolve o canal intacto na orelha certa e
		// nada na outra, entao com a cabeca de frente o estereo passa como veio.
		const float pan = std::max(-1.0f, std::min(1.0f, az / half));
		const float angle = (pan + 1.0f) * 0.7853982f;   // 0..pi/2
		const float panGain[2] = { std::cos(angle), std::sin(angle) };

		const float itd = strength * Itd(az) * (float)rate;

		for(int e = 0; e < 2; e++)
		{
			const float earSign = (e == 0) ? -1.0f : 1.0f;
			// A orelha do lado da fonte ouve antes: o atraso dela encolhe.
			targetDelay[s][e] = kBaseDelay - 0.5f * itd * earSign;

			// Sombra medida como diferenca em relacao a referencia, e nao em
			// absoluto: assim ela nasce em zero com a cabeca de frente, e o que
			// se ouve mudar e o giro, nao a entrada do efeito.
			const float shade = std::max(0.0f,
					strength * (Shade(az, earSign) - Shade(ref[s], earSign)));
			// Coeficiente do polo direto, sem passar por frequencia de corte: o
			// que se quer aqui e uma manopla monotona que em zero nao filtra
			// nada, e uma frequencia de corte "infinita" nunca da exatamente
			// isso. Em 1 o polo fica em torno de 2 kHz, que e a sombra cheia.
			targetDamp[s][e] = shade * 0.77f;
			targetGain[s][e] = panGain[e] * (1.0f - 0.30f * shade);
		}
	}

	// Primeiro bloco da sessao (ou depois de uma pausa): comeca ja no alvo, sem
	// deslizar a partir de zero -- deslizar de zero seria um fade-in do nada.
	if(!primed_)
	{
		primed_ = true;
		for(int s = 0; s < 2; s++)
			for(int e = 0; e < 2; e++)
			{
				delay_[s][e] = targetDelay[s][e];
				gain_[s][e] = targetGain[s][e];
				damp_[s][e] = targetDamp[s][e];
				lowpass_[s][e] = 0.0f;
			}
		for(int s = 0; s < 2; s++)
			for(size_t i = 0; i < kLineSize; i++)
				line_[s][i] = 0.0f;
	}

	const float step = 1.0f / (float)frameCount;

	for(size_t i = 0; i < frameCount; i++)
	{
		const float t = (float)(i + 1) * step;

		write_ = (write_ + 1) & kLineMask;
		line_[0][write_] = (float)frames[i * 2];
		line_[1][write_] = (float)frames[i * 2 + 1];

		float out[2] = { 0.0f, 0.0f };
		for(int s = 0; s < 2; s++)
		{
			for(int e = 0; e < 2; e++)
			{
				const float d = delay_[s][e] + (targetDelay[s][e] - delay_[s][e]) * t;
				const float g = gain_[s][e] + (targetGain[s][e] - gain_[s][e]) * t;
				const float a = damp_[s][e] + (targetDamp[s][e] - damp_[s][e]) * t;

				// Leitura fracionaria: o atraso interaural nao e um numero
				// inteiro de amostras, e arredonda-lo destruiria justamente a
				// pista que se quer -- as diferencas uteis sao de dezenas de
				// microssegundos.
				const size_t base = (write_ - (size_t)d) & kLineMask;
				const float frac = d - std::floor(d);
				const float v0 = line_[s][base];
				const float v1 = line_[s][(base - 1) & kLineMask];
				const float v = v0 + (v1 - v0) * frac;

				lowpass_[s][e] = v + (lowpass_[s][e] - v) * a;
				out[e] += lowpass_[s][e] * g;
			}
		}

		for(int e = 0; e < 2; e++)
		{
			const float clipped = std::max(-32768.0f, std::min(32767.0f, out[e]));
			frames[i * 2 + e] = (int16_t)std::lrintf(clipped);
		}
	}

	for(int s = 0; s < 2; s++)
		for(int e = 0; e < 2; e++)
		{
			delay_[s][e] = targetDelay[s][e];
			gain_[s][e] = targetGain[s][e];
			damp_[s][e] = targetDamp[s][e];
		}
}

namespace {
std::atomic<bool> g_prefers_system_mixer{false};
}

void SetPrefersSystemMixer(bool prefers)
{
	g_prefers_system_mixer.store(prefers);
}

} // namespace p5m

extern "C" int p5m_audio_prefers_system_mixer(void)
{
	return p5m::g_prefers_system_mixer.load() ? 1 : 0;
}

extern "C" void p5m_audio_filter(int16_t *frames, size_t frameCount, uint32_t channels,
		uint32_t rate)
{
	p5m::Spatializer::Instance().Process(frames, frameCount, channels, rate);
}
