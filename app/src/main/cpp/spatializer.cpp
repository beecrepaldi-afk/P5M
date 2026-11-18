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

/**
 * Limite suave no fim da escala de 16 bits.
 *
 * Com a cabeca virada, uma orelha soma os dois alto-falantes, e som que e
 * igual nos dois canais (dialogo, tudo que esta no centro) chega a 1,7 vez o
 * original. O corte seco no int16 transformava esses picos em distorcao
 * audivel. Abaixo do joelho nada muda; acima, o pico e arredondado em vez
 * de decepado. A tanh so roda la em cima, que e raro.
 */
inline float Limitar(float x)
{
	constexpr float kJoelho = 24576.0f;              // ~ -2,5 dBFS
	constexpr float kFolga = 32767.0f - kJoelho;
	const float m = std::fabs(x);
	if(m <= kJoelho)
		return x;
	const float y = kJoelho + kFolga * std::tanh((m - kJoelho) / kFolga);
	return x < 0.0f ? -y : y;
}

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
	room_.store(strength > 1.0f);
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
		roomPrimed_ = false;
		return;
	}
	if(room_.load())
	{
		primed_ = false;
		ProcessRoom(frames, frameCount, rate);
		return;
	}
	roomPrimed_ = false;

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

				float lp = v + (lowpass_[s][e] - v) * a;
				// No silencio digital o filtro decai para sempre sem chegar a
				// zero e para no menor subnormal. Um milesimo de um degrau do
				// int16 nao e som; zerado, sai da faixa subnormal.
				if(std::fabs(lp) < 1e-3f)
					lp = 0.0f;
				lowpass_[s][e] = lp;
				out[e] += lp * g;
			}
		}

		for(int e = 0; e < 2; e++)
		{
			const float clipped = std::max(-32768.0f, std::min(32767.0f, Limitar(out[e])));
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

/** Woodworth para qualquer azimute: atras da cabeca o atraso volta a zero. */
inline float ItdFull(float az)
{
	az = std::remainder(az, 6.2831853f);
	if(az > 1.5707963f)
		az = 3.1415927f - az;
	else if(az < -1.5707963f)
		az = -3.1415927f - az;
	return Itd(az);
}

/**
 * Coeficientes da sombra da cabeca de Brown e Duda para uma fonte em `az`
 * vista pela orelha de lado `earSign` (-1 esquerda, +1 direita).
 * H(s) = (1 + alfa s/w) / (1 + s/w), w = 2c/a, pela transformada bilinear.
 */
inline void Sombra(float az, float earSign, float rate, float &b0, float &b1, float &a1)
{
	// Angulo entre a fonte e o eixo da orelha, de 0 (de frente para ela) a pi.
	const float earAz = earSign * 1.5707963f;
	float theta = std::fabs(std::remainder(az - earAz, 6.2831853f));
	constexpr float kAlfaMin = 0.1f;
	constexpr float kThetaMin = 2.6179939f;   // 150 graus
	const float alfa = (1.0f + kAlfaMin * 0.5f)
			+ (1.0f - kAlfaMin * 0.5f) * std::cos(theta / kThetaMin * 3.1415927f);
	const float w = 2.0f * kSoundSpeed / kHeadRadius;
	const float k = 2.0f * rate / w;
	const float n = 1.0f + k;
	b0 = (1.0f + alfa * k) / n;
	b1 = (1.0f - alfa * k) / n;
	a1 = (1.0f - k) / n;
}

struct Reflexao
{
	float azOffset;     // em relacao ao alto-falante, no mundo (radianos)
	float worldAz;      // azimute fixo no mundo, quando != 0 vale este
	bool fixed;
	float extraMs;
	float gain;
};

// Sala pequena, presa ao mundo: o que gira com a cabeca e o mesmo giro dos
// alto-falantes. Valores de uma sala de estar comum a ~2 m da tela.
constexpr Reflexao kSala[] = {
	{ 0.0f, 0.0f, false, 2.8f, 0.50f },        // chao
	{ 0.0f, 0.0f, false, 5.6f, 0.35f },        // teto
	{ 0.0f, -1.2217305f, true, 6.4f, 0.40f },  // parede esquerda (-70 graus)
	{ 0.0f, 1.2217305f, true, 7.6f, 0.36f },   // parede direita (+70 graus)
	{ 0.0f, 3.1415927f, true, 13.0f, 0.26f },  // fundo
	{ 0.0f, 0.0f, false, 9.2f, 0.24f },        // atras da tela
};
static_assert(sizeof(kSala) / sizeof(kSala[0]) == 6, "kReflections");

constexpr size_t kTailLen[4] = { 1103, 1327, 1543, 1789 };   // primos, 23-37 ms a 48 kHz

std::atomic<bool> g_prefers_system_mixer{false};
}

