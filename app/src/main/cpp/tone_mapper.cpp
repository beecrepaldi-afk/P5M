// SPDX-License-Identifier: AGPL-3.0-only
#include "tone_mapper.h"
#include "log.h"
#include "profundidade_neural.h"

#include <cmath>
#include <cstring>
#include <string>
#include <time.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <cstdio>
#include <cstdlib>
#include <algorithm>

namespace p5m {
namespace {

const char *kVertexShader = R"(#version 300 es
in vec2 aPos;
uniform mat4 uTexMatrix;
uniform vec2 uTexelStep;
out vec2 vUv;
out vec2 vDx;
out vec2 vDy;
// Coordenada no ALVO, de 0 a 1. O vUv aponta para a fonte e ja passou pela
// matriz da SurfaceTexture; para saber em qual metade da tela dividida este
// pixel caiu, o que serve e a posicao no destino, antes de qualquer matriz.
out vec2 vScreen;
void main()
{
	vScreen = aPos * 0.5 + 0.5;
	// A matriz da SurfaceTexture carrega recorte e orientacao do buffer, entao
	// ela resolve tambem a inversao vertical -- aqui nao ha o que corrigir a
	// mao, ao contrario do caminho direto para o compositor.
	vec2 uv = aPos * 0.5 + 0.5;
	vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;

	// O passo de um pixel tem de atravessar a mesma matriz, senao a cruz de
	// amostras da nitidez sai torta em qualquer buffer que venha recortado ou
	// girado. Calculado por vertice, custa quatro multiplicacoes de matriz na
	// sessao inteira em vez de duas por pixel.
	vDx = (uTexMatrix * vec4(uv + vec2(uTexelStep.x, 0.0), 0.0, 1.0)).xy - vUv;
	vDy = (uTexMatrix * vec4(uv + vec2(0.0, uTexelStep.y), 0.0, 1.0)).xy - vUv;

	gl_Position = vec4(aPos, 0.0, 1.0);
}
)";

// Passada da profundidade: um quarto da resolucao em cada eixo, um dezesseis
// avos dos pixels.
//
// Antes isto vivia dentro da passada principal, e era caro pelo pior motivo: a
// passada principal tem o dobro dos pixels no modo 3D, entao cinco amostras de
// profundidade viravam cinco amostras em 3840x1080. Num alvo pequeno o mesmo
// trabalho custa um dezesseis avos, e a leitura de volta sai de graca com
// filtragem linear -- que ainda por cima borra o mapa, que e exatamente o que
// se quer.
//
// A separacao paga tres coisas de uma vez: o custo cai, sobra orcamento para
// pistas melhores, e a profundidade passa a ter um lugar proprio -- se um dia
// ela vier de uma rede neural, e este alvo que ela preenche, sem tocar em mais
// nada.
const char *kDepthShader = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

uniform samplerExternalOES uTexture;
// Profundidade e luminancia do quadro anterior: R e a profundidade ja
// suavizada, G a luminancia, que serve para saber o que ficou parado.
uniform sampler2D uPrev;
uniform int uHasPrev;
uniform float uGroundSign;
// O mapa da rede neural, quando ha um. R e a profundidade ja normalizada,
// maior = mais perto, na mesma grade de tela deste alvo.
uniform sampler2D uNeural;
uniform int uHasNeural;

in vec2 vUv;
in vec2 vDx;
in vec2 vDy;
in vec2 vScreen;
out vec4 fragColor;

void main()
{
	vec3 c = texture(uTexture, vUv).rgb;

	// A cor da vizinhanca, e nao a de um ponto.
	//
	// Cada texel deste alvo cobre dezesseis pixels da fonte, e uma amostra so
	// le um deles: o mapa passa a depender de qual pixel caiu no centro, e isso
	// muda a cada passo da camera -- cintilacao que a media no tempo so
	// disfarca. Quatro amostras em diagonal, cada uma ja com filtragem
	// bilinear, cobrem o bloco quase inteiro pelo preco de tres a mais.
	vec3 c4 = (texture(uTexture, vUv + (vDx + vDy) * 0.75).rgb
			+ texture(uTexture, vUv + (vDx - vDy) * 0.75).rgb
			+ texture(uTexture, vUv - (vDx - vDy) * 0.75).rgb
			+ texture(uTexture, vUv - (vDx + vDy) * 0.75).rgb) * 0.25;

	// Amostras largas: e nelas que moram as pistas de cena -- textura contra
	// neblina, cor viva contra cor lavada. Espalhadas de proposito, porque mapa
	// de profundidade com borda dura produz halo em volta de tudo.
	vec3 n = texture(uTexture, vUv - vDy * 8.0).rgb;
	vec3 s = texture(uTexture, vUv + vDy * 8.0).rgb;
	vec3 w = texture(uTexture, vUv - vDx * 8.0).rgb;
	vec3 e = texture(uTexture, vUv + vDx * 8.0).rgb;

	const vec3 media = vec3(0.3333);
	float detalhe = clamp((abs(dot(n - c4, media)) + abs(dot(s - c4, media))
			+ abs(dot(w - c4, media)) + abs(dot(e - c4, media))) * 2.0, 0.0, 1.0);

	float mx = max(max(c4.r, c4.g), c4.b);
	float mn = min(min(c4.r, c4.g), c4.b);
	float sat = (mx - mn) / max(mx, 1e-4);

	float chao = clamp(uGroundSign > 0.0 ? 1.0 - vScreen.y : vScreen.y, 0.0, 1.0);

	// A pista de detalhe pesa menos do que pesava.
	//
	// Ela empurra para perto o que tem alta frequencia, e texto e a coisa de
	// maior frequencia que existe num quadro -- entao legenda e HUD saltavam
	// para a frente, separados do fundo. Foi o defeito relatado no primeiro
	// teste, e "o texto fica estranho" e exatamente o que essa pista causa.
	//
	// A rampa do chao pesa conforme haja chao.
	//
	// Ela e a pista mais forte -- "o que esta embaixo da tela esta perto" --, e
	// numa cena de terceira pessoa com o piso enquadrado ela acerta quase
	// sozinha. Numa parede, num close ou num menu de tela cheia nao ha chao
	// nenhum, e ela continuava impondo a mesma inclinacao global: a imagem
	// inteira tombava para tras sem que nada na cena pedisse.
	//
	// O que distingue os dois casos e gradiente vertical. Um piso que se afasta
	// clareia ou escurece de baixo para cima; uma parede chapada, uma cara em
	// close e um fundo de menu nao. Onde nao ha essa variacao a rampa recua
	// para menos da metade da autoridade e as outras duas pistas ficam com o que
	// ela devolveu.
	//
	// O gradiente sai de um nivel grosso do mapa anterior, e nao de dois pixels
	// da fonte. Medido ponto a ponto, o peso da rampa variava de um texel para o
	// outro conforme a textura local: parede lisa perdia a rampa, a textura do
	// lado ganhava, e o chao saia manchado. "Ha chao aqui" e pergunta de
	// regiao, e a resposta tem de variar devagar pela tela.
	float grad_v = abs(dot(s - n, media));
	if(uHasPrev == 1)
		grad_v = abs(textureLod(uPrev, vScreen + vec2(0.0, 0.015), 2.0).g
				- textureLod(uPrev, vScreen - vec2(0.0, 0.015), 2.0).g);
	float peso_chao = mix(0.25, 0.60, smoothstep(0.010, 0.075, grad_v));
	float resto = 1.0 - peso_chao;
	// Saturacao e detalhe agora dividem o resto por igual. A saturacao pesava
	// quase dois tercos, e ela varia por cor dentro de um mesmo objeto: a camisa
	// vermelha saltava para a frente do rosto de quem a veste.
	float d = clamp(peso_chao * chao + resto * (0.5 * detalhe + 0.5 * sat),
			0.0, 1.0);
	// Ceu e nevoa (dev.214): as duas pistas de distancia que faltavam.
	//
	// Ceu: no alto, liso, e claro ou azulado. Sem esta pista ele caia no meio
	// da faixa -- a rampa do chao o dava por longe, mas a saturacao do azul o
	// puxava de volta --, e o horizonte parecia colado na tela. Com ela vai
	// para o fundo de verdade. "Liso" e o que separa ceu de copa de arvore ou
	// de predio, que tambem estao no alto.
	float alto = 1.0 - chao;
	float claro = dot(c4, vec3(0.299, 0.587, 0.114));
	float liso = 1.0 - smoothstep(0.03, 0.12, detalhe);
	float azul = smoothstep(0.02, 0.12, c4.b - max(c4.r, c4.g) * 0.92);
	float ceu = smoothstep(0.45, 0.85, alto) * liso * max(smoothstep(0.50, 0.85, claro), azul);
	// Nevoa: perspectiva atmosferica, o truque que todo jogo usa para dar
	// distancia -- o longe fica lavado, claro e sem contraste. Pesa menos que
	// o ceu e so da meia tela para cima, porque parede branca perto da camera
	// tambem e clara e lisa, e ela nao pode afundar.
	float nevoa = (1.0 - smoothstep(0.10, 0.40, sat)) * smoothstep(0.40, 0.80, claro) * liso
			* smoothstep(0.35, 0.70, alto);
	d = mix(d, 0.0, max(ceu * 0.85, nevoa * 0.40));

	// Com a rede, as pistas acima so servem de reserva: o mapa dela entra no
	// lugar da estimativa, e o resto desta passada -- marca de interface,
	// movimento, media no tempo -- continua valendo igual. A media no tempo e
	// o que transforma os 20 mapas por segundo da rede em transicao suave nos
	// 90 quadros do painel, em vez de um degrau a cada mapa.
	if(uHasNeural == 1)
		d = textureLod(uNeural, vScreen, 0.0).r;

	// Contraste em escala fina, de um texel: e o que separa texto de textura.
	// Parede de tijolo tem detalhe largo; letra tem borda dura de um pixel para
	// o outro. Esta e a unica medida que distingue os dois.
	vec3 fn = texture(uTexture, vUv - vDy).rgb;
	vec3 fs = texture(uTexture, vUv + vDy).rgb;
	vec3 fw = texture(uTexture, vUv - vDx).rgb;
	vec3 fe = texture(uTexture, vUv + vDx).rgb;
	float fino = (abs(dot(fn - c, media)) + abs(dot(fs - c, media))
			+ abs(dot(fw - c, media)) + abs(dot(fe - c, media))) * 0.25;

	float luma = dot(c4, vec3(0.299, 0.587, 0.114));
	float parado = 1.0;
	float mudanca = 0.0;
	float interface_ = 0.0;
	if(uHasPrev == 1)
	{
		vec4 ant = textureLod(uPrev, vScreen, 0.0);
		mudanca = abs(luma - ant.g);
		// Parado e o que nao mudou nada de um quadro para o outro. Num jogo, o
		// mundo sempre se mexe um pouco -- camera, animacao, luz --, e o que
		// fica exatamente igual e interface desenhada por cima.
		parado = 1.0 - smoothstep(0.004, 0.03, mudanca);

		// Interface: borda dura E imovel. Uma coisa so nao basta -- textura fina
		// tambem tem borda dura, e ceu tambem fica parado.
		//
		// E a prova se acumula, em vez de ser decidida a cada quadro. Decidida
		// no quadro, a cena respirava: com o jogador parado o mundo inteiro fica
		// imovel, toda textura fina virava interface e achatava no plano da
		// tela, e a profundidade voltava no primeiro passo. So conta como prova
		// o que fica imovel ENQUANTO o resto da imagem se mexe -- a media do
		// movimento no quadro anterior, no ultimo nivel do mapa. Com a cena
		// toda parada ela ainda sobe, mas dez vezes mais devagar.
		float movendo = smoothstep(0.003, 0.012, textureLod(uPrev, vec2(0.5), 16.0).a);
		float candidato = smoothstep(0.05, 0.16, fino) * parado;
		interface_ = ant.b + 0.06 * candidato * mix(0.1, 1.0, movendo);
		// O que mudou nao e interface: cai depressa. E sem prova nenhuma, para
		// qualquer lado, a marca se apaga devagar, em segundos.
		interface_ *= (1.0 - 0.35 * smoothstep(0.02, 0.08, mudanca)) * 0.998;
		interface_ = clamp(interface_, 0.0, 1.0);
	}
	// A marca da interface sai daqui no canal B, e nao misturada na
	// profundidade: quem a aplica e a passada final, depois de a faixa do mapa
	// ser normalizada. Misturada aqui, ela entraria na conta da faixa e
	// puxaria os extremos para o meio -- a interface decidiria o quanto a cena
	// inteira afunda.

	if(uHasPrev == 1)
	{
		vec4 ant = textureLod(uPrev, vScreen, 0.0);
		// Media exponencial com o quadro anterior. A estimativa treme de quadro
		// a quadro porque as pistas tremem, e tremor de profundidade vira
		// cintilacao -- o tipo de desconforto que faz tirar o headset.
		//
		// O passo e adaptativo: numa cena parada a suavizacao e forte e o mapa
		// fica estavel; num corte de cena, em que a imagem inteira muda, ela
		// afrouxa para o mapa nao arrastar o enquadramento antigo por meio
		// segundo.
		float alfa = mix(0.12, 0.85, smoothstep(0.02, 0.20, mudanca));
		d = mix(ant.r, d, alfa);
	}

	// R profundidade, G luminancia, B marca de interface, A movimento do texel.
	// A media de A pela cadeia de mipmaps e o movimento do quadro inteiro.
	fragColor = vec4(clamp(d, 0.0, 1.0), luma, interface_, mudanca);
}
)";

// A captura para a rede neural: o quadro reduzido a 512x288, em RGB de 8 bits,
// que e o que o modelo come.
//
// Cada texel do alvo cobre perto de quatro pixels da fonte em cada eixo; uma
// amostra so leria um deles e o modelo veria serrilhado onde o video nao tem.
// Quatro leituras bilineares em diagonal cobrem o bloco quase inteiro.
const char *kNeuralCaptureShader = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;

uniform samplerExternalOES uTexture;

in vec2 vUv;
in vec2 vDx;
in vec2 vDy;
out vec4 fragColor;

void main()
{
	vec3 c = (texture(uTexture, vUv + (vDx + vDy) * 0.9).rgb
			+ texture(uTexture, vUv + (vDx - vDy) * 0.9).rgb
			+ texture(uTexture, vUv - (vDx - vDy) * 0.9).rgb
			+ texture(uTexture, vUv - (vDx + vDy) * 0.9).rgb) * 0.25;
	fragColor = vec4(c, 1.0);
}
)";

// Um mapa a cada 50 ms: 20 por segundo, o padrao que o Moonlight XR mediu como
// folgado para o ZipDepth no Quest 3 (15 a 16 ms por mapa com o stream
// rodando). Mais que isso a media no tempo da passada de profundidade ja nao
// deixa ver.
constexpr int64_t kPeriodoNeuralNs = 50000000;

int64_t AgoraNs()
{
	timespec ts{};
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// Tamanho de cada quadro que entrou no decodificador, pelo pts (patch 0056).
// Escrito pela thread da rede, lido pela do desenho: cada posicao guarda o
// pts junto, e quem le confere que e o quadro que procura.
constexpr int kAnelTamanhos = 256;
std::atomic<uint64_t> g_tamanho_pts[kAnelTamanhos];
std::atomic<uint32_t> g_tamanho_bytes[kAnelTamanhos];

// Ritmo da entrega (patch 0057): o ultimo quadro que a tela pegou, para o
// decodificador nao entregar outro antes e a Surface descartar o primeiro.
std::mutex g_tela_mutex;
std::condition_variable g_tela_cv;
uint64_t g_pts_pego = 0;           // pts + 1; 0 = nenhum
std::atomic<int64_t> g_pego_ns{0}; // quando a tela pegou o ultimo

void TelaPegou(uint64_t pts)
{
	{
		std::lock_guard<std::mutex> lock(g_tela_mutex);
		g_pts_pego = pts + 1;
	}
	g_pego_ns.store(AgoraNs(), std::memory_order_release);
	g_tela_cv.notify_all();
}

std::atomic<bool> g_verdade_no_pts{false};

/** Bytes do quadro com esse pts, ou -1 se ninguem informou. */
int64_t TamanhoDoQuadro(uint64_t pts)
{
	const int i = (int)(pts % kAnelTamanhos);
	const uint32_t bytes = g_tamanho_bytes[i].load(std::memory_order_acquire);
	if(g_tamanho_pts[i].load(std::memory_order_acquire) != pts + 1)
		return -1;
	return bytes;
}

// A faixa que o mapa esta usando neste quadro, num pixel so.
//
// Uma cena que so use de 0,3 a 0,6 desperdica metade do orcamento, e o efeito
// muda de intensidade a cada corte de cena sem que nada na cena peca. Medindo
// a faixa e esticando-a, o mesmo ajuste vale num corredor escuro e numa
// paisagem aberta.
//
// Sao 64 leituras de um nivel grosso da cadeia de mipmaps -- num alvo de um
// pixel, o custo nao aparece em lugar nenhum. Media e desvio, e nao minimo e
// maximo: um punhado de texels extremos (um brilho, um canto preto) esticaria
// a faixa para nada e faria a cena inteira encolher.
const char *kStatsShader = R"(#version 300 es
precision highp float;

uniform sampler2D uDepth;
// O proprio resultado do quadro anterior, para a faixa nao pular a cada corte.
uniform sampler2D uPrevStats;
uniform int uHasPrev;
uniform float uLod;

out vec4 fragColor;

void main()
{
	float soma = 0.0;
	float soma2 = 0.0;
	for(int y = 0; y < 8; y++)
	{
		for(int x = 0; x < 8; x++)
		{
			vec2 uv = (vec2(float(x), float(y)) + 0.5) / 8.0;
			float d = textureLod(uDepth, uv, uLod).r;
			soma += d;
			soma2 += d * d;
		}
	}
	float media = soma / 64.0;
	float desvio = sqrt(max(soma2 / 64.0 - media * media, 0.0));

	// Um desvio e meio para cada lado cobre a cena e deixa de fora o que so
	// atrapalharia.
	//
	// A faixa minima e o que impede a cena chapada de virar relevo inventado.
	// Ela era 0,15, o que deixava o ganho chegar a quase sete vezes: num menu
	// ou num ceu, onde o mapa so tem ruido, esse ganho transformava o ruido em
	// ondulacao. Em 0,45 o ganho para em pouco mais que o dobro -- estica o que
	// esta comprimido e nao inventa o que nao existe.
	float lo = clamp(media - 1.5 * desvio, 0.0, 1.0);
	float hi = clamp(media + 1.5 * desvio, 0.0, 1.0);
	hi = max(hi, lo + 0.45);

	if(uHasPrev == 1)
	{
		vec4 ant = textureLod(uPrevStats, vec2(0.5), 0.0);
		// Devagar, senao a faixa respira junto com o que passa na frente da
		// camera e a cena inteira infla e murcha. Num corte de cena ela
		// afrouxa: o movimento medio do quadro, que a passada da profundidade
		// deixou no ultimo nivel, e o que distingue os dois casos.
		float movimento = textureLod(uDepth, vec2(0.5), 16.0).a;
		float alfa = mix(0.04, 0.40, smoothstep(0.02, 0.20, movimento));
		lo = mix(ant.r, lo, alfa);
		hi = mix(ant.g, hi, alfa);
	}
	fragColor = vec4(lo, max(hi, lo + 0.45), 0.0, 1.0);
}
)";

