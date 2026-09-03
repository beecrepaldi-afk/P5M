// SPDX-License-Identifier: AGPL-3.0-only
#include "tone_mapper.h"
#include "log.h"

#include <cstring>
#include <string>

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
uniform float uConvergence;

in vec2 vUv;
in vec2 vDx;
in vec2 vDy;
in vec2 vScreen;
out vec4 fragColor;

void main()
{
	vec3 c = texture(uTexture, vUv).rgb;

	// Amostras largas: e nelas que moram as pistas de cena -- textura contra
	// neblina, cor viva contra cor lavada. Espalhadas de proposito, porque mapa
	// de profundidade com borda dura produz halo em volta de tudo.
	vec3 n = texture(uTexture, vUv - vDy * 8.0).rgb;
	vec3 s = texture(uTexture, vUv + vDy * 8.0).rgb;
	vec3 w = texture(uTexture, vUv - vDx * 8.0).rgb;
	vec3 e = texture(uTexture, vUv + vDx * 8.0).rgb;

	const vec3 media = vec3(0.3333);
	float detalhe = clamp((abs(dot(n - c, media)) + abs(dot(s - c, media))
			+ abs(dot(w - c, media)) + abs(dot(e - c, media))) * 2.0, 0.0, 1.0);

	float mx = max(max(c.r, c.g), c.b);
	float mn = min(min(c.r, c.g), c.b);
	float sat = (mx - mn) / max(mx, 1e-4);

	float chao = clamp(uGroundSign > 0.0 ? 1.0 - vScreen.y : vScreen.y, 0.0, 1.0);

	// A pista de detalhe pesa menos do que pesava.
	//
	// Ela empurra para perto o que tem alta frequencia, e texto e a coisa de
	// maior frequencia que existe num quadro -- entao legenda e HUD saltavam
	// para a frente, separados do fundo. Foi o defeito relatado no primeiro
	// teste, e "o texto fica estranho" e exatamente o que essa pista causa.
	float d = clamp(0.60 * chao + 0.15 * detalhe + 0.25 * sat, 0.0, 1.0);

	// Contraste em escala fina, de um texel: e o que separa texto de textura.
	// Parede de tijolo tem detalhe largo; letra tem borda dura de um pixel para
	// o outro. Esta e a unica medida que distingue os dois.
	vec3 fn = texture(uTexture, vUv - vDy).rgb;
	vec3 fs = texture(uTexture, vUv + vDy).rgb;
	vec3 fw = texture(uTexture, vUv - vDx).rgb;
	vec3 fe = texture(uTexture, vUv + vDx).rgb;
	float fino = (abs(dot(fn - c, media)) + abs(dot(fs - c, media))
			+ abs(dot(fw - c, media)) + abs(dot(fe - c, media))) * 0.25;

	float luma = dot(c, vec3(0.299, 0.587, 0.114));
	float parado = 1.0;
	float mudanca = 0.0;
	if(uHasPrev == 1)
	{
		vec4 ant = texture(uPrev, vScreen);
		mudanca = abs(luma - ant.g);
		// Parado e o que nao mudou nada de um quadro para o outro. Num jogo, o
		// mundo sempre se mexe um pouco -- camera, animacao, luz --, e o que
		// fica exatamente igual e interface desenhada por cima.
		parado = 1.0 - smoothstep(0.004, 0.03, mudanca);
	}

	// Interface: borda dura E imovel. Uma coisa so nao basta -- textura fina
	// tambem tem borda dura, e ceu tambem fica parado.
	float interface_ = smoothstep(0.05, 0.16, fino) * parado;
	d = mix(d, uConvergence, interface_ * 0.9);

	if(uHasPrev == 1)
	{
		vec4 ant = texture(uPrev, vScreen);
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

	fragColor = vec4(clamp(d, 0.0, 1.0), luma, 0.0, 1.0);
}
)";

const char *kFragmentShader = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

uniform samplerExternalOES uTexture;
uniform float uTargetNits;
uniform int uPq;
uniform float uSharpen;
uniform int uEncode;

// A mesma matriz do vertice. Declarada aqui tambem porque no modo 3D o
// remapeamento acontece por pixel: a coordenada de origem depende de qual
// metade do alvo o pixel ocupa, e isso o vertice nao tem como resolver.
uniform mat4 uTexMatrix;

