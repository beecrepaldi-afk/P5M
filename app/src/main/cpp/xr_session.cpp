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

// Tamanho do painel de ajuda, em pixels de textura. Largo o bastante para uma
// linha de comando por vez sem quebrar, e pequeno o bastante para o envio por
// frame nao pesar.
constexpr int32_t kHudWidth = 1024;
// 768 e nao 512: com dez linhas de legenda a 40 px, a ultima caia em y=538 --
// fora do painel. As linhas de "Share" e "Circulo" estavam sendo desenhadas
// para fora do bitmap desde que a lista cresceu, e nenhuma delas jamais
// apareceu. A secao de medidores nao caberia de jeito nenhum no tamanho velho.
constexpr int32_t kHudHeight = 768;

// Posicao fixa do painel, em metros. 1,5 m e distancia de leitura confortavel,
// e 0,95 m de largura da cerca de 35 graus -- grande o bastante para ler as
// linhas sem cobrir a tela do jogo atras.
constexpr float kHudDistance = 1.5f;
constexpr float kHudWidth1M = 0.95f;
constexpr float kHudHeightOffset = -0.3f;

XrQuaternionf QuatFromYaw(float yaw)
{
	return XrQuaternionf{0.0f, std::sin(yaw * 0.5f), 0.0f, std::cos(yaw * 0.5f)};
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

void XrVideoSession::SetHudBitmap(const uint8_t *pixels, int32_t width, int32_t height)
{
	if(!pixels || width <= 0 || height <= 0)
		return;
	std::lock_guard<std::mutex> lock(hud_mutex_);
	hud_width_ = width;
	hud_height_ = height;
	hud_pixels_.assign(pixels, pixels + (size_t)width * height * 4);
}

bool XrVideoSession::UpdateHudLayer(XrCompositionLayerQuad &layer, float yaw)
{
	if(hud_swapchain_ == XR_NULL_HANDLE || hud_images_.empty())
		return false;

	uint32_t index = 0;
	XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
	if(XR_FAILED(xrAcquireSwapchainImage(hud_swapchain_, &acquire, &index)))
		return false;

	XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
	wait.timeout = XR_INFINITE_DURATION;
	bool uploaded = false;
	if(XR_SUCCEEDED(xrWaitSwapchainImage(hud_swapchain_, &wait)))
	{
		std::lock_guard<std::mutex> lock(hud_mutex_);
		if(!hud_pixels_.empty() && hud_width_ == kHudWidth && hud_height_ == kHudHeight)
		{
			// O swapchain tem varias imagens em rodizio, entao a atualizacao
			// vai em todas -- guardar um bit de "sujo" so acertaria uma delas e
			// o painel piscaria entre o novo e o antigo.
			glBindTexture(GL_TEXTURE_2D, hud_images_[index].image);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kHudWidth, kHudHeight,
					GL_RGBA, GL_UNSIGNED_BYTE, hud_pixels_.data());
			glBindTexture(GL_TEXTURE_2D, 0);
			uploaded = true;
		}
	}

	XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
	xrReleaseSwapchainImage(hud_swapchain_, &release);
	if(!uploaded)
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

	// Ancorado no espaco, nao na tela.
	//
	// Antes o painel seguia a distancia e a altura da tela, e mexer na distancia
	// fazia a legenda andar junto com o que ela esta explicando -- desconfortavel
	// e sem utilidade: uma referencia que se move enquanto se lê deixa de ser
	// referencia. Fica sempre no mesmo lugar, a uma distancia de leitura, um
	// pouco abaixo da linha dos olhos para nao tapar o jogo.
	//
	// So o yaw acompanha a recentragem, para o painel aparecer a frente de quem
	// abriu o modo em vez de ficar perdido nas costas.
	layer.size = XrExtent2Df{kHudWidth1M, kHudWidth1M * (float)kHudHeight / (float)kHudWidth};
	layer.pose.orientation = QuatFromYaw(yaw);
	layer.pose.position = XrVector3f{
		-std::sin(yaw) * kHudDistance,
		kHudHeightOffset,
		-std::cos(yaw) * kHudDistance
	};
	return true;
}