const char *kFragmentShader = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

uniform samplerExternalOES uTexture;
// Nits que o painel mostra como branco cheio, e o pico da fonte na mesma
// escala (pico em nits / uTargetNits). Ver ToneMapper::target_nits_.
uniform float uTargetNits;
uniform float uPicoFonte;
uniform int uPq;
uniform float uSharpen;
uniform int uEncode;

in vec2 vUv;
in vec2 vDx;
in vec2 vDy;
in vec2 vScreen;
// Duas saidas: a do swapchain e a do historico da extrapolacao. Escrever nas
// duas na mesma passada custa uma gravacao a mais, e nada perto de uma copia
// de tela inteira depois. Quando so ha um anexo, o glDrawBuffers manda a
// posicao 1 para GL_NONE e a segunda gravacao e descartada.
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 historyColor;

// EOTF do SMPTE ST 2084: codigo PQ -> luminancia absoluta, em nits.
vec3 PqToNits(vec3 e)
{
	const float m1 = 0.1593017578125;
	const float m2 = 78.84375;
	const float c1 = 0.8359375;
	const float c2 = 18.8515625;
	const float c3 = 18.6875;
	vec3 p = pow(max(e, vec3(0.0)), vec3(1.0 / m2));
	vec3 num = max(p - c1, vec3(0.0));
	vec3 den = max(c2 - c3 * p, vec3(1e-6));
	return pow(num / den, vec3(1.0 / m1)) * 10000.0;
}

// BT.2020 -> BT.709, em luz linear. O gamut do HDR e mais amplo que o do
// painel; sem esta matriz as cores saem deslocadas, nao so mais claras.
//
// So vale em luz linear, e por isso vem ANTES da compressao. Ate a dev.232 ela
// vinha depois, sobre valores ja comprimidos canal a canal -- uma matriz linear
// aplicada a uma curva, que desloca o matiz justamente nas cores fortes.
vec3 Bt2020ToBt709(vec3 c)
{
	const mat3 m = mat3(
		 1.6605, -0.1246, -0.0182,
		-0.5876,  1.1329, -0.1006,
		-0.0728, -0.0083,  1.1187);
	return m * c;
}

// Traz para dentro do BT.709 a cor que a matriz deixou com canal negativo.
//
// Cortar o negativo em zero muda o matiz e a luminancia de uma vez. Aqui a cor
// anda em linha reta ate o cinza de mesma luminancia, so o bastante para o
// menor canal chegar a zero: perde saturacao, que e o que o painel nao tem, e
// mantem o resto.
vec3 DentroDoGamut(vec3 c)
{
	float mn = min(min(c.r, c.g), c.b);
	if(mn >= 0.0)
		return c;
	float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
	if(y <= 0.0)
		return vec3(0.0);
	return y + (c - y) * (y / (y - mn));
}

// A curva de tons: reta ate o joelho, compressao suave dali ate o pico da fonte.
//
// O Reinhard que estava aqui comprimia a imagem inteira: o branco de
// referencia do jogo saia com metade do brilho, a meia-luz afundava junto, e
// tudo acima de 800 nits era cortado seco. Esta e da familia do EETF do
// BT.2390: o que o painel consegue mostrar passa como veio, e so o excesso e
// dobrado para caber.
//
// Aplicada ao maior dos tres canais, e a cor inteira escalada pela mesma
// razao. Comprimir canal a canal, como antes, desbota e troca o matiz dos
// destaques -- o fogo amarelava e o ceu claro virava branco.
//
// Acima do joelho, com e = excesso, A = folga do painel e R = folga da fonte:
// g(e) = e (1 + e A / R^2) / (1 + e / A). Sai do joelho com inclinacao 1 (sem
// quina), e crescente em toda a faixa e chega a A exatamente em R. Com a fonte
// sem pico acima do painel (R = A) ela vira a identidade.
const float JOELHO = 0.8;

vec3 Comprimir(vec3 c)
{
	float m = max(max(c.r, c.g), c.b);
	if(m <= JOELHO)
		return c;
	float folga = 1.0 - JOELHO;
	float faixa = max(uPicoFonte - JOELHO, folga);
	float e = m - JOELHO;
	float g = e * (1.0 + e * folga / (faixa * faixa)) / (1.0 + e / folga);
	return c * (min(JOELHO + g, 1.0) / m);
}

// Curva do sRGB, para quando o alvo nao a aplica sozinho.
//
// Num swapchain sRGB o proprio GL codifica na escrita, e o shader entrega luz
// linear -- e o caso do compositor. Numa janela comum o alvo e RGBA8 cru:
// entregar luz linear ali deixa a imagem lavada, com as sombras levantadas.
vec3 EncodeSrgb(vec3 c)
{
	c = clamp(c, 0.0, 1.0);
	return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055,
			step(vec3(0.0031308), c));
}

// Pontilhado para a escrita em 8 bits.
//
// Os 10 bits chegam com 1024 degraus e saem num alvo de 8 -- o swapchain sRGB
// do compositor, o intermediario do 3D, a textura da janela. Arredondar direto
// devolve as faixas que os 10 bits existem para tirar. Um ruido de um degrau,
// somado antes do arredondamento, troca a faixa por um granulado que o olho
// ja nao resolve a esta distancia.
//
// Ruido de gradiente intercalado (Jimenez), fixo no espaco: variar por quadro
// cintilaria com a fonte a 60 e o painel a 120. Dois deles somados dao a
// distribuicao triangular, a que nao deixa o ruido variar com o brilho. O
// mesmo nos tres canais: ruido de cor seria mais visivel que o de brilho.
float RuidoIntercalado(vec2 p)
{
	return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

float Pontilhado()
{
	vec2 p = gl_FragCoord.xy;
	return (RuidoIntercalado(p) + RuidoIntercalado(p + vec2(47.0, 17.0)) - 1.0) / 255.0;
}

vec3 DecodeSrgb(vec3 c)
{
	return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

// Escreve luz linear no alvo, com pontilhado.
//
// O ruido vai no valor codificado, que e onde o arredondamento acontece: no
// alvo sRGB o GL recodifica o que sai daqui, entao o shader codifica, soma,
// e devolve em luz linear -- a ida e volta e exata ate o arredondamento final.
vec4 Escrever(vec3 c, int codificar)
{
	vec3 e = clamp(EncodeSrgb(c) + Pontilhado(), 0.0, 1.0);
	return vec4(codificar == 1 ? e : DecodeSrgb(e), 1.0);
}

// Nitidez adaptativa ao contraste, no molde do CAS da AMD.
//
// Aplicada no valor codificado, antes de linearizar: a curva PQ (e a gama, no
// caso SDR) ja distribui os passos de forma aproximadamente perceptual, e um
// realce feito ai cresce junto com o que o olho ve. Em luz linear o mesmo
// realce arrancaria halos nas altas luzes e quase nada nas sombras.
//
// ## O que muda em relacao a mascara de desfoque que estava aqui
//
// A mascara fixa realcava tudo com a mesma forca: `c + (c - blur) * ganho`.
// Numa borda de alto contraste -- legenda branca sobre fundo escuro, mira sobre
// o ceu -- isso produz halo, a orla clara de um lado e escura do outro que
// denuncia o filtro. E numa area quase lisa ela realca o ruido do compressor
// junto com o detalhe, porque para ela os dois sao a mesma diferenca.
//
// O CAS resolve os dois com uma conta so: mede o contraste local e **reduz o
// realce onde ele ja e alto**. A borda forte quase nao e tocada, e o detalhe
// fino de baixo contraste -- textura, grama, tecido, que e onde a nitidez
// realmente aparece -- leva o realce inteiro.
//
// O custo e o mesmo. Sao os mesmos cinco toques de textura da mascara anterior:
// o centro e os quatro vizinhos da cruz. O que mudou foi o peso, nao o numero
// de amostras -- e num caminho que ja paga uma passada de GPU, trocar a conta
// sai de graca.
vec3 Sharpen(vec2 uv)
{
	vec3 c = texture(uTexture, uv).rgb;
	if(uSharpen <= 0.0)
		return c;

	vec3 n = texture(uTexture, uv - vDy).rgb;
	vec3 s = texture(uTexture, uv + vDy).rgb;
	vec3 w = texture(uTexture, uv - vDx).rgb;
	vec3 e = texture(uTexture, uv + vDx).rgb;

	vec3 mn = min(min(min(n, s), min(w, e)), c);
	vec3 mx = max(max(max(n, s), max(w, e)), c);

	// Quanta folga existe ate o preto e ate o branco locais.
	//
	// Numa vizinhanca lisa e clara (mn ~ mx ~ 1) isto vai a 1: realce cheio.
	// Numa borda dura (mn ~ 0, mx ~ 1) vai a 0: nao mexe. E a raiz suaviza a
	// transicao entre os dois, para o filtro nao ligar e desligar de um pixel
	// para o outro e criar uma borda que nao existia na imagem.
	vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / max(mx, 1e-4), 0.0, 1.0));

	// O degrau escolhido vive entre os dois extremos do proprio CAS: -1/8 e o
	// realce contido, -1/5 o maximo que ele admite sem passar a doer.
	vec3 peso = amp * -(1.0 / mix(8.0, 5.0, clamp(uSharpen, 0.0, 1.0)));
	vec3 soma = (n + s + w + e) * peso + c;
	return clamp(soma / max(1.0 + 4.0 * peso, vec3(1e-4)), 0.0, 1.0);
}

void main()
{
	vec3 rgb = Sharpen(vUv);

	if(uPq == 0)
	{
		// Fonte SDR num alvo que nao reencoda: linearizar aqui so para o alvo
		// reencodar em seguida e ida e volta pela mesma curva. Nesse caso o
		// valor sai como veio, ja com a nitidez aplicada.
		if(uEncode == 1)
		{
			fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
			historyColor = fragColor;
			return;
		}
		// Fonte SDR: so linearizar, sem mapear nada -- e pela mesma curva que o
		// alvo sRGB vai reaplicar na escrita, para a ida e volta ser exata. Com
		// a gama 2.2 que estava aqui a volta nao batia com a ida: um cinza de
		// codigo 13 saia como 4 ou 5, e as sombras do SDR com nitidez, e de
		// todo o 3D, ficavam esmagadas.
		fragColor = vec4(DecodeSrgb(clamp(rgb, 0.0, 1.0)), 1.0);
		historyColor = fragColor;
		return;
	}

	// Luz linear, BT.709, com 1.0 no branco do painel -- nessa ordem: a matriz
	// de gamut so vale em luz linear, e a curva de tons so depois dela.
	vec3 linear = Bt2020ToBt709(PqToNits(rgb)) / uTargetNits;
	vec3 rec709 = clamp(Comprimir(DentroDoGamut(linear)), 0.0, 1.0);
	fragColor = Escrever(rec709, uEncode);
	historyColor = fragColor;
}
)";

// A passada dos olhos: pega a imagem ja pronta e so a deforma.
//
// Ela existe para a passada de cima acontecer UMA vez por quadro.
//
// Antes, cada olho refazia tudo: cinco leituras da textura externa -- que
// carregam a conversao de YUV --, a curva PQ com quatro exponenciacoes, a
// matriz de gamut e a nitidez. Duas vezes, em tela cheia, para um resultado
// que so difere pelo deslocamento lateral. Agora a imagem e mapeada e afiada
// uma vez num alvo intermediario, e cada olho faz uma leitura dele.
//
// O alvo intermediario e sRGB, e nao RGBA8 cru: o GL codifica na escrita e
// decodifica na leitura, de graca, e guardar luz linear em oito bits deixaria
// degrau visivel nas sombras.
//
// Isto tambem barateia os quadros repetidos. Com fonte de 60 e painel de 120,
// metade das passadas nao tem imagem nova: elas agora custam so a deformacao,
// porque o intermediario continua valendo.
const char *kWarpShader = R"(#version 300 es
precision highp float;

// A imagem ja mapeada e afiada, em luz linear (o alvo e sRGB).
uniform sampler2D uSource;
// O mapa de profundidade, pronto, vindo da passada pequena. R e a
// profundidade; B, a marca de interface.
uniform sampler2D uDepthTex;
// A faixa que o mapa esta usando, medida no quadro: R o piso, G o teto.
uniform sampler2D uStats;
uniform int uHasStats;
uniform int uEncode;
// Fonte de 10 bits: a deformacao reamostra o intermediario, e a escrita no
// swapchain de 8 bits arredonda de novo -- entao o pontilhado entra de novo.
uniform int uDither;

// Qual olho esta sendo desenhado: -1 esquerdo, +1 direito.
uniform float uEye;
// Disparidade maxima entre os olhos, em fracao da largura da fonte, com
// orcamento separado para cada lado da tela -- os dois limites tem naturezas
// diferentes. Atras, o teto e a distancia interpupilar: acima dela os olhos
// teriam de divergir, o que e impossivel. A frente nao ha divergencia; o
// limite e de conforto, e a pouca profundidade que se ganha ali custa o
// corte na moldura.
uniform float uStereoBack;
uniform float uStereoFront;
// Que profundidade fica exatamente no plano da tela. O que estiver a frente
// salta para fora, o que estiver atras afunda.
uniform float uConvergence;
// Moldura flutuante (modo Pop-out, dev.214), em fracao da largura: a faixa
// preta que cada olho ganha na borda do seu lado. Zero desliga.
uniform float uJanela;

in vec2 vScreen;
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 historyColor;

vec3 EncodeSrgb(vec3 c)
{
	c = clamp(c, 0.0, 1.0);
	return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055,
			step(vec3(0.0031308), c));
}