uniform int uStereo;
// Disparidade maxima, em fracao da largura da fonte. O teto vem da distancia
// interpupilar lida do runtime: acima dela os olhos teriam de divergir, o que
// nao e desconforto, e impossivel.
uniform float uStereoStrength;
// Que profundidade fica exatamente no plano da tela. O que estiver a frente
// salta para fora, o que estiver atras afunda.
uniform float uConvergence;
// Para que lado do quadro fica o chao: +1 quando a borda de baixo do alvo e a
// parte de baixo da cena, -1 quando o compositor inverte a imagem.
// O mapa de profundidade, pronto, vindo da passada pequena. Ler daqui custa
// uma amostra; calcular custava nove, e em dobro de pixels.
uniform sampler2D uDepthTex;

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
vec3 Bt2020ToBt709(vec3 c)
{
	const mat3 m = mat3(
		 1.6605, -0.1246, -0.0182,
		-0.5876,  1.1329, -0.1006,
		-0.0728, -0.0083,  1.1187);
	return m * c;
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
	vec2 uv = vUv;
	if(uStereo == 1)
	{
		// Metade esquerda do alvo e o olho esquerdo. Dentro da metade a
		// coordenada volta a cobrir a fonte inteira, entao cada olho recebe a
		// imagem toda -- e nao meia imagem, que e o que aconteceria se o alvo
		// tivesse a largura da fonte.
		float lado = vScreen.x < 0.5 ? -1.0 : 1.0;
		vec2 meia = vec2(fract(vScreen.x * 2.0), vScreen.y);

		// O deslocamento acontece no espaco normalizado, antes da matriz da
		// SurfaceTexture. E o mesmo espaco em que o mapa de profundidade foi
		// desenhado, entao os dois se enderecam pela mesma coordenada -- e a
		// matriz, que carrega recorte e orientacao do buffer, entra uma vez so
		// no fim, sobre o resultado.
		//
		// Meia disparidade para cada olho, e nao a inteira num deles: assim a
		// imagem nao anda para o lado quando o efeito liga, e o erro se reparte
		// entre os dois em vez de pesar todo num.
		float d = texture(uDepthTex, meia).r;
		meia.x += lado * 0.5 * uStereoStrength * (d - uConvergence);
		uv = (uTexMatrix * vec4(meia, 0.0, 1.0)).xy;
	}

	vec3 rgb = Sharpen(uv);

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
		// Fonte SDR: so linearizar a curva de 8 bits, sem mapear nada.
		fragColor = vec4(pow(max(rgb, vec3(0.0)), vec3(2.2)), 1.0);
		historyColor = fragColor;
		return;
	}

	vec3 nits = PqToNits(rgb);

	// Reinhard estendido, normalizado pelo alvo. Comprime as altas luzes em vez
	// de cortar: cortar em 1.0 transformaria todo destaque num borrao branco
	// chapado, que e justamente o defeito que se quer eliminar.
	vec3 scaled = nits / uTargetNits;
	const float white = 4.0;
	vec3 mapped = scaled * (1.0 + scaled / (white * white)) / (1.0 + scaled);

	vec3 rec709 = clamp(Bt2020ToBt709(mapped), 0.0, 1.0);
	fragColor = vec4(uEncode == 1 ? EncodeSrgb(rec709) : rec709, 1.0);
	historyColor = fragColor;
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
	loc_sharpen_ = glGetUniformLocation(program_, "uSharpen");
	loc_texel_step_ = glGetUniformLocation(program_, "uTexelStep");
	loc_encode_ = glGetUniformLocation(program_, "uEncode");
	loc_stereo_ = glGetUniformLocation(program_, "uStereo");
	loc_stereo_strength_ = glGetUniformLocation(program_, "uStereoStrength");
	loc_convergence_ = glGetUniformLocation(program_, "uConvergence");
	loc_depth_tex_ = glGetUniformLocation(program_, "uDepthTex");

	// O programa da profundidade: mesmo vertice, fragmento proprio.
	//
	// Falhar aqui nao derruba o resto -- o 3D fica sem mapa e o video continua
	// normal, que e muito melhor do que a sessao nao abrir por causa de um
	// recurso opcional.
	GLuint dvs = CompileShader(GL_VERTEX_SHADER, kVertexShader);
	GLuint dfs = dvs ? CompileShader(GL_FRAGMENT_SHADER, kDepthShader) : 0;
	if(dvs && dfs)
	{
		depth_program_ = glCreateProgram();
		glAttachShader(depth_program_, dvs);
		glAttachShader(depth_program_, dfs);
		glBindAttribLocation(depth_program_, 0, "aPos");
		glLinkProgram(depth_program_);
		GLint dok = GL_FALSE;
		glGetProgramiv(depth_program_, GL_LINK_STATUS, &dok);
		if(dok != GL_TRUE)
		{
			GLint len = 0;
			glGetProgramiv(depth_program_, GL_INFO_LOG_LENGTH, &len);
			std::string info((size_t)(len > 0 ? len : 1), '\0');
			glGetProgramInfoLog(depth_program_, len, nullptr, &info[0]);
			LOGE("Failed to link the depth program: %s", info.c_str());
			glDeleteProgram(depth_program_);
			depth_program_ = 0;
		}
		else
		{
			dloc_tex_matrix_ = glGetUniformLocation(depth_program_, "uTexMatrix");
			dloc_texel_step_ = glGetUniformLocation(depth_program_, "uTexelStep");
			dloc_sampler_ = glGetUniformLocation(depth_program_, "uTexture");
			dloc_prev_ = glGetUniformLocation(depth_program_, "uPrev");
			dloc_has_prev_ = glGetUniformLocation(depth_program_, "uHasPrev");
			dloc_ground_sign_ = glGetUniformLocation(depth_program_, "uGroundSign");
			dloc_convergence_ = glGetUniformLocation(depth_program_, "uConvergence");
		}
	}
	if(dvs)
		glDeleteShader(dvs);
	if(dfs)
		glDeleteShader(dfs);
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

