// SPDX-License-Identifier: AGPL-3.0-only
//
// Sessao OpenXR do P5M.
//
// Ideia central: o app nao desenha nada. O video do PS5 e decodificado pelo
// MediaCodec direto dentro de um swapchain que o proprio runtime expoe como
// Surface Android (XR_KHR_android_surface_swapchain), e esse swapchain e
// entregue ao compositor como camada cilindrica (XR_KHR_composition_layer_cylinder).
// Nao ha textura intermediaria, nem passo de GPU nosso, nem copia: o caminho e
// rede -> MediaCodec -> compositor. Esse e o menor custo de latencia possivel
// no Horizon OS, e de quebra a reprojecao do compositor age sobre a tela.
#pragma once

#include <jni.h>
#include "tone_mapper.h"
#include <atomic>
#include <thread>
#include <mutex>
#include <string>
#include <vector>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <EGL/egl.h>
// EGL_OPENGL_ES3_BIT_KHR vive aqui, nao no egl.h do NDK (que e EGL 1.4).
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

namespace p5m {

// Geometria da tela virtual. Os defaults valem uma tela grande e confortavel:
// 4 m de raio, 70 graus de arco. Ajustavel em runtime pelo usuario.
struct ScreenParams
{
	float radius = 4.0f;         // metros, distancia do centro da cabeca
	float centralAngle = 1.22f;  // radianos (~70 graus) de arco horizontal
	float aspectRatio = 16.0f / 9.0f;
	float yawOffset = 0.0f;      // radianos, reposicionamento horizontal
	float heightOffset = 0.0f;   // metros, altura relativa ao ponto de recentragem

	// Quanto a tela curva, de 0 (plana) a 1 (olho no centro do cilindro).
	// O padrao e sutil: a curvatura maxima abraca demais quem assiste.
	float curvature = 0.35f;
};

// Filtro que o compositor aplica na camada de video.
//
// A fonte e 1080p esticada num arco de ~70 graus, entao ela chega ao olho
// abaixo da densidade do painel: sem filtro, fica visivelmente macia. Como o
// app nao gasta um ciclo de GPU proprio, sobra orcamento no compositor
// justamente para isto.
/**
 * Intensidade da nitidez, em quatro degraus.
 *
 * O compositor nao tem manopla: XR_FB_composition_layer_settings so oferece
 * bits ligados ou desligados, e o de sharpening tem uma forca fixa -- a que
 * ficou forte demais. Entao os degraus se constroem de dois jeitos diferentes,
 * conforme o caminho de video:
 *
 * No caminho direto, sem shader, o que existe sao combinacoes de bits. O
 * supersampling amacia, e ligado junto com o sharpening puxa o realce de volta;
 * e uma escala real, ainda que grosseira, e nao custa um microssegundo.
 *
 * No caminho com shader ha uma manopla de verdade: o realce vira um numero no
 * fragment shader, e os degraus sao intensidades de fato.
 */
enum class Sharpness
{
	Off = 0,
	Light = 1,
	Medium = 2,
	Strong = 3,
	// Meta Quest Super Resolution: o QUALITY_SHARPENING desde a v55. E o
	// Snapdragon GSR com otimizacoes da Meta -- escala e afia numa passada so,
	// pelo compositor.
	//
	// Medido no Quest 3, e o resultado separa os dois caminhos de video: no
	// caminho com shader ele funciona; no direto, nao. A diferenca entre os dois
	// e o swapchain -- com shader e um GL sRGB8_ALPHA8 que nos criamos, e no
	// direto e um swapchain-Surface cujo formato quem escolhe e o runtime, para
	// o MediaCodec escrever. Um filtro do compositor que espera textura RGB nao
	// tem o que fazer com aquilo, e o disco esbranquiçado do primeiro teste
	// provavelmente era isso. O NORMAL_SHARPENING funciona nos dois, entao a
	// incompatibilidade e do filtro de qualidade, nao do swapchain-Surface.
	//
	// E, mesmo funcionando, ele so tem o que fazer quando a tela pede mais
	// pixels do que a fonte entrega: e um upscaler. Com a tela "no ponto", nao
	// impressiona porque nao ha o que ampliar.
	Mqsr = 4,
	// O compositor escolhe entre os candidatos que oferecemos, quadro a quadro,
	// com a pose da camada e a carga de GPU na mao -- informacao que nos nao
	// temos deste lado.
	Auto = 5,
};

/**
 * Forma da camada de composicao.
 *
 * Existe porque a especificacao se contradiz: XR_KHR_android_surface_swapchain
 * exige faceCount=0 na criacao do swapchain, e XR_KHR_composition_layer_cylinder
 * exige que o swapchain referenciado tenha sido criado com faceCount=1. Nao da
 * para satisfazer as duas, entao qual forma o runtime aceita e questao empirica.
 */
enum class LayerShape
{
	Cylinder = 0,
	Quad = 1,
};

/**
 * Retrato do desempenho num instante, para o painel de ajuste.
 *
 * Cada campo tem seu proprio "tem valor": um contador que este runtime nao
 * oferece nao e zero, e zero quadro descartado nao e a mesma coisa que nao
 * saber quantos foram. Mostrar zero nos dois casos seria mentir na metade
 * deles.
 */
struct PerformanceSnapshot
{
	int droppedFrames = 0;
	float droppedFrameCount = 0.0f;
	bool hasGpuUtilization = false;
	float gpuUtilization = 0.0f;
	bool hasAppGpuTime = false;
	float appGpuTimeMs = 0.0f;
	bool hasCompositorGpuTime = false;
	float compositorGpuTimeMs = 0.0f;
	// Latencia do movimento da cabeca ate o foton, medida pelo compositor. E o
	// numero que eu ia construir a mao com carimbos de tempo; o runtime ja o
	// tinha, e a lista enumerada e que revelou.
	bool hasMotionToPhoton = false;
	float motionToPhotonMs = 0.0f;
	bool hasCpuUtilization = false;
	float cpuUtilization = 0.0f;
	bool hasThermal = false;
	float thermalHeadroom = 0.0f;
	float thermalSlope = 0.0f;
};

struct QualityParams
{
	Sharpness sharpness = Sharpness::Off;
	// O mesmo degrau como intensidade, para o caminho com shader. Chega pronto
	// da interface em vez de ser recalculado aqui: a tabela e a mesma que o
	// modo janela usa, e duas copias acabariam divergindo.
	float sharpenAmount = 0.0f;
	// Opacidade do passthrough. Abaixo de 1.0 o quarto escurece e a tela salta.
	float passthroughOpacity = 1.0f;