// As mesmas do shader principal; ver o comentario de Escrever la.
float RuidoIntercalado(vec2 p)
{
	return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

float Pontilhado()
{
	vec2 p = gl_FragCoord.xy;
	return (RuidoIntercalado(p) + RuidoIntercalado(p + vec2(47.0, 17.0)) - 1.0) / 255.0;
}

vec3 DecodeSrgb(vec3 c)
{
	return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

// A profundidade normalizada pela faixa do quadro, com a interface ja posta no
// plano da tela.
//
// A marca de interface entra aqui, e nao na passada que escreve o mapa: ali
// ela entraria na medida da faixa, e um HUD grande decidiria o quanto a cena
// afunda.
float Profundidade(vec2 uv)
{
	// Um nivel abaixo do mapa: o que a deformacao mostra como defeito nao e a
	// profundidade errada, e a MUDANCA brusca dela de um ponto para o outro --
	// e ali que a imagem estica e ondula. Ler no nivel 1 dobra a suavizacao sem
	// custar amostra nenhuma (a cadeia ja existe) e sem tirar volume: o volume
	// vem da inclinacao geral da cena, que sobrevive ao borrao.
	vec4 m = textureLod(uDepthTex, uv, 1.0);
	float d = m.r;
	if(uHasStats == 1)
	{
		vec4 faixa = textureLod(uStats, vec2(0.5), 0.0);
		d = clamp((d - faixa.r) / max(faixa.g - faixa.r, 0.45), 0.0, 1.0);
	}
	return mix(d, uConvergence, m.b * 0.9);
}

// Quanto este olho desloca a leitura, para uma profundidade `d` num pixel que
// esta em `x` na tela.
//
// Cada lado da convergencia usa o seu orcamento inteiro: o mais longe que o
// mapa pode dizer (0) recebe todo o de tras, o mais perto (1) todo o da
// frente, qualquer que seja a convergencia escolhida.
//
// A parte da frente vai a zero perto das bordas laterais. O que salta da tela
// e encosta na moldura fica cortado por algo que parece estar ATRAS dele --
// violacao de janela, o conflito mais cansativo do estereo, porque o cerebro
// recebe duas respostas opostas para a mesma borda. Achatar ali custa pouco:
// a moldura continua no plano da tela, e o que estava saltando assenta nela.
// As bordas de cima e de baixo ficam como estao: numa borda horizontal nao ha
// disparidade horizontal que brigue com a da cena.
float Deslocamento(float d, float x)
{
	float p = d - uConvergence;
	float disp;
	// Com a moldura flutuante ligada nao ha o que achatar: a moldura ja esta
	// na frente de tudo o que salta, e a borda deixa de brigar com a cena.
	if(p >= 0.0)
		disp = uStereoFront * p / max(1.0 - uConvergence, 0.05)
				* (uJanela > 0.0 ? 1.0 : smoothstep(0.0, 0.08, min(x, 1.0 - x)));
	else
		disp = uStereoBack * p / max(uConvergence, 0.05);
	// Meia disparidade para cada olho, e nao a inteira num deles: assim a
	// imagem nao anda para o lado quando o efeito liga, e o erro se reparte
	// entre os dois em vez de pesar todo num.
	return uEye * 0.5 * disp;
}

void main()
{
	// O alvo tem o tamanho da fonte e pertence a um olho so, entao a coordenada
	// da tela ja cobre a imagem inteira: nao ha metade a desdobrar. E o
	// intermediario ja esta na orientacao final, entao aqui nao ha matriz de
	// SurfaceTexture nenhuma -- ela entrou na passada de cima.
	//
	// Duas leituras do mapa, e nao uma. A primeira usa a profundidade do pixel
	// de destino, que numa borda entre perto e longe e a do objeto errado: um
	// lado estica e o outro some. A segunda le no ponto de onde a primeira
	// mandou buscar, que e quase sempre o lugar certo -- uma iteracao de ponto
	// fixo, por uma amostra a mais num alvo de um dezesseis avos da tela.
	vec2 meia = vScreen;
	float d = Profundidade(meia);
	vec2 primeira = vec2(meia.x + Deslocamento(d, vScreen.x), meia.y);
	d = Profundidade(primeira);
	meia.x += Deslocamento(d, vScreen.x);

	// Janela flutuante: onde a leitura sai da imagem, este olho ve preto.
	//
	// O olho que puxa a imagem para dentro fica sem conteudo na borda oposta --
	// nao ha o que mostrar ali, porque o console nunca mandou. Recortar a
	// margem nos DOIS olhos, que foi a saida da dev.181, tirava a borda tambem
	// de quem tinha o pixel: com a tela a 1 m passava de 3% da largura, e
	// apareceu como imagem cortada.
	//
	// Preto so em quem nao tem: o outro olho continua vendo a borda inteira, e
	// a perda cai pela metade. E o que uma janela de verdade faz -- de perto,
	// cada olho ve um pouco menos junto do batente --, e o cerebro a aceita
	// como moldura em vez de estranhar. A transicao tem a largura de alguns
	// pixels, porque borda dura num olho so chama atencao para si.
	float janela = smoothstep(0.0, 0.004, meia.x)
			* smoothstep(0.0, 0.004, 1.0 - meia.x);

	// Moldura flutuante: o truque dos filmes 3D de cinema. O olho esquerdo
	// perde uma faixa na borda esquerda e o direito na direita; a moldura
	// passa a ter disparidade cruzada e aparece NA FRENTE da tela. Tudo o que
	// salta fica atras dela, e a violacao de janela -- objeto a frente
	// cortado por uma borda que parece atras dele -- deixa de existir. E o que
	// permite liberar o salto que o modo normal achata perto das bordas.
	if(uJanela > 0.0)
	{
		float lado = uEye < 0.0 ? vScreen.x : 1.0 - vScreen.x;
		janela *= smoothstep(uJanela, uJanela + 0.003, lado);
	}
	vec3 rgb = texture(uSource, meia).rgb * janela;
	if(uDither == 1)
	{
		vec3 e = clamp(EncodeSrgb(rgb) + Pontilhado(), 0.0, 1.0);
		fragColor = vec4(uEncode == 1 ? e : DecodeSrgb(e), 1.0);
	}
	else
		fragColor = vec4(uEncode == 1 ? EncodeSrgb(rgb) : rgb, 1.0);
	historyColor = fragColor;
}
)";

// A sonda da conversao de cor: a mesma grade de pontos, lida duas vezes.
//
// Na primeira pelo caminho de sempre, com o driver convertendo YUV em RGB; na
// segunda crua, pelo GL_EXT_YUV_target, que devolve Y, Cb e Cr como estao no
// buffer. Com os dois na mao, a CPU descobre que matriz e que faixa o driver
// usou -- a unica coisa desta cadeia que nao depende de nos e que ninguem
// tinha conferido.
const char *kProbeShader = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
#extension GL_EXT_YUV_target : require
precision highp float;

uniform samplerExternalOES uTexture;
uniform __samplerExternal2DY2YEXT uYuv;
uniform int uModo;

in vec2 vUv;
out vec4 fragColor;

void main()
{
	fragColor = uModo == 0 ? vec4(texture(uTexture, vUv).rgb, 1.0)
			: vec4(texture(uYuv, vUv).rgb, 1.0);
}
)";

// Mascara de movimento para a previsao (18/11/2026). A extensao preve a
// imagem inteira, e em regiao parada com detalhe fino (grade da escada de
// incendio contra o ceu) ela inventava movimento: com o personagem se
// mexendo, o fundo pulsava. A mascara marca onde os dois ultimos quadros
// reais diferem; fora dela a tela mostra o quadro real.
//
// 1. Diferenca: um texel por bloco de 8x8, o maior |novo - velho| de luma em
//    quatro pontos do bloco.
const char *kMascaraDiffShader = R"(#version 300 es
precision mediump float;
uniform sampler2D uNovo;
uniform sampler2D uVelho;
uniform vec2 uPasso;
in vec2 vScreen;
out vec4 fragColor;
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
void main()
{
	float d = 0.0;
	for(int i = 0; i < 4; i++)
	{
		vec2 o = vec2(float(i % 2) - 0.5, float(i / 2) - 0.5) * uPasso * 4.0;
		vec2 uv = vScreen + o;
		d = max(d, abs(luma(texture(uNovo, uv).rgb) - luma(texture(uVelho, uv).rgb)));
	}
	fragColor = vec4(d, 0.0, 0.0, 1.0);
}
)";

// 2. Dilatacao: o maior dos vizinhos num raio de 4 texels (32 px). O objeto
//    previsto anda para fora de onde estava; sem folga a borda da frente
//    seria cortada.
const char *kMascaraDilataShader = R"(#version 300 es
precision mediump float;
uniform sampler2D uMascara;
uniform vec2 uPasso;
in vec2 vScreen;
out vec4 fragColor;
void main()
{
	float d = 0.0;
	for(int y = -4; y <= 4; y += 2)
		for(int x = -4; x <= 4; x += 2)
			d = max(d, texture(uMascara, vScreen + vec2(float(x), float(y)) * uPasso).r);
	fragColor = vec4(d, 0.0, 0.0, 1.0);
}
)";

// 3. Composicao: previsto onde ha movimento, real onde nao ha. A transicao
//    e suave (smoothstep) para nao desenhar a borda da mascara.
const char *kComporShader = R"(#version 300 es
precision mediump float;
uniform sampler2D uPrevisto;
uniform sampler2D uReal;
uniform sampler2D uMascara;
in vec2 vScreen;
out vec4 fragColor;
void main()
{
	float m = smoothstep(0.03, 0.08, texture(uMascara, vScreen).r);
	fragColor = mix(texture(uReal, vScreen), texture(uPrevisto, vScreen), m);
}
)";

GLuint CompileShader(GLenum type, const char *source)
{
	GLuint shader = glCreateShader(type);
	if(shader == 0)
		return 0;
	glShaderSource(shader, 1, &source, nullptr);
	glCompileShader(shader);

	GLint ok = GL_FALSE;
	glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
	if(ok != GL_TRUE)
	{
		// O log do driver e a unica pista util quando um shader nao compila no
		// hardware, e ele nao aparece em lugar nenhum se nao for lido aqui.
		GLint len = 0;
		glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &len);
		std::string info((size_t)(len > 0 ? len : 1), '\0');
		glGetShaderInfoLog(shader, len, nullptr, &info[0]);
		LOGE("Failed to compile shader: %s", info.c_str());
		glDeleteShader(shader);
		return 0;
	}
	return shader;
}

/**
 * Compila e liga um par de shaders. Devolve zero em qualquer falha, com o log
 * do driver no diario -- e o unico lugar onde a razao aparece, porque shader
 * so compila no aparelho.
 */
GLuint LinkPair(const char *vertex, const char *fragment, const char *nome)
{
	GLuint vs = CompileShader(GL_VERTEX_SHADER, vertex);
	GLuint fs = vs ? CompileShader(GL_FRAGMENT_SHADER, fragment) : 0;
	GLuint program = 0;
	if(vs && fs)
	{
		program = glCreateProgram();
		glAttachShader(program, vs);
		glAttachShader(program, fs);
		glBindAttribLocation(program, 0, "aPos");
		glLinkProgram(program);
		GLint ok = GL_FALSE;
		glGetProgramiv(program, GL_LINK_STATUS, &ok);
		if(ok != GL_TRUE)
		{
			GLint len = 0;
			glGetProgramiv(program, GL_INFO_LOG_LENGTH, &len);
			std::string info((size_t)(len > 0 ? len : 1), '\0');
			glGetProgramInfoLog(program, len, nullptr, &info[0]);
			LOGE("Failed to link %s: %s", nome, info.c_str());
			glDeleteProgram(program);
			program = 0;
		}
	}
	if(vs)
		glDeleteShader(vs);
	if(fs)
		glDeleteShader(fs);
	return program;
}

} // namespace

bool ToneMapper::CompileProgram()
{
	GLuint vs = CompileShader(GL_VERTEX_SHADER, kVertexShader);
	if(vs == 0)
		return false;
	GLuint fs = CompileShader(GL_FRAGMENT_SHADER, kFragmentShader);
	if(fs == 0)
	{
		glDeleteShader(vs);
		return false;
	}

	program_ = glCreateProgram();
	glAttachShader(program_, vs);
	glAttachShader(program_, fs);
	glBindAttribLocation(program_, 0, "aPos");
	glLinkProgram(program_);
	glDeleteShader(vs);
	glDeleteShader(fs);

	GLint ok = GL_FALSE;
	glGetProgramiv(program_, GL_LINK_STATUS, &ok);
	if(ok != GL_TRUE)
	{
		GLint len = 0;
		glGetProgramiv(program_, GL_INFO_LOG_LENGTH, &len);
		std::string info((size_t)(len > 0 ? len : 1), '\0');
		glGetProgramInfoLog(program_, len, nullptr, &info[0]);
		LOGE("Failed to link program: %s", info.c_str());
		glDeleteProgram(program_);
		program_ = 0;
		return false;
	}

	loc_tex_matrix_ = glGetUniformLocation(program_, "uTexMatrix");
	loc_sampler_ = glGetUniformLocation(program_, "uTexture");
	loc_pq_ = glGetUniformLocation(program_, "uPq");
	loc_target_nits_ = glGetUniformLocation(program_, "uTargetNits");
	loc_source_peak_ = glGetUniformLocation(program_, "uPicoFonte");
	loc_sharpen_ = glGetUniformLocation(program_, "uSharpen");
	loc_texel_step_ = glGetUniformLocation(program_, "uTexelStep");
	loc_encode_ = glGetUniformLocation(program_, "uEncode");

	// Os programas do 3D. Falhar em qualquer um deles nao derruba o video: o
	// 3D fica sem o que precisa e a imagem continua, que e muito melhor do que
	// a sessao nao abrir por um recurso opcional.
	warp_program_ = LinkPair(kVertexShader, kWarpShader, "the eye pass");
	if(warp_program_ != 0)
	{
		wloc_source_ = glGetUniformLocation(warp_program_, "uSource");
		wloc_depth_ = glGetUniformLocation(warp_program_, "uDepthTex");
		wloc_stats_ = glGetUniformLocation(warp_program_, "uStats");
		wloc_has_stats_ = glGetUniformLocation(warp_program_, "uHasStats");
		wloc_encode_ = glGetUniformLocation(warp_program_, "uEncode");
		wloc_dither_ = glGetUniformLocation(warp_program_, "uDither");
		wloc_eye_ = glGetUniformLocation(warp_program_, "uEye");
		wloc_back_ = glGetUniformLocation(warp_program_, "uStereoBack");
		wloc_front_ = glGetUniformLocation(warp_program_, "uStereoFront");
		wloc_convergence_ = glGetUniformLocation(warp_program_, "uConvergence");
		wloc_janela_ = glGetUniformLocation(warp_program_, "uJanela");
	}

	stats_program_ = LinkPair(kVertexShader, kStatsShader, "the range pass");
	if(stats_program_ != 0)
	{
		sloc_depth_ = glGetUniformLocation(stats_program_, "uDepth");
		sloc_prev_ = glGetUniformLocation(stats_program_, "uPrevStats");
		sloc_has_prev_ = glGetUniformLocation(stats_program_, "uHasPrev");
		sloc_lod_ = glGetUniformLocation(stats_program_, "uLod");
	}

	// A sonda so existe com a extensao: sem ela o shader nem compila, e a
	// tentativa deixaria um erro de compilacao no diario de toda sessao.
	GLint n_ext = 0;
	glGetIntegerv(GL_NUM_EXTENSIONS, &n_ext);
	bool yuv_target = false;
	for(GLint i = 0; i < n_ext && !yuv_target; i++)
	{
		const char *name = (const char *)glGetStringi(GL_EXTENSIONS, (GLuint)i);
		yuv_target = name && strcmp(name, "GL_EXT_YUV_target") == 0;
	}
	if(yuv_target)
	{
		probe_program_ = LinkPair(kVertexShader, kProbeShader, "the color probe");
		if(probe_program_ != 0)
		{
			ploc_tex_matrix_ = glGetUniformLocation(probe_program_, "uTexMatrix");
			ploc_texel_step_ = glGetUniformLocation(probe_program_, "uTexelStep");
			ploc_rgb_ = glGetUniformLocation(probe_program_, "uTexture");
			ploc_yuv_ = glGetUniformLocation(probe_program_, "uYuv");
			ploc_modo_ = glGetUniformLocation(probe_program_, "uModo");
		}
	}
	else
		LOGI("Color probe unavailable: no GL_EXT_YUV_target");

	// O programa da profundidade: mesmo vertice, fragmento proprio.
	depth_program_ = LinkPair(kVertexShader, kDepthShader, "the depth program");
	if(depth_program_ != 0)
	{
		dloc_tex_matrix_ = glGetUniformLocation(depth_program_, "uTexMatrix");
		dloc_texel_step_ = glGetUniformLocation(depth_program_, "uTexelStep");
		dloc_sampler_ = glGetUniformLocation(depth_program_, "uTexture");
		dloc_prev_ = glGetUniformLocation(depth_program_, "uPrev");
		dloc_has_prev_ = glGetUniformLocation(depth_program_, "uHasPrev");
		dloc_ground_sign_ = glGetUniformLocation(depth_program_, "uGroundSign");
		dloc_neural_ = glGetUniformLocation(depth_program_, "uNeural");
		dloc_has_neural_ = glGetUniformLocation(depth_program_, "uHasNeural");
	}

	neural_program_ = LinkPair(kVertexShader, kNeuralCaptureShader, "the neural depth capture");
	if(neural_program_ != 0)
	{
		nloc_tex_matrix_ = glGetUniformLocation(neural_program_, "uTexMatrix");
		nloc_texel_step_ = glGetUniformLocation(neural_program_, "uTexelStep");
		nloc_sampler_ = glGetUniformLocation(neural_program_, "uTexture");
	}
	return true;
}

bool ToneMapper::Init()
{
	if(!CompileProgram())
		return false;

	glGenTextures(1, &external_tex_);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_tex_);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

	glGenFramebuffers(1, &fbo_);

	// Dois triangulos cobrindo o clip inteiro. Sem indices e sem atributo de
	// UV: a coordenada sai da posicao no vertex shader.
	const float quad[] = {
		-1.0f, -1.0f,
		 1.0f, -1.0f,
		-1.0f,  1.0f,
		 1.0f,  1.0f,
	};
	glGenVertexArrays(1, &vao_);
	glBindVertexArray(vao_);
	glGenBuffers(1, &vbo_);
	glBindBuffer(GL_ARRAY_BUFFER, vbo_);
	glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
	glEnableVertexAttribArray(0);
	glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
	glBindVertexArray(0);

	LOGI("Tone mapper ready (external texture %u)", external_tex_);
	return true;
}

bool ToneMapper::EnableExtrapolation(GLenum format)
{
	if(extrapolate_fn_)
		return true;

	GLint count = 0;
	glGetIntegerv(GL_NUM_EXTENSIONS, &count);
	bool present = false;
	for(GLint i = 0; i < count && !present; i++)
	{
		const char *name = (const char *)glGetStringi(GL_EXTENSIONS, (GLuint)i);
		present = name && strcmp(name, "GL_QCOM_frame_extrapolation") == 0;
	}
	if(!present)
	{
		LOGW("GL_QCOM_frame_extrapolation unavailable; no extrapolated frames");
		return false;
	}

	auto fn = (ExtrapolateFn)eglGetProcAddress("glExtrapolateTex2DQCOM");
	if(!fn)
	{
		// Extensao anunciada e ponteiro ausente acontece, e e pior que ausencia
		// declarada: a checagem passa e a chamada nao existe.
		LOGW("glExtrapolateTex2DQCOM did not resolve despite the extension being advertised");
		return false;
	}
	extrapolate_fn_ = fn;
	history_format_ = format;
	LOGI("Frame extrapolation available (history format 0x%x)",
			(unsigned)format);
	return true;
}

/**
 * O historico e por olho.
 *
 * A extrapolacao preve o proximo quadro a partir dos dois anteriores DAQUELA
 * imagem. No 3D as duas imagens sao diferentes -- e essa diferenca e o efeito
 * inteiro --, entao um historico compartilhado preveria o olho direito a partir
 * do esquerdo e o erro apareceria como profundidade tremendo, que e exatamente
 * o desconforto que se quer evitar.
 */