bool ToneMapper::EnsureHistory(int32_t width, int32_t height)
{
	if(history_[0] != 0 && history_width_ == width && history_height_ == height)
		return true;

	if(history_[0] != 0)
		glDeleteTextures(2, history_);
	history_[0] = history_[1] = 0;
	history_newest_ = -1;
	history_count_ = 0;

	while(glGetError() != GL_NO_ERROR) { }
	glGenTextures(2, history_);
	for(int i = 0; i < 2; i++)
	{
		glBindTexture(GL_TEXTURE_2D, history_[i]);
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
	history_width_ = width;
	history_height_ = height;
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

	glGenTextures(2, depth_tex_);
	for(int i = 0; i < 2; i++)
	{
		glBindTexture(GL_TEXTURE_2D, depth_tex_[i]);
		glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
		// Linear na leitura: e o que faz o mapa pequeno voltar suave na passada
		// principal, sem uma unica amostra a mais.
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}
	glBindTexture(GL_TEXTURE_2D, 0);

	if(depth_fbo_ == 0)
		glGenFramebuffers(1, &depth_fbo_);

	depth_width_ = w;
	depth_height_ = h;
	if(!logged_depth_)
	{
		logged_depth_ = true;
		LOGI("Depth pass: %dx%d (a quarter of %dx%d in each axis)", w, h, width, height);
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
	glUniform1f(dloc_convergence_, convergence_);
	glUniform1i(dloc_has_prev_, depth_has_prev_ ? 1 : 0);

	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_tex_);
	glUniform1i(dloc_sampler_, 0);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, depth_tex_[depth_newest_]);
	glUniform1i(dloc_prev_, 1);

	glBindVertexArray(vao_);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glBindVertexArray(0);

	glActiveTexture(GL_TEXTURE0);
	depth_newest_ = destino;
	depth_has_prev_ = true;
}

