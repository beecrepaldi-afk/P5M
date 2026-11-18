// SPDX-License-Identifier: AGPL-3.0-only
#include "xr_session.h"
#include "spatializer.h"
#include "log.h"

#include <chrono>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <unistd.h>
#include <vector>
#include <string>

namespace p5m {
namespace {

// Tamanho do buffer que o decodificador entrega, contado pelo lado do chiaki
// (video-decoder.c) quando o formato de saida muda. Global porque quem conta
// nao conhece a sessao; so ha uma por processo.
//
// Existe por causa da faixa deformada no pe da tela no caminho direto: o HEVC
// de 1080 linhas sai como buffer de 1088, e o swapchain de Surface do runtime
// mostra o buffer inteiro esticado nas 1080 da camada, sem o recorte. A
// SurfaceTexture do caminho com shader aplica o recorte sozinha, e la a faixa
// nunca apareceu.
std::atomic<int32_t> g_decoder_width{0};
std::atomic<int32_t> g_decoder_height{0};
std::atomic<int32_t> g_decoder_crop_top{-1};
std::atomic<int32_t> g_decoder_crop_bottom{-1};

const char *XrResultStr(XrInstance instance, XrResult res)
{
	static thread_local char buf[XR_MAX_RESULT_STRING_SIZE];
	if(instance != XR_NULL_HANDLE && XR_SUCCEEDED(xrResultToString(instance, res, buf)))
		return buf;
	snprintf(buf, sizeof(buf), "XrResult(%d)", (int)res);
	return buf;
}

#define XR_CHECK(instance, expr, what)                                              \
	do {                                                                            \
		XrResult _res = (expr);                                                     \
		if(XR_FAILED(_res)) {                                                       \
			LOGE("%s failed: %s", (what), XrResultStr((instance), _res));           \
			return false;                                                           \
		}                                                                           \
	} while(0)

// Tamanho do painel de ajuda, em pixels de textura.
//
// Largo e baixo (dev.210), e nao mais 1024x768: o painel fica logo abaixo da
// tela do jogo, e uma faixa comprida cobre menos imagem que um quadro. O dono
// pediu algo perto de 32:9; 2048x464 tem cinco colunas de tres linhas e mais
// nada -- cada linha a mais seria painel por cima do jogo.
constexpr int32_t kHudWidth = 2048;
constexpr int32_t kHudHeight = 464;

// Distancia de leitura e largura fisica. A dev.210 usou 1,9 m, a densidade
// do painel antigo (0,95 m para 1024 px); o dono achou grande e pediu uns 25%
// a menos. Mesma textura, so menor no espaco: o texto encolhe junto.
constexpr float kHudDistance = 1.5f;
constexpr float kHudWidth1M = 1.425f;
// Entre a borda de baixo da tela e o topo do painel.
constexpr float kHudGapRadians = 0.03f;
// Sem tela visivel (teclado do PIN, antes do video), o centro fica um pouco
// abaixo da linha dos olhos, como sempre foi.
constexpr float kHudCenterPitch = -0.2f;
// Tela enorme ou muito baixa empurraria o painel para o colo; daqui nao passa.
constexpr float kHudMinPitch = -0.75f;
// Ate dez graus acima dos olhos a tela fica reta (dev.217): ~0,7 m a 4 m.
constexpr float kScreenStraightBand = 10.0f * 3.14159265f / 180.0f;

XrQuaternionf QuatFromYaw(float yaw)
{
	return XrQuaternionf{0.0f, std::sin(yaw * 0.5f), 0.0f, std::cos(yaw * 0.5f)};
}

/**
 * Inclinacao da tela para ficar de frente para os olhos (dev.215).
 *
 * Tela reta acima da cabeca e vista de baixo, em trapezio, como TV presa
 * alta na parede sem suporte inclinado. O espaco LOCAL tem a origem nos olhos
 * da recentragem, entao o angulo ate o centro da tela e so a altura sobre a
 * distancia; girar a tela por ele a deixa perpendicular a linha de visao. O
 * mesmo vale para baixo, como a tela de um notebook no colo.
 */
float ScreenTilt(const ScreenParams &params)
{
	if(params.radius <= 0.0f)
		return 0.0f;
	// Faixa reta logo acima dos olhos (dev.217): o dono gosta da tela um
	// pouco alta e reta, e so a queria inclinada quando sobe de verdade. Acima
	// da faixa conta so o excesso, para a inclinacao nascer do zero e nao
	// pular de uma vez. Abaixo dos olhos continua inteira.
	const float elevacao = std::atan2(params.heightOffset, params.radius);
	if(elevacao < 0.0f)
		return elevacao;
	return std::max(elevacao - kScreenStraightBand, 0.0f);
}

/** Yaw seguido de pitch: o painel de frente para quem olha para baixo. */
XrQuaternionf QuatFromYawPitch(float yaw, float pitch)
{
	const float cy = std::cos(yaw * 0.5f), sy = std::sin(yaw * 0.5f);
	const float cp = std::cos(pitch * 0.5f), sp = std::sin(pitch * 0.5f);
	// q = qYaw * qPitch, com qYaw em torno de Y e qPitch em torno de X.
	return XrQuaternionf{cy * sp, sy * cp, -sy * sp, cy * cp};
}

/** Gira um vetor pelo inverso de um quaternion unitario. */
XrVector3f RotateByInverse(const XrQuaternionf &q, const XrVector3f &v)
{
	// v' = q* v q, com q* = (-x, -y, -z, w). Escrito como duas produtos
	// vetoriais em vez de montar a matriz: sao menos operacoes e nao ha matriz
	// para manter em lugar nenhum.
	const XrVector3f u{-q.x, -q.y, -q.z};
	const XrVector3f t{
		2.0f * (u.y * v.z - u.z * v.y),
		2.0f * (u.z * v.x - u.x * v.z),
		2.0f * (u.x * v.y - u.y * v.x)};
	return XrVector3f{
		v.x + q.w * t.x + (u.y * t.z - u.z * t.y),
		v.y + q.w * t.y + (u.z * t.x - u.x * t.z),
		v.z + q.w * t.z + (u.x * t.y - u.y * t.x)};
}

// So o yaw interessa na recentragem: inclinar a tela junto com a cabeca
// enjoa e nao ajuda em nada.
float YawFromQuat(const XrQuaternionf &q)
{
	return std::atan2(2.0f * (q.w * q.y + q.x * q.z),
	                  1.0f - 2.0f * (q.y * q.y + q.x * q.x));
}

} // namespace

XrVideoSession::~XrVideoSession()
{
	Destroy();
}

bool XrVideoSession::Create(JavaVM *vm, jobject activity)
{
	vm_ = vm;

	JNIEnv *env = nullptr;
	if(vm_->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK)
	{
		LOGE("GetEnv failed while creating the session");
		return false;
	}
	activity_ = env->NewGlobalRef(activity);

	// No Android o loader precisa da VM e do Context antes de qualquer outra
	// chamada OpenXR, senao nao localiza o runtime do Horizon OS.
	PFN_xrInitializeLoaderKHR pfnInitializeLoader = nullptr;
	if(XR_SUCCEEDED(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
			(PFN_xrVoidFunction *)&pfnInitializeLoader)) && pfnInitializeLoader)
	{
		XrLoaderInitInfoAndroidKHR loader_init{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
		loader_init.applicationVM = vm_;
		loader_init.applicationContext = activity_;
		pfnInitializeLoader((const XrLoaderInitInfoBaseHeaderKHR *)&loader_init);
	}
	else
	{
		LOGW("xrInitializeLoaderKHR unavailable; carrying on anyway");
	}

	uint32_t ext_count = 0;
	if(XR_FAILED(xrEnumerateInstanceExtensionProperties(nullptr, 0, &ext_count, nullptr)))
	{
		LOGE("Could not enumerate OpenXR extensions");
		return false;
	}
	std::vector<XrExtensionProperties> available(ext_count, {XR_TYPE_EXTENSION_PROPERTIES});
	xrEnumerateInstanceExtensionProperties(nullptr, ext_count, &ext_count, available.data());

	// A lista inteira, e nao so as que ligamos.
	//
	// Ate aqui o log dizia apenas "Extensao habilitada: X" para cada uma das
	// nossas, o que responde "o que pedimos" e nunca "o que existe". Toda
	// pergunta sobre o que mais este aparelho oferece terminava em suposicao.
	//
	// Quebrada em linhas de oito nomes: o log do Android corta mensagem em
	// torno de 4 KB, e uma lista de cem extensoes passa disso com folga --
	// sairia truncada exatamente onde comeca a ficar interessante.
	{
		LOGI("Extensions the runtime offers: %u", ext_count);
		std::string line;
		int on_line = 0;
		for(uint32_t i = 0; i < ext_count; i++)
		{
			line += "  ";
			line += available[i].extensionName;
			if(++on_line == 8 || i + 1 == ext_count)
			{
				LOGI("  available:%s", line.c_str());
				line.clear();
				on_line = 0;
			}
		}
	}

	auto has_ext = [&available](const char *name) {
		for(const auto &e : available)
			if(strcmp(e.extensionName, name) == 0)
				return true;
		return false;
	};

	std::vector<const char *> extensions;
	// Obrigatorias: sem qualquer uma delas o projeto nao existe.
	const char *required[] = {
		XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
		XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
		XR_KHR_ANDROID_SURFACE_SWAPCHAIN_EXTENSION_NAME,
		XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME,
	};
	for(const char *name : required)
	{
		if(!has_ext(name))
		{
			last_error_ = std::string("Required OpenXR extension missing: ") + name;
			LOGE("%s", last_error_.c_str());
			return false;
		}
		extensions.push_back(name);
	}
	// Opcional: sem passthrough o app cai para ambiente escuro.
	if(has_ext(XR_FB_PASSTHROUGH_EXTENSION_NAME))
	{
		extensions.push_back(XR_FB_PASSTHROUGH_EXTENSION_NAME);
		passthrough_supported_ = true;
	}
	else
	{
		LOGW("XR_FB_passthrough unavailable; mixed mode disabled");
	}

	// Opcionais de qualidade. Nenhuma delas e critica: se faltar, o app roda
	// igual, so mais macio, com cor menos exata ou com judder.
	// A Meta trata esta como obrigatoria na pratica: a especificacao dela diz
	// que XrAndroidSurfaceSwapchainCreateInfoFB *must* estar no next chain do
	// XrSwapchainCreateInfo ao chamar xrCreateSwapchainAndroidSurfaceKHR. Sem
	// ela o swapchain e criado, o MediaCodec decodifica normalmente, e o
	// runtime morre ao compor a camada.
	if(has_ext(XR_FB_ANDROID_SURFACE_SWAPCHAIN_CREATE_EXTENSION_NAME))
	{
		surface_swapchain_create_supported_ = true;
		extensions.push_back(XR_FB_ANDROID_SURFACE_SWAPCHAIN_CREATE_EXTENSION_NAME);
	}
	else
		LOGW("XR_FB_android_surface_swapchain_create unavailable");

	// A imagem chega espelhada verticalmente: o BufferQueue do Android tem
	// origem no canto superior esquerdo e o compositor amostra pela convencao
	// do GL, com origem embaixo. Esta extensao existe exatamente para isso, e e
	// a unica correcao que nao custa uma passada de GPU -- espelhamento nao se
	// faz com pose, porque rotacao rigida nunca produz reflexo.
	if(has_ext(XR_FB_COMPOSITION_LAYER_IMAGE_LAYOUT_EXTENSION_NAME))
	{
		image_layout_supported_ = true;
		extensions.push_back(XR_FB_COMPOSITION_LAYER_IMAGE_LAYOUT_EXTENSION_NAME);
	}
	else
		LOGW("XR_FB_composition_layer_image_layout unavailable; the image will be mirrored");

	if(has_ext(XR_FB_COMPOSITION_LAYER_SETTINGS_EXTENSION_NAME))
	{
		extensions.push_back(XR_FB_COMPOSITION_LAYER_SETTINGS_EXTENSION_NAME);
		layer_settings_supported_ = true;
	}
	if(has_ext(XR_FB_COLOR_SPACE_EXTENSION_NAME))
	{
		extensions.push_back(XR_FB_COLOR_SPACE_EXTENSION_NAME);
		color_space_supported_ = true;
	}
	if(has_ext(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME))
	{
		extensions.push_back(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
		refresh_rate_supported_ = true;
	}

	// Atenuacao local do painel.
	//
	// O Quest 3 tem retroiluminacao em zonas, e por padrao ela fica no modo
	// conservador porque uma interface comum tem branco espalhado pela tela
	// inteira. Uma camada de video em quarto escuro e o caso oposto: quase tudo
	// e preto, e sem atenuacao local esse preto sai cinza -- que e o que faz uma
	// cena noturna parecer lavada.
	//
	// So vale no modo escuro. Com passthrough ligado o compositor mostra o
	// quarto, o painel tem conteudo claro em toda parte, e ligar isto nao muda
	// nada -- por isso o modo e escolhido por frame, e nao uma vez na abertura.
	if(has_ext(XR_META_LOCAL_DIMMING_EXTENSION_NAME))
	{
		extensions.push_back(XR_META_LOCAL_DIMMING_EXTENSION_NAME);
		local_dimming_supported_ = true;
	}
	else
		LOGW("XR_META_local_dimming unavailable; black stays panel black");

	// Declara ao sistema qual thread desenha. Sem isso a thread do frame loop e
	// tratada como qualquer outra: pode ser escalonada num nucleo pequeno e
	// perder a fatia de tempo bem na hora de submeter o frame, o que aparece
	// como engasgo esporadico e nao como lentidao constante.
	if(has_ext(XR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME))
	{
		extensions.push_back(XR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME);
		thread_settings_supported_ = true;
	}
	else
		LOGW("XR_KHR_android_thread_settings unavailable; no render priority");

	// Deixa o runtime escolher o filtro de cada camada em vez de impor um.
	//
	// E o caminho certo depois do que o MQSR fez aqui: pedir explicitamente o
	// sharpening de qualidade produziu um disco claro acompanhando a cabeca, nas
	// duas formas de tela. Esta extensao inverte a decisao -- o compositor sabe
	// o tamanho aparente da camada, a densidade do painel e o que esta em volta,
	// e nos nao sabemos nada disso do lado de ca.
	if(has_ext(XR_META_AUTOMATIC_LAYER_FILTER_EXTENSION_NAME))
	{
		extensions.push_back(XR_META_AUTOMATIC_LAYER_FILTER_EXTENSION_NAME);
		auto_filter_supported_ = true;
	}

	// O runtime sabe dizer em que resolucao a camada deveria estar para casar
	// com a densidade do painel no tamanho em que ela aparece. Nao da para mudar
	// a resolucao da fonte -- 1080p e o teto do Remote Play --, mas da para
	// dizer ao usuario se a tela em que ele esta jogando desperdica pixels ou
	// pede mais do que existe, que e a informacao que falta para escolher o
	// tamanho com criterio em vez de por gosto.
	if(has_ext(XR_META_RECOMMENDED_LAYER_RESOLUTION_EXTENSION_NAME))
	{
		extensions.push_back(XR_META_RECOMMENDED_LAYER_RESOLUTION_EXTENSION_NAME);
		recommended_resolution_supported_ = true;
	}

	// Brilho e contraste da camada, aplicados pelo compositor.
	//
	// O caminho direto nao tem shader por definicao -- e o que o torna direto --,
	// entao ate agora nao havia nenhuma forma de mexer na imagem sem sair dele.
	// Isto e escala e viés por canal, feito na composicao: nao custa passada de
	// GPU, nao custa copia, e serve exatamente para amansar os brancos altos que
	// os 10 bits trazem.
	//
	// Nao substitui o mapeamento de tons: escala e reta, e curva PQ nao. Ela
	// abaixa a imagem inteira em vez de comprimir so as altas luzes. Ameniza, e
	// e o que da para ter de graca.
	if(has_ext(XR_KHR_COMPOSITION_LAYER_COLOR_SCALE_BIAS_EXTENSION_NAME))
	{
		extensions.push_back(XR_KHR_COMPOSITION_LAYER_COLOR_SCALE_BIAS_EXTENSION_NAME);
		color_scale_supported_ = true;
	}

	// XR_EXT_local_floor existe neste aparelho e fica de fora de proposito.
	//
	// Com ele a origem passaria a ser o chao em vez da cabeca na abertura, e a
	// altura salva -- que hoje significa "em relacao aos seus olhos" -- passaria
	// a significar "em relacao ao chao". A tela iria parar nos pes na primeira
	// abertura, e a unica saida seria zerar o ajuste de todo mundo. O ganho
	// seria uma altura estavel entre sessoes; o custo, quebrar a que ja esta
	// ajustada. Nao compensa.
	//
	// Saber quando o headset sai da cabeca. Por enquanto so registra: pausar o
	// stream sozinho seria decidir pelo usuario que tirar o aparelho por dez
	// segundos significa parar de jogar, e nem sempre significa.
	if(has_ext(XR_EXT_USER_PRESENCE_EXTENSION_NAME))
		extensions.push_back(XR_EXT_USER_PRESENCE_EXTENSION_NAME);

	// Nivel de CPU e GPU declarado, em vez de deixado ao governador.
	//
	// Sem isto o sistema decide pela carga observada, e o padrao dele e
	// conservador: um app que gasta pouca GPU -- que e o nosso caso, o
	// compositor faz quase tudo -- e lido como app que pode rodar em nucleo
	// pequeno e relogio baixo. O custo aparece como engasgo esporadico quando a
	// decodificacao e a submissao caem no mesmo instante.
	if(has_ext(XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME))
	{
		extensions.push_back(XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME);
		perf_settings_supported_ = true;
	}

	// Contadores do compositor: quadros descartados, tempo de quadro, uso de
	// GPU. E a metade da cadeia que nunca teve numero nenhum -- do lado da rede
	// o chiaki ja contava pacotes, mas do compositor para ca so havia
	// impressao.
	if(has_ext(XR_META_PERFORMANCE_METRICS_EXTENSION_NAME))
	{
		extensions.push_back(XR_META_PERFORMANCE_METRICS_EXTENSION_NAME);
		perf_metrics_supported_ = true;
	}

	XrInstanceCreateInfoAndroidKHR android_info{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
	android_info.applicationVM = vm_;
	android_info.applicationActivity = activity_;

	XrInstanceCreateInfo instance_info{XR_TYPE_INSTANCE_CREATE_INFO};
	instance_info.next = &android_info;
	instance_info.enabledExtensionCount = (uint32_t)extensions.size();
	instance_info.enabledExtensionNames = extensions.data();
	strcpy(instance_info.applicationInfo.applicationName, "P5M");
	instance_info.applicationInfo.applicationVersion = 1;
	strcpy(instance_info.applicationInfo.engineName, "P5M");
	instance_info.applicationInfo.engineVersion = 1;
	instance_info.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;

	for(const char *name : extensions)
		LOGI("Extension enabled: %s", name);

	XR_CHECK(XR_NULL_HANDLE, xrCreateInstance(&instance_info, &instance_), "xrCreateInstance");

	XrSystemGetInfo system_info{XR_TYPE_SYSTEM_GET_INFO};
	system_info.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
	XR_CHECK(instance_, xrGetSystem(instance_, &system_info, &system_id_), "xrGetSystem");

	if(passthrough_supported_)
	{
		XrSystemPassthroughProperties2FB pt_props{XR_TYPE_SYSTEM_PASSTHROUGH_PROPERTIES2_FB};
		XrSystemProperties sys_props{XR_TYPE_SYSTEM_PROPERTIES};
		sys_props.next = &pt_props;
		if(XR_SUCCEEDED(xrGetSystemProperties(instance_, system_id_, &sys_props)))
			passthrough_supported_ = (pt_props.capabilities & XR_PASSTHROUGH_CAPABILITY_BIT_FB) != 0;
	}

	if(!LoadExtensionFunctions())
		return false;

	// A extensao GL ES exige que xrGetOpenGLESGraphicsRequirementsKHR seja
	// chamada antes de xrCreateSession, mesmo que ignoremos o resultado.
	PFN_xrGetOpenGLESGraphicsRequirementsKHR pfnGetReq = nullptr;
	xrGetInstanceProcAddr(instance_, "xrGetOpenGLESGraphicsRequirementsKHR",
			(PFN_xrVoidFunction *)&pfnGetReq);
	if(pfnGetReq)
	{
		XrGraphicsRequirementsOpenGLESKHR reqs{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
		pfnGetReq(instance_, system_id_, &reqs);
	}

	if(!InitEgl())
		return false;

	XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
	binding.display = egl_display_;
	binding.config = egl_config_;
	binding.context = egl_context_;

	XrSessionCreateInfo session_info{XR_TYPE_SESSION_CREATE_INFO};
	session_info.next = &binding;
	session_info.systemId = system_id_;
	XR_CHECK(instance_, xrCreateSession(instance_, &session_info, &session_), "xrCreateSession");

	XrReferenceSpaceCreateInfo space_info{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
	space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
	space_info.poseInReferenceSpace.orientation = XrQuaternionf{0, 0, 0, 1};
	space_info.poseInReferenceSpace.position = XrVector3f{0, 0, 0};
	XR_CHECK(instance_, xrCreateReferenceSpace(session_, &space_info, &app_space_),
			"xrCreateReferenceSpace(LOCAL)");

	space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
	XR_CHECK(instance_, xrCreateReferenceSpace(session_, &space_info, &view_space_),
			"xrCreateReferenceSpace(VIEW)");

	// O app nao renderiza geometria, entao em tese nao precisa saber nada sobre
	// as views. Mas todo app OpenXR faz este handshake, e um runtime pode muito
	// bem so montar seu estado de view quando ele acontece -- e estado de view
	// faltando e exatamente o tipo de coisa que faz o xrEndFrame saltar para um
	// ponteiro nulo. Custa uma chamada na criacao.
	{
		uint32_t count = 0;
		XrResult res = xrEnumerateViewConfigurationViews(instance_, system_id_,
				XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &count, nullptr);
		if(XR_SUCCEEDED(res) && count > 0)
		{
			std::vector<XrViewConfigurationView> config(count,
					{XR_TYPE_VIEW_CONFIGURATION_VIEW});
			res = xrEnumerateViewConfigurationViews(instance_, system_id_,
					XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, &count, config.data());
			if(XR_SUCCEEDED(res))
			{
				view_count_ = count;
				views_.assign(count, XrView{XR_TYPE_VIEW});
				LOGI("Views: %u, recommended %ux%u", count,
						config[0].recommendedImageRectWidth,
						config[0].recommendedImageRectHeight);
			}
		}
		if(view_count_ == 0)
			LOGW("xrEnumerateViewConfigurationViews failed: %s", XrResultStr(instance_, res));
	}

	ApplyColorSpace();

	if(passthrough_supported_ && !InitPassthrough())
	{
		LOGW("Passthrough failed to initialize; carrying on without it");
		passthrough_supported_ = false;
	}

	// Antes de soltar o contexto, e nao depois: o swapchain do painel e de
	// textura GL, e o runtime precisa de um contexto corrente para aloca-la.
	// Criado depois da soltura, ele falhava com XR_ERROR_RUNTIME_FAILURE em
	// qualquer combinacao de usage e formato -- a mensagem nao dizia nada sobre
	// contexto, e o swapchain de video escapava por ser Surface e nao GL.
	if(!InitHudSwapchain())
		LOGW("Help panel unavailable; tuning mode will have no caption");

	// Solta o contexto nesta thread. Ele foi criado e ficou corrente aqui para
	// abrir a sessao, mas quem vai usa-lo e a thread do frame loop -- e o EGL
	// so permite um contexto corrente em uma thread por vez. Sem isto o
	// eglMakeCurrent do loop falha com EGL_BAD_ACCESS e a thread que chama
	// xrEndFrame fica sem contexto nenhum.
	if(!eglMakeCurrent(egl_display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT))
		LOGW("eglMakeCurrent(release) failed: 0x%x", eglGetError());

	LOGI("OpenXR session created (passthrough=%d)", (int)passthrough_supported_);
	return true;
}

bool XrVideoSession::LoadExtensionFunctions()
{
	auto get = [this](const char *name, PFN_xrVoidFunction *out) {
		XrResult res = xrGetInstanceProcAddr(instance_, name, out);
		if(XR_FAILED(res))
			LOGW("xrGetInstanceProcAddr(%s) failed", name);
		return XR_SUCCEEDED(res);
	};

	if(!get("xrCreateSwapchainAndroidSurfaceKHR",
			(PFN_xrVoidFunction *)&pfnCreateSwapchainAndroidSurfaceKHR))
	{
		LOGE("xrCreateSwapchainAndroidSurfaceKHR unavailable");
		return false;
	}

	if(color_space_supported_ && !get("xrSetColorSpaceFB", (PFN_xrVoidFunction *)&pfnSetColorSpaceFB))
		color_space_supported_ = false;
	if(refresh_rate_supported_)
	{
		get("xrEnumerateDisplayRefreshRatesFB", (PFN_xrVoidFunction *)&pfnEnumerateDisplayRefreshRatesFB);
		get("xrRequestDisplayRefreshRateFB", (PFN_xrVoidFunction *)&pfnRequestDisplayRefreshRateFB);
		if(!pfnEnumerateDisplayRefreshRatesFB || !pfnRequestDisplayRefreshRateFB)
			refresh_rate_supported_ = false;
	}
	if(thread_settings_supported_)
	{
		get("xrSetAndroidApplicationThreadKHR",
				(PFN_xrVoidFunction *)&pfnSetAndroidApplicationThreadKHR);
		if(!pfnSetAndroidApplicationThreadKHR)
			thread_settings_supported_ = false;
	}
	if(recommended_resolution_supported_)
	{
		get("xrGetRecommendedLayerResolutionMETA",
				(PFN_xrVoidFunction *)&pfnGetRecommendedLayerResolutionMETA);
		if(!pfnGetRecommendedLayerResolutionMETA)
			recommended_resolution_supported_ = false;
	}

	if(perf_settings_supported_)
	{
		get("xrPerfSettingsSetPerformanceLevelEXT",
				(PFN_xrVoidFunction *)&pfnPerfSettingsSetPerformanceLevelEXT);
		get("xrThermalGetTemperatureTrendEXT",
				(PFN_xrVoidFunction *)&pfnThermalGetTemperatureTrendEXT);
		if(!pfnPerfSettingsSetPerformanceLevelEXT)
			perf_settings_supported_ = false;
	}

	if(perf_metrics_supported_)
	{
		get("xrEnumeratePerformanceMetricsCounterPathsMETA",
				(PFN_xrVoidFunction *)&pfnEnumeratePerformanceMetricsCounterPathsMETA);
		get("xrSetPerformanceMetricsStateMETA",
				(PFN_xrVoidFunction *)&pfnSetPerformanceMetricsStateMETA);
		get("xrQueryPerformanceMetricsCounterMETA",
				(PFN_xrVoidFunction *)&pfnQueryPerformanceMetricsCounterMETA);
		if(!pfnSetPerformanceMetricsStateMETA || !pfnQueryPerformanceMetricsCounterMETA
				|| !pfnEnumeratePerformanceMetricsCounterPathsMETA)
			perf_metrics_supported_ = false;
	}

	if(passthrough_supported_)
	{
		get("xrPassthroughLayerSetStyleFB", (PFN_xrVoidFunction *)&pfnPassthroughLayerSetStyleFB);
		get("xrCreatePassthroughFB", (PFN_xrVoidFunction *)&pfnCreatePassthroughFB);
		get("xrDestroyPassthroughFB", (PFN_xrVoidFunction *)&pfnDestroyPassthroughFB);
		get("xrPassthroughStartFB", (PFN_xrVoidFunction *)&pfnPassthroughStartFB);
		get("xrPassthroughPauseFB", (PFN_xrVoidFunction *)&pfnPassthroughPauseFB);
		get("xrCreatePassthroughLayerFB", (PFN_xrVoidFunction *)&pfnCreatePassthroughLayerFB);
		get("xrDestroyPassthroughLayerFB", (PFN_xrVoidFunction *)&pfnDestroyPassthroughLayerFB);
		get("xrPassthroughLayerResumeFB", (PFN_xrVoidFunction *)&pfnPassthroughLayerResumeFB);
		get("xrPassthroughLayerPauseFB", (PFN_xrVoidFunction *)&pfnPassthroughLayerPauseFB);

		if(!pfnCreatePassthroughFB || !pfnCreatePassthroughLayerFB || !pfnPassthroughStartFB)
			passthrough_supported_ = false;
	}
	return true;
}

bool XrVideoSession::InitEgl()
{
	// O OpenXR exige um binding grafico para abrir a sessao. Como nao
	// desenhamos nada, este contexto so existe para satisfazer o runtime:
	// uma pbuffer 1x1 basta.
	egl_display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
	if(egl_display_ == EGL_NO_DISPLAY)
	{
		LOGE("eglGetDisplay failed");
		return false;
	}
	EGLint major = 0, minor = 0;
	if(!eglInitialize(egl_display_, &major, &minor))
	{
		LOGE("eglInitialize failed");
		return false;
	}

	const EGLint config_attribs[] = {
		EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
		EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
		EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
		EGL_DEPTH_SIZE, 0, EGL_STENCIL_SIZE, 0,
		EGL_NONE
	};
	EGLint num_configs = 0;
	if(!eglChooseConfig(egl_display_, config_attribs, &egl_config_, 1, &num_configs) || num_configs < 1)
	{
		LOGE("eglChooseConfig returned no valid configuration");
		return false;
	}

	const EGLint context_attribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
	egl_context_ = eglCreateContext(egl_display_, egl_config_, EGL_NO_CONTEXT, context_attribs);
	if(egl_context_ == EGL_NO_CONTEXT)
	{
		LOGE("eglCreateContext failed");
		return false;
	}

	const EGLint pbuffer_attribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
	egl_surface_ = eglCreatePbufferSurface(egl_display_, egl_config_, pbuffer_attribs);
	if(egl_surface_ == EGL_NO_SURFACE)
	{
		LOGE("eglCreatePbufferSurface failed");
		return false;
	}
	if(!eglMakeCurrent(egl_display_, egl_surface_, egl_surface_, egl_context_))
	{
		LOGE("eglMakeCurrent failed");
		return false;
	}

	LogGlCapabilities();
	return true;
}

/**
 * A lista de extensoes do GL, pelo mesmo motivo da lista do OpenXR.
 *
 * Tudo que se discutiu sobre upscaler e sintese de quadros na Adreno --
 * GL_QCOM_motion_estimation, processamento de imagem, o que mais houver -- e
 * documentacao da Qualcomm ate alguem perguntar a este aparelho. E ninguem
 * tinha perguntado: o contexto GL existe desde o primeiro commit e nunca
 * dissemos uma linha sobre o que ele oferece.
 *
 * Pelo indice, e nao pelo glGetString(GL_EXTENSIONS) de uma string so: em ES 3
 * a forma indexada e a correta, e a string unica pode vir truncada ou nula
 * conforme o driver.
 */
void XrVideoSession::LogGlCapabilities()
{
	const char *vendor = (const char *)glGetString(GL_VENDOR);
	const char *renderer = (const char *)glGetString(GL_RENDERER);
	const char *version = (const char *)glGetString(GL_VERSION);
	LOGI("GL: %s / %s / %s", vendor ? vendor : "?", renderer ? renderer : "?",
			version ? version : "?");

	GLint count = 0;
	glGetIntegerv(GL_NUM_EXTENSIONS, &count);
	if(count <= 0)
	{
		LOGW("GL did not return extensions by index");
		return;
	}

	LOGI("GL extensions available: %d", (int)count);
	std::string line;
	int on_line = 0;
	for(GLint i = 0; i < count; i++)
	{
		const char *name = (const char *)glGetStringi(GL_EXTENSIONS, (GLuint)i);
		if(!name)
			continue;
		line += "  ";
		line += name;
		// Quatro por linha: nome de extensao GL e mais longo que o de OpenXR, e
		// o log do Android corta perto de 4 KB.
		if(++on_line == 4 || i + 1 == count)
		{
			LOGI("  gl:%s", line.c_str());
			line.clear();
			on_line = 0;
		}
	}
}

void XrVideoSession::DestroyEgl()
{
	if(egl_display_ != EGL_NO_DISPLAY)
	{
		eglMakeCurrent(egl_display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
		if(egl_surface_ != EGL_NO_SURFACE)
			eglDestroySurface(egl_display_, egl_surface_);
		if(egl_context_ != EGL_NO_CONTEXT)
			eglDestroyContext(egl_display_, egl_context_);
		eglTerminate(egl_display_);
	}
	egl_display_ = EGL_NO_DISPLAY;
	egl_surface_ = EGL_NO_SURFACE;
	egl_context_ = EGL_NO_CONTEXT;
}

bool XrVideoSession::InitPassthrough()
{
	XrPassthroughCreateInfoFB pt_info{XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
	XR_CHECK(instance_, pfnCreatePassthroughFB(session_, &pt_info, &passthrough_),
			"xrCreatePassthroughFB");

	XrPassthroughLayerCreateInfoFB layer_info{XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
	layer_info.passthrough = passthrough_;
	layer_info.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
	XR_CHECK(instance_, pfnCreatePassthroughLayerFB(session_, &layer_info, &passthrough_layer_),
			"xrCreatePassthroughLayerFB");

	// Criado ligado, mas so entra na composicao quando o usuario pedir.
	XR_CHECK(instance_, pfnPassthroughStartFB(passthrough_), "xrPassthroughStartFB");
	if(pfnPassthroughLayerPauseFB)
		pfnPassthroughLayerPauseFB(passthrough_layer_);
	return true;
}

void XrVideoSession::ApplyColorSpace()
{
	if(!color_space_supported_)
		return;

	// O padrao do Horizon OS e Display P3, mais amplo que qualquer coisa que o
	// Remote Play envie. Sem fixar, vermelho e verde saem estourados: parece
	// "vivo", mas e cor errada.
	//
	// Qual espaco fixar depende da profundidade. Em 8 bits o console manda
	// Rec.709. Em 10 bits vem o perfil HDR, e com ele o gamut BT.2020 -- que
	// continua BT.2020 mesmo depois de o decodificador mapear a curva para SDR,
	// porque mapeamento de tons trata de luminancia, nao de primarias. Declarar
	// 709 sobre conteudo 2020 faz o compositor esticar as cores para um gamut
	// que nao e o delas, e o resultado e imagem clara e lavada.
	const bool wide = wide_color_.load();
	const XrColorSpaceFB space = wide ? XR_COLOR_SPACE_REC2020_FB : XR_COLOR_SPACE_REC709_FB;
	XrResult res = pfnSetColorSpaceFB(session_, space);
	if(XR_FAILED(res))
		LOGW("xrSetColorSpaceFB(%s) failed: %s", wide ? "REC2020" : "REC709",
				XrResultStr(instance_, res));
	else
		LOGI("Color space pinned to %s", wide ? "Rec.2020" : "Rec.709");
}

void XrVideoSession::SetWideColor(bool wide)
{
	if(wide_color_.exchange(wide) == wide)
		return;
	if(session_ != XR_NULL_HANDLE)
		ApplyColorSpace();
}

bool XrVideoSession::InitHudSwapchain()
{
	uint32_t format_count = 0;
	if(XR_FAILED(xrEnumerateSwapchainFormats(session_, 0, &format_count, nullptr))
			|| format_count == 0)
		return false;
	std::vector<int64_t> formats(format_count);
	if(XR_FAILED(xrEnumerateSwapchainFormats(session_, format_count, &format_count,
			formats.data())))
		return false;

	// GL_SRGB8_ALPHA8 primeiro: o compositor faz a conversao para linear na
	// amostragem, entao o painel sai com a mesma clareza em que foi desenhado.
	// GL_RGBA8 serve de reserva.
	const int64_t kSrgb8Alpha8 = 0x8C43;
	const int64_t kRgba8 = 0x8058;
	int64_t chosen = 0;
	for(int64_t candidate : { kSrgb8Alpha8, kRgba8 })
	{
		for(int64_t f : formats)
		{
			if(f == candidate)
			{
				chosen = candidate;
				break;
			}
		}
		if(chosen != 0)
			break;
	}
	if(chosen == 0)
	{
		// Sem os formatos em maos nao da para escolher o proximo candidato sem
		// mais uma rodada de build, entao eles vao para o log.
		std::string list;
		for(int64_t f : formats)
		{
			char buf[16];
			snprintf(buf, sizeof(buf), "0x%llx ", (unsigned long long)f);
			list += buf;
		}
		LOGW("No 8-bit RGBA format for the panel. Available: %s", list.c_str());
		return false;
	}

	// A primeira tentativa pediu SAMPLED|TRANSFER_DST e o runtime respondeu
	// XR_ERROR_RUNTIME_FAILURE, sem dizer o que recusou. Como a combinacao
	// aceita nao esta documentada, o codigo tenta as plausiveis em ordem, da
	// mais comum para a mais especifica, e registra qual passou -- adivinhar
	// custa uma rodada de build por tentativa.
	const struct { XrSwapchainUsageFlags usage; const char *name; } kUsages[] = {
		{ XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT,
				"SAMPLED|COLOR_ATTACHMENT" },
		{ XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT
				| XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT, "SAMPLED|COLOR_ATTACHMENT|TRANSFER_DST" },
		{ XR_SWAPCHAIN_USAGE_SAMPLED_BIT, "SAMPLED" },
		{ XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT, "COLOR_ATTACHMENT" },
	};

	LOGI("Help panel: format 0x%llx chosen out of %u available",
			(unsigned long long)chosen, format_count);

	XrResult res = XR_ERROR_RUNTIME_FAILURE;
	for(const auto &attempt : kUsages)
	{
		XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
		info.usageFlags = attempt.usage;
		info.format = chosen;
		info.sampleCount = 1;
		info.width = (uint32_t)kHudWidth;
		info.height = (uint32_t)kHudHeight;
		info.faceCount = 1;
		info.arraySize = 1;
		info.mipCount = 1;
		res = xrCreateSwapchain(session_, &info, &hud_swapchain_);
		if(XR_SUCCEEDED(res))
		{
			LOGI("Help panel: usage %s accepted", attempt.name);
			break;
		}
		LOGW("Help panel: usage %s refused (%s)", attempt.name,
				XrResultStr(instance_, res));
		hud_swapchain_ = XR_NULL_HANDLE;
	}
	if(XR_FAILED(res))
		return false;

	uint32_t image_count = 0;
	if(XR_FAILED(xrEnumerateSwapchainImages(hud_swapchain_, 0, &image_count, nullptr))
			|| image_count == 0)
		return false;
	hud_images_.assign(image_count, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
	if(XR_FAILED(xrEnumerateSwapchainImages(hud_swapchain_, image_count, &image_count,
			reinterpret_cast<XrSwapchainImageBaseHeader *>(hud_images_.data()))))
		return false;

	LOGI("Help panel: %dx%d, format 0x%llx, %u images", kHudWidth, kHudHeight,
			(unsigned long long)chosen, image_count);
	return true;
}

void XrVideoSession::SetHudBitmap(const uint8_t *pixels, int32_t width, int32_t height,
		int32_t stride)
{
	if(!pixels || width <= 0 || height <= 0 || stride < width * 4)
		return;
	std::lock_guard<std::mutex> lock(hud_mutex_);
	hud_width_ = width;
	hud_height_ = height;
	// Linha a linha: o Bitmap pode ter enchimento no fim de cada linha, e a
	// copia corrida o arrastaria para dentro da imagem.
	const size_t linha = (size_t)width * 4;
	hud_pixels_.resize(linha * height);
	for(int32_t y = 0; y < height; y++)
		std::memcpy(hud_pixels_.data() + linha * y, pixels + (size_t)stride * y, linha);
	hud_uploads_pending_ = (uint32_t)std::max<size_t>(hud_images_.size(), 1);
}

bool XrVideoSession::UpdateHudLayer(XrCompositionLayerQuad &layer, float yaw,
		const ScreenParams &screen, bool below_screen)
{
	if(hud_swapchain_ == XR_NULL_HANDLE || hud_images_.empty())
		return false;

	uint32_t index = 0;
	XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
	if(XR_FAILED(xrAcquireSwapchainImage(hud_swapchain_, &acquire, &index)))
		return false;

	XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
	wait.timeout = XR_INFINITE_DURATION;
	if(XR_SUCCEEDED(xrWaitSwapchainImage(hud_swapchain_, &wait)))
	{
		std::lock_guard<std::mutex> lock(hud_mutex_);
		// O swapchain tem varias imagens em rodizio, entao cada painel novo vai
		// para todas -- um bit de "sujo" so acertaria uma delas e o painel
		// piscaria entre o novo e o antigo. Mas so enquanto houver imagem sem
		// ele: antes eram 3,8 MB subindo a cada quadro, 120 vezes por segundo,
		// com o painel parado.
		if(hud_uploads_pending_ > 0 && !hud_pixels_.empty()
				&& hud_width_ == kHudWidth && hud_height_ == kHudHeight)
		{
			glBindTexture(GL_TEXTURE_2D, hud_images_[index].image);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kHudWidth, kHudHeight,
					GL_RGBA, GL_UNSIGNED_BYTE, hud_pixels_.data());
			glBindTexture(GL_TEXTURE_2D, 0);
			hud_uploads_pending_--;
			hud_has_content_ = true;
		}
	}

	XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
	xrReleaseSwapchainImage(hud_swapchain_, &release);
	if(!hud_has_content_)
		return false;

	layer.type = XR_TYPE_COMPOSITION_LAYER_QUAD;
	// Mistura por alfa: o painel tem cantos e fundo translucido, e o Canvas do
	// Android entrega alfa ja pre-multiplicado, que e o que o OpenXR assume.
	layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
	layer.space = app_space_;
	layer.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
	layer.subImage.swapchain = hud_swapchain_;
	layer.subImage.imageRect.offset = XrOffset2Di{0, 0};
	layer.subImage.imageRect.extent = XrExtent2Di{kHudWidth, kHudHeight};
	layer.subImage.imageArrayIndex = 0;

	// Ancorado no espaco, logo abaixo da tela do jogo.
	//
	// Nao segue a cabeca: uma referencia que se move enquanto se le deixa de
	// ser referencia. Segue a tela so no angulo -- o topo do painel fica um
	// pouco abaixo da borda de baixo dela, na distancia de leitura, inclinado
	// para ficar de frente para o olho. Assim ele tapa o menos possivel do jogo,
	// que era a queixa com o quadro antigo no meio da vista (dev.210).
	const float width = kHudWidth1M;
	const float height = kHudWidth1M * (float)kHudHeight / (float)kHudWidth;
	const float half_angle = std::atan2(height * 0.5f, kHudDistance);
	float pitch = kHudCenterPitch;
	if(below_screen && screen.aspectRatio > 0.0f && screen.radius > 0.0f)
	{
		// Altura aparente da tela, pela mesma conta do quad equivalente.
		const float screen_w = 2.0f * screen.radius * std::tan(screen.centralAngle * 0.5f);
		const float screen_h = screen_w / screen.aspectRatio;
		// Inclinada, a borda de baixo desce menos e se afasta um pouco.
		const float tilt = ScreenTilt(screen);
		const float bottom = std::atan2(screen.heightOffset - std::cos(tilt) * screen_h * 0.5f,
				screen.radius + std::sin(tilt) * screen_h * 0.5f);
		pitch = std::max(kHudMinPitch, bottom - kHudGapRadians - half_angle);
	}
	layer.size = XrExtent2Df{width, height};
	layer.pose.orientation = QuatFromYawPitch(yaw, pitch);
	layer.pose.position = XrVector3f{
		-std::sin(yaw) * std::cos(pitch) * kHudDistance,
		std::sin(pitch) * kHudDistance,
		-std::cos(yaw) * std::cos(pitch) * kHudDistance
	};
	return true;
}

bool XrVideoSession::CreateGlVideoSwapchain(int32_t width, int32_t height)
{
	// No estereo sintetizado nascem dois swapchains do tamanho da fonte, um por
	// olho, em vez de um do dobro da largura com um olho em cada metade.
	//
	// Os pixels sao os mesmos. O que muda e como a camada se submete: com dois
	// alvos inteiros cada olho e uma imagem completa, e com um alvo dobrado
	// cada olho e um recorte. Sobre recorte o filtro de nitidez do compositor
	// desenhou um X atravessando a tela -- medido em hardware --, e por isso o
	// 3D vinha sem MQSR nenhum, que e o maior ganho de nitidez que existe aqui.
	const bool dois_alvos = stereo_synthetic_.load();

	uint32_t format_count = 0;
	if(XR_FAILED(xrEnumerateSwapchainFormats(session_, 0, &format_count, nullptr))
			|| format_count == 0)
		return false;
	std::vector<int64_t> formats(format_count);
	if(XR_FAILED(xrEnumerateSwapchainFormats(session_, format_count, &format_count,
			formats.data())))
		return false;

	// sRGB de preferencia: o shader escreve luz linear, e num alvo sRGB o
	// proprio GL faz a codificacao na escrita. Com RGBA8 comum a conversao teria
	// de entrar no shader, e o resultado seria o mesmo por um caminho pior.
	const int64_t kSrgb8Alpha8 = 0x8C43;
	const int64_t kRgba8 = 0x8058;
	int64_t chosen = 0;
	for(int64_t candidate : { kSrgb8Alpha8, kRgba8 })
	{
		for(int64_t f : formats)
			if(f == candidate) { chosen = candidate; break; }
		if(chosen != 0)
			break;
	}
	if(chosen == 0)
	{
		LOGE("No 8-bit RGBA format for the video");
		return false;
	}

	XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
	info.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
	info.format = chosen;
	info.sampleCount = 1;
	info.width = (uint32_t)width;
	info.height = (uint32_t)height;
	info.faceCount = 1;
	info.arraySize = 1;
	info.mipCount = 1;
	XrResult res = xrCreateSwapchain(session_, &info, &video_swapchain_);
	if(XR_FAILED(res))
	{
		LOGE("xrCreateSwapchain(video GL): %s", XrResultStr(instance_, res));
		video_swapchain_ = XR_NULL_HANDLE;
		return false;
	}

	// Sair daqui sem desfazer o swapchain deixaria o handle vivo com a lista de
	// imagens vazia -- um estado que mente para todo mundo que pergunta se ha
	// video, porque a pergunta e feita pelo handle.
	uint32_t image_count = 0;
	if(XR_FAILED(xrEnumerateSwapchainImages(video_swapchain_, 0, &image_count, nullptr))
			|| image_count == 0)
	{
		xrDestroySwapchain(video_swapchain_);
		video_swapchain_ = XR_NULL_HANDLE;
		return false;
	}
	video_images_.assign(image_count, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
	if(XR_FAILED(xrEnumerateSwapchainImages(video_swapchain_, image_count, &image_count,
			reinterpret_cast<XrSwapchainImageBaseHeader *>(video_images_.data()))))
	{
		xrDestroySwapchain(video_swapchain_);
		video_swapchain_ = XR_NULL_HANDLE;
		video_images_.clear();
		return false;
	}

	// O runtime cria os nomes de textura no contexto EGL corrente. Se nao houver
	// contexto na thread que chama, o xrCreateSwapchain ainda devolve XR_SUCCESS
	// e o enumerate ainda devolve a contagem certa -- so que todas as texturas
	// vem zeradas, e o framebuffer que as recebe fica incompleto sem que nada
	// antes disso tenha acusado erro. Vale conferir em vez de descobrir tres
	// camadas adiante.
	for(uint32_t i = 0; i < image_count; i++)
	{
		if(video_images_[i].image == 0)
		{
			LOGE("The GL video swapchain returned a zeroed texture at index %u: "
					"there is no EGL context current on this thread", i);
			xrDestroySwapchain(video_swapchain_);
			video_swapchain_ = XR_NULL_HANDLE;
			video_images_.clear();
			return false;
		}
	}

	video_gl_format_ = chosen;
	video_width_ = width;
	video_height_ = height;

	if(dois_alvos)
	{
		XrResult res2 = xrCreateSwapchain(session_, &info, &video_swapchain_right_);
		if(XR_FAILED(res2))
		{
			// Sem o segundo alvo o 3D nao tem para onde ir. Derruba o primeiro
			// tambem: meio caminho aqui viraria camada apontando para swapchain
			// nulo dentro do xrEndFrame, que e queda nativa e nao imagem
			// faltando.
			LOGE("xrCreateSwapchain(right eye): %s", XrResultStr(instance_, res2));
			xrDestroySwapchain(video_swapchain_);
			video_swapchain_ = XR_NULL_HANDLE;
			video_images_.clear();
			video_swapchain_right_ = XR_NULL_HANDLE;
			return false;
		}

		uint32_t direita = 0;
		xrEnumerateSwapchainImages(video_swapchain_right_, 0, &direita, nullptr);
		video_images_right_.assign(direita, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
		if(direita == 0 || XR_FAILED(xrEnumerateSwapchainImages(video_swapchain_right_,
				direita, &direita,
				reinterpret_cast<XrSwapchainImageBaseHeader *>(video_images_right_.data()))))
		{
			LOGE("The right eye swapchain returned no images");
			xrDestroySwapchain(video_swapchain_right_);
			video_swapchain_right_ = XR_NULL_HANDLE;
			video_images_right_.clear();
			xrDestroySwapchain(video_swapchain_);
			video_swapchain_ = XR_NULL_HANDLE;
			video_images_.clear();
			return false;
		}
		LOGI("Synthetic 3D: a second %dx%d swapchain for the right eye, %u images",
				width, height, direita);
	}

	LOGI("GL video swapchain: %dx%d, format 0x%llx, %u images", width, height,
			(unsigned long long)chosen, image_count);
	return true;
}

bool XrVideoSession::AttachSurfaceTexture(JNIEnv *env, jobject surface_texture)
{
	if(render_path_ != RenderPath::ToneMapped)
	{
		LOGW("SurfaceTexture handed over on the direct path; ignoring");
		return false;
	}
	ASurfaceTexture *native = ASurfaceTexture_fromSurfaceTexture(env, surface_texture);
	if(!native)
	{
		last_error_ = "ASurfaceTexture_fromSurfaceTexture failed";
		LOGE("%s", last_error_.c_str());
		return false;
	}
	// Guardada para a thread do frame loop: a textura externa e o anexo
	// pertencem ao contexto EGL de la, nao ao desta chamada.
	pending_surface_texture_ = native;
	return true;
}

bool XrVideoSession::RenderVideoThroughShader()
{
	if(video_swapchain_ == XR_NULL_HANDLE && pending_gl_width_ > 0)
	{
		int32_t w = pending_gl_width_;
		int32_t h = pending_gl_height_;
		// Zerado antes da tentativa: se falhar, falha uma vez, e nao um pedido
		// novo a cada quadro pelo resto da sessao.
		pending_gl_width_ = 0;
		pending_gl_height_ = 0;
		if(!CreateGlVideoSwapchain(w, h))
		{
			last_error_ = "Could not create the video swapchain for the shader";
			LOGE("%s", last_error_.c_str());
			return false;
		}
	}
	if(video_swapchain_ == XR_NULL_HANDLE)
		return false;

	if(pending_surface_texture_ && !tone_mapper_ready_)
	{
		if(!tone_mapper_.Init() || !tone_mapper_.Attach(pending_surface_texture_))
		{
			LOGE("Tone mapper unavailable; nothing will be displayed");
			ASurfaceTexture_release(pending_surface_texture_);
			pending_surface_texture_ = nullptr;
			return false;
		}
		tone_mapper_ready_ = true;
		pending_surface_texture_ = nullptr;
		// Depois do Init, que e quem cria o contexto de GL deste lado, e com o
		// formato do swapchain na mao: a extensao exige que o historico tenha
		// dimensao e formato iguais aos do destino.
		tone_mapper_.EnableExtrapolation((GLenum)video_gl_format_);
	}
	if(!tone_mapper_ready_ || video_images_.empty())
		return false;

	uint32_t index = 0;
	XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
	if(XR_FAILED(xrAcquireSwapchainImage(video_swapchain_, &acquire, &index)))
		return false;

	XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
	wait.timeout = XR_INFINITE_DURATION;
	bool drawn = false;
	float sharpen = 0.0f;
	bool extrapolate = false;
	Sharpness nitidez = Sharpness::Off;
	{
		std::lock_guard<std::mutex> lock(params_mutex_);
		sharpen = quality_.sharpenAmount;
		extrapolate = quality_.frameExtrapolation;
		nitidez = quality_.sharpness;
	}

	// Orcamento de disparidade do olho sintetizado, um para cada lado da tela.
	//
	// A conta e feita em dioptrias -- o inverso da distancia --, e nao em
	// metros, porque e assim que o olho trabalha: a diferenca entre 1 e 2 m
	// cansa, e a mesma diferenca de um metro entre 10 e 11 m nao existe para
	// ninguem. E o unico jeito de um mesmo ajuste valer com a tela a 1 m e a
	// 2,5 m, que e onde este app e usado.
	//
	// A referencia e o plano em que as lentes do aparelho focam, e nao a tela.
	// O foco do Quest 3 e fixo, em torno de 1,3 m (a Meta nao publica o numero,
	// entao isto e estimativa), e o olho acomoda sempre nele, esteja a tela
	// onde estiver. Quanto mais a convergencia se afasta desse plano, mais a
	// vergencia briga com a acomodacao -- e esse conflito e o que da o
	// cansaco que nao se sabe de onde vem. Meia dioptria para tras e 0,3 para
	// a frente ficam dentro da faixa em que a literatura poe o desconforto
	// como baixo.
	//
	// Duas travas em cima disso, e as duas dependem da IPD do usuario, lida do
	// runtime:
	//  - Separacao na tela igual a IPD e o infinito, e maior que ela obriga os
	//    olhos a divergir, o que e impossivel. O teto e 70% dela.
	//  - Piso de 40% da IPD, senao numa tela longe a conta em dioptrias
	//    devolveria profundidade nenhuma.
	//
	// A frente ganha ainda um teto em angulo, porque o que salta da tela e o
	// que briga com a moldura.
	//
	// A versao anterior usava 70% da IPD sempre, sem olhar a distancia da tela.
	// Com a tela a 1 m isso punha o fundo a 3,3 m -- 0,7 dioptria de conflito,
	// e a faixa perdida na borda passava de 3% da largura, que foi o corte
	// visivel no teste da dev.181.
	//
	if(stereo_synthetic_.load())
	{
		ScreenParams params;
		{
			std::lock_guard<std::mutex> lock(params_mutex_);
			params = params_;
		}
		// Sem leitura valida do runtime ainda, a media adulta.
		float ipd = ipd_meters_.load();
		if(!(ipd > 0.0f))
			ipd = 0.063f;
		const float distancia = params.radius > 0.01f ? params.radius : 4.0f;
		const float angulo = params.centralAngle > 0.01f ? params.centralAngle : 1.22f;
		const float largura_m = std::max(2.0f * distancia * std::tan(angulo * 0.5f), 0.01f);
		const float forca = std::min(std::max(stereo_strength_.load(), 0.0f), 1.0f);

		// Onde as lentes focam, em dioptrias, e o quanto a vergencia pode
		// afastar-se disso para cada lado.
		const float foco = 1.0f / 1.3f;
		const float atras_d = 0.5f;
		const float frente_d = 0.3f;

		// O fundo: a vergencia mais distante admitida, presa entre o teto da
		// divergencia (70% da IPD) e o piso de profundidade (40% dela).
		const float v_tela = 1.0f / distancia;
		const float v_fundo = std::min(std::max(foco - atras_d, 0.30f * v_tela),
				0.60f * v_tela);

		// Teto de qualidade, e nao de conforto: 1% da largura.
		//
		// O conforto admite bem mais -- na tela a 1,5 m a conta em dioptrias
		// dava 2,4% --, mas quem carrega a disparidade e um palpite de
		// profundidade, e o erro dele cresce junto. Medido na dev.183: acima de
		// cerca de 1% da largura o palpite aparece como borracha, com o objeto
		// esticando e ondulando na borda, e a imagem parece pior do que em 2D.
		// Foi o "50, 75 e 100% ficaram impossiveis de usar" do teste.
		//
		// Isto nao e teimosia do numero: se um dia a profundidade vier de uma
		// rede neural, este teto sobe junto com a confianca no mapa. Ele existe
		// porque o limite de hoje e a estimativa, e nao o olho.
		const float teto_qualidade = 0.010f;
		// Separacao na tela, em metros, e depois em fracao da largura.
		const float atras = std::min(ipd * (1.0f - distancia * v_fundo) / largura_m,
				teto_qualidade) * forca;

		// A frente, pelo mesmo caminho, e ainda limitada em angulo.
		// Pop-out (dev.214): a frente ganha mais teto e mais angulo, a
		// convergencia desce para haver o que saltar, e a moldura flutuante vai
		// na frente de tudo isso. Na dev.216 o dono achou quase imperceptivel
		// (moldura de 0,1 a 1,25%); a dev.217 dobra teto e angulo e desce a
		// convergencia a 45%. O teto e o que segura a borracha do palpite de
		// profundidade: se ela aparecer nas bordas, e aqui que se recua.
		const bool salta = stereo_pop_out_.load();
		const float v_perto = foco + (salta ? frente_d * 2.0f : frente_d);
		const float frente_m = std::max(ipd * (distancia * v_perto - 1.0f), 0.0f);
		const float frente = std::min(std::min(frente_m / largura_m,
				((salta ? 1.4f : 0.35f) * 3.14159265f / 180.0f) / angulo),
				teto_qualidade * (salta ? 2.0f : 0.5f)) * forca;
		// Com a convergencia em 100% nada fica a frente da tela: o Pop-out a
		// traz para no maximo 45%, e a parte mais perto da cena salta.
		const float convergencia = salta ? std::min(stereo_convergence_.load(), 0.45f)
				: stereo_convergence_.load();
		// A moldura na disparidade maxima da frente, e um pouco alem: o que
		// salta ao maximo continua atras dela.
		const float janela = salta ? frente * 1.15f + 0.001f : 0.0f;
		tone_mapper_.SetStereo(true, atras, frente, convergencia, !vertical_flip_.load(), janela);

		// Uma linha quando o que vale muda. A forca em porcentagem nao diz
		// quanto a cena afunda -- isso depende da IPD, da distancia e do
		// tamanho da tela --, e foi por o diario nao mostrar o valor aplicado
		// que um teto engoliu o botao de forca sem ninguem ver.
		if(std::fabs(atras - logged_stereo_back_) > 1e-5f
				|| std::fabs(convergencia - logged_stereo_convergence_) > 1e-4f
				|| salta != logged_pop_out_)
		{
			logged_pop_out_ = salta;
			if(salta)
				LOGI("3D pop-out: floating window %.2f%% of width per eye", (double)(janela * 100.0f));
			logged_stereo_back_ = atras;
			logged_stereo_convergence_ = convergencia;
			const float fundo = v_fundo > 1e-3f ? 1.0f / v_fundo : 0.0f;
			const float perto = frente > 0.0f ? 1.0f / v_perto : distancia;
			LOGI("3D disparity: behind %.2f%% of width (far end ~%.1f m), in front %.2f%% "
					"(near end ~%.1f m), convergence %.0f%%, one-eye edge strip up to %.2f%% | "
					"IPD %.1f mm, screen %.2f m wide at %.1f m",
					(double)(atras * 100.0f), (double)fundo, (double)(frente * 100.0f),
					(double)perto, (double)(convergencia * 100.0f),
					(double)(100.0f * std::max(atras, frente)),
					(double)(ipd * 1000.0f), (double)largura_m, (double)distancia);
		}
	}
	else
		tone_mapper_.SetStereo(false, 0.0f, 0.0f, 1.0f, true);

	// O olho direito, quando existe, e adquirido junto: o desenho dos dois
	// acontece numa passada so do tone mapper, que assim consome um quadro do
	// decodificador e nao dois.
	uint32_t index_right = 0;
	bool right_acquired = false;
	// Zerado a cada quadro: a submissao le isto depois, e um valor herdado do
	// quadro anterior mandaria o compositor compor uma imagem que ninguem
	// escreveu -- e, na primeira falha, uma que nunca foi liberada.
	right_layer_ready_ = false;
	if(video_swapchain_right_ != XR_NULL_HANDLE)
	{
		XrSwapchainImageAcquireInfo acquire_right{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
		if(XR_SUCCEEDED(xrAcquireSwapchainImage(video_swapchain_right_, &acquire_right,
				&index_right)))
		{
			XrSwapchainImageWaitInfo wait_right{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
			wait_right.timeout = XR_INFINITE_DURATION;
			right_acquired = XR_SUCCEEDED(xrWaitSwapchainImage(video_swapchain_right_,
					&wait_right));
			if(!right_acquired)
			{
				// Adquirida e nao esperada continua sendo nossa: sem soltar, o
				// rodizio do swapchain trava no quadro seguinte.
				XrSwapchainImageReleaseInfo solta{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
				xrReleaseSwapchainImage(video_swapchain_right_, &solta);
			}
		}
	}

	if(XR_SUCCEEDED(xrWaitSwapchainImage(video_swapchain_, &wait)))
		drawn = tone_mapper_.Render(video_images_[index].image, video_width_, video_height_,
				tone_map_pq_.load(), sharpen, false, extrapolate,
				right_acquired ? video_images_right_[index_right].image : 0);

	XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
	xrReleaseSwapchainImage(video_swapchain_, &release);
	if(right_acquired)
	{
		XrSwapchainImageReleaseInfo release_right{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
		xrReleaseSwapchainImage(video_swapchain_right_, &release_right);
		right_layer_ready_ = drawn;
	}
	return drawn;
}

jobject XrVideoSession::CreateVideoSurface(JNIEnv *env, int32_t width, int32_t height)
{
	if(session_ == XR_NULL_HANDLE || video_swapchain_ != XR_NULL_HANDLE
			|| pending_gl_width_ > 0)
	{
		LOGE("CreateVideoSurface called out of order");
		return nullptr;
	}

	// No caminho com shader quem produz o Surface e o Java, a partir de uma
	// SurfaceTexture: aqui so o swapchain de destino precisa existir.
	if(render_path_ == RenderPath::ToneMapped)
	{
		// O swapchain nao pode nascer aqui. Esta chamada vem da thread do Java,
		// e a essa altura o Create() ja soltou o contexto EGL para entrega-lo ao
		// frame loop -- o runtime criaria as texturas sem contexto nenhum e
		// devolveria zeros. Ficam so as medidas; quem cria e o frame loop, no
		// primeiro quadro, com o contexto na mao.
		pending_gl_width_ = width;
		pending_gl_height_ = height;
		{
			std::lock_guard<std::mutex> lock(params_mutex_);
			params_.aspectRatio = (float)width / (float)height;
		}
		return nullptr;
	}

	// Ver o comentario na selecao de extensoes: sem este elo, o swapchain nasce
	// pela metade e o runtime salta para ponteiro nulo dentro do xrEndFrame na
	// primeira submissao que o referencia.
	//
	// createFlags fica em zero de proposito. SYNCHRONOUS faria o compositor
	// enfileirar buffers em vez de sempre pegar o mais recente, e USE_TIMESTAMPS
	// o faria esperar o buffer com timestamp certo: os dois trocam latencia por
	// sincronia de A/V, que e o oposto do que este projeto quer.
	XrAndroidSurfaceSwapchainCreateInfoFB surface_info{
			XR_TYPE_ANDROID_SURFACE_SWAPCHAIN_CREATE_INFO_FB};
	surface_info.createFlags = 0;

	XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
	if(surface_swapchain_create_supported_)
		info.next = &surface_info;
	// A especificacao de XR_KHR_android_surface_swapchain e explicita:
	//
	//   "The format, sampleCount, faceCount, arraySize and mipCount members of
	//    the structure passed as the info parameter must be zero."
	//
	// Nao sao ignorados, sao *obrigatoriamente zero*. Passar 1 -- o valor que
	// faria sentido num swapchain de textura comum -- devolve
	// XR_ERROR_VALIDATION_FAILURE. Aqui quem produz as imagens e o MediaCodec
	// pelo lado Surface, entao o runtime nao tem formato nem niveis para alocar.
	info.createFlags = 0;
	info.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
	info.format = 0;
	info.sampleCount = 0;
	info.width = (uint32_t)width;
	info.height = (uint32_t)height;
	info.faceCount = 0;
	info.arraySize = 0;
	info.mipCount = 0;

	jobject surface = nullptr;
	XrResult res = pfnCreateSwapchainAndroidSurfaceKHR(session_, &info, &video_swapchain_, &surface);
	if(XR_FAILED(res) || surface == nullptr)
	{
		last_error_ = std::string("xrCreateSwapchainAndroidSurfaceKHR: ")
				+ XrResultStr(instance_, res)
				+ (surface == nullptr ? " (surface nula)" : "");
		LOGE("%s", last_error_.c_str());
		video_swapchain_ = XR_NULL_HANDLE;
		return nullptr;
	}

	video_width_ = width;
	video_height_ = height;
	// O decodificador desta sessao ainda nao contou o tamanho; o da sessao
	// anterior podia ser de outra resolucao, e cortaria errado ate a primeira
	// contagem nova. Este Surface e que vai nascer o decodificador, entao
	// zerar aqui vem sempre antes.
	g_decoder_height.store(0);
	logged_direct_rows_ = -1;
	{
		std::lock_guard<std::mutex> lock(params_mutex_);
		params_.aspectRatio = (float)width / (float)height;
	}

	LOGI("Video swapchain created: %dx%d (create_info_fb=%d)", width, height,
			(int)surface_swapchain_create_supported_);
	// Devolvida como veio: o retorno do JNI ja entrega ao Kotlin uma
	// referencia que o coletor acompanha. A global que ia aqui nunca era
	// apagada, e prendia um Surface por sessao ate o processo morrer.
	return surface;
}

void XrVideoSession::SetScreenParams(const ScreenParams &params)
{
	std::lock_guard<std::mutex> lock(params_mutex_);
	float aspect = params_.aspectRatio;
	params_ = params;
	// O aspecto vem do video, nao da UI.
	if(video_width_ > 0)
		params_.aspectRatio = aspect;
}

void XrVideoSession::SetQualityParams(const QualityParams &params)
{
	{
		std::lock_guard<std::mutex> lock(params_mutex_);
		if(params.frameExtrapolation != quality_.frameExtrapolation)
			LOGI("Quality: frame extrapolation %s", params.frameExtrapolation ? "on" : "off");
		quality_ = params;
	}
	if(passthrough_layer_ != XR_NULL_HANDLE && pfnPassthroughLayerSetStyleFB)
	{
		XrPassthroughStyleFB style{XR_TYPE_PASSTHROUGH_STYLE_FB};
		style.textureOpacityFactor = params.passthroughOpacity;
		style.edgeColor = XrColor4f{0.0f, 0.0f, 0.0f, 0.0f};

		// So encadeia quando ha o que mudar. Nos valores neutros o elo nao
		// acrescenta nada, e um runtime que nao conheca a estrutura recusaria o
		// estilo inteiro -- perder a opacidade por causa de um pedido que nao
		// pedia nada seria troca ruim.
		XrPassthroughBrightnessContrastSaturationFB grade{
				XR_TYPE_PASSTHROUGH_BRIGHTNESS_CONTRAST_SATURATION_FB};
		const bool graded = params.passthroughBrightness != 0.0f
				|| params.passthroughContrast != 1.0f
				|| params.passthroughSaturation != 1.0f;
		if(graded)
		{
			grade.brightness = params.passthroughBrightness;
			grade.contrast = params.passthroughContrast;
			grade.saturation = params.passthroughSaturation;
			style.next = &grade;
		}

		XrResult res = pfnPassthroughLayerSetStyleFB(passthrough_layer_, &style);
		if(XR_FAILED(res))
		{
			LOGW("xrPassthroughLayerSetStyleFB%s: %s", graded ? " (with cinema mode)" : "",
					XrResultStr(instance_, res));
			// Se o runtime recusou por causa do elo novo, o estilo simples ainda
			// vale: melhor perder o modo cinema do que a opacidade junto.
			if(graded)
			{
				style.next = nullptr;
				pfnPassthroughLayerSetStyleFB(passthrough_layer_, &style);
			}
		}
		else if(graded && !logged_cinema_)
		{
			logged_cinema_ = true;
			LOGI("Cinema mode accepted: brightness %.2f contrast %.2f saturation %.2f",
					(double)grade.brightness, (double)grade.contrast,
					(double)grade.saturation);
		}
	}
}

float XrVideoSession::SelectDisplayRefreshRate(int sourceFps, int maxHz)
{
	if(maxHz < 72)
		maxHz = 120;
	refresh_max_hz_.store(maxHz);
	// Pedido cedo demais nao vale nada: enquanto a sessao nao passa pelo
	// xrBeginSession o runtime da Meta responde XR_SUCCESS com lista vazia, e
	// era exatamente isso que o log mostrava -- "devolveu 0 taxas: XR_SUCCESS".
	// Entao o pedido fica guardado e o frame loop o cumpre assim que a sessao
	// comeca de fato.
	refresh_fps_wanted_.store(sourceFps);
	if(!session_running_)
	{
		pending_refresh_fps_.store(sourceFps);
		LOGI("Panel rate deferred until the session starts (%d fps source)", sourceFps);
		return 0.0f;
	}

	// Cada saida antecipada com seu motivo. Antes as tres devolviam zero em
	// silencio, e o log ficava sem nenhuma linha sobre taxa -- indistinguivel de
	// a funcao nem ter sido chamada. O painel podia estar nos 72 Hz de fabrica
	// desde sempre, sem nada denunciar.
	if(!refresh_rate_supported_)
	{
		LOGW("XR_FB_display_refresh_rate unavailable; the panel stays at the system rate");
		return 0.0f;
	}
	if(session_ == XR_NULL_HANDLE)
	{
		LOGW("Panel rate requested with no session");
		return 0.0f;
	}

	uint32_t count = 0;
	XrResult res = pfnEnumerateDisplayRefreshRatesFB(session_, 0, &count, nullptr);
	if(XR_FAILED(res) || count == 0)
	{
		LOGW("xrEnumerateDisplayRefreshRatesFB returned %u rates: %s", count,
				XrResultStr(instance_, res));

		// Lista vazia com sucesso declarado, mesmo depois do xrBeginSession:
		// nao e questao de chamar cedo demais, ja tentamos. Sobra pedir sem
		// consultar. A especificacao nao exige que a taxa pedida tenha vindo da
		// enumeracao -- ela recusa com XR_ERROR_DISPLAY_REFRESH_RATE_UNSUPPORTED_FB
		// se nao servir --, e uma lista vazia pode ser apenas o runtime nao
		// publicando as taxas estendidas. A recusa e barata e o log diz qual foi.
		const float kBlind[] = { 120.0f, 90.0f };
		for(float rate : kBlind)
		{
			res = pfnRequestDisplayRefreshRateFB(session_, rate);
			if(XR_SUCCEEDED(res))
			{
				LOGI("Panel at %.1f Hz, asked for blind (the list came back empty)", rate);
				return rate;
			}
			LOGW("Blind request for %.1f Hz refused: %s", rate,
					XrResultStr(instance_, res));
		}
		return 0.0f;
	}
	std::vector<float> rates(count, 0.0f);
	res = pfnEnumerateDisplayRefreshRatesFB(session_, count, &count, rates.data());
	if(XR_FAILED(res))
	{
		LOGW("xrEnumerateDisplayRefreshRatesFB(lista): %s", XrResultStr(instance_, res));
		return 0.0f;
	}

	// A lista inteira no log: com as taxas estendidas do Horizon OS v2.7 o Quest 3
	// aceita qualquer inteiro de 72 a 207 Hz, e saber o que o runtime realmente
	// oferece e o que separa "escolhemos 120" de "so existia 72".
	{
		std::string list;
		for(float r : rates)
		{
			char buf[16];
			snprintf(buf, sizeof(buf), "%.0f ", r);
			list += buf;
		}
		LOGI("Rates the panel offers: %s", list.c_str());
	}

	// Preferimos o maior multiplo inteiro do framerate da fonte ate o teto
	// (120 Hz padrao, 180 opcional): assim cada frame do console ocupa um
	// numero inteiro de frames do painel e a cadencia fica uniforme. So se
	// nenhum multiplo existir e que caimos na maior taxa ate o teto.
	float best = 0.0f;
	for(float rate : rates)
	{
		float ratio = rate / (float)sourceFps;
		bool integral = std::fabs(ratio - std::round(ratio)) < 0.01f && ratio >= 1.0f;
		if(integral && rate <= (float)maxHz + 0.5f && rate > best)
			best = rate;
	}
	if(best <= 0.0f)
	{
		// Em 18/11/2026 (adb, VrApi em FPS=72) o runtime passou a devolver uma
		// lista so com 72 -- antes vinha vazia e o pedido as cegas abaixo dava
		// 120. A lista curta nao quer dizer que o painel recuse: pedir o
		// multiplo antes de aceitar o judder, como no caso da lista vazia.
		const float kBlind[] = { 120.0f, 90.0f };
		for(float rate : kBlind)
		{
			if(rate > (float)maxHz + 0.5f)
				continue;
			res = pfnRequestDisplayRefreshRateFB(session_, rate);
			if(XR_SUCCEEDED(res))
			{
				LOGI("Panel at %.1f Hz, asked for although the list did not offer it", rate);
				return rate;
			}
			LOGW("Request for %.1f Hz outside the list refused: %s", rate,
					XrResultStr(instance_, res));
		}
		for(float rate : rates)
			if(rate <= (float)maxHz + 0.5f && rate > best)
				best = rate;
		if(best <= 0.0f)
			best = rates.front();
		LOGW("No rate that is a multiple of %d Hz; using %.1f Hz and accepting judder",
				sourceFps, best);
	}

	res = pfnRequestDisplayRefreshRateFB(session_, best);
	if(XR_FAILED(res))
	{
		LOGW("xrRequestDisplayRefreshRateFB(%.1f) failed: %s", best, XrResultStr(instance_, res));
		return 0.0f;
	}
	LOGI("Panel at %.1f Hz for a %d fps source (%.0f panel frames per source frame)",
			best, sourceFps, best / (float)sourceFps);
	return best;
}

void XrVideoSession::ApplyPerformanceLevels()
{
	if(!perf_settings_supported_ || !pfnPerfSettingsSetPerformanceLevelEXT)
	{
		LOGW("XR_EXT_performance_settings unavailable; clocks are left to the system");
		return;
	}

	// SUSTAINED_HIGH nos dois dominios, e nao BOOST.
	//
	// BOOST existe para rajadas de poucos segundos -- carregar uma cena, passar
	// por uma transicao -- e a propria especificacao avisa que pode nao ser
	// sustentavel. Uma sessao de jogo dura horas: pedir BOOST aqui compraria
	// alguns minutos rapidos e depois o termico cobraria a conta de volta, com
	// juros, no meio da partida. SUSTAINED_HIGH e o teto que o aparelho aguenta
	// segurar, e consistencia vale mais que pico num stream de 60 fps.
	struct { XrPerfSettingsDomainEXT domain; const char *name; } domains[] = {
		{ XR_PERF_SETTINGS_DOMAIN_CPU_EXT, "CPU" },
		{ XR_PERF_SETTINGS_DOMAIN_GPU_EXT, "GPU" },
	};
	for(const auto &d : domains)
	{
		XrResult res = pfnPerfSettingsSetPerformanceLevelEXT(session_, d.domain,
				XR_PERF_SETTINGS_LEVEL_SUSTAINED_HIGH_EXT);
		if(XR_FAILED(res))
			LOGW("Performance level for %s refused: %s", d.name, XrResultStr(instance_, res));
		else
			LOGI("%s at sustained high level", d.name);
	}
}

void XrVideoSession::InitPerformanceMetrics()
{
	if(!perf_metrics_supported_)
	{
		LOGW("XR_META_performance_metrics unavailable; no numbers from the compositor");
		return;
	}

	XrPerformanceMetricsStateMETA state{XR_TYPE_PERFORMANCE_METRICS_STATE_META};
	state.enabled = XR_TRUE;
	XrResult res = pfnSetPerformanceMetricsStateMETA(session_, &state);
	if(XR_FAILED(res))
	{
		LOGW("xrSetPerformanceMetricsStateMETA: %s", XrResultStr(instance_, res));
		perf_metrics_supported_ = false;
		return;
	}

	// Os caminhos vem enumerados, e nao escritos a mao.
	//
	// A alternativa seria chutar as strings da documentacao da Meta, e chute
	// que erra devolve XR_ERROR_PATH_UNSUPPORTED -- indistinguivel, no log, de
	// "este aparelho nao tem esse contador". Enumerar e casar por trecho diz as
	// duas coisas e sobrevive a mudanca de nome numa versao futura.
	uint32_t path_count = 0;
	if(XR_FAILED(pfnEnumeratePerformanceMetricsCounterPathsMETA(instance_, 0, &path_count, nullptr))
			|| path_count == 0)
	{
		LOGW("No performance counter enumerated");
		perf_metrics_supported_ = false;
		return;
	}
	std::vector<XrPath> paths(path_count, XR_NULL_PATH);
	if(XR_FAILED(pfnEnumeratePerformanceMetricsCounterPathsMETA(instance_, path_count,
			&path_count, paths.data())))
	{
		perf_metrics_supported_ = false;
		return;
	}

	LOGI("Performance counters offered: %u", path_count);
	for(XrPath path : paths)
	{
		uint32_t len = 0;
		if(XR_FAILED(xrPathToString(instance_, path, 0, &len, nullptr)) || len == 0)
			continue;
		std::string name(len, '\0');
		if(XR_FAILED(xrPathToString(instance_, path, len, &len, &name[0])))
			continue;
		name.resize(len > 0 ? len - 1 : 0);
		LOGI("  counter: %s", name.c_str());

		// Casado por trecho do caminho real, e nao pelo nome que eu supunha.
		//
		// A primeira versao procurava "gpu_time" e o caminho e "gpu_frametime":
		// os dois contadores de tempo de GPU nunca casavam, e o painel
		// simplesmente nao mostrava a linha -- que, pela regra de so mostrar o
		// que existe, era indistinguivel de "este aparelho nao tem". Foi a
		// lista enumerada que denunciou.
		if(name.find("dropped_frame") != std::string::npos)
			counter_dropped_frames_ = path;
		else if(name.find("gpu_utilization") != std::string::npos)
			counter_gpu_utilization_ = path;
		else if(name.find("motion_to_photon") != std::string::npos)
			counter_motion_to_photon_ = path;
		else if(name.find("cpu_utilization_average") != std::string::npos)
			counter_cpu_utilization_ = path;
		else if(name.find("app") != std::string::npos
				&& name.find("gpu_frametime") != std::string::npos)
			counter_app_gpu_time_ = path;
		else if(name.find("compositor") != std::string::npos
				&& name.find("gpu_frametime") != std::string::npos)
			counter_comp_gpu_time_ = path;
	}
}

bool XrVideoSession::ReadCounter(XrPath path, float *out) const
{
	if(path == XR_NULL_PATH || !pfnQueryPerformanceMetricsCounterMETA)
		return false;
	XrPerformanceMetricsCounterMETA counter{XR_TYPE_PERFORMANCE_METRICS_COUNTER_META};
	if(XR_FAILED(pfnQueryPerformanceMetricsCounterMETA(session_, path, &counter)))
		return false;
	if(counter.counterFlags & XR_PERFORMANCE_METRICS_COUNTER_FLOAT_VALUE_VALID_BIT_META)
		*out = counter.floatValue;
	else if(counter.counterFlags & XR_PERFORMANCE_METRICS_COUNTER_UINT_VALUE_VALID_BIT_META)
		*out = (float)counter.uintValue;
	else
		return false;
	return true;
}

PerformanceSnapshot XrVideoSession::ReadPerformance() const
{
	PerformanceSnapshot snap;
	snap.droppedFrames = ReadCounter(counter_dropped_frames_, &snap.droppedFrameCount) ? 1 : 0;
	snap.hasGpuUtilization = ReadCounter(counter_gpu_utilization_, &snap.gpuUtilization);
	snap.hasAppGpuTime = ReadCounter(counter_app_gpu_time_, &snap.appGpuTimeMs);
	snap.hasCompositorGpuTime = ReadCounter(counter_comp_gpu_time_, &snap.compositorGpuTimeMs);
	snap.hasMotionToPhoton = ReadCounter(counter_motion_to_photon_, &snap.motionToPhotonMs);
	snap.hasCpuUtilization = ReadCounter(counter_cpu_utilization_, &snap.cpuUtilization);

	if(pfnThermalGetTemperatureTrendEXT)
	{
		XrPerfSettingsNotificationLevelEXT level = XR_PERF_SETTINGS_NOTIF_LEVEL_NORMAL_EXT;
		float headroom = 0.0f, slope = 0.0f;
		if(XR_SUCCEEDED(pfnThermalGetTemperatureTrendEXT(session_,
				XR_PERF_SETTINGS_DOMAIN_GPU_EXT, &level, &headroom, &slope)))
		{
			snap.hasThermal = true;
			snap.thermalHeadroom = headroom;
			snap.thermalSlope = slope;
		}
	}
	return snap;
}

void XrVideoSession::SetPassthroughEnabled(bool enabled)
{
	if(!passthrough_supported_)
		return;
	passthrough_enabled_ = enabled;
	if(enabled && pfnPassthroughLayerResumeFB)
		pfnPassthroughLayerResumeFB(passthrough_layer_);
	else if(!enabled && pfnPassthroughLayerPauseFB)
		pfnPassthroughLayerPauseFB(passthrough_layer_);
}

void XrVideoSession::Recenter()
{
	recenter_requested_ = true;
}

void XrVideoSession::StartFrameLoop()
{
	if(running_.exchange(true))
		return;
	// Uma thread que saiu sozinha (o eglMakeCurrent dela falhou) ainda precisa
	// de join: atribuir por cima de uma std::thread nao juntada aborta o app.
	if(frame_thread_.joinable())
		frame_thread_.join();
	// Quem chama isto e a activity, na thread principal do app -- e este e o
	// unico ponto em que temos o tid dela. O frame loop precisa dele para
	// declara-la ao runtime, e la dentro `gettid()` ja devolveria outra coisa.
	main_thread_tid_.store((uint32_t)gettid());
	frame_thread_ = std::thread(&XrVideoSession::FrameLoop, this);
}

void XrVideoSession::StopFrameLoop()
{
	// O join vem antes de olhar se o loop rodava: a thread que desiste no
	// eglMakeCurrent zera running_ sozinha, e sair cedo aqui a deixava sem
	// join -- o destrutor de uma std::thread nessa situacao chama terminate.
	const bool rodava = running_.exchange(false);
	if(frame_thread_.joinable())
		frame_thread_.join();
	if(!rodava)
		return;

	// Terminar a sessao aqui, e nao por sorte de agendamento.
	//
	// O xrEndSession so acontece quando o STOPPING e lido, e quem lia eventos
	// era a thread que acabou de morrer. Ate aqui funcionava por um fio: o
	// runtime enfileirava o STOPPING no mesmo instante do onPause e a ultima
	// passada do loop calhava de pega-lo. Quando nao calhasse, a sessao seria
	// destruida ainda rodando, sem um xrEndSession sequer, e sem uma linha no
	// diario dizendo por que.
	//
	// A espera e curta de proposito: isto roda na thread principal, dentro do
	// onPause. Duzentos milissegundos e mais do que o runtime leva e menos do
	// que o Android reclama.
	if(session_running_ && !exit_requested_)
	{
		const auto limite = std::chrono::steady_clock::now() + std::chrono::milliseconds(200);
		while(session_running_ && !exit_requested_
				&& std::chrono::steady_clock::now() < limite)
		{
			PollEvents();
			if(!session_running_)
				break;
			std::this_thread::sleep_for(std::chrono::milliseconds(2));
		}
		if(session_running_)
			LOGW("The OpenXR session was still running 200 ms after the loop stopped: "
					"STOPPING never arrived, and xrEndSession never happened");
	}
}

void XrVideoSession::FrameLoop()
{
	// O contexto EGL foi criado na thread de origem, que o soltou ao fim do
	// Create justamente para esta assumi-lo. Se isto falhar, a thread fica sem
	// contexto e o runtime morre dentro do xrEndFrame com pc=0, sem dizer por
	// que -- entao a falha e reportada aqui, onde ainda da para entender.
	if(!eglMakeCurrent(egl_display_, egl_surface_, egl_surface_, egl_context_))
	{
		EGLint err = eglGetError();
		LOGE("eglMakeCurrent on the frame loop thread failed: 0x%x", err);
		last_error_ = "eglMakeCurrent on the render thread failed (0x"
				+ std::to_string(err) + ")";
		running_ = false;
		return;
	}
	LOGI("EGL context current on the frame loop thread (%s)",
			eglGetCurrentContext() == egl_context_ ? "confirmado" : "OUTRO CONTEXTO");

	// Esta e a thread que submete os frames. Declarada como RENDERER_MAIN, o
	// sistema a mantem nos nucleos grandes e fora da fila comum -- sem isso ela
	// concorre com qualquer trabalho de fundo justamente no instante em que o
	// compositor espera o frame, e o efeito e engasgo esporadico, o pior tipo
	// de defeito de latencia porque nao aparece em media nenhuma.
	if(thread_settings_supported_)
	{
		const uint32_t tid = (uint32_t)gettid();
		XrResult res = pfnSetAndroidApplicationThreadKHR(session_,
				XR_ANDROID_THREAD_TYPE_RENDERER_MAIN_KHR, tid);
		if(XR_FAILED(res))
			LOGW("xrSetAndroidApplicationThreadKHR(RENDERER_MAIN, %u): %s", tid,
					XrResultStr(instance_, res));
		else
			LOGI("Frame loop thread declared as RENDERER_MAIN (tid %u)", tid);

		// A thread principal do app tambem, e por um motivo diferente.
		//
		// Ela nao desenha, mas e ela que recebe entrada, roda o ciclo de vida da
		// activity e entrega os quadros do decodificador. Se o sistema a puser
		// num nucleo pequeno, o atraso nao aparece no frame loop -- aparece
		// antes dele, como quadro que chegou tarde, e procurar isso no lugar
		// errado ja custou tempo aqui. Declarar as duas e o que a documentacao
		// do Horizon pede; declarar so uma e meio caminho.
		const uint32_t main_tid = main_thread_tid_.load();
		if(main_tid != 0)
		{
			XrResult mres = pfnSetAndroidApplicationThreadKHR(session_,
					XR_ANDROID_THREAD_TYPE_APPLICATION_MAIN_KHR, main_tid);
			if(XR_FAILED(mres))
				LOGW("xrSetAndroidApplicationThreadKHR(APPLICATION_MAIN, %u): %s", main_tid,
						XrResultStr(instance_, mres));
			else
				LOGI("App main thread declared as APPLICATION_MAIN (tid %u)", main_tid);
		}
	}

	while(running_ && !exit_requested_)
	{
		PollEvents();
		if(!session_running_)
		{
			// Sessao ociosa (headset na cabeca ainda nao, ou app em background):
			// nao ocupamos CPU girando.
			std::this_thread::sleep_for(std::chrono::milliseconds(50));
			continue;
		}
		RenderFrame();
	}

	eglMakeCurrent(egl_display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

void XrVideoSession::PollEvents()
{
	XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
	while(true)
	{
		event = XrEventDataBuffer{XR_TYPE_EVENT_DATA_BUFFER};
		XrResult res = xrPollEvent(instance_, &event);
		if(res != XR_SUCCESS)
			break;

		switch(event.type)
		{
			case XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED:
			{
				const auto *changed = reinterpret_cast<const XrEventDataSessionStateChanged *>(&event);
				HandleSessionStateChange(changed->state);
				break;
			}
			case XR_TYPE_EVENT_DATA_USER_PRESENCE_CHANGED_EXT:
			{
				const auto *presence =
						reinterpret_cast<const XrEventDataUserPresenceChangedEXT *>(&event);
				LOGI("Headset %s", presence->isUserPresent ? "on the head"
						: "off the head (the stream carries on)");
				break;
			}
			case XR_TYPE_EVENT_DATA_PERF_SETTINGS_EXT:
			{
				// O runtime avisa antes de cortar relogio, e este e o unico
				// aviso que existe: quando o corte chega, ele aparece como
				// engasgo e nao como mensagem.
				const auto *perf =
						reinterpret_cast<const XrEventDataPerfSettingsEXT *>(&event);
				LOGW("Performance notice: domain %d, subdomain %d, level %d -> %d",
						(int)perf->domain, (int)perf->subDomain,
						(int)perf->fromLevel, (int)perf->toLevel);
				break;
			}
			case XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING:
				LOGW("The OpenXR instance will be lost; ending the loop");
				exit_requested_ = true;
				session_ended_ = true;
				break;
			case XR_TYPE_EVENT_DATA_DISPLAY_REFRESH_RATE_CHANGED_FB:
			{
				// Quem troca a taxa por fora e o sistema; o diario precisa ver,
				// porque 60 fps num painel que nao e multiplo de 60 e engasgo.
				const auto *rate =
						reinterpret_cast<const XrEventDataDisplayRefreshRateChangedFB *>(&event);
				LOGI("Panel rate changed: %.1f -> %.1f Hz", rate->fromDisplayRefreshRate,
						rate->toDisplayRefreshRate);
				break;
			}
			default:
				break;
		}
	}
}

// Nome legivel do estado, so para o diario.
//
// Sem isto o runtime podia mandar a sessao para EXITING e o loop de quadro
// parava em silencio: o diario nao registrava uma linha sequer, e do lado de
// dentro do headset nao ha como olhar de fora.
static const char *XrSessionStateStr(XrSessionState state)
{
	switch(state)
	{
		case XR_SESSION_STATE_IDLE: return "IDLE";
		case XR_SESSION_STATE_READY: return "READY";
		case XR_SESSION_STATE_SYNCHRONIZED: return "SYNCHRONIZED";
		case XR_SESSION_STATE_VISIBLE: return "VISIBLE";
		case XR_SESSION_STATE_FOCUSED: return "FOCUSED";
		case XR_SESSION_STATE_STOPPING: return "STOPPING";
		case XR_SESSION_STATE_LOSS_PENDING: return "LOSS_PENDING";
		case XR_SESSION_STATE_EXITING: return "EXITING";
		default: return "?";
	}
}

void XrVideoSession::HandleSessionStateChange(XrSessionState state)
{
	LOGI("OpenXR session state: %s", XrSessionStateStr(state));
	switch(state)
	{
		case XR_SESSION_STATE_READY:
		{
			XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
			begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
			XrResult res = xrBeginSession(session_, &begin);
			if(XR_FAILED(res))
				LOGE("xrBeginSession failed: %s; nothing will be shown until the next READY",
						XrResultStr(instance_, res));
			else
			{
				session_running_ = true;
				// Em todo READY, e nao so no primeiro: depois de tirar o
				// headset ou abrir o menu do sistema a sessao passa por
				// STOPPING e READY de novo, e nada garante que o runtime
				// devolva a taxa que tinhamos pedido.
				int fps = pending_refresh_fps_.exchange(0);
				if(fps <= 0)
					fps = refresh_fps_wanted_.load();
				if(fps > 0)
					SelectDisplayRefreshRate(fps, refresh_max_hz_.load());
				ApplyPerformanceLevels();
				InitPerformanceMetrics();
			}
			break;
		}
		case XR_SESSION_STATE_STOPPING:
			session_running_ = false;
			xrEndSession(session_);
			break;
		case XR_SESSION_STATE_EXITING:
		case XR_SESSION_STATE_LOSS_PENDING:
			LOGW("The runtime asked to end the session (%s); the frame loop stops here",
					XrSessionStateStr(state));
			session_running_ = false;
			exit_requested_ = true;
			session_ended_ = true;
			break;
		default:
			break;
	}
}

void XrVideoSession::RenderFrame()
{
	XrFrameWaitInfo wait_info{XR_TYPE_FRAME_WAIT_INFO};
	XrFrameState frame_state{XR_TYPE_FRAME_STATE};
	XrResult wait_res = xrWaitFrame(session_, &wait_info, &frame_state);
	if(XR_FAILED(wait_res))
	{
		// O xrWaitFrame e quem da o ritmo ao loop; falhando, o loop girava
		// sem pausa nenhuma, um nucleo inteiro por nada.
		if(wait_res == XR_ERROR_SESSION_LOST || wait_res == XR_ERROR_INSTANCE_LOST)
		{
			LOGE("xrWaitFrame: %s; the immersive session is over", XrResultStr(instance_, wait_res));
			session_ended_ = true;
			exit_requested_ = true;
			return;
		}
		ContarFalhaDeQuadro("xrWaitFrame", wait_res);
		std::this_thread::sleep_for(std::chrono::milliseconds(5));
		return;
	}

	XrFrameBeginInfo begin_info{XR_TYPE_FRAME_BEGIN_INFO};
	XrResult begin_res = xrBeginFrame(session_, &begin_info);
	if(XR_FAILED(begin_res))
	{
		ContarFalhaDeQuadro("xrBeginFrame", begin_res);
		return;
	}

	// Localizar as views todo frame e o que qualquer app OpenXR faz. A camada e
	// posicionada no espaco LOCAL e nao por olho, entao a pose em si nao e
	// usada para desenhar -- mas manter o ciclo canonico evita depender de um
	// caminho que nenhum outro app exercita, e a distancia entre as duas views
	// e a distancia interpupilar em uso.
	if(view_count_ > 0 && frame_state.shouldRender)
	{
		XrViewLocateInfo locate{XR_TYPE_VIEW_LOCATE_INFO};
		locate.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
		locate.displayTime = frame_state.predictedDisplayTime;
		locate.space = app_space_;
		XrViewState view_state{XR_TYPE_VIEW_STATE};
		uint32_t located = 0;
		xrLocateViews(session_, &locate, &view_state, view_count_, &located, views_.data());

		// A distancia interpupilar, medida e nao perguntada.
		//
		// Ela nao entra no desenho: o runtime ja aplica a IPD do usuario ao
		// compor as camadas, e uma camada quad ou cilindro aparece na
		// profundidade certa sem o app saber nada disso. Pedir o valor numa
		// tela seria duplicar um numero que o sistema ja tem certo, e um
		// palpite errado brigaria com o valor bom.
		//
		// Onde ela importa e no olho sintetizado: ela escala a separacao que
		// cada profundidade recebe, e da o teto rigido -- disparidade maior
		// que a IPD obriga os olhos a divergir, o que nao e desconforto, e
		// impossivel. Nenhum par de olhos gira para fora.
		//
		// Lida todo quadro, e nao uma vez so: a roda de IPD do Quest se mexe
		// com a sessao aberta, e o valor guardado na abertura passava a estar
		// errado sem nada dizer. Escrever um float atomico por quadro nao
		// aparece na conta. O diario continua falando uma vez.
		if(located >= 2 && (view_state.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT))
		{
			const XrVector3f &a = views_[0].pose.position;
			const XrVector3f &b = views_[1].pose.position;
			float dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
			float ipd = std::sqrt(dx * dx + dy * dy + dz * dz);
			if(ipd > 0.03f && ipd < 0.09f)
			{
				ipd_meters_.store(ipd);
				if(!logged_ipd_)
				{
					logged_ipd_ = true;
					LOGI("Interpupillary distance in use: %.1f mm (read from the runtime, "
							"not asked for)", (double)(ipd * 1000.0f));
				}
			}
		}
	}

	// Onde a tela esta em relacao a cabeca, para os alto-falantes virtuais.
	// Publicado a cada quadro; quem le e a thread de audio, por atomicos.
	if(view_count_ > 0 && !views_.empty())
	{
		ScreenParams params;
		XrQuaternionf recenter;
		{
			std::lock_guard<std::mutex> lock(params_mutex_);
			params = params_;
			recenter = recenter_orientation_;
		}
		const float screen_yaw = YawFromQuat(recenter) + params.yawOffset;
		const XrVector3f center{
			-std::sin(screen_yaw) * params.radius,
			params.heightOffset,
			-std::cos(screen_yaw) * params.radius};

		// A cabeca fica entre os dois olhos; usar so o esquerdo deslocaria o
		// palco alguns centimetros para o lado, o suficiente para a imagem nao
		// ficar centrada quando ela deveria.
		const XrPosef &eye = views_[0].pose;
		XrVector3f head = eye.position;
		if(views_.size() > 1)
		{
			head.x = (head.x + views_[1].pose.position.x) * 0.5f;
			head.y = (head.y + views_[1].pose.position.y) * 0.5f;
			head.z = (head.z + views_[1].pose.position.z) * 0.5f;
		}

		const XrVector3f to_screen{center.x - head.x, center.y - head.y, center.z - head.z};
		const XrVector3f local = RotateByInverse(eye.orientation, to_screen);
		// x para a direita, -z para a frente: e a convencao do OpenXR, e o
		// azimute sai direto dela.
		Spatializer::Instance().SetGeometry(std::atan2(local.x, -local.z),
				params.centralAngle * 0.5f);
	}

	if(recenter_requested_.exchange(false))
	{
		XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
		if(XR_SUCCEEDED(xrLocateSpace(view_space_, app_space_, frame_state.predictedDisplayTime, &loc))
				&& (loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT))
		{
			std::lock_guard<std::mutex> lock(params_mutex_);
			recenter_orientation_ = QuatFromYaw(YawFromQuat(loc.pose.orientation));
		}
		else
			// Sem rastreio ainda (o pedido da abertura chega antes dele): fica
			// para o quadro seguinte, em vez de a tela nascer no yaw zero.
			recenter_requested_ = true;
	}

	// Reaproveitada de quadro em quadro: criar a lista a cada quadro era uma
	// alocacao e uma liberacao por quadro, 90 a 120 vezes por segundo.
	auto &layers = frame_layers_;
	layers.clear();

	XrCompositionLayerPassthroughFB passthrough_layer_info{XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB};
	if(passthrough_supported_ && passthrough_enabled_)
	{
		passthrough_layer_info.flags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
		passthrough_layer_info.space = XR_NULL_HANDLE;
		passthrough_layer_info.layerHandle = passthrough_layer_;
		layers.push_back(reinterpret_cast<const XrCompositionLayerBaseHeader *>(&passthrough_layer_info));
	}

	XrCompositionLayerCylinderKHR cylinder{XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
	XrCompositionLayerQuad quad{XR_TYPE_COMPOSITION_LAYER_QUAD};
	// Copias para o olho direito, quando a imagem carrega os dois. Vivem aqui,
	// e nao dentro do bloco, porque o xrEndFrame le as estruturas depois: uma
	// copia local dentro de um escopo que fecha viraria ponteiro morto na hora
	// exata em que o runtime for lê-la.
	XrCompositionLayerCylinderKHR cylinder_right{XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
	XrCompositionLayerQuad quad_right{XR_TYPE_COMPOSITION_LAYER_QUAD};
	XrCompositionLayerSettingsFB layer_settings{XR_TYPE_COMPOSITION_LAYER_SETTINGS_FB};
	XrCompositionLayerImageLayoutFB image_layout{XR_TYPE_COMPOSITION_LAYER_IMAGE_LAYOUT_FB};
	XrCompositionLayerColorScaleBiasKHR color_scale{
			XR_TYPE_COMPOSITION_LAYER_COLOR_SCALE_BIAS_KHR};
	// No caminho com shader a imagem precisa ser produzida antes de a camada ser
	// montada; no direto o MediaCodec ja escreveu sozinho.
	bool video_ready = video_layer_enabled_.load() && frame_state.shouldRender;
	if(render_path_ == RenderPath::ToneMapped)
		// No caminho com shader o swapchain pode ainda nem existir: e aqui
		// dentro que ele nasce, no primeiro quadro depois da conexao.
		video_ready = video_ready && RenderVideoThroughShader();
	else
		video_ready = video_ready && video_swapchain_ != XR_NULL_HANDLE;

	if(video_ready)
	{
		ScreenParams params;
		QualityParams quality;
		XrQuaternionf recenter;
		{
			std::lock_guard<std::mutex> lock(params_mutex_);
			params = params_;
			quality = quality_;
			recenter = recenter_orientation_;
		}

		// Os quatro primeiros degraus sao do compositor no caminho direto, e do
		// shader no caminho com shader: pedir os dois somaria dois realces sobre
		// a mesma imagem, e o degrau mais baixo sairia mais forte que o mais
		// alto do outro caminho.
		//
		// MQSR e automatico sao diferentes: nao ha equivalente no shader, e a
		// camada e composta pelo compositor nos dois caminhos. Entao esses dois
		// valem sempre, e la o shader nao afia nada (a tabela de intensidades
		// tem zero nas duas posicoes).
		// O que tira o filtro do compositor de cena e o recorte, e nao o 3D.
		//
		// Quando a camada e metade de uma textura maior, o MQSR e o automatico
		// desenharam um X discreto atravessando a tela -- medido em hardware.
		// O filtro nao foi feito para uma camada que e um recorte de outra.
		// No 3D emulado cada olho tem swapchain proprio e entra inteiro, entao o
		// filtro vale como vale no mono.
		const bool compositor_owns_filter = (render_path_ == RenderPath::Direct
						|| quality.sharpness == Sharpness::Mqsr
						|| quality.sharpness == Sharpness::Auto);
		if(compositor_owns_filter)
		{
			// O bit de sharpening tem forca fixa. Os degraus intermediarios
			// saem de liga-lo junto com o supersampling, que amacia e devolve
			// parte do realce -- e do supersampling sozinho, que fica um passo
			// abaixo de nao filtrar nada.
			switch(quality.sharpness)
			{
				case Sharpness::Off:
					break;
				case Sharpness::Light:
					layer_settings.layerFlags |=
							XR_COMPOSITION_LAYER_SETTINGS_NORMAL_SUPER_SAMPLING_BIT_FB;
					break;
				case Sharpness::Medium:
					layer_settings.layerFlags |=
							XR_COMPOSITION_LAYER_SETTINGS_NORMAL_SHARPENING_BIT_FB
							| XR_COMPOSITION_LAYER_SETTINGS_NORMAL_SUPER_SAMPLING_BIT_FB;
					break;
				case Sharpness::Strong:
					layer_settings.layerFlags |=
							XR_COMPOSITION_LAYER_SETTINGS_NORMAL_SHARPENING_BIT_FB;
					break;
				case Sharpness::Mqsr:
					// Compatibilidade com o indice antigo. O MQSR explicito fez
					// um disco acompanhar a cabeca; so participa como candidato.
				case Sharpness::Auto:
					// O bit automatico exige um conjunto de candidatos junto: a
					// especificacao manda o runtime devolver
					// XR_ERROR_VALIDATION_FAILURE do xrEndFrame se ele vier
					// sozinho. Nao e "escolha o que quiser", e "escolha entre
					// estes".
					if(auto_filter_supported_)
						layer_settings.layerFlags |=
								XR_COMPOSITION_LAYER_SETTINGS_AUTO_LAYER_FILTER_BIT_META
								| XR_COMPOSITION_LAYER_SETTINGS_NORMAL_SHARPENING_BIT_FB
								| XR_COMPOSITION_LAYER_SETTINGS_QUALITY_SHARPENING_BIT_FB
								| XR_COMPOSITION_LAYER_SETTINGS_NORMAL_SUPER_SAMPLING_BIT_FB;
					else
						layer_settings.layerFlags |=
								XR_COMPOSITION_LAYER_SETTINGS_NORMAL_SHARPENING_BIT_FB;
					break;
			}
		}

		// Sem XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT: o video e
		// opaco, e com swapchain-Surface quem escolhe o formato e o runtime --
		// pedir mistura por alfa sobre um formato que pode nao ter canal alfa e
		// pedir problema.
		cylinder.layerFlags = 0;
		if(layer_settings_supported_ && layer_settings.layerFlags != 0)
		{
			cylinder.next = &layer_settings;
			if(!logged_layer_config_)
				LOGI("Layer with XrCompositionLayerSettingsFB, flags=0x%x",
						(unsigned)layer_settings.layerFlags);
		}

		// Brilho da camada, pelo compositor.
		//
		// Entra antes dos outros elos porque encadear e so encadear: a ordem no
		// next chain nao importa para o runtime. Fica fora quando nao muda nada,
		// pelo mesmo motivo do modo cinema -- elo inutil e superficie a mais
		// para um runtime recusar.
		if(color_scale_supported_ && quality.videoBrightness != 1.0f)
		{
			color_scale.colorScale = XrColor4f{quality.videoBrightness,
					quality.videoBrightness, quality.videoBrightness, 1.0f};
			color_scale.colorBias = XrColor4f{0.0f, 0.0f, 0.0f, 0.0f};
			color_scale.next = const_cast<void *>(cylinder.next);
			cylinder.next = &color_scale;
		}

		// Entra na frente do que ja estiver encadeado: a ordem no next chain
		// nao importa para o runtime, e assim o elo do flip nao depende de o
		// filtro estar ligado.
		// So no caminho direto. Com shader, a matriz da SurfaceTexture ja
		// orientou a imagem, e inverter de novo desfaria o acerto.
		if(render_path_ == RenderPath::Direct && image_layout_supported_
				&& vertical_flip_.load())
		{
			image_layout.flags = XR_COMPOSITION_LAYER_IMAGE_LAYOUT_VERTICAL_FLIP_BIT_FB;
			image_layout.next = const_cast<void *>(cylinder.next);
			cylinder.next = &image_layout;
		}

		cylinder.space = app_space_;
		cylinder.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
		cylinder.subImage.swapchain = video_swapchain_;
		cylinder.subImage.imageRect.offset = XrOffset2Di{0, 0};
		cylinder.subImage.imageRect.extent = XrExtent2Di{video_width_, video_height_};
		if(render_path_ == RenderPath::Direct)
			cylinder.subImage.imageRect.extent.height = DirectContentHeight();
		cylinder.subImage.imageArrayIndex = 0;

		// A tela e um arco: a pose marca o eixo do cilindro, e o arco visivel
		// fica centrado em -Z dessa pose. Yaw da recentragem + ajuste do usuario.
		float yaw = YawFromQuat(recenter) + params.yawOffset;
		const float tilt = ScreenTilt(params);
		cylinder.pose.orientation = QuatFromYawPitch(yaw, tilt);
		// Curvatura separada da distancia.
		//
		// Com a pose na origem, o olho fica no centro do cilindro e a tela sai
		// com a curvatura maxima possivel -- toda a superficie a mesma distancia,
		// abracando quem assiste. Fica exagerado.
		//
		// Para suavizar, o cilindro precisa de um raio maior que a distancia de
		// visao, com o eixo recuado para tras do olho: a superficie continua a
		// `radius` metros, mas descreve um arco de raio R = radius / curvature.
		// Com curvature = 1 o eixo volta a origem e a curva e a maxima; quanto
		// menor, mais plana, e no limite vira a tela chata.
		const float curvature = params.curvature;
		const float distance = params.radius;
		const float arc_radius = distance / curvature;

		// O eixo fica atras do centro da tela ao longo da frente dela, e a
		// frente inclina junto com a tela: sem inclinacao, e o recuo de sempre.
		cylinder.pose.position = XrVector3f{
			-std::sin(yaw) * distance + std::sin(yaw) * std::cos(tilt) * arc_radius,
			params.heightOffset - std::sin(tilt) * arc_radius,
			-std::cos(yaw) * distance + std::cos(yaw) * std::cos(tilt) * arc_radius
		};
		cylinder.radius = arc_radius;
		// O arco tem de encolher junto com a curvatura, senao a tela cresceria
		// ao ser aplainada: o que se quer manter e a largura aparente, nao o
		// angulo do cilindro.
		cylinder.centralAngle = 2.0f * curvature * std::tan(params.centralAngle * 0.5f);
		cylinder.aspectRatio = params.aspectRatio;

		// Quantos pixels o compositor gostaria de ter nesta camada, do jeito que
		// ela esta agora. Antes do bloco de log logo abaixo, e nao depois: na
		// primeira versao a consulta vinha depois, e a linha de log do primeiro
		// quadro reportava "nao devolveu valor valido" sobre um atomico que
		// ainda estava em zero -- a consulta funcionava, o relato e que mentia.
		//
		// Consultado a cada quadro, e nao uma vez por sessao como antes. O
		// numero muda junto com o tamanho e a distancia da tela, e so serve para
		// escolher o tamanho se puder ser visto *enquanto* se escolhe -- num log
		// lido depois ele conta o que ja passou. Custa uma chamada por quadro e
		// vai para atomicos, que e de onde o painel de ajuste o le.
		// Quem le e o painel de ajuste, e ele so existe com o HUD a mostra; fora
		// dele basta a leitura do primeiro quadro, que vai para o diario.
		if(recommended_resolution_supported_ && pfnGetRecommendedLayerResolutionMETA
				&& (hud_visible_.load() || !logged_layer_config_))
		{
			XrRecommendedLayerResolutionGetInfoMETA info{
					XR_TYPE_RECOMMENDED_LAYER_RESOLUTION_GET_INFO_META};
			info.layer = reinterpret_cast<const XrCompositionLayerBaseHeader *>(&cylinder);
			info.predictedDisplayTime = frame_state.predictedDisplayTime;
			XrRecommendedLayerResolutionMETA rec{XR_TYPE_RECOMMENDED_LAYER_RESOLUTION_META};
			if(XR_SUCCEEDED(pfnGetRecommendedLayerResolutionMETA(session_, &info, &rec))
					&& rec.isValid)
			{
				recommended_width_.store((int)rec.recommendedImageDimensions.width);
				recommended_height_.store((int)rec.recommendedImageDimensions.height);
			}
		}

		// Depois das atribuicoes, nao antes: a versao anterior deste log rodava
		// com a struct ainda zerada e reportava raio, arco e aspecto todos em
		// zero, o que parecia defeito de configuracao e nao era.
		if(!logged_layer_config_)
		{
			logged_layer_config_ = true;
			LOGI("First submission: shape=%s %dx%d radius=%.2f arc=%.2f aspect=%.2f "
					"passthrough=%d chain=%d",
					layer_shape_.load() == LayerShape::Cylinder ? "cilindro" : "quad",
					video_width_, video_height_, cylinder.radius, cylinder.centralAngle,
					cylinder.aspectRatio, (int)(passthrough_supported_ && passthrough_enabled_),
					(int)(cylinder.next != nullptr));
			if(recommended_resolution_supported_ && !logged_recommended_resolution_)
			{
				logged_recommended_resolution_ = true;
				if(recommended_width_.load() > 0)
					LOGI("Recommended resolution for this screen: %dx%d (the source delivers %dx%d)",
							recommended_width_.load(), recommended_height_.load(),
							video_width_, video_height_);
				else
					LOGW("xrGetRecommendedLayerResolutionMETA returned no valid value");
			}

			// "Disponivel", e nao "ativo".
			//
			// A versao anterior dizia "ativo" sempre que a extensao estivesse
			// habilitada, o que e outra coisa: o bit AUTO_LAYER_FILTER so entra
			// no degrau "automatica" da nitidez. A linha vinha afirmando, em
			// todo log, que um recurso estava em uso quando nao estava.
			LOGI("Automatic layer filter: %s",
					auto_filter_supported_ ? "available (the 'automatic' step)"
					: "unavailable");
			LOGI("Vertical flip: %s", (image_layout_supported_ && vertical_flip_.load())
					? "corrected by the compositor"
					: (image_layout_supported_ ? "off" : "NOT SUPPORTED"));
		}

		// Estereo: duas camadas, uma por olho. A geometria nao muda -- a tela
		// continua do mesmo tamanho e no mesmo lugar --, so muda o que cada olho
		// enxerga.
		//
		// Um swapchain por olho, imagem inteira em cada camada. Se o segundo
		// alvo nao existir -- criacao falhou, ou o quadro nao pode ser
		// adquirido --, os dois olhos veem o esquerdo: um quadro sem
		// profundidade se nota menos do que um olho vazio.
		const bool stereo = stereo_synthetic_.load();
		if(stereo)
		{
			cylinder_right = cylinder;
			if(video_swapchain_right_ != XR_NULL_HANDLE && right_layer_ready_)
				cylinder_right.subImage.swapchain = video_swapchain_right_;
			cylinder.eyeVisibility = XR_EYE_VISIBILITY_LEFT;
			cylinder_right.eyeVisibility = XR_EYE_VISIBILITY_RIGHT;
		}

		if(layer_shape_.load() == LayerShape::Cylinder)
		{
			layers.push_back(reinterpret_cast<const XrCompositionLayerBaseHeader *>(&cylinder));
			if(stereo)
				layers.push_back(
						reinterpret_cast<const XrCompositionLayerBaseHeader *>(&cylinder_right));
		}
		else
		{
			// Quad equivalente: largura que subtende o mesmo arco a mesma
			// distancia, para a troca de forma nao mudar o tamanho aparente.
			quad.type = XR_TYPE_COMPOSITION_LAYER_QUAD;
			quad.next = cylinder.next;
			quad.layerFlags = cylinder.layerFlags;
			quad.space = cylinder.space;
			quad.eyeVisibility = cylinder.eyeVisibility;
			quad.subImage = cylinder.subImage;

			float width = 2.0f * params.radius * std::tan(params.centralAngle * 0.5f);
			float height = width / params.aspectRatio;
			quad.size = XrExtent2Df{width, height};

			// A pose do cilindro marca o eixo; a do quad marca o centro da tela,
			// entao ele precisa ser empurrado para a frente pelo raio.
			float yaw = YawFromQuat(recenter) + params.yawOffset;
			quad.pose.orientation = QuatFromYawPitch(yaw, ScreenTilt(params));
			quad.pose.position = XrVector3f{
				-std::sin(yaw) * params.radius,
				params.heightOffset,
				-std::cos(yaw) * params.radius
			};

			layers.push_back(reinterpret_cast<const XrCompositionLayerBaseHeader *>(&quad));
			if(stereo)
			{
				quad_right = quad;
				quad_right.eyeVisibility = cylinder_right.eyeVisibility;
				quad_right.subImage = cylinder_right.subImage;
				layers.push_back(
						reinterpret_cast<const XrCompositionLayerBaseHeader *>(&quad_right));
			}
		}
	}

	// O painel entra por ultimo, para ficar por cima da tela do console.
	XrCompositionLayerQuad hud_layer{XR_TYPE_COMPOSITION_LAYER_QUAD};
	XrCompositionLayerImageLayoutFB hud_layout{XR_TYPE_COMPOSITION_LAYER_IMAGE_LAYOUT_FB};
	if(hud_visible_.load())
	{
		ScreenParams params;
		XrQuaternionf recenter;
		{
			std::lock_guard<std::mutex> lock(params_mutex_);
			params = params_;
			recenter = recenter_orientation_;
		}
		if(UpdateHudLayer(hud_layer, YawFromQuat(recenter) + params.yawOffset, params,
				hud_below_screen_.load()))
		{
			// Mesma inversao de origem do video: a linha zero da textura fica
			// na base pela convencao do GL, e o painel foi desenhado de cima
			// para baixo.
			if(image_layout_supported_ && vertical_flip_.load())
			{
				hud_layout.flags = XR_COMPOSITION_LAYER_IMAGE_LAYOUT_VERTICAL_FLIP_BIT_FB;
				hud_layer.next = &hud_layout;
			}
			layers.push_back(reinterpret_cast<const XrCompositionLayerBaseHeader *>(&hud_layer));
		}
	}

	XrFrameEndInfo end_info{XR_TYPE_FRAME_END_INFO};
	end_info.displayTime = frame_state.predictedDisplayTime;
	end_info.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
	end_info.layerCount = (uint32_t)layers.size();
	end_info.layers = layers.empty() ? nullptr : layers.data();

	// Atenuacao local: ligada so quando a cena e nossa.
	//
	// Com passthrough o quarto ocupa a tela toda e as zonas claras mandam na
	// retroiluminacao de qualquer jeito; no escuro, em volta da tela so ha
	// preto, e e ai que ela desce o suficiente para o preto ser preto.
	XrLocalDimmingFrameEndInfoMETA dimming{XR_TYPE_LOCAL_DIMMING_FRAME_END_INFO_META};
	if(local_dimming_supported_)
	{
		dimming.localDimmingMode = passthrough_enabled_.load()
				? XR_LOCAL_DIMMING_MODE_OFF_META
				: XR_LOCAL_DIMMING_MODE_ON_META;
		dimming.next = end_info.next;
		end_info.next = &dimming;
		if(!logged_local_dimming_)
		{
			logged_local_dimming_ = true;
			LOGI("Local dimming attached to the frame (off while passthrough is on)");
		}
	}
	XrResult end_res = xrEndFrame(session_, &end_info);
	if(XR_FAILED(end_res))
		ContarFalhaDeQuadro("xrEndFrame", end_res);
}

// Uma camada recusada vira quadro preto sem mais nada: o runtime nao
// escreve no diario. A primeira falha sai inteira, as seguintes em conta a
// cada 10 s (a 120 Hz uma linha por falha afogaria o resto).
void XrVideoSession::ContarFalhaDeQuadro(const char *chamada, XrResult res)
{
	const auto agora = std::chrono::steady_clock::now();
	++frame_failures_;
	if(frame_failures_ == 1 || agora - last_failure_log_ >= std::chrono::seconds(10))
	{
		last_failure_log_ = agora;
		LOGE("%s failed: %s (%u frame call failures in this session)", chamada,
				XrResultStr(instance_, res), frame_failures_);
	}
}

void XrVideoSession::Destroy()
{
	StopFrameLoop();

	// Sem sessao imersiva nao ha cabeca para rastrear, e o filtro de audio tem
	// de voltar a deixar o som passar como veio.
	Spatializer::Instance().Clear();

	if(passthrough_layer_ != XR_NULL_HANDLE && pfnDestroyPassthroughLayerFB)
		pfnDestroyPassthroughLayerFB(passthrough_layer_);
	if(passthrough_ != XR_NULL_HANDLE && pfnDestroyPassthroughFB)
		pfnDestroyPassthroughFB(passthrough_);
	passthrough_layer_ = XR_NULL_HANDLE;
	passthrough_ = XR_NULL_HANDLE;

	if(video_swapchain_ != XR_NULL_HANDLE)
		xrDestroySwapchain(video_swapchain_);
	video_swapchain_ = XR_NULL_HANDLE;
	if(video_swapchain_right_ != XR_NULL_HANDLE)
		xrDestroySwapchain(video_swapchain_right_);
	video_swapchain_right_ = XR_NULL_HANDLE;

	// O loop soltou o contexto ao sair, e o que vem abaixo e GL: sem contexto
	// corrente o glDelete* e o detach da SurfaceTexture viram nada, em
	// silencio, e o que eles deviam soltar fica ate o eglDestroyContext.
	const bool com_contexto = egl_display_ != EGL_NO_DISPLAY && egl_context_ != EGL_NO_CONTEXT
			&& eglMakeCurrent(egl_display_, egl_surface_, egl_surface_, egl_context_);
	if(!com_contexto && egl_display_ != EGL_NO_DISPLAY)
		LOGW("Session release: no GL context for the teardown (0x%x)", eglGetError());
	tone_mapper_.Destroy();
	// Entregue pelo Kotlin mas nunca anexada (a sessao acabou antes do
	// primeiro quadro): so o Attach passava a posse ao tone mapper.
	if(pending_surface_texture_)
	{
		ASurfaceTexture_release(pending_surface_texture_);
		pending_surface_texture_ = nullptr;
	}
	tone_mapper_ready_ = false;
	video_images_.clear();
	video_images_right_.clear();
	pending_gl_width_ = 0;
	pending_gl_height_ = 0;

	if(hud_swapchain_ != XR_NULL_HANDLE)
		xrDestroySwapchain(hud_swapchain_);
	hud_swapchain_ = XR_NULL_HANDLE;
	hud_images_.clear();

	if(app_space_ != XR_NULL_HANDLE)
		xrDestroySpace(app_space_);
	if(view_space_ != XR_NULL_HANDLE)
		xrDestroySpace(view_space_);
	app_space_ = XR_NULL_HANDLE;
	view_space_ = XR_NULL_HANDLE;

	if(session_ != XR_NULL_HANDLE)
		xrDestroySession(session_);
	session_ = XR_NULL_HANDLE;

	DestroyEgl();

	if(instance_ != XR_NULL_HANDLE)
		xrDestroyInstance(instance_);
	instance_ = XR_NULL_HANDLE;

	if(activity_ != nullptr && vm_ != nullptr)
	{
		JNIEnv *env = nullptr;
		if(vm_->GetEnv((void **)&env, JNI_VERSION_1_6) == JNI_OK)
			env->DeleteGlobalRef(activity_);
		activity_ = nullptr;
	}
}

} // namespace p5m

namespace p5m {

/**
 * Quantas linhas do swapchain direto sao imagem, e nao enchimento do codec.
 *
 * O runtime estica o buffer inteiro do decodificador sobre as linhas do
 * swapchain, sem olhar o recorte. Com buffer de 1088 para fonte de 1080, as 8
 * de sobra viram a faixa no pe da tela e o resto sai 0,7% achatado. O conteudo
 * de verdade ocupa entao 1080 * 1080 / 1088 = 1072 linhas do swapchain, e e so
 * isso que a camada passa a mostrar.
 *
 * Nada muda enquanto o decodificador nao contar o tamanho, nem quando o buffer
 * ja vier do tamanho da fonte: nao se corta nada por regra fixa.
 */
int32_t XrVideoSession::DirectContentHeight()
{
	const int32_t decoded = g_decoder_height.load();
	if(video_height_ <= 0 || decoded <= video_height_)
		return video_height_;
	int32_t content = video_height_;
	const int32_t top = g_decoder_crop_top.load(), bottom = g_decoder_crop_bottom.load();
	if(top >= 0 && bottom >= top)
		content = std::min(bottom - top + 1, decoded);
	const int32_t rows = (int32_t)std::lround((double)video_height_ * content / decoded);
	if(rows != logged_direct_rows_)
	{
		logged_direct_rows_ = rows;
		LOGI("Direct path: decoder buffer %dx%d for a %dx%d swapchain; showing the top %d rows "
				"(%d of the buffer are picture, the rest codec padding)",
				g_decoder_width.load(), decoded, video_width_, video_height_, rows, content);
	}
	return rows;
}

} // namespace p5m

/**
 * Chamado pelo decodificador do chiaki (patch 0033) quando o formato de saida
 * muda. Recorte -1 e "nao informado".
 */
extern "C" void p5m_video_decoder_size(int32_t width, int32_t height, int32_t crop_top,
		int32_t crop_bottom)
{
	p5m::g_decoder_width.store(width);
	p5m::g_decoder_crop_top.store(crop_top);
	p5m::g_decoder_crop_bottom.store(crop_bottom);
	p5m::g_decoder_height.store(height);
}