bool ToneMapper::EnsureHistory(int32_t width, int32_t height, int slot)
{
	GLuint *hist = history_[slot];
	if(hist[0] != 0 && history_width_[slot] == width && history_height_[slot] == height)
		return true;

	if(hist[0] != 0)
		glDeleteTextures(2, hist);
	hist[0] = hist[1] = 0;
	history_newest_[slot] = -1;
	history_count_[slot] = 0;

	while(glGetError() != GL_NO_ERROR) { }
	glGenTextures(2, hist);
	for(int i = 0; i < 2; i++)
	{
		glBindTexture(GL_TEXTURE_2D, hist[i]);
		// Armazenamento imutavel: o formato tem de bater exatamente com o do
		// swapchain, e glTexStorage2D e o que garante isso sem depender de o
		// driver escolher um formato interno equivalente por conta propria.
		glTexStorage2D(GL_TEXTURE_2D, 1, history_format_, width, height);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}
	glBindTexture(GL_TEXTURE_2D, 0);
	history_width_[slot] = width;
	history_height_[slot] = height;
	// Mesmo cuidado: sem esvaziar a fila antes, um erro alheio seria cobrado
	// desta funcao.
	return glGetError() == GL_NO_ERROR;
}

bool ToneMapper::Attach(ASurfaceTexture *texture)
{
	surface_texture_ = texture;
	if(!surface_texture_ || external_tex_ == 0)
		return false;
	if(ASurfaceTexture_attachToGLContext(surface_texture_, external_tex_) != 0)
	{
		LOGE("ASurfaceTexture_attachToGLContext failed");
		return false;
	}
	attached_ = true;
	return true;
}

/**
 * Cria (ou redimensiona) o alvo pequeno da profundidade.
 *
 * Um quarto da resolucao em cada eixo. Nao e economia cega: o mapa que
 * queremos e borrado de proposito -- borda dura nele produz halo em volta de
 * cada objeto --, entao resolucao baixa e leitura com filtragem linear dao de
 * graca a suavizacao que de outro modo custaria amostras.
 *
 * Duas texturas, em rodizio: a passada le a anterior para suavizar no tempo e
 * para saber o que ficou parado, e escreve na outra. Ler e escrever a mesma
 * textura na mesma passada e comportamento indefinido em GL.
 */
/**
 * A textura em que a janela e desenhada antes de ir para a tela.
 *
 * Recriada quando a janela muda de tamanho, e so entao: alocar por quadro
 * seria pagar uma textura de tela inteira sessenta vezes por segundo.
 */
/**
 * O alvo intermediario do 3D: a imagem ja mapeada e afiada, uma vez por quadro.
 *
 * sRGB, e nao RGBA8 cru. O GL codifica na escrita e decodifica na leitura sem
 * custo nenhum, e guardar luz linear em oito bits deixaria degrau visivel nas
 * sombras -- que e justamente o defeito que o caminho com shader existe para
 * evitar.
 */
bool ToneMapper::EnsureIntermediate(int32_t width, int32_t height)
{
	if(intermediate_tex_ != 0 && intermediate_w_ == width && intermediate_h_ == height)
		return true;
	if(intermediate_tex_ != 0)
		glDeleteTextures(1, &intermediate_tex_);
	intermediate_tex_ = 0;
	intermediate_valid_ = false;
	if(width <= 0 || height <= 0)
		return false;

	while(glGetError() != GL_NO_ERROR) { }
	glGenTextures(1, &intermediate_tex_);
	glBindTexture(GL_TEXTURE_2D, intermediate_tex_);
	glTexStorage2D(GL_TEXTURE_2D, 1, GL_SRGB8_ALPHA8, width, height);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	glBindTexture(GL_TEXTURE_2D, 0);

	if(glGetError() != GL_NO_ERROR)
	{
		glDeleteTextures(1, &intermediate_tex_);
		intermediate_tex_ = 0;
		if(!logged_intermediate_)
		{
			logged_intermediate_ = true;
			LOGE("Could not create the intermediate target (%dx%d); 3D stays off", width, height);
		}
		return false;
	}
	intermediate_w_ = width;
	intermediate_h_ = height;
	if(!logged_intermediate_)
	{
		logged_intermediate_ = true;
		LOGI("3D intermediate target: %dx%d sRGB, tone mapping and sharpening run once "
				"per frame and each eye only warps it", width, height);
	}
	return true;
}

/**
 * Mede a faixa que o mapa esta usando, num alvo de um pixel.
 *
 * Roda depois da profundidade e antes dos olhos, e so em quadro novo: num
 * quadro repetido o mapa e o mesmo, e medir de novo so andaria a suavizacao no
 * tempo mais uma vez sobre o mesmo dado.
 */
void ToneMapper::RenderStats()
{
	if(stats_program_ == 0 || stats_tex_[0] == 0 || depth_tex_[depth_newest_] == 0)
		return;

	const int destino = 1 - stats_newest_;
	glBindFramebuffer(GL_FRAMEBUFFER, depth_fbo_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
			stats_tex_[destino], 0);
	if(glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
	{
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		return;
	}

	glViewport(0, 0, 1, 1);
	glUseProgram(stats_program_);
	glUniform1f(sloc_lod_, stats_lod_);
	glUniform1i(sloc_has_prev_, stats_has_prev_ ? 1 : 0);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, depth_tex_[depth_newest_]);
	glUniform1i(sloc_depth_, 0);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, stats_tex_[stats_newest_]);
	glUniform1i(sloc_prev_, 1);

	glBindVertexArray(vao_);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glBindVertexArray(0);

	glActiveTexture(GL_TEXTURE0);
	stats_newest_ = destino;
	stats_has_prev_ = true;
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

bool ToneMapper::EnsureWindowTarget(int32_t width, int32_t height)
{
	if(window_tex_ != 0 && window_w_ == width && window_h_ == height)
		return true;
	if(window_tex_ != 0)
	{
		glDeleteTextures(1, &window_tex_);
		window_tex_ = 0;
	}
	if(width <= 0 || height <= 0)
		return false;

	glGenTextures(1, &window_tex_);
	glBindTexture(GL_TEXTURE_2D, window_tex_);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA,
			GL_UNSIGNED_BYTE, nullptr);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	glBindTexture(GL_TEXTURE_2D, 0);

	window_w_ = width;
	window_h_ = height;
	LOGI("Window target texture created: %dx%d", width, height);
	return true;
}

/**
 * O mapa de profundidade guarda meia precisao quando o aparelho deixa.
 *
 * Em RGBA8 a media exponencial do fim da passada trava. `mix(ant, d, alfa)` com
 * `alfa` em 0,12 -- que e o valor de cena parada, justamente onde se quer
 * estabilidade -- anda 0,12 da diferenca, e num alvo de 8 bits qualquer passo
 * menor que meio nivel arredonda de volta ao ponto de partida. Diferencas
 * abaixo de cerca de 4/255 nunca convergem: em vez de chegar devagar, o mapa
 * simplesmente para onde esta.
 *
 * A magnitude e pequena -- 1,6% da faixa, subpixel depois do teto de
 * disparidade --, entao isto e acabamento e nao conserto. Mas o alvo tem um
 * dezesseis avos dos pixels e a troca custa so o dobro por pixel dele.
 *
 * Duas condicoes, e nao uma: alem de o formato ser renderizavel, ele precisa
 * aceitar filtragem linear. Sem `OES_texture_half_float_linear` a textura fica
 * incompleta na leitura e devolve preto -- e preto aqui nao da erro, da um mapa
 * de profundidade zerado, que e um sintoma mudo. Por isso a extensao e conferida
 * antes, e nao depois.
 */
bool ToneMapper::EnsureDepthTargets(int32_t width, int32_t height)
{
	const int32_t w = width > 4 ? width / 4 : 1;
	const int32_t h = height > 4 ? height / 4 : 1;
	if(depth_tex_[0] != 0 && depth_width_ == w && depth_height_ == h)
		return true;

	if(depth_tex_[0] != 0)
		glDeleteTextures(2, depth_tex_);
	depth_tex_[0] = depth_tex_[1] = 0;
	depth_has_prev_ = false;

	if(depth_fbo_ == 0)
		glGenFramebuffers(1, &depth_fbo_);

	bool meia_precisao = false;
	{
		GLint count = 0;
		glGetIntegerv(GL_NUM_EXTENSIONS, &count);
		bool renderizavel = false;
		bool filtravel = false;
		for(GLint i = 0; i < count; i++)
		{
			const char *name = (const char *)glGetStringi(GL_EXTENSIONS, (GLuint)i);
			if(!name)
				continue;
			if(strcmp(name, "GL_EXT_color_buffer_half_float") == 0
					|| strcmp(name, "GL_EXT_color_buffer_float") == 0)
				renderizavel = true;
			else if(strcmp(name, "GL_OES_texture_half_float_linear") == 0)
				filtravel = true;
		}
		meia_precisao = renderizavel && filtravel;
	}

	// A cadeia de mipmaps inteira. O ultimo nivel, de um texel, e a media do
	// mapa todo -- e dele que sai o movimento do quadro inteiro, que decide se
	// algo imovel e interface. O nivel 2 serve a pista do chao, que quer a
	// resposta de uma regiao e nao de um ponto.
	GLsizei niveis = 1;
	for(int32_t lado = w > h ? w : h; lado > 1; lado >>= 1)
		niveis++;

	const GLenum candidatos[2] = { GL_RGBA16F, GL_RGBA8 };
	GLenum escolhido = 0;
	for(int c = meia_precisao ? 0 : 1; c < 2 && escolhido == 0; c++)
	{
		while(glGetError() != GL_NO_ERROR) { }
		glGenTextures(2, depth_tex_);
		for(int i = 0; i < 2; i++)
		{
			glBindTexture(GL_TEXTURE_2D, depth_tex_[i]);
			glTexStorage2D(GL_TEXTURE_2D, niveis, candidatos[c], w, h);
			// Linear na leitura: e o que faz o mapa pequeno voltar suave na
			// passada principal, sem uma unica amostra a mais. O filtro de
			// reducao tem de ser de mipmap, senao o textureLod ignora o nivel
			// pedido e le sempre o 0. Toda leitura do nivel 0 pede o nivel
			// explicitamente, entao isso nao muda nenhuma delas.
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		}
		glBindTexture(GL_TEXTURE_2D, 0);

		// A prova e anexar de verdade: a lista de extensoes diz o que o driver
		// promete, e o framebuffer diz o que ele faz.
		GLint anterior = 0;
		glGetIntegerv(GL_FRAMEBUFFER_BINDING, &anterior);
		glBindFramebuffer(GL_FRAMEBUFFER, depth_fbo_);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
				depth_tex_[0], 0);
		const bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
				&& glGetError() == GL_NO_ERROR;
		glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)anterior);

		if(ok)
			escolhido = candidatos[c];
		else
		{
			glDeleteTextures(2, depth_tex_);
			depth_tex_[0] = depth_tex_[1] = 0;
		}
	}

	if(escolhido == 0)
	{
		if(!logged_depth_failure_)
		{
			logged_depth_failure_ = true;
			LOGE("No renderable format for the depth target; 3D will have no depth");
		}
		return false;
	}

	depth_width_ = w;
	depth_height_ = h;

	// O par de um pixel onde a faixa do mapa e medida, e o nivel da cadeia em
	// que ela le: o primeiro com menos de dezesseis texels de lado, para as 64
	// leituras cobrirem o quadro inteiro sem cair no ruido do mapa cheio.
	//
	// So em meia precisao. Em oito bits, a suavizacao no tempo da faixa
	// travaria pelo mesmo motivo que a do mapa travava -- passo menor que meio
	// nivel arredonda de volta --, e uma faixa que nao converge estica a cena
	// para um valor errado e fica la. Sem ela, o mapa vale como vem.
	if(stats_tex_[0] != 0)
		glDeleteTextures(2, stats_tex_);
	stats_tex_[0] = stats_tex_[1] = 0;
	stats_has_prev_ = false;
	stats_ready_ = false;
	stats_lod_ = 0.0f;
	for(int32_t lado = w > h ? w : h; lado > 16; lado >>= 1)
		stats_lod_ += 1.0f;
	if(escolhido == GL_RGBA16F && stats_program_ != 0)
	{
		while(glGetError() != GL_NO_ERROR) { }
		glGenTextures(2, stats_tex_);
		for(int i = 0; i < 2; i++)
		{
			glBindTexture(GL_TEXTURE_2D, stats_tex_[i]);
			glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, 1, 1);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		}
		glBindTexture(GL_TEXTURE_2D, 0);
		stats_ready_ = glGetError() == GL_NO_ERROR;
		if(!stats_ready_)
		{
			glDeleteTextures(2, stats_tex_);
			stats_tex_[0] = stats_tex_[1] = 0;
		}
	}

	if(!logged_depth_)
	{
		logged_depth_ = true;
		LOGI("Depth pass: %dx%d (a quarter of %dx%d in each axis), format %s, "
				"range measured at lod %.0f: %s",
				w, h, width, height, escolhido == GL_RGBA16F ? "RGBA16F" : "RGBA8",
				(double)stats_lod_, stats_ready_ ? "yes" : "no");
	}
	return true;
}

/** Desenha o mapa de profundidade do quadro, no alvo pequeno. */
void ToneMapper::RenderDepth(const float *matrix, int32_t width, int32_t height)
{
	if(depth_program_ == 0 || !EnsureDepthTargets(width, height))
		return;

	const int destino = 1 - depth_newest_;
	glBindFramebuffer(GL_FRAMEBUFFER, depth_fbo_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
			depth_tex_[destino], 0);
	if(glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
	{
		if(!logged_depth_failure_)
		{
			logged_depth_failure_ = true;
			LOGE("Incomplete framebuffer in the depth pass; 3D will have no depth");
		}
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		return;
	}

	glViewport(0, 0, depth_width_, depth_height_);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	glDisable(GL_CULL_FACE);

	glUseProgram(depth_program_);
	glUniformMatrix4fv(dloc_tex_matrix_, 1, GL_FALSE, matrix);
	// O passo e o da FONTE, e nao o do alvo pequeno: a pista de escala fina
	// procura borda de um pixel do video, que e o que separa letra de textura.
	// Medida na grade pequena, ela mediria borda de quatro pixels e o texto
	// deixaria de se distinguir de uma parede.
	glUniform2f(dloc_texel_step_, width > 0 ? 1.0f / (float)width : 0.0f,
			height > 0 ? 1.0f / (float)height : 0.0f);
	glUniform1f(dloc_ground_sign_, ground_sign_);
	glUniform1i(dloc_has_prev_, depth_has_prev_ ? 1 : 0);

	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_tex_);
	glUniform1i(dloc_sampler_, 0);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, depth_tex_[depth_newest_]);
	glUniform1i(dloc_prev_, 1);
	const bool neural = neural_tem_mapa_ && neural_map_tex_ != 0;
	glUniform1i(dloc_has_neural_, neural ? 1 : 0);
	if(neural)
	{
		glActiveTexture(GL_TEXTURE2);
		glBindTexture(GL_TEXTURE_2D, neural_map_tex_);
		glUniform1i(dloc_neural_, 2);
	}

	glBindVertexArray(vao_);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glBindVertexArray(0);

	depth_newest_ = destino;
	depth_has_prev_ = true;
	// Devolve o framebuffer. Quem chama pode sair antes de prender o seu -- e o
	// caso do quadro previsto --, e deixar o alvo pequeno preso faria a proxima
	// escrita cair nele.
	glBindFramebuffer(GL_FRAMEBUFFER, 0);

	// Os niveis de baixo, a partir do que acabou de ser escrito. Numa textura
	// de um dezesseis avos da tela a cadeia inteira custa menos que ela.
	glBindTexture(GL_TEXTURE_2D, depth_tex_[destino]);
	glGenerateMipmap(GL_TEXTURE_2D);
	glActiveTexture(GL_TEXTURE0);
}

bool ToneMapper::EnsureNeuralTargets()
{
	if(neural_fbo_ != 0)
		return true;
	if(logged_neural_failure_)
		return false;

	const int32_t w = ProfundidadeNeural::kLargura;
	const int32_t h = ProfundidadeNeural::kAltura;
	while(glGetError() != GL_NO_ERROR) { }

	glGenTextures(1, &neural_cap_tex_);
	glBindTexture(GL_TEXTURE_2D, neural_cap_tex_);
	glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

	// O mapa: um canal de 8 bits basta. A rede entrega profundidade relativa,
	// e 256 degraus numa faixa ja normalizada ficam abaixo do que a media no
	// tempo da passada de profundidade deixa ver.
	glGenTextures(1, &neural_map_tex_);
	glBindTexture(GL_TEXTURE_2D, neural_map_tex_);
	glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8, w, h);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	glBindTexture(GL_TEXTURE_2D, 0);

	glGenBuffers(1, &neural_pbo_);
	glBindBuffer(GL_PIXEL_PACK_BUFFER, neural_pbo_);
	glBufferData(GL_PIXEL_PACK_BUFFER, (GLsizeiptr)w * h * 4, nullptr, GL_STREAM_READ);
	glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);

	glGenFramebuffers(1, &neural_fbo_);
	glBindFramebuffer(GL_FRAMEBUFFER, neural_fbo_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
			neural_cap_tex_, 0);
	const bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
			&& glGetError() == GL_NO_ERROR;
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	if(!ok)
	{
		// Uma vez so: sem o alvo a rede nao tem o que comer, e o 3D segue
		// com as pistas, que e o que ele fazia antes dela.
		logged_neural_failure_ = true;
		LOGE("Neural depth capture target refused by the driver; 3D stays on the image cues");
		glDeleteFramebuffers(1, &neural_fbo_);
		glDeleteTextures(1, &neural_cap_tex_);
		glDeleteTextures(1, &neural_map_tex_);
		glDeleteBuffers(1, &neural_pbo_);
		neural_fbo_ = neural_cap_tex_ = neural_map_tex_ = neural_pbo_ = 0;
		return false;
	}
	neural_mapa_.assign((size_t)w * h, 0);
	return true;
}