	// Modo cinema: escurece e dessatura a sala sem tocar na tela.
	//
	// Nao e opacidade. Baixar a opacidade do passthrough deixa o quarto
	// translucido, o que embaralha a imagem em vez de apaga-la; isto age no
	// brilho, no contraste e na saturacao da propria camera, entao a sala fica
	// escura e sem cor -- e a tela, que nao passa por aqui, salta dela.
	//
	// Neutros: brilho 0, contraste 1, saturacao 1. Nesses valores o elo nem
	// entra no encadeamento.
	float passthroughBrightness = 0.0f;
	float passthroughContrast = 1.0f;
	float passthroughSaturation = 1.0f;

	// Escala aplicada a cor da camada de video pelo compositor. 1.0 nao mexe
	// em nada. Abaixo de 1 escurece a tela sem tocar no passthrough.
	float videoBrightness = 1.0f;

	// Quadros previstos entre os do console, pela extensao da Adreno. So no
	// caminho com shader: e la que existe pipeline de GL para guardar o
	// historico de que a extrapolacao precisa.
	bool frameExtrapolation = false;
};

/**
 * Como o video chega ao compositor.
 *
 * Direct e o caminho sem copia: o MediaCodec escreve no swapchain do proprio
 * compositor e nada mais toca na imagem. E a menor latencia possivel, e o
 * motivo de existir deste projeto -- mas entrega ao compositor exatamente o que
 * o decodificador produziu, curva PQ inclusive.
 *
 * ToneMapped passa por uma SurfaceTexture e um shader, que converte PQ para SDR
 * e BT.2020 para BT.709 antes de escrever num swapchain comum. Custa uma
 * passada de GPU e a copia que o outro modo evita, e e a unica forma de ter 10
 * bits com cor correta neste hardware, ja que o decodificador ignora o pedido
 * de mapeamento.
 */
enum class RenderPath
{
	Direct = 0,
	ToneMapped = 1,
};

class XrVideoSession
{
public:
	XrVideoSession() = default;
	~XrVideoSession();

