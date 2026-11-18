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
#include <vector>

namespace p5m {

/** Banco de ensaio: conferir a classificacao contra a verdade no pts. */
void DefinirVerdadeDoBanco(bool ligado);

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
			bool encode, bool extrapolate = false, GLuint target_right = 0);

	/**
	 * Liga o olho sintetizado.
	 *
	 * `back` e `front` sao a disparidade maxima entre os olhos atras e a
	 * frente da tela, em fracao da largura da fonte, e `convergence` a
	 * profundidade que fica no plano da tela. `groundDown`
	 * diz se a borda de baixo do alvo e a parte de baixo da cena -- a pista
	 * mais forte da estimativa depende disso, e o compositor pode estar
	 * invertendo a imagem.
	 *
	 * Cada olho tem um alvo proprio, do tamanho da fonte, passado ao Render
	 * como `target` e `target_right`. Ja foi um alvo unico do dobro da largura,
	 * com cada olho numa metade: funcionava, mas obrigava a submeter cada
	 * camada como recorte de uma textura maior, e sobre um recorte o filtro de
	 * nitidez do compositor produziu um X atravessando a tela. Dois alvos
	 * inteiros custam o mesmo numero de pixels e devolvem o MQSR ao 3D.
	 */
	void SetStereo(bool on, float back, float front, float convergence, bool groundDown,
			float janela = 0.0f)
	{
		stereo_ = on;
		janela_ = janela;
		stereo_back_ = back;
		stereo_front_ = front;
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


private:
	bool CompileProgram();
	bool EnsureHistory(int32_t width, int32_t height, int slot);
	/**
	 * Desenha um alvo. `source` diferente de zero e o intermediario do 3D, e
	 * entao quem desenha e a passada dos olhos; zero e o caminho de sempre,
	 * direto da textura externa.
	 */
	bool DrawEye(GLuint target, int32_t width, int32_t height, bool pq, float sharpen,
			bool encode, bool extrapolate, bool fresh, const float *matrix,
			int eye, int slot, GLuint source = 0);
	bool EnsureWindowTarget(int32_t width, int32_t height);

	ASurfaceTexture *surface_texture_ = nullptr;
	bool attached_ = false;
	bool has_frame_ = false;
	// Erro dentro do loop de frame fala uma vez: repetido, so afoga o log.
	bool logged_fbo_failure_ = false;
	bool logged_layered_ = false;

	GLuint program_ = 0;
	GLuint external_tex_ = 0;
	GLuint fbo_ = 0;
	/**
	 * Textura em que a janela e desenhada antes de ir para a tela.
	 *
	 * O modo janela nao desenha mais direto no framebuffer padrao. Ver o
	 * comentario em Render(): o shader tem duas saidas e o driver Adreno recusa
	 * isso quando o alvo e a tela.
	 */
	GLuint window_tex_ = 0;
	int32_t window_w_ = 0;
	int32_t window_h_ = 0;
	GLuint vbo_ = 0;
	GLuint vao_ = 0;

	GLint loc_tex_matrix_ = -1;
	GLint loc_sampler_ = -1;
	GLint loc_pq_ = -1;
	GLint loc_target_nits_ = -1;
	GLint loc_source_peak_ = -1;
	GLint loc_sharpen_ = -1;
	GLint loc_encode_ = -1;
	GLint loc_texel_step_ = -1;

	// -- Passada dos olhos, no 3D -------------------------------------------
	//
	// A imagem e mapeada e afiada UMA vez, num alvo intermediario sRGB, e cada
	// olho so a deforma. Antes os dois olhos refaziam a conversao de gamut, a
	// curva PQ e as cinco amostras da nitidez em tela cheia, para um resultado
	// que so difere pelo deslocamento lateral.
	GLuint warp_program_ = 0;
	GLuint intermediate_tex_ = 0;
	int32_t intermediate_w_ = 0;
	int32_t intermediate_h_ = 0;
	// Se ja ha imagem util nele: o quadro repetido redesenha os olhos sem
	// refazer a passada de cima, e no primeiro quadro nao ha o que ler.
	bool intermediate_valid_ = false;
	bool logged_intermediate_ = false;

	GLint wloc_source_ = -1;
	GLint wloc_depth_ = -1;
	GLint wloc_stats_ = -1;
	GLint wloc_has_stats_ = -1;
	GLint wloc_encode_ = -1;
	GLint wloc_dither_ = -1;
	GLint wloc_eye_ = -1;
	GLint wloc_back_ = -1;
	GLint wloc_front_ = -1;
	GLint wloc_convergence_ = -1;
	GLint wloc_janela_ = -1;

	// -- Faixa do mapa, num pixel -------------------------------------------
	//
	// Piso e teto do que o mapa esta usando neste quadro, para esticar a faixa
	// util. Duas texturas de um pixel em rodizio, porque a medida e suavizada
	// no tempo e ler e escrever a mesma e indefinido.
	GLuint stats_program_ = 0;
	GLuint stats_tex_[2] = { 0, 0 };
	int stats_newest_ = 0;
	bool stats_has_prev_ = false;
	bool stats_ready_ = false;
	float stats_lod_ = 0.0f;

	GLint sloc_depth_ = -1;
	GLint sloc_prev_ = -1;
	GLint sloc_has_prev_ = -1;
	GLint sloc_lod_ = -1;

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
	GLint dloc_neural_ = -1;
	GLint dloc_has_neural_ = -1;

	// Profundidade da rede neural (ver profundidade_neural.h). A captura
	// reduz o video a 512x288 num alvo proprio e o le de volta por um PBO,
	// sem esperar a GPU; o mapa que volta da rede sobe numa textura de 8 bits
	// que a passada de profundidade le no lugar das pistas.
	enum class EstadoNeural { Livre, Lendo, NaRede };
	GLuint neural_program_ = 0;
	GLint nloc_tex_matrix_ = -1;
	GLint nloc_texel_step_ = -1;
	GLint nloc_sampler_ = -1;
	GLuint neural_fbo_ = 0;
	GLuint neural_cap_tex_ = 0;
	GLuint neural_pbo_ = 0;
	GLuint neural_map_tex_ = 0;
	GLsync neural_fence_ = nullptr;
	EstadoNeural neural_estado_ = EstadoNeural::Livre;
	uint32_t neural_geracao_ = 0;
	int64_t neural_ultima_ns_ = 0;
	bool neural_tem_mapa_ = false;
	bool neural_inverter_ = true;
	bool logged_neural_ = false;
	bool logged_neural_failure_ = false;
	std::vector<uint8_t> neural_mapa_;

	// -- Sonda da conversao de cor (ver SondarCor) ---------------------------
	GLuint probe_program_ = 0;
	GLuint probe_fbo_ = 0;
	GLuint probe_tex_ = 0;
	int probe_frames_ = 0;
	int probe_runs_ = 0;
	bool probe_done_ = false;
	GLint ploc_tex_matrix_ = -1;
	GLint ploc_texel_step_ = -1;
	GLint ploc_rgb_ = -1;
	GLint ploc_yuv_ = -1;
	GLint ploc_modo_ = -1;
	void SondarCor(const float *matrix);

	bool EnsureDepthTargets(int32_t width, int32_t height);
	bool EnsureNeuralTargets();
	void TickNeural(const float *matrix, int32_t width, int32_t height);
	void ResetNeural();
	void RenderDepth(const float *matrix, int32_t width, int32_t height);
	bool EnsureIntermediate(int32_t width, int32_t height);
	void RenderStats();

	// Estado do modo 3D, posto pelo SetStereo e lido pelo Render.
	bool stereo_ = false;
	float stereo_back_ = 0.0f;
	float stereo_front_ = 0.0f;
	/** Moldura flutuante do Pop-out, em fracao da largura; zero desliga. */
	float janela_ = 0.0f;
	float convergence_ = 1.0f;
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
	// Um par por olho: [olho][duas mais recentes]. No mono so o par 0 nasce.
	GLuint history_[2][2] = {{0, 0}, {0, 0}};
	int32_t history_width_[2] = {0, 0};
	int32_t history_height_[2] = {0, 0};
	// Qual das duas guarda o quadro mais recente, e quantas já foram escritas:
	// com uma só não há de onde extrapolar, e prever a partir de uma textura
	// ainda em branco desenharia lixo no primeiro quadro da sessão.
	int history_newest_[2] = {-1, -1};
	int history_count_[2] = {0, 0};
	// Mascara de movimento: a previsao vai para previsto_tex_ e so entra na
	// tela onde os dois ultimos reais diferem (ver kComporShader).
	int history_gen_[2] = {0, 0};
	int mascara_gen_[2] = {-1, -1};
	GLuint previsto_tex_[2] = {0, 0};
	GLuint mascara_tex_[2][2] = {{0, 0}, {0, 0}};
	int32_t mascara_w_[2] = {0, 0};
	int32_t mascara_h_[2] = {0, 0};
	GLuint prog_diff_ = 0, prog_dilata_ = 0, prog_compor_ = 0;
	bool compor_ok_ = true;
	// O intermediario do 3D guarda um quadro previsto (e nao o real).
	bool intermediate_previsto_ = false;
	bool MascaraPronta(int slot, int32_t width, int32_t height);
	bool Compor(GLuint target, int slot, int32_t width, int32_t height);
	bool logged_extrapolation_ = false;
	// Timestamp do ultimo buffer prendido. E o que separa quadro novo de quadro
	// repetido: o updateTexImage devolve sucesso nos dois casos.
	// -1, e nao 0: um primeiro buffer com timestamp 0 seria "repetido".
	int64_t last_timestamp_ = -1;
	bool logged_window_ = false;
	int frames_seen_ = 0;
	int frames_fresh_ = 0;

	// -- Cadencia da fonte (jogo a 30 num stream de 60) ----------------------
	//
	// Jogo travado em 30 manda, a 60, um quadro de verdade e uma repeticao. A
	// repeticao chega quase vazia (o tamanho vem do decodificador, patch
	// 0056) e e tratada como "nada novo": nao entra no historico e a passada
	// mostra um quadro previsto. O passo da previsao deixa de ser fixo em meio
	// quadro e passa a ser o tempo desde o ultimo quadro real dividido pelo
	// periodo real da fonte -- 0,25/0,5/0,75 a 30 fps em 120 Hz, 0,5 a 60.
	int64_t real_newest_ns_ = 0;
	bool historico_vizinho_ = false;
	bool movimento_ = false;
	float diff_real_rapido_ = 0.0f;
	int64_t periodo_fonte_ns_ = 16666667;
	double periodo_media_ns_ = 16666667.0;
	double tamanho_real_medio_ = 0.0;
	int repeticoes_seguidas_ = 0;
	float extrap_scale_ = 0.5f;
	// Comparacao de imagem: luma de uma grade 64x36 do quadro, lida de volta e
	// comparada com a do quadro anterior. Tamanho nao serve (o PS5 refina a
	// repeticao com a banda que sobra e ela chega com 50-90% do tamanho).
	GLuint amostra_tex_ = 0;
	GLuint amostra_fbo_ = 0;
	std::vector<uint8_t> amostra_atual_;
	std::vector<uint8_t> amostra_anterior_;
	double diff_real_medio_ = 0.0;
	int64_t cad_leitura_ns_ = 0;
	int64_t cad_leitura_max_ns_ = 0;
	int cad_leituras_ = 0;
	float seq_diff_[60] = {};
	uint32_t seq_bytes_[60] = {};
	int seq_n_ = -1;
	int seq_quadros_ = 0;
	// Leitura adiantada: com a cadencia firme, a classe vem do padrao e a
	// leitura vai para um PBO, conferida no quadro novo seguinte, sem parar a
	// GPU. Padrao quebrado: oito quadros lidos na hora ate firmar de novo.
	GLuint amostra_pbo_ = 0;
	GLsync amostra_fence_ = nullptr;
	bool amostra_pendente_ = false;
	bool pendente_predito_ = false;
	bool pendente_provisorio_ = false;
	bool comparando_ = false;
	int cad_paradas_ = 0;
	int cad_retomadas_ = 0;
	int cad_cortes_ = 0;
	int cad_sem_prazo_ = 0;
	int64_t neural_periodo_ns_ = 50000000;
	int64_t bytes_pendente_ = -1;
	static constexpr int kClasses = 12;
	bool classes_[kClasses] = {};
	int n_classes_ = 0;
	int sem_trava_ = 0;
	int cad_na_hora_ = 0;
	int cad_adiantadas_ = 0;
	int cad_erros_ = 0;
	// Banco de ensaio, por parte do cenario: classificados, errados, passadas
	// previstas.
	int ver_total_[3] = {0, 0, 0};
	int ver_errados_[3] = {0, 0, 0};
	int ver_previstas_[3] = {0, 0, 0};
	int ver_parte_ = 0;
	// Quantas passadas da tela cada imagem real ficou (pela verdade do pts):
	// a regularidade que se ve. 30 fps em 120 Hz e 4 sempre.
	int ver_passadas_desde_real_ = 0;
	int ver_hist_[3][16] = {};
	int64_t cad_conferencia_ns_ = 0;
	int64_t cad_conferencia_max_ns_ = 0;
	void DesenharAmostra(const float *matrix);
	/** Diferenca media de luma (0-255) contra a amostra anterior; -1 sem base. */
	float CompararAmostra();
	float LerAmostraAgora();
	void LerAmostraDepois();
	float LerAmostraPendente();
	bool EhRepeticao(float diff);
	float diffs_recentes_[3] = {0.0f, 0.0f, 0.0f};
	int diffs_pos_ = 0;
	void AtualizarDiffReal(float diff);
	int PeriodoTravado() const;
	void RegistrarDiff(float diff, int64_t bytes);
	float extrap_limite_ = 1.5f;
	float escala_max_ext_ = 1.5f;
	bool escala_acima_testada_ = false;
	// Estatistica do diario, zerada a cada linha.
	int cad_reais_ = 0;
	int cad_repeticoes_ = 0;
	int cad_previstas_ = 0;
	int cad_atrasadas_ = 0;
	uint32_t cad_maior_repeticao_ = 0;
	uint32_t cad_menor_real_ = UINT32_MAX;
	int cad_sem_tamanho_ = 0;
	bool logged_pts_ = false;

	// Quantos nits da fonte viram o branco cheio do painel.
	//
	// 240, para o branco de referência do HDR (203 nits, BT.2408) cair logo
	// acima do joelho da curva (0,8 do painel = 192 nits): ele sai quase
	// inteiro, e sobra um quinto do painel para os destaques. Com os 200 de
	// antes e o Reinhard, o mesmo branco saía com metade do brilho.
	//
	// A meia-luz fica um pouco abaixo da versão SDR do mesmo jogo (o cinza de
	// 18% de um jogo em 203 sai em 15% em vez de 18%); o brilho da tela, no
	// Share, compensa quem preferir.
	float target_nits_ = 240.0f;

	// O pico que a curva guarda espaço para mostrar. 1000 nits e o que a
	// maior parte do HDR10 de jogo usa e o que o PS5 sugere na calibração; o
	// que passar disso satura no branco. O valor real (MaxCLL) viaja nos
	// metadados do HEVC, mas chega do lado do chiaki, fora desta biblioteca.
	float source_peak_nits_ = 1000.0f;
};

} // namespace p5m