void ToneMapper::ResetNeural()
{
	if(neural_fence_ != nullptr)
		glDeleteSync(neural_fence_);
	neural_fence_ = nullptr;
	neural_estado_ = EstadoNeural::Livre;
	neural_tem_mapa_ = false;
	neural_ultima_ns_ = 0;
}

/**
 * Um passo da profundidade neural, uma vez por quadro novo, antes da passada
 * de profundidade.
 *
 * Nada aqui espera a GPU nem a rede. Cada passo faz no maximo tres coisas
 * curtas, nesta ordem: sobe o mapa que a rede devolveu (147 KB), entrega a
 * captura cuja leitura ja terminou (um memcpy de 590 KB) e, se o ritmo
 * pedir, dispara a proxima captura -- uma passada num alvo de 512x288 e um
 * glReadPixels para um PBO, que volta na hora e so e lido quadros depois,
 * quando a cerca diz que a GPU acabou.
 *
 * Sem a rede ligada (opcao desligada, modelo que nao carregou, thread que
 * caiu), nao faz nada e a passada de profundidade fica com as pistas.
 */
void ToneMapper::TickNeural(const float *matrix, int32_t width, int32_t height)
{
	ProfundidadeNeural &rede = ProfundidadeNeural::Instance();
	const bool ligada = rede.Ligada();
	const uint32_t geracao = rede.Geracao();
	if(!ligada || geracao != neural_geracao_)
	{
		// Rodada nova (ou nenhuma): o que estava em voo era da anterior.
		ResetNeural();
		neural_geracao_ = geracao;
		if(!ligada)
			return;
	}
	if(neural_program_ == 0 || !EnsureNeuralTargets())
		return;

	const int32_t w = ProfundidadeNeural::kLargura;
	const int32_t h = ProfundidadeNeural::kAltura;

	if(neural_estado_ == EstadoNeural::NaRede)
	{
		bool devolvida = false;
		if(rede.TomarSaida(neural_mapa_.data(), geracao, &devolvida))
		{
			glBindTexture(GL_TEXTURE_2D, neural_map_tex_);
			glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RED, GL_UNSIGNED_BYTE,
					neural_mapa_.data());
			glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
			glBindTexture(GL_TEXTURE_2D, 0);
			if(!logged_neural_)
			{
				logged_neural_ = true;
				LOGI("Neural depth: first map in (%dx%d); 3D now follows the model", w, h);
			}
			neural_tem_mapa_ = true;
		}
		if(devolvida)
			neural_estado_ = EstadoNeural::Livre;
	}

	if(neural_estado_ == EstadoNeural::Lendo)
	{
		const GLenum r = glClientWaitSync(neural_fence_, GL_SYNC_FLUSH_COMMANDS_BIT, 0);
		if(r == GL_ALREADY_SIGNALED || r == GL_CONDITION_SATISFIED)
		{
			glDeleteSync(neural_fence_);
			neural_fence_ = nullptr;
			bool entregue = false;
			glBindBuffer(GL_PIXEL_PACK_BUFFER, neural_pbo_);
			const void *p = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0,
					(GLsizeiptr)w * h * 4, GL_MAP_READ_BIT);
			if(p)
			{
				entregue = rede.EntregarEntrada(static_cast<const uint8_t *>(p),
						neural_inverter_, geracao);
				glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
			}
			glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
			neural_estado_ = entregue ? EstadoNeural::NaRede : EstadoNeural::Livre;
		}
		else if(r == GL_WAIT_FAILED)
		{
			glDeleteSync(neural_fence_);
			neural_fence_ = nullptr;
			neural_estado_ = EstadoNeural::Livre;
		}
	}

	const int64_t agora = AgoraNs();
	if(neural_estado_ != EstadoNeural::Livre || agora - neural_ultima_ns_ < neural_periodo_ns_)
		return;
	neural_ultima_ns_ = agora;

	glBindFramebuffer(GL_FRAMEBUFFER, neural_fbo_);
	glViewport(0, 0, w, h);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	glDisable(GL_CULL_FACE);
	glUseProgram(neural_program_);
	glUniformMatrix4fv(nloc_tex_matrix_, 1, GL_FALSE, matrix);
	glUniform2f(nloc_texel_step_, width > 0 ? 1.0f / (float)width : 0.0f,
			height > 0 ? 1.0f / (float)height : 0.0f);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_tex_);
	glUniform1i(nloc_sampler_, 0);
	glBindVertexArray(vao_);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glBindVertexArray(0);

	// A leitura vai para o PBO e volta na hora: o glReadPixels so enfileira a
	// copia. Quem le os bytes e um passo de quadros depois, com a cerca dizendo
	// que a GPU terminou -- antes dela, o glMapBufferRange e que esperaria.
	glBindBuffer(GL_PIXEL_PACK_BUFFER, neural_pbo_);
	glPixelStorei(GL_PACK_ALIGNMENT, 4);
	glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
	glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
	neural_fence_ = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);

	// A mesma regra da pista do chao: com a imagem em pe, a linha de baixo do
	// alvo e a de baixo do quadro, e o modelo quer a de cima primeiro.
	neural_inverter_ = ground_sign_ > 0.0f;
	neural_estado_ = neural_fence_ != nullptr ? EstadoNeural::Lendo : EstadoNeural::Livre;
}

bool ToneMapper::Render(GLuint target, int32_t width, int32_t height, bool pq,
		float sharpen, bool encode, bool extrapolate, GLuint target_right)
{
	if(!attached_ || program_ == 0)
		return false;

	// Sem frame novo o desenho ainda vale a pena: o compositor precisa de
	// conteudo em toda imagem do swapchain, e pular a passada deixaria a
	// anterior aparecer piscando entre as do rodizio.
	//
	// Mas "novo" nao se descobre pelo retorno do updateTexImage.
	//
	// A primeira versao usava `== 0` como sinal de quadro novo, e esse foi o
	// motivo de a extrapolacao nunca ter rodado uma unica vez: a funcao devolve
	// sucesso tambem quando nao ha quadro novo -- ela apenas reprende o mesmo
	// buffer. Entao `fresh` era sempre verdadeiro, o ramo de quadro previsto
	// nunca era alcancado, e o log dizia "Extrapolacao de quadros disponivel"
	// sem nunca dizer "Primeiro quadro previsto".
	//
	// O que separa um do outro e o timestamp do buffer, que e como o proprio
	// pipeline de video define quadro.
	const bool updated = ASurfaceTexture_updateTexImage(surface_texture_) == 0;
	const int64_t timestamp = updated
			? (int64_t)ASurfaceTexture_getTimestamp(surface_texture_)
			: last_timestamp_;
	const bool fresh = updated && timestamp != last_timestamp_;
	last_timestamp_ = timestamp;
	if(fresh)
		has_frame_ = true;
	if(!has_frame_)
		return false;

	if(g_verdade_no_pts.load(std::memory_order_relaxed))
	{
		ver_passadas_desde_real_++;
		if(fresh)
		{
			const uint64_t pts = (uint64_t)(timestamp / 1000);
			if((pts & 1) == 0)
			{
				const int parte = (int)((pts >> 1) & 3) % 3;
				ver_hist_[parte][std::min(ver_passadas_desde_real_, 15)]++;
				ver_passadas_desde_real_ = 0;
			}
		}
	}

	// Quadro real ou repeticao. O timestamp da SurfaceTexture e o pts que o
	// decodificador deu ao quadro, em nanossegundos (pts * 1000).
	const int64_t agora = AgoraNs();
	bool repeticao = false;
	if(fresh)
		TelaPegou((uint64_t)(timestamp / 1000));
	if(fresh)
	{
		float matrix_atual[16];
		ASurfaceTexture_getTransformMatrix(surface_texture_, matrix_atual);
		const uint64_t pts = (uint64_t)(timestamp / 1000);
		const int64_t bytes = TamanhoDoQuadro(pts);
		if(!logged_pts_)
		{
			logged_pts_ = true;
			LOGI("Cadence: first frame timestamp %lld ns -> pts %llu, %lld bytes",
					(long long)timestamp, (unsigned long long)pts, (long long)bytes);
		}
		// So vale a comparacao quando a previsao esta ligada: sem previsao a
		// resposta nao muda nada.
		const bool comparar = extrapolate && probe_program_ != 0;
		comparando_ = comparar;
		bool provisorio = false;
		bool classificado = false;
		if(comparar)
		{
			// 1. Confere a leitura do quadro anterior, quando ela foi
			// adiantada pelo padrao. Errou: o padrao quebrou (queda de fps,
			// cena que mudou) e as proximas voltam a ser lidas na hora.
			if(amostra_pendente_)
			{
				const float d = LerAmostraPendente();
				if(d >= 0.0f)
				{
					const bool rep = EhRepeticao(d);
					RegistrarDiff(d, bytes_pendente_);
					if(!rep)
						AtualizarDiffReal(d);
					if(pendente_provisorio_)
					{
						// Lida sem esperar com a cena parada: a classe so e
						// conhecida agora, e e escrita no padrao sem contar erro.
						if(n_classes_ > 0)
							classes_[kClasses - 1] = rep;
					}
					else if(rep != pendente_predito_)
					{
						cad_erros_++;
						sem_trava_ = 8;
						if(n_classes_ > 0)
							classes_[kClasses - 1] = rep;
					}
				}
			}
			// 2. A classe deste quadro: pelo padrao, quando ele e firme, com a
			// leitura so para conferir depois; senao lida agora, esperando a
			// GPU.
			const int periodo = sem_trava_ > 0 ? 0 : PeriodoTravado();
			DesenharAmostra(matrix_atual);
			provisorio = false;
			if(periodo > 0)
			{
				repeticao = classes_[kClasses - periodo];
				LerAmostraDepois();
				pendente_predito_ = repeticao;
				pendente_provisorio_ = false;
				bytes_pendente_ = bytes;
				cad_adiantadas_++;
				classificado = true;
			}
			else if(!movimento_ && sem_trava_ == 0)
			{
				// Cena parada: a previsao esta desligada, entao a classe pode
				// chegar um quadro depois. Conta como real por enquanto (a tela
				// mostra o quadro de verdade, que e o certo parado) e a leitura
				// vai sem parar a GPU. Quando o movimento volta, o historico e
				// descartado e refeito com leituras na hora (ver movimento_).
				repeticao = false;
				LerAmostraDepois();
				pendente_predito_ = false;
				pendente_provisorio_ = true;
				bytes_pendente_ = bytes;
				cad_paradas_++;
				classificado = true;
				provisorio = true;
			}
			else
			{
				if(sem_trava_ > 0)
					sem_trava_--;
				const float d = LerAmostraAgora();
				cad_na_hora_++;
				if(d < -1.0f)
				{
					// Fora do prazo: segue como real por enquanto, conferido
					// no proximo quadro novo, como na cena parada.
					repeticao = false;
					pendente_predito_ = false;
					pendente_provisorio_ = true;
					bytes_pendente_ = bytes;
					classificado = true;
					provisorio = true;
				}
				else if(d >= 0.0f)
				{
					repeticao = EhRepeticao(d);
					RegistrarDiff(d, bytes);
					if(!repeticao)
						AtualizarDiffReal(d);
					classificado = true;
				}
			}
			if(classificado)
			{
				for(int k = 0; k + 1 < kClasses; k++)
					classes_[k] = classes_[k + 1];
				classes_[kClasses - 1] = repeticao;
				if(n_classes_ < kClasses)
					n_classes_++;
			}
		}
		if(classificado && g_verdade_no_pts.load(std::memory_order_relaxed))
		{
			const uint64_t pts = (uint64_t)(timestamp / 1000);
			ver_parte_ = (int)((pts >> 1) & 3) % 3;
			const bool verdade = (pts & 1) != 0;
			ver_total_[ver_parte_]++;
			if(verdade != repeticao)
				ver_errados_[ver_parte_]++;
		}
		if(classificado)
		{
			// Tela parada por muito tempo (menu, pausa) manda tudo igual: depois
			// de meio segundo conta como quadro real, senao a previsao ficaria
			// parada no historico velho.
			if(repeticao && ++repeticoes_seguidas_ > 30)
				repeticao = false;
			if(!repeticao)
				repeticoes_seguidas_ = 0;
			if(repeticao)
			{
				cad_repeticoes_++;
				if(bytes > 0 && (uint32_t)bytes > cad_maior_repeticao_)
					cad_maior_repeticao_ = (uint32_t)bytes;
			}
			else if(bytes > 0 && (uint32_t)bytes < cad_menor_real_)
				cad_menor_real_ = (uint32_t)bytes;
		}
		else if(bytes < 0)
			cad_sem_tamanho_++;
		if(!repeticao)
		{
			cad_reais_++;
			if(real_newest_ns_ > 0)
			{
				const int64_t intervalo = agora - real_newest_ns_;
				// Os dois quadros do historico tem de ser vizinhos. Separados
				// por meio segundo de "repeticoes" (cena parada), a diferenca
				// entre eles e o movimento acumulado, e a previsao o projetaria
				// inteiro num passo: a tela pulsava. Ate o proximo par
				// vizinho, sem previsao.
				historico_vizinho_ = intervalo <= periodo_fonte_ns_ * 17 / 10;
				// Engasgo de rede nao e cadencia, nem quadro so provisorio.
				if(!provisorio && intervalo > 4000000 && intervalo < 100000000)
					periodo_media_ns_ += ((double)intervalo - periodo_media_ns_) / 8.0;
				// Sem arredondar para 60 ou 30: jogo a 40 fps (fidelidade com
				// 120 Hz no PS5) chega a 25 ms em media, alternando 16 e 33.
				periodo_fonte_ns_ = (int64_t)std::min(std::max(periodo_media_ns_, 16666667.0), 33333333.0);
			}
			real_newest_ns_ = agora;
		}
	}
	// Quanto do periodo da fonte ja passou desde o ultimo quadro real. Acima
	// de 1 o quadro seguinte atrasou: prever mais longe so aumentaria o erro.
	extrap_scale_ = real_newest_ns_ > 0
			? (float)((double)(agora - real_newest_ns_) / (double)periodo_fonte_ns_) : 0.0f;
	// Com previsao ligada, repeticao conta como passada sem quadro novo.
	const bool novo = fresh && !(extrapolate && repeticao);
	// Quadro real atrasado nao e so rede: jogo de 30 que cai para 25 segura um
	// quadro por 50 ms, e jogo de 40 alterna 16 e 33. Nesses casos o proximo
	// quadro traz o movimento do tempo a mais (o jogo anda pelo relogio), entao
	// continuar a previsao por mais meio periodo e o que acerta. Acima disso a
	// tela segura o ultimo quadro real.
	extrap_limite_ = 1.5f;
	// Previsao so com movimento de verdade. Parado, o quadro real difere do
	// anterior por 0,1-0,5 (ruido de compressao, respiracao); a extensao acha
	// "movimento" nesse ruido e a imagem tremula (Spider-Man 2, 18/11/2026).
	// Com a camera andando a diferenca passa de 7. Abaixo de 1, repetir o
	// quadro real e o certo: nao ha o que prever.
	const bool movimento_antes = movimento_;
	// Com margem: liga acima de 3, desliga abaixo de 1,5. Um limite unico
	// (1,0) deixava cena quase parada -- personagem respirando na frente da
	// escada de incendio, diff 1,1-1,7 -- ligando e desligando 10-36 vezes
	// em 10 s, e cada troca era um pulso (18/11/2026). Movimento pequeno
	// assim nao ganha nada com previsao; camera andando mede 7 ou mais.
	movimento_ = movimento_ ? diff_real_rapido_ >= 1.5f : diff_real_rapido_ >= 3.0f;
	if(movimento_ && !movimento_antes && comparando_)
	{
		// Saindo da cena parada: o historico pode ter repeticoes contadas como
		// reais (lidas sem esperar). Descarta e refaz com leituras na hora: a
		// previsao volta dois quadros reais depois, sem chute errado.
		history_count_[0] = history_count_[1] = 0;
		history_newest_[0] = history_newest_[1] = -1;
		historico_vizinho_ = false;
		sem_trava_ = 8;
		cad_retomadas_++;
	}
	if(extrapolate && !novo)
	{
		if(movimento_ && historico_vizinho_ && extrap_scale_ > 0.05f && extrap_scale_ <= extrap_limite_)
		{
			cad_previstas_++;
			ver_previstas_[ver_parte_]++;
		}
		else
			cad_atrasadas_++;
	}

	// Uma vez, depois de algumas centenas de passadas: quantas tinham quadro
	// novo. Com fonte de 60 e painel de 120 a conta deveria dar perto de
	// metade, e e essa suposicao que a extrapolacao inteira assume. Se sair
	// muito longe disso, o problema nao esta no efeito e sim na cadencia.
	frames_seen_++;
	if(fresh)
		frames_fresh_++;
	if(frames_seen_ >= 1200)
	{
		const int origem = (int)std::lround(1e9 / (double)periodo_fonte_ns_);
		LOGI("Cadence: %d of %d passes had a new frame; %d real, %d repeats "
				"(largest repeat %u B, smallest real %u B, typical real diff %.1f, %d without size); "
				"source ~%d fps; %d predicted passes, %d past the next frame; "
				"image compare: %d read at once (%.2f ms avg, %.2f max), %d read later by the pattern "
				"(check %.2f ms max), %d pattern misses, %d read later while still, %d motion restarts, "
				"%d scene cuts, %d past the 2 ms limit",
				frames_fresh_, frames_seen_, cad_reais_, cad_repeticoes_,
				cad_maior_repeticao_, cad_menor_real_ == UINT32_MAX ? 0u : cad_menor_real_,
				diff_real_medio_, cad_sem_tamanho_, origem,
				cad_previstas_, cad_atrasadas_,
				cad_na_hora_, cad_leituras_ ? cad_leitura_ns_ / 1e6 / cad_leituras_ : 0.0,
				cad_leitura_max_ns_ / 1e6, cad_adiantadas_, cad_conferencia_max_ns_ / 1e6, cad_erros_,
				cad_paradas_, cad_retomadas_, cad_cortes_, cad_sem_prazo_);
		if(g_verdade_no_pts.load(std::memory_order_relaxed))
		{
			LOGI("Bench truth: pan 30 fps %d wrong of %d (%d predicted passes) | still with noise %d of %d (%d) | "
					"pan with drops %d of %d (%d)",
					ver_errados_[0], ver_total_[0], ver_previstas_[0],
					ver_errados_[1], ver_total_[1], ver_previstas_[1],
					ver_errados_[2], ver_total_[2], ver_previstas_[2]);
			const char *nomes[3] = {"pan 30 fps", "still", "pan with drops"};
			for(int k = 0; k < 3; k++)
			{
				std::string h;
				char b[24];
				for(int q = 1; q < 16; q++)
					if(ver_hist_[k][q])
					{
						snprintf(b, sizeof b, " %d:%d", q, ver_hist_[k][q]);
						h += b;
					}
				LOGI("Bench passes per real frame, %s (passes:frames):%s", nomes[k], h.c_str());
			}
			for(int k = 0; k < 3; k++)
			{
				ver_total_[k] = ver_errados_[k] = ver_previstas_[k] = 0;
				for(int q = 0; q < 16; q++)
					ver_hist_[k][q] = 0;
			}
		}
		cad_leitura_ns_ = cad_leitura_max_ns_ = cad_conferencia_ns_ = cad_conferencia_max_ns_ = 0;
		cad_leituras_ = cad_na_hora_ = cad_adiantadas_ = cad_erros_ = cad_paradas_ = cad_retomadas_ = cad_cortes_ = cad_sem_prazo_ = 0;
		frames_seen_ = frames_fresh_ = 0;
		cad_reais_ = cad_repeticoes_ = cad_previstas_ = cad_atrasadas_ = cad_sem_tamanho_ = 0;
		cad_maior_repeticao_ = 0;
		cad_menor_real_ = UINT32_MAX;
	}

	float matrix[16];
	ASurfaceTexture_getTransformMatrix(surface_texture_, matrix);

	// A profundidade primeiro, e num alvo proprio. Tem de acontecer antes de o
	// framebuffer de destino ser preso: ela usa o seu, e trocar de framebuffer
	// no meio da passada principal seria pior do que fazer as duas em ordem.
	//
	// So em quadro novo, e isso importa desde que a passada saiu de dentro do
	// desenho. Num quadro repetido a imagem e a mesma, entao a estimativa
	// tambem seria -- mas a media exponencial nao sabe disso: ela andaria mais
	// uma vez sobre o mesmo dado, e o mapa avancaria ao dobro da velocidade
	// pretendida. Alem de custar uma passada de GPU justamente nos quadros que
	// a extrapolacao existe para baratear.
	if(fresh && pq && probe_program_ != 0)
		SondarCor(matrix);

	// Com previsao a GPU tem mais o que fazer: a profundidade cai para 10
	// mapas por segundo (de 20). Ela ja e suavizada no tempo, e a 3D nao
	// muda de forma visivel; os 35 ms de GPU por mapa, sim.
	neural_periodo_ns_ = extrapolate ? 2 * kPeriodoNeuralNs : kPeriodoNeuralNs;
	// So em quadro real: a repeticao do jogo a 30 fps e a mesma imagem, e
	// estimar profundidade de novo nela so gastaria GPU.
	if(stereo_ && fresh && !repeticao)
	{
		TickNeural(matrix, width, height);
		RenderDepth(matrix, width, height);
		RenderStats();
	}

	// O 3D so acontece de verdade com o intermediario: e dele que os dois olhos
	// leem. Sem ele -- alvo recusado pelo driver, ou programa que nao ligou --,
	// o caminho de sempre desenha a mesma imagem nos dois olhos: 3D nenhum, mas
	// video normal, com a razao escrita no diario.
	// A profundidade tambem: sem o programa dela ou sem o alvo, a deformacao
	// leria a textura 0 como profundidade zero -- o deslocamento maximo em
	// toda a imagem, um empurrao lateral sem 3D nenhum.
	const bool tres_d = stereo_ && target_right != 0 && warp_program_ != 0
			&& depth_program_ != 0 && depth_tex_[depth_newest_] != 0
			&& EnsureIntermediate(width, height);
	if(tres_d)
	{
		// A passada cara, uma vez por quadro novo. Num quadro repetido o
		// intermediario continua valendo, e os olhos custam so a deformacao --
		// que e o que torna esta mudanca mais barata que o caminho anterior,
		// e nao so empatada.
		//
		// Com previsao, ela acontece aqui, uma vez, na imagem plana -- e nao em
		// cada olho, como ate 18/11/2026. Prevista por olho, cada um podia
		// errar diferente (cintilacao no 3D) e a troca de mapa de profundidade
		// (~17 por segundo) entrava na previsao como se fosse movimento. Agora
		// o historico e o intermediario, a previsao escreve nele, e os olhos so
		// deformam o que ele tiver: o quadro real ou o previsto.
		const bool vai_prever = extrapolate && !novo && extrapolate_fn_ && history_count_[0] >= 2
				&& movimento_ && historico_vizinho_ && extrap_scale_ > 0.05f
				&& extrap_scale_ <= extrap_limite_;
		if(fresh || !intermediate_valid_ || vai_prever || intermediate_previsto_)
		{
			// Sem codificar: o alvo e sRGB e o GL codifica na escrita.
			if(DrawEye(intermediate_tex_, width, height, pq, sharpen, false, extrapolate,
					novo || !intermediate_valid_, matrix, 0, 0))
				intermediate_valid_ = true;
			intermediate_previsto_ = vai_prever;
		}

		// Os dois olhos, cada um no seu swapchain. As duas chamadas vao para
		// variaveis proprias de proposito: escritas dentro de um `&&` a segunda
		// nao aconteceria quando a primeira falhasse, e o olho direito ficaria
		// com o conteudo de dois quadros atras -- que se ve como um tranco
		// lateral, e nao como um quadro perdido.
		const bool esq = DrawEye(target, width, height, pq, sharpen, encode,
				false, true, matrix, -1, 0, intermediate_tex_);
		const bool dir = DrawEye(target_right, width, height, pq, sharpen, encode,
				false, true, matrix, +1, 1, intermediate_tex_);
		return esq && dir;
	}

	if(stereo_ && target_right != 0)
	{
		const bool esq = DrawEye(target, width, height, pq, sharpen, encode,
				extrapolate, novo, matrix, -1, 0);
		const bool dir = DrawEye(target_right, width, height, pq, sharpen, encode,
				extrapolate, novo, matrix, +1, 1);
		return esq && dir;
	}
	return DrawEye(target, width, height, pq, sharpen, encode, extrapolate, novo,
			matrix, 0, 0);
}