	// Cria instancia, sistema, contexto EGL e sessao. Nao inicia o frame loop.
	bool Create(JavaVM *vm, jobject activity);

	// Cria o swapchain-Surface do video e devolve uma referencia global ao
	// android.view.Surface. O chamador repassa esse Surface direto para o
	// chiaki (Session.setSurface), sem intermediario.
	// So pode ser chamado uma vez por sessao.
	jobject CreateVideoSurface(JNIEnv *env, int32_t width, int32_t height);

	/**
	 * Escolhe o caminho do video. Precisa vir antes de CreateVideoSurface: os
	 * dois caminhos criam swapchains de tipos diferentes.
	 */
	void SetRenderPath(RenderPath path) { render_path_ = path; }
	void SetSourceIsPq(bool pq) { tone_map_pq_ = pq; }
	RenderPath GetRenderPath() const { return render_path_; }

	/**
	 * Liga a SurfaceTexture criada do lado Java ao caminho com shader.
	 *
	 * Só faz sentido em ToneMapped: no caminho direto o Surface vem do próprio
	 * runtime e ninguém intercepta a imagem.
	 */
	bool AttachSurfaceTexture(JNIEnv *env, jobject surface_texture);

	void StartFrameLoop();
	void StopFrameLoop();

	void SetScreenParams(const ScreenParams &params);
	void SetQualityParams(const QualityParams &params);

	/** Contadores do compositor e do termico, para o painel. */
	PerformanceSnapshot ReadPerformance() const;

	/**
	 * Quantos pixels o compositor gostaria de ter na camada de video, no
	 * tamanho e na distancia em que ela esta agora.
	 *
	 * Comparado com o que a fonte entrega, diz se a tela desperdica detalhe
	 * (recomendacao menor que a fonte) ou pede mais do que existe (maior). Zero
	 * quando o runtime nao respondeu.
	 */
	void RecommendedResolution(int *width, int *height) const
	{
		*width = recommended_width_.load();
		*height = recommended_height_.load();
	}
	void SetLayerShape(LayerShape shape) { layer_shape_ = shape; }

	/**
	 * Libera a submissao da camada de video.
	 *
	 * Enquanto isto for falso o frame loop roda normalmente (xrWaitFrame /
	 * xrBeginFrame / xrEndFrame) mas nao empurra a camada de video. Existe
	 * porque a swapchain-Surface so tem conteudo depois que o MediaCodec
	 * entrega o primeiro frame decodificado; referenciar antes disso e pedir
	 * ao compositor que resolva um buffer que ainda nao existe.
	 */
	void SetVideoLayerEnabled(bool enabled) { video_layer_enabled_ = enabled; }

	/**
	 * Corrige o espelhamento vertical da imagem, no compositor.
	 *
	 * Ligado por padrao porque e o que o hardware pede hoje. Fica ajustavel
	 * porque a inversao vem da convencao de origem do BufferQueue, que nao e
	 * contrato nosso: se um dia o runtime passar a corrigir sozinho, a imagem
	 * inverte de novo e o usuario precisa poder desfazer sem esperar build.
	 */
	void SetVerticalFlip(bool enabled) { vertical_flip_ = enabled; }
	void SetPassthroughEnabled(bool enabled);

	/**
	 * Como a imagem carrega os dois olhos: 0 mono, 1 lado a lado, 2 uma sobre
	 * a outra.
	 *
	 * Num headset nao existe estereo alternado no tempo: as duas vistas sao
	 * mostradas no mesmo instante, e o `eyeVisibility` da camada escolhe quem
	 * ve o que. Alternar olho a cada quadro de painel e tecnica de TV com
	 * oculos obturador, onde os dois olhos dividem a mesma tela -- aqui
	 * cortaria a taxa por olho pela metade em troca de nada.
	 */
	void SetStereoMode(int mode) { stereo_mode_ = mode; }

