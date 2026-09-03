// SPDX-License-Identifier: AGPL-3.0-only
//
// Ponte JNI entre XrBridge.kt e a sessao OpenXR.
#include <jni.h>
#include <android/bitmap.h>
#include <android/surface_texture_jni.h>
#include "spatializer.h"
#include "tone_mapper.h"
#include "xr_session.h"
#include "log.h"

using p5m::XrVideoSession;
using p5m::ScreenParams;

namespace {

JavaVM *g_vm = nullptr;

inline XrVideoSession *Handle(jlong ptr)
{
	return reinterpret_cast<XrVideoSession *>(ptr);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *)
{
	g_vm = vm;
	return JNI_VERSION_1_6;
}

extern "C" {

#define JNI_FCN(name) Java_io_github_gblandro_p5m_XrBridge_##name

JNIEXPORT jlong JNICALL JNI_FCN(nativeCreate)(JNIEnv *env, jobject, jobject activity)
{
	auto *session = new XrVideoSession();
	if(!session->Create(g_vm, activity))
	{
		delete session;
		return 0;
	}
	return reinterpret_cast<jlong>(session);
}

JNIEXPORT jobject JNICALL JNI_FCN(nativeCreateVideoSurface)(JNIEnv *env, jobject, jlong ptr,
		jint width, jint height)
{
	if(!ptr)
		return nullptr;
	return Handle(ptr)->CreateVideoSurface(env, width, height);
}

JNIEXPORT jstring JNICALL JNI_FCN(nativeLastError)(JNIEnv *env, jobject, jlong ptr)
{
	if(!ptr)
		return env->NewStringUTF("");
	return env->NewStringUTF(Handle(ptr)->LastError());
}

JNIEXPORT void JNICALL JNI_FCN(nativeStart)(JNIEnv *, jobject, jlong ptr)
{
	if(ptr)
		Handle(ptr)->StartFrameLoop();
}

JNIEXPORT void JNICALL JNI_FCN(nativeStop)(JNIEnv *, jobject, jlong ptr)
{
	if(ptr)
		Handle(ptr)->StopFrameLoop();
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetScreenParams)(JNIEnv *, jobject, jlong ptr,
		jfloat radius, jfloat centralAngle, jfloat yawOffset, jfloat heightOffset,
		jfloat curvature)
{
	if(!ptr)
		return;
	ScreenParams params;
	params.radius = radius;
	params.centralAngle = centralAngle;
	params.curvature = curvature;
	params.yawOffset = yawOffset;
	params.heightOffset = heightOffset;
	Handle(ptr)->SetScreenParams(params);
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetQuality)(JNIEnv *, jobject, jlong ptr,
		jint sharpness, jfloat sharpenAmount, jfloat passthroughOpacity,
		jfloat cinemaBrightness, jfloat cinemaContrast, jfloat cinemaSaturation,
		jfloat videoBrightness, jboolean frameExtrapolation)
{
	if(!ptr)
		return;
	p5m::QualityParams params;
	switch(sharpness)
	{
		case 1: params.sharpness = p5m::Sharpness::Light; break;
		case 2: params.sharpness = p5m::Sharpness::Medium; break;
		case 3: params.sharpness = p5m::Sharpness::Strong; break;
		case 4: params.sharpness = p5m::Sharpness::Mqsr; break;
		case 5: params.sharpness = p5m::Sharpness::Auto; break;
		default: params.sharpness = p5m::Sharpness::Off; break;
	}
	params.sharpenAmount = sharpenAmount;
	params.passthroughOpacity = passthroughOpacity;
	params.passthroughBrightness = cinemaBrightness;
	params.passthroughContrast = cinemaContrast;
	params.passthroughSaturation = cinemaSaturation;
	params.videoBrightness = videoBrightness;
	params.frameExtrapolation = frameExtrapolation == JNI_TRUE;
	Handle(ptr)->SetQualityParams(params);
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetLayerShape)(JNIEnv *, jobject, jlong ptr, jint shape)
{
	if(ptr)
		Handle(ptr)->SetLayerShape(shape == 1 ? p5m::LayerShape::Quad
				: p5m::LayerShape::Cylinder);
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetVideoLayerEnabled)(JNIEnv *, jobject, jlong ptr,
		jboolean enabled)
{
	if(ptr)
		Handle(ptr)->SetVideoLayerEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetRenderPath)(JNIEnv *, jobject, jlong ptr,
		jint path, jboolean sourceIsPq)
{
	if(!ptr)
		return;
	Handle(ptr)->SetRenderPath(path == 1 ? p5m::RenderPath::ToneMapped
			: p5m::RenderPath::Direct);
	Handle(ptr)->SetSourceIsPq(sourceIsPq == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL JNI_FCN(nativeAttachSurfaceTexture)(JNIEnv *env, jobject, jlong ptr,
		jobject surfaceTexture)
{
	if(!ptr || !surfaceTexture)
		return JNI_FALSE;
	return Handle(ptr)->AttachSurfaceTexture(env, surfaceTexture) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetWideColor)(JNIEnv *, jobject, jlong ptr,
		jboolean wide)
{
	if(ptr)
		Handle(ptr)->SetWideColor(wide == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetVerticalFlip)(JNIEnv *, jobject, jlong ptr,
		jboolean enabled)
{
	if(ptr)
		Handle(ptr)->SetVerticalFlip(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetStereoMode)(JNIEnv *, jobject, jlong ptr,
		jint mode)
{
	if(ptr)
		Handle(ptr)->SetStereoMode((int)mode);
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetStereoTuning)(JNIEnv *, jobject, jlong ptr,
		jfloat strength, jfloat convergence)
{
	if(ptr)
		Handle(ptr)->SetStereoTuning((float)strength, (float)convergence);
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetHudBitmap)(JNIEnv *env, jobject, jlong ptr,
		jobject bitmap)
{
	if(!ptr || !bitmap)
		return;

	AndroidBitmapInfo info{};
	if(AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
	{
		LOGE("AndroidBitmap_getInfo failed");
		return;
	}
	if(info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
	{
		LOGE("The help panel needs ARGB_8888, got format %d", (int)info.format);
		return;
	}

	void *pixels = nullptr;
	if(AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
	{
		LOGE("AndroidBitmap_lockPixels failed");
		return;
	}
	Handle(ptr)->SetHudBitmap(static_cast<const uint8_t *>(pixels), (int32_t)info.width,
			(int32_t)info.height);
	AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetHudVisible)(JNIEnv *, jobject, jlong ptr,
		jboolean visible)
{
	if(ptr)
		Handle(ptr)->SetHudVisible(visible == JNI_TRUE);
}

JNIEXPORT jfloat JNICALL JNI_FCN(nativeSelectDisplayRefreshRate)(JNIEnv *, jobject, jlong ptr,
		jint sourceFps)
{
	if(!ptr)
		return 0.0f;
	return Handle(ptr)->SelectDisplayRefreshRate(sourceFps);
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetPassthrough)(JNIEnv *, jobject, jlong ptr, jboolean enabled)
{
	if(ptr)
		Handle(ptr)->SetPassthroughEnabled(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL JNI_FCN(nativeIsPassthroughSupported)(JNIEnv *, jobject, jlong ptr)
{
	return (ptr && Handle(ptr)->PassthroughSupported()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FCN(nativeSetSpatialAudio)(JNIEnv *, jobject, jfloat strength)
{
	// Sem ponteiro de sessao: o espacializador e um so no processo, porque quem
	// o chama e a thread de audio do chiaki, que nao sabe nada da sessao OpenXR.
	p5m::Spatializer::Instance().SetStrength(strength);
	if(strength > 0.0f)
		LOGI("Virtual speakers on, strength %.2f", (double)strength);
	else
		LOGI("Virtual speakers off: the audio passes through as it came");
}

/**
 * Retrato do desempenho num array de floats, para o painel.
 *
 * Array e nao objeto: isto e lido uma vez por segundo com o painel aberto, e um
 * objeto por leitura seria uma alocacao e uma classe a manter dos dois lados
 * para carregar oito numeros. O layout esta documentado no lado Kotlin, que e
 * quem os le.
 */
JNIEXPORT jfloatArray JNICALL JNI_FCN(nativeReadPerformance)(JNIEnv *env, jobject, jlong ptr)
{
	if(!ptr)
		return nullptr;
	p5m::PerformanceSnapshot snap = Handle(ptr)->ReadPerformance();
	jfloat values[] = {
		snap.droppedFrames ? 1.0f : 0.0f, snap.droppedFrameCount,
		snap.hasGpuUtilization ? 1.0f : 0.0f, snap.gpuUtilization,
		snap.hasAppGpuTime ? 1.0f : 0.0f, snap.appGpuTimeMs,
		snap.hasCompositorGpuTime ? 1.0f : 0.0f, snap.compositorGpuTimeMs,
		snap.hasThermal ? 1.0f : 0.0f, snap.thermalHeadroom, snap.thermalSlope,
		snap.hasMotionToPhoton ? 1.0f : 0.0f, snap.motionToPhotonMs,
		snap.hasCpuUtilization ? 1.0f : 0.0f, snap.cpuUtilization,
	};
	const jsize count = (jsize)(sizeof(values) / sizeof(values[0]));
	jfloatArray out = env->NewFloatArray(count);
	if(!out)
		return nullptr;
	env->SetFloatArrayRegion(out, 0, count, values);
	return out;
}

/** Largura nos 32 bits altos, altura nos baixos. Zero quando nao ha valor. */
JNIEXPORT jlong JNICALL JNI_FCN(nativeRecommendedResolution)(JNIEnv *, jobject, jlong ptr)
{
	if(!ptr)
		return 0;
	int w = 0, h = 0;
	Handle(ptr)->RecommendedResolution(&w, &h);
	return ((jlong)w << 32) | (jlong)(uint32_t)h;
}

JNIEXPORT void JNICALL JNI_FCN(nativeRecenter)(JNIEnv *, jobject, jlong ptr)
{
	if(ptr)
		Handle(ptr)->Recenter();
}

JNIEXPORT void JNICALL JNI_FCN(nativeDestroy)(JNIEnv *, jobject, jlong ptr)
{
	if(!ptr)
		return;
	auto *session = Handle(ptr);
	session->Destroy();
	delete session;
}


// -- Filtro do modo janela ------------------------------------------------
//
// No modo janela nao ha camada nossa no compositor, e portanto nao ha bits de
// sharpening para pedir: quem compoe e o Horizon OS, e o app e um painel comum
// como qualquer outro. A nitidez so pode ser nossa, entao o mesmo conversor de
// tons do modo imersivo roda aqui, desenhando no framebuffer da GLSurfaceView
// em vez de num swapchain.
//
// E de proposito que e o mesmo ToneMapper, e nao um shader gemeo do lado Java:
// dois shaders que deveriam concordar acabam divergindo, e a nitidez media de
// um modo deixaria de ser a nitidez media do outro.

#undef JNI_FCN
#define JNI_FCN(name) Java_io_github_gblandro_p5m_AudioRoute_##name

JNIEXPORT void JNICALL JNI_FCN(nativeSetPrefersSystemMixer)(JNIEnv *, jobject,
		jboolean prefers)
{
	p5m::SetPrefersSystemMixer(prefers == JNI_TRUE);
	LOGI("Audio output through the %s", prefers == JNI_TRUE
			? "system mixer (Horizon OS can position the sound)"
			: "direct path to the hardware (lower latency)");
}

#undef JNI_FCN
#define JNI_FCN(name) Java_io_github_gblandro_p5m_VideoFilter_##name

JNIEXPORT jlong JNICALL JNI_FCN(nativeCreate)(JNIEnv *, jobject)
{
	return reinterpret_cast<jlong>(new p5m::ToneMapper());
}

JNIEXPORT jboolean JNICALL JNI_FCN(nativeInit)(JNIEnv *env, jobject, jlong ptr,
		jobject surface_texture)
{
	if(!ptr)
		return JNI_FALSE;
	auto *mapper = reinterpret_cast<p5m::ToneMapper *>(ptr);
	if(!mapper->Init())
		return JNI_FALSE;
	ASurfaceTexture *native = ASurfaceTexture_fromSurfaceTexture(env, surface_texture);
	if(!native)
	{
		LOGE("ASurfaceTexture_fromSurfaceTexture failed in window mode");
		return JNI_FALSE;
	}
	return mapper->Attach(native) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FCN(nativeDraw)(JNIEnv *, jobject, jlong ptr,
		jint width, jint height, jboolean pq, jfloat sharpen)
{
	if(!ptr)
		return JNI_FALSE;
	// Alvo zero: o framebuffer padrao da GLSurfaceView. E encode ligado, porque
	// a janela e RGBA8 cru e nao aplica a curva do sRGB na escrita como faz o
	// swapchain do compositor.
	return reinterpret_cast<p5m::ToneMapper *>(ptr)->Render(0, width, height,
			pq == JNI_TRUE, sharpen, true) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FCN(nativeDestroy)(JNIEnv *, jobject, jlong ptr)
{
	if(!ptr)
		return;
	auto *mapper = reinterpret_cast<p5m::ToneMapper *>(ptr);
	mapper->Destroy();
	delete mapper;
}

} // extern "C"