/**
 * Desenha um alvo: a imagem inteira, para um olho.
 *
 * Saiu de dentro do Render porque no 3D ela acontece duas vezes por quadro, uma
 * por swapchain, enquanto tudo o que vem antes -- prender o buffer, decidir se
 * o quadro e novo, estimar a profundidade -- acontece uma vez so. Chamar Render
 * duas vezes consumiria dois quadros do decodificador para mostrar um.
 *
 * `eye` e -1, +1 ou 0 quando nao ha olho a distinguir. `slot` escolhe o par de
 * texturas de historico, que e por olho. `source` diferente de zero manda
 * desenhar com a passada dos olhos, lendo o intermediario ja pronto em vez da
 * textura externa.
 */
namespace {

/** Uma hipotese de como o driver converte YUV em RGB. */
struct Conversao
{
	const char *nome;
	float kr;
	float kb;
	bool limitada;
};

const Conversao kConversoes[] = {
	{ "BT.2020 limited", 0.2627f, 0.0593f, true },
	{ "BT.2020 full", 0.2627f, 0.0593f, false },
	{ "BT.709 limited", 0.2126f, 0.0722f, true },
	{ "BT.709 full", 0.2126f, 0.0722f, false },
	{ "BT.601 limited", 0.299f, 0.114f, true },
	{ "BT.601 full", 0.299f, 0.114f, false },
};

/**
 * YUV cru (0-1) em RGB (0-1), pela hipotese dada.
 *
 * Faixa limitada em 10 bits: Y de 64 a 940, croma de 64 a 960 com centro em
 * 512. O P010 guarda os 10 bits no alto de 16, e normalizar por 1023 ou pelo
 * recipiente da diferenca de milesimos -- abaixo do que a sonda mede.
 */
void Converter(const Conversao &c, const float *yuv, float *rgb)
{
	float y = yuv[0], cb = yuv[1], cr = yuv[2];
	if(c.limitada)
	{
		y = (y * 1023.0f - 64.0f) / 876.0f;
		cb = (cb * 1023.0f - 512.0f) / 896.0f;
		cr = (cr * 1023.0f - 512.0f) / 896.0f;
	}
	else
	{
		cb -= 512.0f / 1023.0f;
		cr -= 512.0f / 1023.0f;
	}
	const float r = y + 2.0f * (1.0f - c.kr) * cr;
	const float b = y + 2.0f * (1.0f - c.kb) * cb;
	const float g = (y - c.kr * r - c.kb * b) / (1.0f - c.kr - c.kb);
	rgb[0] = r < 0.0f ? 0.0f : (r > 1.0f ? 1.0f : r);
	rgb[1] = g < 0.0f ? 0.0f : (g > 1.0f ? 1.0f : g);
	rgb[2] = b < 0.0f ? 0.0f : (b > 1.0f ? 1.0f : b);
}

} // namespace

/**
 * Confere, algumas vezes por sessao, a conversao de YUV que o driver faz.
 *
 * A imagem de 10 bits chega em YUV BT.2020 de faixa limitada (o decodificador
 * diz isso no diario: color-standard=6, color-range=2), e quem a converte em
 * RGB e o driver, ao ler a textura externa -- nao ha como pedir nem conferir
 * pela API. Se ele usar a matriz do BT.709, as cores fortes saem deslocadas;
 * se ignorar a faixa, o preto fica cinza e o branco nunca chega.
 *
 * Le a grade crua e a convertida, testa cada hipotese e escreve a que bate.
 * So conclui com cor na cena: numa imagem cinza as matrizes coincidem, e ai a
 * rodada nao conta e a proxima tenta de novo. O glReadPixels para o pipeline
 * uma vez; por isso sao poucas rodadas, espacadas, e nunca mais depois da
 * conclusao.
 */
namespace {
constexpr int kAmW = 64, kAmH = 36;
}

void ToneMapper::DesenharAmostra(const float *matrix)
{
	if(amostra_tex_ == 0)
	{
		glGenTextures(1, &amostra_tex_);
		glBindTexture(GL_TEXTURE_2D, amostra_tex_);
		glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, kAmW, kAmH);
		glBindTexture(GL_TEXTURE_2D, 0);
		glGenFramebuffers(1, &amostra_fbo_);
		glGenBuffers(1, &amostra_pbo_);
		glBindBuffer(GL_PIXEL_PACK_BUFFER, amostra_pbo_);
		glBufferData(GL_PIXEL_PACK_BUFFER, kAmW * kAmH * 4, nullptr, GL_STREAM_READ);
		glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
		amostra_atual_.assign(kAmW * kAmH * 4, 0);
		amostra_anterior_.clear();
	}
	glBindFramebuffer(GL_FRAMEBUFFER, amostra_fbo_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, amostra_tex_, 0);
	const GLenum single[] = { GL_COLOR_ATTACHMENT0 };
	glDrawBuffers(1, single);
	glDisable(GL_BLEND);
	glUseProgram(probe_program_);
	glUniformMatrix4fv(ploc_tex_matrix_, 1, GL_FALSE, matrix);
	glUniform2f(ploc_texel_step_, 0.0f, 0.0f);
	// Luma crua (modo 1, sampler YUV), por ponto: cada amostra e um pixel
	// decodificado, sem conversao de cor nem media de vizinhos no caminho.
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_tex_);
	glUniform1i(ploc_rgb_, 0);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_tex_);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	glUniform1i(ploc_yuv_, 1);
	glUniform1i(ploc_modo_, 1);
	glBindVertexArray(vao_);
	glViewport(0, 0, kAmW, kAmH);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glBindVertexArray(0);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
	// O framebuffer fica preso: a leitura que vem logo depois e dele.
}

float ToneMapper::CompararAmostra()
{
	float diff = -1.0f;
	if(amostra_anterior_.size() == amostra_atual_.size())
	{
		int64_t soma = 0;
		for(size_t k = 0; k < amostra_atual_.size(); k += 4)
			soma += std::abs((int)amostra_atual_[k] - (int)amostra_anterior_[k]);
		diff = (float)((double)soma / (double)(kAmW * kAmH));
	}
	amostra_anterior_.swap(amostra_atual_);
	if(amostra_atual_.size() != amostra_anterior_.size())
		amostra_atual_.assign(amostra_anterior_.size(), 0);
	return diff;
}

float ToneMapper::LerAmostraAgora()
{
	// Leitura "na hora", mas com prazo de 2 ms. Antes era glReadPixels direto,
	// que espera a GPU esvaziar a fila -- e com o 3D a rede de profundidade
	// ocupa a GPU por ~35 ms: a espera media foi 8 ms, com picos de 35, o
	// painel perdia metade dos quadros e o jogo engasgava (18/11/2026). Agora
	// vai para o PBO com uma cerca; se a GPU nao entregar no prazo, a leitura
	// fica pendente (conferida no proximo quadro novo) e devolve -2.
	const int64_t inicio = AgoraNs();
	LerAmostraDepois();
	const GLenum r = glClientWaitSync(amostra_fence_, GL_SYNC_FLUSH_COMMANDS_BIT, 2000000);
	const int64_t custo = AgoraNs() - inicio;
	cad_leitura_ns_ += custo;
	cad_leituras_++;
	if(custo > cad_leitura_max_ns_)
		cad_leitura_max_ns_ = custo;
	if(r != GL_ALREADY_SIGNALED && r != GL_CONDITION_SATISFIED)
	{
		cad_sem_prazo_++;
		return -2.0f;
	}
	return LerAmostraPendente();
}

void ToneMapper::LerAmostraDepois()
{
	// Para o PBO, com uma cerca: volta na hora, e os dados sao pegos no
	// proximo quadro novo, 16-33 ms depois, quando a GPU ja terminou.
	glBindBuffer(GL_PIXEL_PACK_BUFFER, amostra_pbo_);
	glReadPixels(0, 0, kAmW, kAmH, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
	glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	if(amostra_fence_)
		glDeleteSync(amostra_fence_);
	amostra_fence_ = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
	amostra_pendente_ = true;
}

float ToneMapper::LerAmostraPendente()
{
	amostra_pendente_ = false;
	if(!amostra_fence_)
		return -1.0f;
	const int64_t inicio = AgoraNs();
	// Normalmente ja passou; o teto so protege de uma GPU travada.
	glClientWaitSync(amostra_fence_, GL_SYNC_FLUSH_COMMANDS_BIT, 5000000);
	glDeleteSync(amostra_fence_);
	amostra_fence_ = nullptr;
	float diff = -1.0f;
	glBindBuffer(GL_PIXEL_PACK_BUFFER, amostra_pbo_);
	const void *dados = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, kAmW * kAmH * 4, GL_MAP_READ_BIT);
	if(dados)
	{
		memcpy(amostra_atual_.data(), dados, amostra_atual_.size());
		glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
		diff = CompararAmostra();
	}
	glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
	const int64_t custo = AgoraNs() - inicio;
	cad_conferencia_ns_ += custo;
	if(custo > cad_conferencia_max_ns_)
		cad_conferencia_max_ns_ = custo;
	return diff;
}

bool ToneMapper::EhRepeticao(float diff)
{
	// Imagem quase igual a anterior. "Quase" porque o PS5 refina a repeticao
	// e o ruido de codificacao muda um pouco: repeticao mede 0,1-0,5, quadro
	// real 7-11 com a camera andando (Spider-Man 2, 18/11/2026).
	//
	// Comparado com os vizinhos, e nao com um numero fixo: parado, o quadro
	// real muda pouco (personagem respirando, folhas) e um limite fixo de 1,5
	// o chamava de repeticao. A previsao andava sozinha meio segundo e voltava
	// -- a tela "pulsava como coracao". Repeticao e a que fica bem abaixo do
	// maior dos ultimos tres; abaixo de 0,05 e igual.
	float maior = 0.0f;
	for(float d : diffs_recentes_)
		maior = std::max(maior, d);
	// Parado de verdade (Spider-Man 2, 18/11/2026) o real mede 0,2 e a
	// repeticao 0,0: o piso absoluto e so para o zero.
	const bool rep = diff < 0.05f || (maior > 0.0f && diff < maior * 0.35f);
	diffs_recentes_[diffs_pos_] = diff;
	diffs_pos_ = (diffs_pos_ + 1) % 3;
	return rep;
}