void Spatializer::ProcessRoom(int16_t *frames, size_t frameCount, uint32_t rate)
{
	const float half = halfAngle_.load();
	const float yaw = yaw_.load();
	const float fs = (float)rate;
	const float az[2] = { -half + yaw, half + yaw };

	// Fonte direta: atraso e coeficientes da sombra por orelha.
	float targetDelay[2][2];
	float b0[2][2], b1[2][2], a1[2][2];
	for(int s = 0; s < 2; s++)
	{
		const float itd = ItdFull(az[s]) * fs;
		for(int e = 0; e < 2; e++)
		{
			const float earSign = (e == 0) ? -1.0f : 1.0f;
			targetDelay[s][e] = kBaseDelay - 0.5f * itd * earSign;
			Sombra(az[s], earSign, fs, b0[s][e], b1[s][e], a1[s][e]);
		}
	}

	// Reflexoes: atraso por orelha e ganho com uma diferenca de nivel simples.
	float targetReflDelay[2][kReflections][2];
	float targetReflGain[2][kReflections][2];
	for(int s = 0; s < 2; s++)
		for(int r = 0; r < kReflections; r++)
		{
			const Reflexao &k = kSala[r];
			const float raz = k.fixed ? (k.worldAz + yaw) : az[s];
			const float itd = ItdFull(raz) * fs;
			for(int e = 0; e < 2; e++)
			{
				const float earSign = (e == 0) ? -1.0f : 1.0f;
				targetReflDelay[s][r][e] = kBaseDelay + k.extraMs * 0.001f * fs - 0.5f * itd * earSign;
				// Orelha do lado oposto ouve ~6 dB menos, de forma suave.
				const float lado = std::sin(raz) * earSign;   // +1 do lado da orelha
				targetReflGain[s][r][e] = k.gain * (0.75f + 0.25f * lado);
			}
		}

	if(!roomPrimed_)
	{
		roomPrimed_ = true;
		for(int s = 0; s < 2; s++)
		{
			for(size_t i = 0; i < kLineSize; i++)
				line_[s][i] = 0.0f;
			for(int e = 0; e < 2; e++)
			{
				delay_[s][e] = targetDelay[s][e];
				shadowX_[s][e] = shadowY_[s][e] = 0.0f;
				for(int r = 0; r < kReflections; r++)
				{
					reflDelay_[s][r][e] = targetReflDelay[s][r][e];
					reflGain_[s][r][e] = targetReflGain[s][r][e];
				}
			}
		}
		reflLowpass_[0] = reflLowpass_[1] = 0.0f;
		for(int l = 0; l < kTailLines; l++)
		{
			for(size_t i = 0; i < kTailSize; i++)
				tail_[l][i] = 0.0f;
			tailDamp_[l] = 0.0f;
		}
	}

	// Niveis: direto inteiro, sala ~ -8 dB, cauda ~ -20 dB. O total fica perto
	// do original para som correlacionado (dialogo no centro).
	constexpr float kDireto = 0.80f;
	constexpr float kSalaNivel = 0.38f;
	constexpr float kCauda = 0.09f;
	constexpr float kRealimenta = 0.55f;     // ~0,25 s de cauda
	constexpr float kAbsorcao = 0.45f;       // passa-baixa das paredes
	const float step = 1.0f / (float)frameCount;

	auto ler = [this](int s, float d) {
		const size_t base = (write_ - (size_t)d) & kLineMask;
		const float frac = d - std::floor(d);
		const float v0 = line_[s][base];
		const float v1 = line_[s][(base - 1) & kLineMask];
		return v0 + (v1 - v0) * frac;
	};

	for(size_t i = 0; i < frameCount; i++)
	{
		const float t = (float)(i + 1) * step;
		write_ = (write_ + 1) & kLineMask;
		line_[0][write_] = (float)frames[i * 2];
		line_[1][write_] = (float)frames[i * 2 + 1];

		float out[2] = { 0.0f, 0.0f };
		float refl[2] = { 0.0f, 0.0f };
		for(int s = 0; s < 2; s++)
			for(int e = 0; e < 2; e++)
			{
				const float d = delay_[s][e] + (targetDelay[s][e] - delay_[s][e]) * t;
				const float x = ler(s, d);
				float y = b0[s][e] * x + b1[s][e] * shadowX_[s][e] - a1[s][e] * shadowY_[s][e];
				if(std::fabs(y) < 1e-3f)
					y = 0.0f;
				shadowX_[s][e] = x;
				shadowY_[s][e] = y;
				out[e] += y * kDireto;

				for(int r = 0; r < kReflections; r++)
				{
					const float rd = reflDelay_[s][r][e] + (targetReflDelay[s][r][e] - reflDelay_[s][r][e]) * t;
					const float rg = reflGain_[s][r][e] + (targetReflGain[s][r][e] - reflGain_[s][r][e]) * t;
					refl[e] += ler(s, rd) * rg;
				}
			}

		for(int e = 0; e < 2; e++)
		{
			float lp = refl[e] + (reflLowpass_[e] - refl[e]) * kAbsorcao;
			if(std::fabs(lp) < 1e-3f)
				lp = 0.0f;
			reflLowpass_[e] = lp;
			out[e] += lp * kSalaNivel;
		}

		// Cauda: quatro linhas com matriz de Hadamard, alimentadas pelo meio.
		const float entrada = (line_[0][write_] + line_[1][write_]) * 0.25f;
		float v[kTailLines];
		for(int l = 0; l < kTailLines; l++)
		{
			const size_t idx = (tailWrite_ - kTailLen[l]) & (kTailSize - 1);
			float x = tail_[l][idx];
			tailDamp_[l] = x + (tailDamp_[l] - x) * 0.35f;
			if(std::fabs(tailDamp_[l]) < 1e-3f)
				tailDamp_[l] = 0.0f;
			v[l] = tailDamp_[l];
		}
		const float h[4] = { v[0] + v[1] + v[2] + v[3], v[0] - v[1] + v[2] - v[3],
				v[0] + v[1] - v[2] - v[3], v[0] - v[1] - v[2] + v[3] };
		tailWrite_ = (tailWrite_ + 1) & (kTailSize - 1);
		for(int l = 0; l < kTailLines; l++)
			tail_[l][tailWrite_] = entrada + h[l] * 0.5f * kRealimenta;
		out[0] += (v[0] + v[2]) * kCauda;
		out[1] += (v[1] + v[3]) * kCauda;

		for(int e = 0; e < 2; e++)
		{
			const float clipped = std::max(-32768.0f, std::min(32767.0f, Limitar(out[e])));
			frames[i * 2 + e] = (int16_t)std::lrintf(clipped);
		}
	}

	for(int s = 0; s < 2; s++)
		for(int e = 0; e < 2; e++)
		{
			delay_[s][e] = targetDelay[s][e];
			for(int r = 0; r < kReflections; r++)
			{
				reflDelay_[s][r][e] = targetReflDelay[s][r][e];
				reflGain_[s][r][e] = targetReflGain[s][r][e];
			}
		}
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
