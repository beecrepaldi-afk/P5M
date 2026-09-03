// SPDX-License-Identifier: AGPL-3.0-only
//
// Converte o vídeo HDR do console para SDR, na GPU.
//
// Existe porque o decodificador do Quest 3 ignora o pedido de mapeamento de
// tons: ele entrega P010 com curva PQ intacta (color-transfer=6), e uma curva
// PQ lida como se fosse SDR estoura os brancos em qualquer cena clara.
//
// O custo é uma passada de GPU e o fim do caminho sem cópia: em vez de o
// MediaCodec escrever direto no swapchain do compositor, ele escreve numa
// SurfaceTexture que este código lê, converte e desenha num swapchain comum.
// É por isso que o modo direto continua existindo — quem quer latência mínima
// e não se incomoda com faixas em gradiente fica com ele.
#pragma once

#include <android/surface_texture.h>
// ASurfaceTexture_fromSurfaceTexture vive no header JNI, nao no principal.
#include <android/surface_texture_jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
// GL_TEXTURE_EXTERNAL_OES e extensao OES: nao esta no gl3.h.
#include <GLES2/gl2ext.h>
#include <stdint.h>

namespace p5m {

class ToneMapper
{
public:
	/**
	 * Compila o programa e cria a textura externa.
	 *
	 * Precisa rodar na thread do frame loop, com o contexto EGL corrente: a
	 * textura externa pertence ao contexto em que nasce.
	 */
	bool Init();

	/** Liga a SurfaceTexture do Java ao contexto GL desta thread. */
	bool Attach(ASurfaceTexture *texture);

	/**
	 * Consome o frame mais recente e desenha no destino.
	 *
	 * `target` e a textura do swapchain, ja adquirida, ou zero para desenhar no
	 * framebuffer padrao -- o caso da janela, onde nao ha swapchain.
	 *
	 * `sharpen` e a intensidade da mascara de nitidez, 0 para nenhuma. Chega
	 * como numero, e nao como degrau, porque quem escolhe os degraus e a
	 * interface -- aqui so existe a manopla.
	 *
	 * `encode` diz se o shader precisa aplicar a curva do sRGB antes de
	 * escrever. Falso quando o alvo e um swapchain sRGB, que a aplica sozinho.
	 *
	 * `pq` e true quando a fonte e PQ (10 bits), false para SDR em 8 bits.
	 *
	 * Devolve false quando nao havia frame novo nem antigo para desenhar.
	 */
	bool Render(GLuint target, int32_t width, int32_t height, bool pq, float sharpen,
			bool encode, bool extrapolate = false);

	/**
	 * Liga o olho sintetizado.
	 *
	 * `strength` e a disparidade maxima em fracao da largura da fonte, e
	 * `convergence` a profundidade que fica no plano da tela. `groundDown`
	 * diz se a borda de baixo do alvo e a parte de baixo da cena -- a pista
	 * mais forte da estimativa depende disso, e o compositor pode estar
	 * invertendo a imagem.
	 *
	 * O alvo precisa ter o dobro da largura da fonte: cada olho ocupa uma
	 * metade e recebe a imagem inteira. Sem isso cada olho ficaria com metade
	 * da resolucao horizontal.
	 */
	void SetStereo(bool on, float strength, float convergence, bool groundDown)
	{
		stereo_ = on;
		stereo_strength_ = strength;
		convergence_ = convergence;
		ground_sign_ = groundDown ? 1.0f : -1.0f;
	}

	/**
	 * Liga o histórico usado pela extrapolação de quadros.
	 *
	 * `format` precisa ser o mesmo do swapchain de destino: a extensão exige
	 * que as três texturas tenham dimensão e formato iguais, e um formato
	 * diferente faria a chamada falhar em silêncio em vez de dar erro.
	 *
	 * Devolve false quando o aparelho não tem `GL_QCOM_frame_extrapolation`, e
	 * aí a extrapolação simplesmente não existe -- o caminho normal continua.
	 */
	bool EnableExtrapolation(GLenum format);

	bool ExtrapolationReady() const { return extrapolate_fn_ != nullptr; }

	void Destroy();

	/** Nits em que o branco de referência do SDR é mapeado. */
	void SetTargetNits(float nits) { target_nits_ = nits; }

private:
	bool CompileProgram();
	bool EnsureHistory(int32_t width, int32_t height);