	/**
	 * Forca e convergencia do olho sintetizado.
	 *
	 * `strength` e a disparidade pedida, em fracao da largura da imagem; o que
	 * vale e o menor entre ela e o teto que a IPD impoe. `convergence` diz que
	 * profundidade fica no plano da tela: o que estiver a frente salta, o que
	 * estiver atras afunda.
	 */
	void SetStereoTuning(float strength, float convergence)
	{
		stereo_strength_ = strength;
		stereo_convergence_ = convergence;
	}

	/**
	 * Declara o gamut do conteudo: BT.2020 com 10 bits, Rec.709 com 8.
	 *
	 * Mapeamento de tons trata de luminancia, nao de primarias -- o video em 10
	 * bits continua BT.2020 depois de o decodificador converter a curva para
	 * SDR. Declarar o gamut errado deixa a imagem clara e lavada.
	 */
	void SetWideColor(bool wide);

	/**
	 * Entrega o painel de ajuda ja desenhado, em RGBA de 8 bits por canal.
	 *
	 * O texto e desenhado do lado Java, com Canvas: fonte, acentuacao e quebra
	 * de linha saem de graca. Aqui so sobe para a textura -- escrever um
	 * renderizador de texto em GLES para mostrar seis linhas seria trabalho
	 * grande para resolver um problema que o Android ja resolve.
	 *
	 * Pode ser chamada de qualquer thread; o envio para a GPU acontece no frame
	 * loop, que e onde o contexto EGL esta corrente.
	 */
	void SetHudBitmap(const uint8_t *pixels, int32_t width, int32_t height);
	void SetHudVisible(bool visible) { hud_visible_ = visible; }

	/**
	 * Escolhe a taxa de atualizacao do painel.
	 *
	 * Nao e "quanto maior melhor": com fonte de 60 fps, 120 Hz e multiplo exato
	 * e cada frame do console aparece por exatamente dois frames do painel. A
	 * 90 Hz a razao e 1.5 e a cadencia fica irregular -- judder visivel em
	 * panorâmica, mesmo com a rede perfeita.
	 *
	 * @param sourceFps framerate negociado com o console
	 * @return taxa efetivamente aplicada, ou 0 se a extensao nao existir
	 */
	float SelectDisplayRefreshRate(int sourceFps);
	// Zera a orientacao: a tela volta para a frente de onde a cabeca olha agora.
	void Recenter();

	bool PassthroughSupported() const { return passthrough_supported_; }

	void Destroy();

	/** Motivo da ultima falha, para a UI mostrar sem depender do log. */
	const char *LastError() const { return last_error_.c_str(); }

private:
	bool InitEgl();
	void DestroyEgl();
	bool InitPassthrough();
	void ApplyColorSpace();
	bool InitHudSwapchain();
	bool CreateGlVideoSwapchain(int32_t width, int32_t height);
	void LogGlCapabilities();
	void ApplyPerformanceLevels();
	void InitPerformanceMetrics();
	bool ReadCounter(XrPath path, float *out) const;
	// Devolve false quando nao ha imagem pronta para submeter neste frame.
	bool RenderVideoThroughShader();
	// Devolve false quando nao ha o que compor; so entao a camada e omitida.
	// Nao recebe ScreenParams: o painel tem posicao fixa, independente da tela.
	bool UpdateHudLayer(XrCompositionLayerQuad &layer, float yaw);
	void PollEvents();
	void RenderFrame();
	void FrameLoop();
	void HandleSessionStateChange(XrSessionState state);
	bool LoadExtensionFunctions();

	JavaVM *vm_ = nullptr;
	jobject activity_ = nullptr; // referencia global

	XrInstance instance_ = XR_NULL_HANDLE;
	XrSystemId system_id_ = XR_NULL_SYSTEM_ID;
	::XrSession session_ = XR_NULL_HANDLE;
	XrSpace app_space_ = XR_NULL_HANDLE;   // espaco de referencia LOCAL
	XrSpace view_space_ = XR_NULL_HANDLE;  // espaco VIEW, usado na recentragem
	XrSessionState session_state_ = XR_SESSION_STATE_UNKNOWN;