bool ToneMapper::Render(GLuint target, int32_t width, int32_t height, bool pq,
		float sharpen, bool encode, bool extrapolate)
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

	// Uma vez, depois de algumas centenas de passadas: quantas tinham quadro
	// novo. Com fonte de 60 e painel de 120 a conta deveria dar perto de
	// metade, e e essa suposicao que a extrapolacao inteira assume. Se sair
	// muito longe disso, o problema nao esta no efeito e sim na cadencia.
	if(!logged_cadence_)
	{
		frames_seen_++;
		if(fresh)
			frames_fresh_++;
		if(frames_seen_ >= 600)
		{
			logged_cadence_ = true;
			LOGI("Cadence: %d of %d passes had a fresh frame (%.0f%%)",
					frames_fresh_, frames_seen_,
					100.0 * frames_fresh_ / (double)frames_seen_);
		}
	}

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
			&& EnsureHistory(width, height);
	if(!fresh && use_history && history_count_ >= 2)
	{
		const int newest = history_newest_;
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

		extrapolate_fn_(history_[older], history_[newest], target, 0.5f);

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
		LOGW("glExtrapolateTex2DQCOM devolveu 0x%x; voltando ao redesenho", err);
		extrapolate_fn_ = nullptr;
	}

	float matrix[16];
	ASurfaceTexture_getTransformMatrix(surface_texture_, matrix);

	// A profundidade primeiro, e num alvo proprio. Tem de acontecer antes de o
	// framebuffer de destino ser preso: ela usa o seu, e trocar de framebuffer
	// no meio da passada principal seria pior do que fazer as duas em ordem.
	const int32_t fonte_w = stereo_ ? width / 2 : width;
	if(stereo_)
		RenderDepth(matrix, fonte_w, height);

	// Alvo zero e a janela: no modo janela quem recebe o desenho e o
	// framebuffer padrao da GLSurfaceView, e nao ha textura para anexar.
	const bool to_window = target == 0;
	glBindFramebuffer(GL_FRAMEBUFFER, to_window ? 0 : fbo_);

	// O historico e escrito na mesma passada, por um segundo anexo. Custa uma
	// gravacao a mais no fragment shader; copiar a tela depois custaria uma
	// passada inteira.
	bool write_history = fresh && use_history;
	int history_slot = 0;
	if(write_history)
	{
		history_slot = history_newest_ < 0 ? 0 : 1 - history_newest_;
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D,
				history_[history_slot], 0);
	}
	else if(!to_window)
	{
		// Solta um anexo que possa ter sobrado de um quadro anterior. Anexo
		// esquecido nao e ignorado por nao estar nos draw buffers: a completude
		// do framebuffer olha o que esta anexado, e nao o que sera escrito.
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
	}

	if(!to_window)
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
		if(!to_window)
			glDrawBuffers(write_history ? 2 : 1, write_history ? both : single);
	}

	glViewport(0, 0, width, height);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	glDisable(GL_CULL_FACE);

	glUseProgram(program_);
	glUniformMatrix4fv(loc_tex_matrix_, 1, GL_FALSE, matrix);
	glUniform1i(loc_pq_, pq ? 1 : 0);
	glUniform1f(loc_target_nits_, target_nits_);
	glUniform1f(loc_sharpen_, sharpen);
	// O passo de um texel e o da FONTE, e nao o do alvo. Com o alvo do dobro da
	// largura no modo 3D, usar a largura dele daria meio texel -- a nitidez
	// amostraria dentro do proprio pixel e praticamente sumiria, e as amostras
	// da profundidade encolheriam junto.
	float largura_fonte = stereo_ ? (float)width * 0.5f : (float)width;
	glUniform2f(loc_texel_step_, largura_fonte > 0.0f ? 1.0f / largura_fonte : 0.0f,
			height > 0 ? 1.0f / (float)height : 0.0f);
	glUniform1i(loc_encode_, encode ? 1 : 0);
	glUniform1i(loc_stereo_, stereo_ ? 1 : 0);
	glUniform1f(loc_stereo_strength_, stereo_strength_);
	glUniform1f(loc_convergence_, convergence_);
	if(stereo_ && depth_tex_[depth_newest_] != 0)
	{
		glActiveTexture(GL_TEXTURE1);
		glBindTexture(GL_TEXTURE_2D, depth_tex_[depth_newest_]);
		glUniform1i(loc_depth_tex_, 1);
		glActiveTexture(GL_TEXTURE0);
	}

	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_tex_);
	glUniform1i(loc_sampler_, 0);

	glBindVertexArray(vao_);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

	if(write_history)
	{
		history_newest_ = history_slot;
		if(history_count_ < 2)
			history_count_++;
		// Solta o anexo: a proxima passada pode ser sem historico, e um anexo
		// esquecido apontando para textura que sera reescrita e a receita do
		// framebuffer incompleto que ja custou uma sessao aqui.
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
	}
	glBindVertexArray(0);

	glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
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
	if(history_[0] != 0)
		glDeleteTextures(2, history_);
	history_[0] = history_[1] = 0;
	history_newest_ = -1;
	history_count_ = 0;
	history_width_ = history_height_ = 0;
	extrapolate_fn_ = nullptr;

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

	if(external_tex_ != 0)
		glDeleteTextures(1, &external_tex_);
	if(program_ != 0)
		glDeleteProgram(program_);
	vbo_ = vao_ = fbo_ = external_tex_ = program_ = 0;

	if(surface_texture_)
		ASurfaceTexture_release(surface_texture_);
	surface_texture_ = nullptr;
	has_frame_ = false;
}

} // namespace p5m