void ToneMapper::AtualizarDiffReal(float diff)
{
	// Corte de cena (cutscene, troca de camera, menu): o quadro real novo e de
	// outra cena, e prever a partir do par atravessaria o corte -- uma ou duas
	// passadas de imagem deformada. Corte e diferenca grande e muito acima do
	// movimento recente (camera andando mede 7-17 sem saltar assim). O par e
	// descartado e a previsao fica desligada ate a cena nova mostrar movimento.
	if(diff > 25.0f && diff > 4.0f * std::max(diff_real_rapido_, 2.0f))
	{
		history_count_[0] = history_count_[1] = 0;
		history_newest_[0] = history_newest_[1] = -1;
		historico_vizinho_ = false;
		// A cena nova vira a referencia: zerar faria todo quadro seguinte com
		// movimento parecer outro corte (o banco de ensaio pegou: 172 em 10 s).
		diff_real_rapido_ = diff;
		sem_trava_ = 8;
		cad_cortes_++;
		return;
	}
	diff_real_rapido_ = diff_real_rapido_ <= 0.0f ? diff : (diff_real_rapido_ + diff) * 0.5f;
	diff_real_medio_ = diff_real_medio_ <= 0.0 ? diff
			: diff_real_medio_ + (diff - diff_real_medio_) / 16.0;
}

int ToneMapper::PeriodoTravado() const
{
	// Padrao firme: as ultimas nove classes repetem as de um periodo antes.
	// 1 = tudo real (jogo de 60), 2 = real/repeticao (30), 3 = 40 fps.
	if(n_classes_ < kClasses)
		return 0;
	for(int p = 1; p <= 3; p++)
	{
		bool firme = true;
		for(int i = kClasses - 9; i < kClasses && firme; i++)
			firme = classes_[i] == classes_[i - p];
		if(firme)
			return p;
	}
	return 0;
}

void ToneMapper::RegistrarDiff(float diff, int64_t bytes)
{
	// Sequencia de diagnostico, uma vez por sessao, depois de 20 s.
	if(seq_n_ < 0 && ++seq_quadros_ >= 1200)
		seq_n_ = 0;
	if(seq_n_ < 0 || seq_n_ >= 60)
		return;
	seq_diff_[seq_n_] = diff;
	seq_bytes_[seq_n_] = bytes > 0 ? (uint32_t)bytes : 0;
	if(++seq_n_ == 60)
	{
		std::string linha;
		char b[32];
		for(int k = 0; k < 60; k++)
		{
			snprintf(b, sizeof b, "%.1f/%u%s", seq_diff_[k], seq_bytes_[k] / 1024,
					k % 15 == 14 ? " | " : " ");
			linha += b;
		}
		LOGI("Frame diffs (luma diff/KB, 60 new frames in a row): %s", linha.c_str());
	}
}

bool ToneMapper::MascaraPronta(int slot, int32_t width, int32_t height)
{
	if(!compor_ok_)
		return false;
	if(prog_diff_ == 0)
	{
		prog_diff_ = LinkPair(kVertexShader, kMascaraDiffShader, "the motion mask");
		prog_dilata_ = LinkPair(kVertexShader, kMascaraDilataShader, "the mask dilation");
		prog_compor_ = LinkPair(kVertexShader, kComporShader, "the prediction blend");
		if(!prog_diff_ || !prog_dilata_ || !prog_compor_)
		{
			compor_ok_ = false;
			LOGW("Motion mask unavailable; predicting the whole frame");
			return false;
		}
		LOGI("Motion mask ready: predicted frames only where the last two real frames differ");
	}
	const int32_t mw = std::max(1, width / 8), mh = std::max(1, height / 8);
	if(previsto_tex_[slot] == 0 || mascara_w_[slot] != mw || mascara_h_[slot] != mh)
	{
		if(previsto_tex_[slot] != 0)
		{
			glDeleteTextures(1, &previsto_tex_[slot]);
			glDeleteTextures(2, mascara_tex_[slot]);
		}
		glGenTextures(1, &previsto_tex_[slot]);
		glBindTexture(GL_TEXTURE_2D, previsto_tex_[slot]);
		glTexStorage2D(GL_TEXTURE_2D, 1, history_format_, width, height);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glGenTextures(2, mascara_tex_[slot]);
		for(int i = 0; i < 2; i++)
		{
			glBindTexture(GL_TEXTURE_2D, mascara_tex_[slot][i]);
			glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, mw, mh);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		}
		glBindTexture(GL_TEXTURE_2D, 0);
		mascara_w_[slot] = mw;
		mascara_h_[slot] = mh;
		mascara_gen_[slot] = -1;
	}
	if(mascara_gen_[slot] == history_gen_[slot])
		return true;

	// Uma vez por quadro real: diferenca e dilatacao, em 1/8 da resolucao.
	const int newest = history_newest_[slot];
	const GLuint novo = history_[slot][newest], velho = history_[slot][1 - newest];
	const GLenum single[] = { GL_COLOR_ATTACHMENT0 };
	glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mascara_tex_[slot][0], 0);
	glDrawBuffers(1, single);
	glViewport(0, 0, mw, mh);
	glDisable(GL_BLEND);
	glBindVertexArray(vao_);
	glUseProgram(prog_diff_);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, novo);
	glUniform1i(glGetUniformLocation(prog_diff_, "uNovo"), 0);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, velho);
	glUniform1i(glGetUniformLocation(prog_diff_, "uVelho"), 1);
	glUniform2f(glGetUniformLocation(prog_diff_, "uPasso"), 1.0f / width, 1.0f / height);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mascara_tex_[slot][1], 0);
	glUseProgram(prog_dilata_);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, 0);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, mascara_tex_[slot][0]);
	glUniform1i(glGetUniformLocation(prog_dilata_, "uMascara"), 0);
	glUniform2f(glGetUniformLocation(prog_dilata_, "uPasso"), 1.0f / mw, 1.0f / mh);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glBindVertexArray(0);
	glBindTexture(GL_TEXTURE_2D, 0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	mascara_gen_[slot] = history_gen_[slot];
	return true;
}

bool ToneMapper::Compor(GLuint target, int slot, int32_t width, int32_t height)
{
	glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
	GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
	if(status != GL_FRAMEBUFFER_COMPLETE)
	{
		glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, target, 0, 0);
		status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
	}
	if(status != GL_FRAMEBUFFER_COMPLETE)
	{
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		return false;
	}
	const GLenum single[] = { GL_COLOR_ATTACHMENT0 };
	glDrawBuffers(1, single);
	glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, single);
	glViewport(0, 0, width, height);
	glDisable(GL_BLEND);
	glBindVertexArray(vao_);
	glUseProgram(prog_compor_);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, previsto_tex_[slot]);
	glUniform1i(glGetUniformLocation(prog_compor_, "uPrevisto"), 0);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, history_[slot][history_newest_[slot]]);
	glUniform1i(glGetUniformLocation(prog_compor_, "uReal"), 1);
	glActiveTexture(GL_TEXTURE2);
	glBindTexture(GL_TEXTURE_2D, mascara_tex_[slot][1]);
	glUniform1i(glGetUniformLocation(prog_compor_, "uMascara"), 2);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glBindVertexArray(0);
	glBindTexture(GL_TEXTURE_2D, 0);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, 0);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, 0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	return true;
}

void ToneMapper::SondarCor(const float *matrix)
{
	if(probe_done_)
		return;
	probe_frames_++;
	// Aos 5 s e depois a cada 20 s, ate seis rodadas.
	if(probe_frames_ < 300 || (probe_frames_ - 300) % 1200 != 0)
		return;
	if(++probe_runs_ > 6)
	{
		probe_done_ = true;
		LOGI("Color probe: no scene with enough color in six tries; conversion not confirmed");
		return;
	}

	const int kW = 32, kH = 18;
	if(probe_tex_ == 0)
	{
		glGenTextures(1, &probe_tex_);
		glBindTexture(GL_TEXTURE_2D, probe_tex_);
		glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, kW, kH * 2);
		glBindTexture(GL_TEXTURE_2D, 0);
		glGenFramebuffers(1, &probe_fbo_);
	}

	while(glGetError() != GL_NO_ERROR) { }
	glBindFramebuffer(GL_FRAMEBUFFER, probe_fbo_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, probe_tex_, 0);
	const GLenum single[] = { GL_COLOR_ATTACHMENT0 };
	glDrawBuffers(1, single);
	glDisable(GL_BLEND);

	glUseProgram(probe_program_);
	glUniformMatrix4fv(ploc_tex_matrix_, 1, GL_FALSE, matrix);
	glUniform2f(ploc_texel_step_, 0.0f, 0.0f);
	// A mesma textura em duas unidades: dois tipos de sampler na mesma unidade
	// e erro. E filtragem por ponto, para os dois lados lerem o mesmo pixel --
	// com a bilinear o RGB seria media de vizinhos e o cru nao.
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_tex_);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	glUniform1i(ploc_rgb_, 0);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_tex_);
	glUniform1i(ploc_yuv_, 1);

	glBindVertexArray(vao_);
	glViewport(0, 0, kW, kH);
	glUniform1i(ploc_modo_, 0);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glViewport(0, kH, kW, kH);
	glUniform1i(ploc_modo_, 1);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glBindVertexArray(0);

	uint8_t px[kW * kH * 2 * 4];
	glReadPixels(0, 0, kW, kH * 2, GL_RGBA, GL_UNSIGNED_BYTE, px);
	const GLenum err = glGetError();

	glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
	glActiveTexture(GL_TEXTURE0);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);

	if(err != GL_NO_ERROR)
	{
		probe_done_ = true;
		LOGW("Color probe failed (gl 0x%x); conversion not confirmed", err);
		return;
	}

	const int n = kW * kH;
	const int kC = (int)(sizeof(kConversoes) / sizeof(kConversoes[0]));
	double erro[kC] = {};
	double croma = 0.0;
	float y_min = 1.0f, y_max = 0.0f;
	for(int i = 0; i < n; i++)
	{
		const uint8_t *d = px + i * 4;
		const uint8_t *c = px + (n + i) * 4;
		const float yuv[3] = { c[0] / 255.0f, c[1] / 255.0f, c[2] / 255.0f };
		if(yuv[0] < y_min) y_min = yuv[0];
		if(yuv[0] > y_max) y_max = yuv[0];
		croma += fabsf(yuv[1] - 0.5f) + fabsf(yuv[2] - 0.5f);
		for(int k = 0; k < kC; k++)
		{
			float rgb[3];
			Converter(kConversoes[k], yuv, rgb);
			erro[k] += fabsf(rgb[0] - d[0] / 255.0f) + fabsf(rgb[1] - d[1] / 255.0f)
					+ fabsf(rgb[2] - d[2] / 255.0f);
		}
	}
	croma /= n;

	int melhor = 0, segundo = -1;
	for(int k = 1; k < kC; k++)
		if(erro[k] < erro[melhor])
			melhor = k;
	for(int k = 0; k < kC; k++)
		if(k != melhor && (segundo < 0 || erro[k] < erro[segundo]))
			segundo = k;

	// Com cor de menos na cena as hipoteses empatam, e um vencedor por
	// milesimos nao diria nada.
	const bool conclusiva = croma >= 0.03 && y_max - y_min >= 0.2f;
	LOGI("Color probe %d: driver matches %s (mean error %.3f), next %s (%.3f); "
			"raw Y %.3f-%.3f, chroma spread %.3f%s",
			probe_runs_, kConversoes[melhor].nome, erro[melhor] / (3.0 * n),
			kConversoes[segundo].nome, erro[segundo] / (3.0 * n), y_min, y_max, croma,
			conclusiva ? "" : " (scene too flat, trying again later)");
	if(conclusiva)
	{
		probe_done_ = true;
		if(melhor != 0)
			LOGW("Color probe: the driver is NOT converting as BT.2020 limited range; "
					"10-bit colors are off");
	}
}