	RenderPath render_path_ = RenderPath::Direct;
	ToneMapper tone_mapper_;
	bool tone_mapper_ready_ = false;
	// Fonte em PQ (10 bits) ou SDR (8). O shader trata os dois: com 8 bits so
	// lineariza, sem mapear nada, para o caminho servir as duas profundidades.
	std::atomic<bool> tone_map_pq_{true};
	ASurfaceTexture *pending_surface_texture_ = nullptr;
	// O swapchain GL do video nasce tarde, ja dentro do frame loop. Ver o
	// comentario em CreateVideoSurface: aqui so ficam as medidas a espera.
	int32_t pending_gl_width_ = 0;
	int32_t pending_gl_height_ = 0;
	std::vector<XrSwapchainImageOpenGLESKHR> video_images_;

	XrSwapchain video_swapchain_ = XR_NULL_HANDLE;
	// Formato do swapchain GL, guardado porque o historico da extrapolacao
	// precisa nascer identico a ele.
	int64_t video_gl_format_ = 0;
	int32_t video_width_ = 0;
	int32_t video_height_ = 0;

	// Painel de ajuda do modo de ajuste. Swapchain comum, nao Surface: quem
	// produz a imagem somos nos, nao um decodificador.
	XrSwapchain hud_swapchain_ = XR_NULL_HANDLE;
	std::vector<XrSwapchainImageOpenGLESKHR> hud_images_;
	int32_t hud_width_ = 0;
	int32_t hud_height_ = 0;
	std::mutex hud_mutex_;
	std::vector<uint8_t> hud_pixels_;
	std::atomic<bool> hud_visible_{false};

	XrPassthroughFB passthrough_ = XR_NULL_HANDLE;
	XrPassthroughLayerFB passthrough_layer_ = XR_NULL_HANDLE;
	bool passthrough_supported_ = false;
	std::atomic<bool> passthrough_enabled_{false};

	EGLDisplay egl_display_ = EGL_NO_DISPLAY;
	EGLContext egl_context_ = EGL_NO_CONTEXT;
	EGLSurface egl_surface_ = EGL_NO_SURFACE;
	EGLConfig egl_config_ = nullptr;

	std::thread frame_thread_;
	std::atomic<bool> running_{false};
	std::atomic<bool> session_running_{false};
	std::atomic<bool> exit_requested_{false};

	std::string last_error_;
	bool logged_layer_config_ = false;
	std::atomic<LayerShape> layer_shape_{LayerShape::Cylinder};
	std::atomic<bool> video_layer_enabled_{false};
	std::atomic<bool> vertical_flip_{true};
	std::atomic<bool> wide_color_{false};

	// Como a imagem carrega os dois olhos, quando carrega.
	//
	// 0 = mono: uma camada so, vista pelos dois olhos, que e o caso de todo
	//     jogo e o padrao.
	// 1 = lado a lado: metade esquerda para o olho esquerdo, direita para o
	//     direito.
	// 2 = uma sobre a outra: metade de cima para o esquerdo.
	//
	// A mesma chave serve para duas origens diferentes de estereo, e e de
	// proposito: um video que ja veio lado a lado do console e a saida do nosso
	// proprio sintetizador, que escreve os dois olhos na mesma textura. Do lado
	// da submissao os dois casos sao identicos, e unificar evita dois caminhos
	// que fariam a mesma coisa de jeitos que acabariam divergindo.
	std::atomic<int> stereo_mode_{0};

	// Distancia interpupilar em metros, lida do runtime. Zero ate a primeira
	// leitura valida. Nao entra no desenho -- o runtime ja aplica a IPD ao
	// compor as camadas; serve de teto para a disparidade do olho sintetizado,
	// porque acima dela os olhos teriam de divergir.
	std::atomic<float> ipd_meters_{0.0f};

	// Forca e convergencia do olho sintetizado, ajustaveis em jogo. A forca e
	// um pedido: o limite real sai da IPD e do tamanho da tela, e o Render
	// aplica o menor dos dois.
	std::atomic<float> stereo_strength_{0.012f};
	std::atomic<float> stereo_convergence_{0.35f};
	bool logged_ipd_ = false;
	bool logged_stereo_sharpen_ = false;

	// Configuracao de view do sistema, consultada uma vez na criacao. O app nao
	// desenha nada, mas o runtime espera esse handshake e ha implementacoes que
	// so inicializam estado interno de view quando ele acontece.
	uint32_t view_count_ = 0;
	std::vector<XrView> views_;