bool XrVideoSession::CreateGlVideoSwapchain(int32_t width, int32_t height)
{
	// No modo 3D o alvo tem o dobro da largura: cada olho ocupa uma metade e
	// recebe a imagem inteira. Sem isso cada olho ficaria com metade da
	// resolucao horizontal, e uma tela 3D borrada nao troca por uma 2D nitida.
	//
	// video_width_ passa a ser a largura dobrada, e e o que a submissao quer:
	// ela divide o imageRect ao meio para achar a metade de cada olho.
	if(stereo_mode_.load() != 0)
		width *= 2;

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

	uint32_t image_count = 0;
	if(XR_FAILED(xrEnumerateSwapchainImages(video_swapchain_, 0, &image_count, nullptr))
			|| image_count == 0)
		return false;
	video_images_.assign(image_count, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
	if(XR_FAILED(xrEnumerateSwapchainImages(video_swapchain_, image_count, &image_count,
			reinterpret_cast<XrSwapchainImageBaseHeader *>(video_images_.data()))))
		return false;

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

	// MQSR e automatico nao existem no 3D, e a tabela de intensidades tem zero
	// nessas duas posicoes -- foi feita assim porque quem afiava era o
	// compositor. Sem o desvio abaixo, escolher MQSR com o 3D ligado deixaria a
	// imagem sem realce nenhum, em silencio, e pareceria que o botao quebrou.
	if(stereo_mode_.load() != 0
			&& (nitidez == Sharpness::Mqsr || nitidez == Sharpness::Auto))
	{
		sharpen = 1.0f;
		if(!logged_stereo_sharpen_)
		{
			logged_stereo_sharpen_ = true;
			LOGI("3D is on: the compositor filter does not apply to a layer that is "
					"half of a texture, so sharpening falls back to the shader");
		}
	}

	// Teto de disparidade pela distancia interpupilar.
	//
	// Uma separacao na tela maior que a distancia entre os olhos obriga os
	// olhos a divergir, e isso nao e desconforto: e impossivel, nenhum par de
	// olhos gira para fora. O limite sai da IPD que o runtime informou e da
	// largura angular da tela, porque a mesma separacao em pixels vale mais
	// numa tela grande e perto do que numa pequena e longe.
	if(stereo_mode_.load() != 0)
	{
		ScreenParams params;
		{
			std::lock_guard<std::mutex> lock(params_mutex_);
			params = params_;
		}
		float ipd = ipd_meters_.load();
		float teto = 0.02f;
		if(ipd > 0.0f && params.radius > 0.01f && params.centralAngle > 0.01f)
		{
			// Largura da tela em metros, e quanto dela a IPD ocupa. Essa fracao
			// e a separacao maxima admissivel, em fracao da largura da imagem.
			float largura_m = 2.0f * params.radius * std::tan(params.centralAngle * 0.5f);
			teto = ipd / std::max(largura_m, 0.01f);
		}
		// Metade do teto teorico: a divergencia comeca a incomodar bem antes de
		// ser geometricamente impossivel, e o palpite de profundidade erra.
		float forca = std::min(stereo_strength_.load(), teto * 0.5f);
		tone_mapper_.SetStereo(true, forca, stereo_convergence_.load(),
				!vertical_flip_.load());
	}
	else
		tone_mapper_.SetStereo(false, 0.0f, 0.0f, true);

	if(XR_SUCCEEDED(xrWaitSwapchainImage(video_swapchain_, &wait)))
		drawn = tone_mapper_.Render(video_images_[index].image, video_width_, video_height_,
				tone_map_pq_.load(), sharpen, false, extrapolate);

	XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
	xrReleaseSwapchainImage(video_swapchain_, &release);
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
	{
		std::lock_guard<std::mutex> lock(params_mutex_);
		params_.aspectRatio = (float)width / (float)height;
	}

	LOGI("Video swapchain created: %dx%d (create_info_fb=%d)", width, height,
			(int)surface_swapchain_create_supported_);
	// Devolvemos referencia global: o Surface vai viver no lado Kotlin.
	return env->NewGlobalRef(surface);
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

float XrVideoSession::SelectDisplayRefreshRate(int sourceFps)
{
	// Pedido cedo demais nao vale nada: enquanto a sessao nao passa pelo
	// xrBeginSession o runtime da Meta responde XR_SUCCESS com lista vazia, e
	// era exatamente isso que o log mostrava -- "devolveu 0 taxas: XR_SUCCESS".
	// Entao o pedido fica guardado e o frame loop o cumpre assim que a sessao
	// comeca de fato.
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

	// Preferimos o maior multiplo inteiro do framerate da fonte: assim cada
	// frame do console ocupa um numero inteiro de frames do painel e a cadencia
	// fica uniforme. So se nenhum multiplo existir e que caimos na maior taxa.
	float best = 0.0f;
	for(float rate : rates)
	{
		float ratio = rate / (float)sourceFps;
		bool integral = std::fabs(ratio - std::round(ratio)) < 0.01f && ratio >= 1.0f;
		if(integral && rate > best)
			best = rate;
	}
	if(best <= 0.0f)
	{
		for(float rate : rates)
			if(rate > best)
				best = rate;
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
	frame_thread_ = std::thread(&XrVideoSession::FrameLoop, this);
}

void XrVideoSession::StopFrameLoop()
{
	if(!running_.exchange(false))
		return;
	if(frame_thread_.joinable())
		frame_thread_.join();

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
				break;
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
	session_state_ = state;
	switch(state)
	{
		case XR_SESSION_STATE_READY:
		{
			XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
			begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
			if(XR_SUCCEEDED(xrBeginSession(session_, &begin)))
			{
				session_running_ = true;
				int fps = pending_refresh_fps_.exchange(0);
				if(fps > 0)
					SelectDisplayRefreshRate(fps);
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
			break;
		default:
			break;
	}
}

void XrVideoSession::RenderFrame()
{
	XrFrameWaitInfo wait_info{XR_TYPE_FRAME_WAIT_INFO};
	XrFrameState frame_state{XR_TYPE_FRAME_STATE};
	if(XR_FAILED(xrWaitFrame(session_, &wait_info, &frame_state)))
		return;

	XrFrameBeginInfo begin_info{XR_TYPE_FRAME_BEGIN_INFO};
	if(XR_FAILED(xrBeginFrame(session_, &begin_info)))
		return;

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
		// Onde ela vai importar e no olho sintetizado, e por um motivo so:
		// disparidade maior que a IPD obriga os olhos a divergir, o que nao e
		// desconforto, e impossivel -- nenhum par de olhos gira para fora. E o
		// teto rigido da forca do efeito, e ele sai daqui de graca.
		if(located >= 2 && !logged_ipd_ &&
				(view_state.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT))
		{
			const XrVector3f &a = views_[0].pose.position;
			const XrVector3f &b = views_[1].pose.position;
			float dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
			float ipd = std::sqrt(dx * dx + dy * dy + dz * dz);
			if(ipd > 0.03f && ipd < 0.09f)
			{
				logged_ipd_ = true;
				ipd_meters_.store(ipd);
				LOGI("Interpupillary distance in use: %.1f mm (read from the runtime, "
						"not asked for)", (double)(ipd * 1000.0f));
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
	}

	std::vector<const XrCompositionLayerBaseHeader *> layers;

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
		// No 3D o filtro do compositor sai de cena.
		//
		// Com o olho sintetizado a camada entra duas vezes, cada uma pegando
		// metade de uma textura do dobro da largura. O MQSR e o automatico
		// filtram cada camada por conta, e sobre meia textura o resultado
		// relatado em hardware foi um X discreto atravessando a tela -- o filtro
		// nao foi feito para uma camada que e um recorte de outra. O realce por
		// shader nao tem esse problema: ele acontece antes, sobre a imagem
		// inteira, e continua valendo nos degraus de 1 a 3.
		const bool compositor_owns_filter = stereo_mode_.load() == 0
				&& (render_path_ == RenderPath::Direct
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
					// Sozinho, sem o normal junto. A especificacao e explicita:
					// com os dois de sharpening ligados, o normal tem
					// precedencia -- e o MQSR nunca rodaria, parecendo defeito
					// dele quando seria erro nosso.
					layer_settings.layerFlags |=
							XR_COMPOSITION_LAYER_SETTINGS_QUALITY_SHARPENING_BIT_FB;
					break;
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
				LOGI("Camada com XrCompositionLayerSettingsFB, flags=0x%x",
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
		cylinder.subImage.imageArrayIndex = 0;

		// A tela e um arco: a pose marca o eixo do cilindro, e o arco visivel
		// fica centrado em -Z dessa pose. Yaw da recentragem + ajuste do usuario.
		float yaw = YawFromQuat(recenter) + params.yawOffset;
		cylinder.pose.orientation = QuatFromYaw(yaw);
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
		const float axis_back = arc_radius - distance;

		cylinder.pose.position = XrVector3f{
			std::sin(yaw) * axis_back,
			params.heightOffset,
			std::cos(yaw) * axis_back
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
		if(recommended_resolution_supported_ && pfnGetRecommendedLayerResolutionMETA)
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

		// Estereo: a mesma swapchain entra duas vezes, cada vez com metade da
		// imagem e um olho. Nao ha copia nem passada de GPU nenhuma -- so uma
		// camada a mais na lista, que e o que torna este modo praticamente de
		// graca comparado a qualquer coisa que sintetize imagem.
		const int stereo = stereo_mode_.load();
		if(stereo != 0)
		{
			XrRect2Di esquerdo = cylinder.subImage.imageRect;
			XrRect2Di direito = esquerdo;
			if(stereo == 1)
			{
				esquerdo.extent.width /= 2;
				direito.extent.width /= 2;
				direito.offset.x += esquerdo.extent.width;
			}
			else
			{
				esquerdo.extent.height /= 2;
				direito.extent.height /= 2;
				direito.offset.y += esquerdo.extent.height;
			}
			// A geometria da camada nao muda: a tela continua do mesmo tamanho
			// e no mesmo lugar. O que muda e qual pedaco da textura cada olho
			// enxerga.
			cylinder_right = cylinder;
			cylinder.eyeVisibility = XR_EYE_VISIBILITY_LEFT;
			cylinder.subImage.imageRect = esquerdo;
			cylinder_right.eyeVisibility = XR_EYE_VISIBILITY_RIGHT;
			cylinder_right.subImage.imageRect = direito;
		}

		if(layer_shape_.load() == LayerShape::Cylinder)
		{
			layers.push_back(reinterpret_cast<const XrCompositionLayerBaseHeader *>(&cylinder));
			if(stereo != 0)
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
			quad.pose.orientation = QuatFromYaw(yaw);
			quad.pose.position = XrVector3f{
				-std::sin(yaw) * params.radius,
				params.heightOffset,
				-std::cos(yaw) * params.radius
			};

			layers.push_back(reinterpret_cast<const XrCompositionLayerBaseHeader *>(&quad));
			if(stereo != 0)
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
		if(UpdateHudLayer(hud_layer, YawFromQuat(recenter) + params.yawOffset))
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
	xrEndFrame(session_, &end_info);
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

	tone_mapper_.Destroy();
	tone_mapper_ready_ = false;
	video_images_.clear();
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