bool ToneMapper::DrawEye(GLuint target, int32_t width, int32_t height, bool pq,
		float sharpen, bool encode, bool extrapolate, bool fresh, const float *matrix,
		int eye, int slot, GLuint source)
{
	const bool deformar = source != 0 && warp_program_ != 0;
	// Quadro previsto, quando nao ha um novo para mostrar.
	//
	// A fonte entrega 60 por segundo e o painel mostra 120: metade das passadas
	// nao tem imagem nova. Em vez de repetir a anterior, a extensao da Qualcomm
	// produz das duas ultimas um quadro adiante no tempo. Meio passo, porque e
	// exatamente onde esta a passada intermediaria.
	//
	// E extrapolacao e nao interpolacao: nada e esperado, entao nao ha latencia
	// adicionada -- o preco e artefato no que foi previsto errado, nao atraso.
	const bool use_history = extrapolate && extrapolate_fn_ && target != 0
			&& EnsureHistory(width, height, slot);
	// O passo vem do Render: o quanto do periodo da fonte ja passou desde o
	// ultimo quadro real. Fora de (0, 1] desenha o quadro de verdade.
	if(!fresh && use_history && history_count_[slot] >= 2
			&& movimento_ && historico_vizinho_ && extrap_scale_ > 0.05f && extrap_scale_ <= extrap_limite_)
	{
		const int newest = history_newest_[slot];
		const int older = 1 - newest;

		// A conferencia de erro so acontece ate a primeira vez que da certo.
		//
		// Duas razoes. glGetError devolve *um* erro por chamada e o remove da
		// fila: um erro deixado para tras por qualquer chamada anterior seria
		// lido aqui como falha da extrapolacao, e o recurso se desligaria
		// sozinho, errado, logo na primeira tentativa. Por isso a fila e
		// esvaziada antes. E porque glGetError forca sincronizacao com o driver
		// em varias implementacoes -- pagar isso sessenta vezes por segundo para
		// confirmar o que ja foi confirmado seria caro a troco de nada.
		const bool checking = !logged_extrapolation_;
		if(checking)
			while(glGetError() != GL_NO_ERROR) { }

		// Acima de 1 fica em 1: nao se sabe se a extensao aceita escala maior, e
		// um pedido recusado deixaria na tela o que estava no swapchain.
		// Escala acima de 1 so depois de confirmada pelo driver: na primeira vez
		// que ela aparece, o erro do GL e conferido; recusada, o teto vira 1.
		const float escala = std::min(extrap_scale_, escala_max_ext_);
		const bool testar_acima = escala > 1.0f && !escala_acima_testada_;
		if(testar_acima)
			while(glGetError() != GL_NO_ERROR) { }
		// Com a mascara pronta, a previsao vai para uma textura nossa e a
		// composicao escreve no alvo; sem ela, direto no alvo como antes.
		const bool compor = MascaraPronta(slot, width, height);
		const GLuint saida = compor ? previsto_tex_[slot] : target;
		extrapolate_fn_(history_[slot][older], history_[slot][newest], saida, escala);
		if(compor && !Compor(target, slot, width, height))
		{
			// O alvo nao aceitou o framebuffer da composicao: desliga a
			// mascara de vez e preve direto no alvo.
			compor_ok_ = false;
			LOGW("Motion mask: could not draw into the swapchain image; predicting the whole frame");
			extrapolate_fn_(history_[slot][older], history_[slot][newest], target, escala);
		}
		if(testar_acima)
		{
			escala_acima_testada_ = true;
			const GLenum e = glGetError();
			if(e != GL_NO_ERROR)
			{
				escala_max_ext_ = 1.0f;
				LOGW("Frame extrapolation refused scale %.2f (gl 0x%x); predictions stop at one source frame",
						escala, e);
				extrapolate_fn_(history_[slot][older], history_[slot][newest], saida, 1.0f);
				if(compor)
					Compor(target, slot, width, height);
			}
			else
				LOGI("Frame extrapolation accepted scale %.2f; late frames keep moving", escala);
		}

		if(!checking)
			return true;

		const GLenum err = glGetError();
		logged_extrapolation_ = true;
		if(err == GL_NO_ERROR)
		{
			LOGI("First extrapolated frame handed to the compositor");
			return true;
		}
		// Falhou: cai no redesenho de sempre. Uma vez que falha, falha sempre,
		// entao desliga em vez de tentar a cada quadro.
		LOGW("glExtrapolateTex2DQCOM returned 0x%x; falling back to a full redraw", err);
		extrapolate_fn_ = nullptr;
	}

	// Alvo zero e a janela: no modo janela quem recebe o desenho e o
	// framebuffer padrao da GLSurfaceView, e nao ha textura para anexar.
	// A janela nao e desenhada direto na tela.
	//
	// O fragment shader declara duas saidas: a imagem e o historico da
	// extrapolacao. O framebuffer padrao tem uma so. Pela especificacao a
	// segunda deveria ser descartada em silencio, e no compositor e o que
	// acontece -- o modo imersivo sempre funcionou. Neste Adreno, com a tela
	// como alvo, o desenho vira GL_INVALID_OPERATION e nao sai nada. O diario
	// mostrou exatamente isso: 58 passadas desenhadas, `gl 0x502`, tela preta
	// com som. Declarar o draw buffer como GL_BACK nao resolveu.
	//
	// Entao a janela passa a usar o mesmo caminho do modo imersivo, que e o
	// caminho que funciona: desenha numa textura nossa e copia para a tela. A
	// copia e um blit de framebuffer, feito pelo proprio GL, e a 1600x900 ela
	// nao aparece na conta.
	const bool to_window = target == 0;
	// Uma passada instrumentada, a primeira da janela. Saber que houve erro nao
	// diz qual chamada o produziu, e essa diferenca ja custou duas builds.
	const bool depurar = to_window && !logged_window_;
	if(depurar)
		while(glGetError() != GL_NO_ERROR) { }
	if(to_window)
	{
		if(!EnsureWindowTarget(width, height))
		{
			if(!logged_fbo_failure_)
			{
				logged_fbo_failure_ = true;
				LOGE("Could not create the window target texture (%dx%d)", width, height);
			}
			return false;
		}
		target = window_tex_;
	}
	glBindFramebuffer(GL_FRAMEBUFFER, fbo_);

	// O historico e escrito na mesma passada, por um segundo anexo. Custa uma
	// gravacao a mais no fragment shader; copiar a tela depois custaria uma
	// passada inteira.
	bool write_history = fresh && use_history;
	int history_slot = 0;
	if(write_history)
	{
		history_slot = history_newest_[slot] < 0 ? 0 : 1 - history_newest_[slot];
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D,
				history_[slot][history_slot], 0);
	}
	else
	{
		// Solta um anexo que possa ter sobrado de um quadro anterior. Anexo
		// esquecido nao e ignorado por nao estar nos draw buffers: a completude
		// do framebuffer olha o que esta anexado, e nao o que sera escrito.
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
	}

	{
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
		GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);

		// Se o histórico for o culpado, ele sai -- e o video fica.
		//
		// Esta ordem importa e a primeira versao a tinha errada: o anexo do
		// historico entrava antes da checagem, entao qualquer incompatibilidade
		// dele (formato diferente do swapchain, ou um swapchain que venha como
		// camada de array, que nao se mistura com anexo 2D) derrubava o
		// framebuffer inteiro e a imagem sumia. Perder a extrapolacao e um
		// recurso a menos; perder o framebuffer e a tela preta que ja nos custou
		// uma noite. Entre os dois nao ha duvida.
		if(status != GL_FRAMEBUFFER_COMPLETE && write_history)
		{
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
			GLenum without = glCheckFramebufferStatus(GL_FRAMEBUFFER);
			if(without == GL_FRAMEBUFFER_COMPLETE)
			{
				if(!logged_extrapolation_)
				{
					logged_extrapolation_ = true;
					LOGW("The extrapolation history does not match the swapchain "
							"(status 0x%x); carrying on without extrapolated frames", status);
				}
				extrapolate_fn_ = nullptr;
				write_history = false;
				status = without;
			}
		}

		// O runtime pode entregar a imagem do swapchain como camada de um array
		// de texturas, e nao como GL_TEXTURE_2D -- e comum em runtimes de VR,
		// onde o array serve ao multiview. Anexar um array como 2D deixa o
		// framebuffer incompleto, entao a segunda tentativa o trata como camada.
		if(status != GL_FRAMEBUFFER_COMPLETE)
		{
			glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, target, 0, 0);
			status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
			if(status == GL_FRAMEBUFFER_COMPLETE && !logged_layered_)
			{
				logged_layered_ = true;
				LOGI("Swapchain handed over as a texture array; attached per layer");
			}
		}

		if(status != GL_FRAMEBUFFER_COMPLETE)
		{
			// Uma vez por sessao, com o codigo do status.
			//
			// A versao anterior registrava isto a cada frame: em poucos minutos
			// foram 1668 linhas identicas, que encheram o diario e empurraram
			// para fora justamente as linhas de abertura que diziam por que.
			// Erro dentro do loop de frame nao pode falar mais de uma vez -- ele
			// nao informa mais por repetir, so apaga o resto.
			if(!logged_fbo_failure_)
			{
				logged_fbo_failure_ = true;
				LOGE("Incomplete framebuffer in the tone mapper: status 0x%x, texture %u",
						status, target);
			}
			glBindFramebuffer(GL_FRAMEBUFFER, 0);
			return false;
		}
	}

	{
		// Sem o glDrawBuffers a posicao 1 do shader nao teria destino definido.
		// Com um alvo so, ela vai para GL_NONE e a segunda gravacao e
		// descartada pelo proprio GL -- e por isso que o shader pode escrever
		// nas duas sempre, sem variante.
		const GLenum both[] = { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1 };
		const GLenum single[] = { GL_COLOR_ATTACHMENT0 };
		glDrawBuffers(write_history ? 2 : 1, write_history ? both : single);

		// A passada escreve todo pixel dos alvos (quadrado cheio, sem mistura,
		// sem discard). Dizer isso ao driver poupa, numa GPU de ladrilhos como
		// a do Quest, carregar o conteudo antigo de cada alvo para a memoria do
		// chip antes de desenhar por cima -- um quadro inteiro de leitura por
		// alvo, por passada.
		glInvalidateFramebuffer(GL_FRAMEBUFFER, write_history ? 2 : 1, both);
	}

	glViewport(0, 0, width, height);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	glDisable(GL_CULL_FACE);

	if(deformar)
	{
		// A passada de um olho: nenhuma leitura da textura externa, nenhuma
		// curva PQ, nenhuma matriz de gamut. Uma amostra da imagem pronta, duas
		// do mapa pequeno, e a conta do deslocamento.
		glUseProgram(warp_program_);
		glUniform1i(wloc_encode_, encode ? 1 : 0);
		glUniform1i(wloc_dither_, pq ? 1 : 0);
		glUniform1f(wloc_eye_, (float)eye);
		glUniform1f(wloc_back_, stereo_back_);
		glUniform1f(wloc_front_, stereo_front_);
		glUniform1f(wloc_convergence_, convergence_);
		glUniform1f(wloc_janela_, janela_);
		const bool com_faixa = stats_ready_ && stats_has_prev_;
		glUniform1i(wloc_has_stats_, com_faixa ? 1 : 0);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, source);
		glUniform1i(wloc_source_, 0);
		glActiveTexture(GL_TEXTURE1);
		glBindTexture(GL_TEXTURE_2D, depth_tex_[depth_newest_]);
		glUniform1i(wloc_depth_, 1);
		glActiveTexture(GL_TEXTURE2);
		glBindTexture(GL_TEXTURE_2D, com_faixa ? stats_tex_[stats_newest_] : source);
		glUniform1i(wloc_stats_, 2);
		glActiveTexture(GL_TEXTURE0);
	}
	else
	{
		glUseProgram(program_);
		glUniformMatrix4fv(loc_tex_matrix_, 1, GL_FALSE, matrix);
		glUniform1i(loc_pq_, pq ? 1 : 0);
		glUniform1f(loc_target_nits_, target_nits_);
		glUniform1f(loc_source_peak_, source_peak_nits_ / target_nits_);
		glUniform1f(loc_sharpen_, sharpen);
		// O passo de um texel e o da fonte, que e tambem o do alvo: cada olho
		// tem alvo proprio do tamanho da imagem. Enquanto o alvo tinha o dobro
		// da largura era preciso dividir aqui, senao a nitidez amostrava dentro
		// do proprio pixel e praticamente sumia.
		glUniform2f(loc_texel_step_, width > 0 ? 1.0f / (float)width : 0.0f,
				height > 0 ? 1.0f / (float)height : 0.0f);
		glUniform1i(loc_encode_, encode ? 1 : 0);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_tex_);
		glUniform1i(loc_sampler_, 0);
	}

	const GLenum erro_antes = depurar ? glGetError() : GL_NO_ERROR;

	glBindVertexArray(vao_);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	const GLenum erro_desenho = depurar ? glGetError() : GL_NO_ERROR;

	if(write_history)
	{
		history_newest_[slot] = history_slot;
		if(history_count_[slot] < 2)
			history_count_[slot]++;
		history_gen_[slot]++;
		// Solta o anexo: a proxima passada pode ser sem historico, e um anexo
		// esquecido apontando para textura que sera reescrita e a receita do
		// framebuffer incompleto que ja custou uma sessao aqui.
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
	}
	glBindVertexArray(0);

	glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

	// A copia para a tela, no modo janela.
	GLenum erro_copia = GL_NO_ERROR;
	if(to_window)
	{
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo_);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
				GL_COLOR_BUFFER_BIT, GL_NEAREST);
		if(depurar)
			erro_copia = glGetError();
		// GL_NEAREST porque origem e destino tem o mesmo tamanho: nao ha o que
		// filtrar, e pedir filtragem so custaria.
	}

	// Uma linha, na primeira passada para a janela.
	//
	// Ate aqui o modo janela ficava mudo depois de "Tone mapper ready": nao
	// havia como distinguir "desenhou preto" de "nao desenhou". O erro do GL
	// vai junto porque uma janela preta com som e, quase sempre, uma chamada
	// recusada que ninguem leu.
	if(to_window && !logged_window_)
	{
		logged_window_ = true;
		const GLenum err = glGetError();
		LOGI("First window frame drawn: %dx%d, sharpen %.2f, pq %d | "
				"gl before 0x%x, draw 0x%x, blit 0x%x, after 0x%x",
				width, height, sharpen, pq ? 1 : 0,
				erro_antes, erro_desenho, erro_copia, err);
	}

	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	return true;
}

void ToneMapper::Destroy()
{
	if(attached_ && surface_texture_)
		ASurfaceTexture_detachFromGLContext(surface_texture_);
	attached_ = false;

	if(vbo_ != 0)
		glDeleteBuffers(1, &vbo_);
	if(vao_ != 0)
		glDeleteVertexArrays(1, &vao_);
	if(fbo_ != 0)
		glDeleteFramebuffers(1, &fbo_);
	for(int slot = 0; slot < 2; slot++)
	{
		if(history_[slot][0] != 0)
			glDeleteTextures(2, history_[slot]);
		history_[slot][0] = history_[slot][1] = 0;
		history_newest_[slot] = -1;
		history_count_[slot] = 0;
		history_width_[slot] = history_height_[slot] = 0;
	}
	extrapolate_fn_ = nullptr;

	if(window_tex_ != 0)
		glDeleteTextures(1, &window_tex_);
	window_tex_ = 0;
	window_w_ = window_h_ = 0;

	if(intermediate_tex_ != 0)
		glDeleteTextures(1, &intermediate_tex_);
	intermediate_tex_ = 0;
	intermediate_w_ = intermediate_h_ = 0;
	intermediate_valid_ = false;
	if(warp_program_ != 0)
		glDeleteProgram(warp_program_);
	warp_program_ = 0;

	if(probe_program_ != 0)
		glDeleteProgram(probe_program_);
	probe_program_ = 0;
	if(probe_tex_ != 0)
		glDeleteTextures(1, &probe_tex_);
	probe_tex_ = 0;
	if(amostra_tex_ != 0)
		glDeleteTextures(1, &amostra_tex_);
	amostra_tex_ = 0;
	if(amostra_fbo_ != 0)
		glDeleteFramebuffers(1, &amostra_fbo_);
	amostra_fbo_ = 0;
	if(amostra_pbo_ != 0)
		glDeleteBuffers(1, &amostra_pbo_);
	amostra_pbo_ = 0;
	for(int k = 0; k < 2; k++)
	{
		if(previsto_tex_[k] != 0)
		{
			glDeleteTextures(1, &previsto_tex_[k]);
			glDeleteTextures(2, mascara_tex_[k]);
		}
		previsto_tex_[k] = mascara_tex_[k][0] = mascara_tex_[k][1] = 0;
		mascara_w_[k] = mascara_h_[k] = 0;
		mascara_gen_[k] = -1;
	}
	if(prog_diff_) glDeleteProgram(prog_diff_);
	if(prog_dilata_) glDeleteProgram(prog_dilata_);
	if(prog_compor_) glDeleteProgram(prog_compor_);
	prog_diff_ = prog_dilata_ = prog_compor_ = 0;
	if(amostra_fence_)
		glDeleteSync(amostra_fence_);
	amostra_fence_ = nullptr;
	amostra_pendente_ = false;
	n_classes_ = 0;
	amostra_anterior_.clear();
	if(probe_fbo_ != 0)
		glDeleteFramebuffers(1, &probe_fbo_);
	probe_fbo_ = 0;
	probe_frames_ = probe_runs_ = 0;
	probe_done_ = false;

	if(stats_tex_[0] != 0)
		glDeleteTextures(2, stats_tex_);
	stats_tex_[0] = stats_tex_[1] = 0;
	stats_newest_ = 0;
	stats_has_prev_ = false;
	stats_ready_ = false;
	if(stats_program_ != 0)
		glDeleteProgram(stats_program_);
	stats_program_ = 0;

	if(depth_fbo_ != 0)
		glDeleteFramebuffers(1, &depth_fbo_);
	if(depth_tex_[0] != 0)
		glDeleteTextures(2, depth_tex_);
	depth_tex_[0] = depth_tex_[1] = 0;
	depth_fbo_ = 0;
	depth_newest_ = 0;
	depth_has_prev_ = false;
	depth_width_ = depth_height_ = 0;
	if(depth_program_ != 0)
		glDeleteProgram(depth_program_);
	depth_program_ = 0;

	ResetNeural();
	if(neural_fbo_ != 0)
		glDeleteFramebuffers(1, &neural_fbo_);
	if(neural_cap_tex_ != 0)
		glDeleteTextures(1, &neural_cap_tex_);
	if(neural_map_tex_ != 0)
		glDeleteTextures(1, &neural_map_tex_);
	if(neural_pbo_ != 0)
		glDeleteBuffers(1, &neural_pbo_);
	neural_fbo_ = neural_cap_tex_ = neural_map_tex_ = neural_pbo_ = 0;
	if(neural_program_ != 0)
		glDeleteProgram(neural_program_);
	neural_program_ = 0;
	logged_neural_ = logged_neural_failure_ = false;

	if(external_tex_ != 0)
		glDeleteTextures(1, &external_tex_);
	if(program_ != 0)
		glDeleteProgram(program_);
	vbo_ = vao_ = fbo_ = external_tex_ = program_ = 0;

	if(surface_texture_)
		ASurfaceTexture_release(surface_texture_);
	surface_texture_ = nullptr;
	has_frame_ = false;

	// O tone mapper e reaproveitado de sessao em sessao. O que so vale uma
	// vez por sessao volta ao comeco: o logged_extrapolation_ em particular e
	// tambem o "a extensao ja foi validada", e herdado ele pulava a checagem
	// de erro da sessao seguinte.
	last_timestamp_ = -1;
	frames_seen_ = frames_fresh_ = 0;
	logged_window_ = logged_extrapolation_ = logged_pts_ = false;
	real_newest_ns_ = 0;
	periodo_fonte_ns_ = 16666667;
	periodo_media_ns_ = 16666667.0;
	tamanho_real_medio_ = 0.0;
	diff_real_medio_ = 0.0;
	amostra_anterior_.clear();
	seq_n_ = -1;
	seq_quadros_ = 0;
	n_classes_ = 0;
	sem_trava_ = 0;
	historico_vizinho_ = false;
	movimento_ = false;
	diff_real_rapido_ = 0.0f;
	diffs_recentes_[0] = diffs_recentes_[1] = diffs_recentes_[2] = 0.0f;
	if(amostra_fence_)
		glDeleteSync(amostra_fence_);
	amostra_fence_ = nullptr;
	amostra_pendente_ = false;
	repeticoes_seguidas_ = 0;
	extrap_scale_ = 0.5f;
	cad_reais_ = cad_repeticoes_ = cad_previstas_ = cad_atrasadas_ = cad_sem_tamanho_ = 0;
	cad_maior_repeticao_ = 0;
	cad_menor_real_ = UINT32_MAX;
	logged_fbo_failure_ = logged_layered_ = logged_intermediate_ = false;
	logged_depth_ = logged_depth_failure_ = false;
}

} // namespace p5m

/**
 * Chamado pelo decodificador do chiaki (patch 0056) a cada quadro que entra,
 * com o pts que ele recebe e o tamanho em bytes.
 */
extern "C" __attribute__((visibility("default"))) void p5m_video_sample_size(uint64_t pts, uint32_t bytes)
{
	const int i = (int)(pts % p5m::kAnelTamanhos);
	// Invalida antes de escrever: quem ler no meio ve pts diferente e ignora.
	p5m::g_tamanho_pts[i].store(0, std::memory_order_release);
	p5m::g_tamanho_bytes[i].store(bytes, std::memory_order_release);
	p5m::g_tamanho_pts[i].store(pts + 1, std::memory_order_release);

}

/**
 * Chamado pelo decodificador (patch 0057) antes de entregar cada quadro a
 * Surface: espera a tela pegar o anterior, no maximo 12 ms (uma passada a
 * 90 Hz). So quando a tela e nossa -- no caminho direto ninguem avisa o que
 * foi pego, e esperar so atrasaria.
 */
extern "C" __attribute__((visibility("default"))) void p5m_video_wait_for_display(uint64_t pts)
{
	static uint64_t ultimo = 0; // pts + 1 do ultimo entregue
	static int entregues = 0, esperas = 0, estouros = 0;
	static int64_t espera_total = 0, espera_max = 0, janela_ns = 0;
	const int64_t agora = p5m::AgoraNs();
	// Sessao nova: o pts volta ao comeco.
	if(pts + 1 < ultimo)
		ultimo = 0;
	const bool tela_ativa = agora - p5m::g_pego_ns.load(std::memory_order_acquire) < 100000000;
	if(ultimo != 0 && tela_ativa)
	{
		std::unique_lock<std::mutex> lock(p5m::g_tela_mutex);
		if(p5m::g_pts_pego < ultimo)
		{
			const bool pegou = p5m::g_tela_cv.wait_for(lock, std::chrono::milliseconds(12),
					[]{ return p5m::g_pts_pego >= ultimo; });
			const int64_t esperou = p5m::AgoraNs() - agora;
			esperas++;
			espera_total += esperou;
			if(esperou > espera_max)
				espera_max = esperou;
			if(!pegou)
				estouros++;
		}
	}
	ultimo = pts + 1;
	entregues++;
	if(janela_ns == 0)
		janela_ns = agora;
	if(agora - janela_ns >= 10000000000LL)
	{
		if(tela_ativa)
			LOGI("Display pacing 10s: %d frames, %d held for the screen (avg %.1f ms, max %.1f), %d gave up",
					entregues, esperas, esperas ? espera_total / 1e6 / esperas : 0.0, espera_max / 1e6, estouros);
		entregues = esperas = estouros = 0;
		espera_total = espera_max = 0;
		janela_ns = agora;
	}
}

namespace p5m {
void DefinirVerdadeDoBanco(bool ligado)
{
	g_verdade_no_pts.store(ligado, std::memory_order_relaxed);
}
} // namespace p5m