	std::mutex params_mutex_;
	ScreenParams params_;
	QualityParams quality_;
	XrQuaternionf recenter_orientation_{0.0f, 0.0f, 0.0f, 1.0f};
	std::atomic<bool> recenter_requested_{false};

	// Ponteiros de extensao resolvidos via xrGetInstanceProcAddr.
	bool layer_settings_supported_ = false;
	bool surface_swapchain_create_supported_ = false;
	bool image_layout_supported_ = false;
	bool color_space_supported_ = false;
	bool refresh_rate_supported_ = false;
	bool thread_settings_supported_ = false;
	bool auto_filter_supported_ = false;
	bool recommended_resolution_supported_ = false;
	bool perf_settings_supported_ = false;
	bool perf_metrics_supported_ = false;
	bool color_scale_supported_ = false;
	bool logged_cinema_ = false;
	// Resolucao que o compositor pediria para a camada de video, atualizada a
	// cada quadro. Lida pelo painel de ajuste, de outra thread.
	std::atomic<int> recommended_width_{0};
	std::atomic<int> recommended_height_{0};
	bool logged_recommended_resolution_ = false;

	PFN_xrCreateSwapchainAndroidSurfaceKHR pfnCreateSwapchainAndroidSurfaceKHR = nullptr;
	PFN_xrSetAndroidApplicationThreadKHR pfnSetAndroidApplicationThreadKHR = nullptr;
	PFN_xrGetRecommendedLayerResolutionMETA pfnGetRecommendedLayerResolutionMETA = nullptr;
	PFN_xrSetColorSpaceFB pfnSetColorSpaceFB = nullptr;
	PFN_xrEnumerateDisplayRefreshRatesFB pfnEnumerateDisplayRefreshRatesFB = nullptr;
	PFN_xrRequestDisplayRefreshRateFB pfnRequestDisplayRefreshRateFB = nullptr;
	PFN_xrPerfSettingsSetPerformanceLevelEXT pfnPerfSettingsSetPerformanceLevelEXT = nullptr;
	PFN_xrThermalGetTemperatureTrendEXT pfnThermalGetTemperatureTrendEXT = nullptr;
	PFN_xrEnumeratePerformanceMetricsCounterPathsMETA
			pfnEnumeratePerformanceMetricsCounterPathsMETA = nullptr;
	PFN_xrSetPerformanceMetricsStateMETA pfnSetPerformanceMetricsStateMETA = nullptr;
	PFN_xrQueryPerformanceMetricsCounterMETA pfnQueryPerformanceMetricsCounterMETA = nullptr;
	// Caminhos dos contadores, resolvidos uma vez. Guardados como XrPath porque
	// a consulta e por quadro e converter string a cada vez seria trabalho no
	// caminho quente.
	XrPath counter_dropped_frames_ = XR_NULL_PATH;
	XrPath counter_gpu_utilization_ = XR_NULL_PATH;
	XrPath counter_app_gpu_time_ = XR_NULL_PATH;
	XrPath counter_comp_gpu_time_ = XR_NULL_PATH;
	XrPath counter_motion_to_photon_ = XR_NULL_PATH;
	XrPath counter_cpu_utilization_ = XR_NULL_PATH;
	// Framerate da fonte guardado a espera do xrBeginSession. Ver
	// SelectDisplayRefreshRate.
	std::atomic<int> pending_refresh_fps_{0};
	PFN_xrPassthroughLayerSetStyleFB pfnPassthroughLayerSetStyleFB = nullptr;
	PFN_xrCreatePassthroughFB pfnCreatePassthroughFB = nullptr;
	PFN_xrDestroyPassthroughFB pfnDestroyPassthroughFB = nullptr;
	PFN_xrPassthroughStartFB pfnPassthroughStartFB = nullptr;
	PFN_xrPassthroughPauseFB pfnPassthroughPauseFB = nullptr;
	PFN_xrCreatePassthroughLayerFB pfnCreatePassthroughLayerFB = nullptr;
	PFN_xrDestroyPassthroughLayerFB pfnDestroyPassthroughLayerFB = nullptr;
	PFN_xrPassthroughLayerResumeFB pfnPassthroughLayerResumeFB = nullptr;
	PFN_xrPassthroughLayerPauseFB pfnPassthroughLayerPauseFB = nullptr;
};

} // namespace p5m