	ASurfaceTexture *surface_texture_ = nullptr;
	bool attached_ = false;
	bool has_frame_ = false;
	// Erro dentro do loop de frame fala uma vez: repetido, so afoga o log.
	bool logged_fbo_failure_ = false;
	bool logged_layered_ = false;

	GLuint program_ = 0;
	GLuint external_tex_ = 0;
	GLuint fbo_ = 0;
	GLuint vbo_ = 0;
	GLuint vao_ = 0;

	GLint loc_tex_matrix_ = -1;
	GLint loc_sampler_ = -1;
	GLint loc_pq_ = -1;
	GLint loc_target_nits_ = -1;
	GLint loc_sharpen_ = -1;
	GLint loc_encode_ = -1;
	GLint loc_texel_step_ = -1;
	GLint loc_stereo_ = -1;
	GLint loc_depth_tex_ = -1;

	// -- Passada da profundidade -------------------------------------------
	//
	// Alvo proprio, a um quarto da resolucao em cada eixo. Duas texturas em
	// rodizio porque a passada le a anterior -- para suavizar no tempo e para
	// saber o que ficou parado -- e escreve na outra.
	GLuint depth_program_ = 0;
	GLuint depth_fbo_ = 0;
	GLuint depth_tex_[2] = { 0, 0 };
	int depth_newest_ = 0;
	bool depth_has_prev_ = false;
	int32_t depth_width_ = 0;
	int32_t depth_height_ = 0;
	bool logged_depth_ = false;
	bool logged_depth_failure_ = false;

	GLint dloc_tex_matrix_ = -1;
	GLint dloc_texel_step_ = -1;
	GLint dloc_sampler_ = -1;
	GLint dloc_prev_ = -1;
	GLint dloc_has_prev_ = -1;
	GLint dloc_ground_sign_ = -1;
	GLint dloc_convergence_ = -1;

	bool EnsureDepthTargets(int32_t width, int32_t height);
	void RenderDepth(const float *matrix, int32_t width, int32_t height);
	GLint loc_stereo_strength_ = -1;
	GLint loc_convergence_ = -1;

	// Estado do modo 3D, posto pelo SetStereo e lido pelo Render.
	bool stereo_ = false;
	float stereo_strength_ = 0.0f;
	float convergence_ = 0.35f;
	float ground_sign_ = 1.0f;

	// -- Extrapolação de quadros (GL_QCOM_frame_extrapolation) --------------
	//
	// A fonte entrega 60 quadros por segundo e o painel mostra 120: metade das
	// passadas do frame loop não tem imagem nova, e hoje redesenham a anterior.
	// A extensão da Qualcomm produz, dessas duas últimas, um quadro *previsto*
	// -- extrapolação, não interpolação, então nada é esperado e nenhuma
	// latência é adicionada.
	//
	// O histórico é nosso e não do swapchain: as imagens do swapchain giram e
	// pertencem ao compositor, e ler uma que ele já está compondo não é
	// contrato que exista.
	typedef void (*ExtrapolateFn)(GLuint src1, GLuint src2, GLuint output, GLfloat scale);
	ExtrapolateFn extrapolate_fn_ = nullptr;
	GLenum history_format_ = 0;
	GLuint history_[2] = {0, 0};
	int32_t history_width_ = 0;
	int32_t history_height_ = 0;
	// Qual das duas guarda o quadro mais recente, e quantas já foram escritas:
	// com uma só não há de onde extrapolar, e prever a partir de uma textura
	// ainda em branco desenharia lixo no primeiro quadro da sessão.
	int history_newest_ = -1;
	int history_count_ = 0;
	bool logged_extrapolation_ = false;
	// Timestamp do ultimo buffer prendido. E o que separa quadro novo de quadro
	// repetido: o updateTexImage devolve sucesso nos dois casos.
	int64_t last_timestamp_ = 0;
	bool logged_cadence_ = false;
	int frames_seen_ = 0;
	int frames_fresh_ = 0;

	// 200 nits: o branco de referência do SDR fica em 100, e o conteúdo de jogo
	// costuma ser gravado com destaques bem acima disso. Mapear o pico do PQ
	// direto em 100 apagaria o brilho todo; 200 mantém alguma sensação de
	// destaque sem estourar.
	float target_nits_ = 200.0f;
};

} // namespace p5m
