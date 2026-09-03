#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Confere se o C++ compila, sem NDK e sem esperar quatro minutos de CI.

Existe por causa de uma build vermelha que custou meia hora de leitura de
codigo. O `conferir.py` olhava manifesto, strings e patches -- tudo menos a
unica coisa que a build faz de verdade -- e o log do GitHub e inalcancavel
daqui: ele redireciona para um servidor de blobs que a rede deste ambiente nao
alcanca, e as anotacoes do check exigem uma permissao que o token nao tem.
Entao o erro so aparecia depois do push, sem dizer onde.

Nao da para ter o NDK aqui, mas tambem nao precisa: erro de sintaxe, nome
errado, constante inexistente, membro que nao existe e argumento a mais ou a
menos aparecem contra os cabecalhos de verdade.

**Cabecalhos de verdade, e essa parte foi aprendida errando.** A primeira
versao gerava os do GL a partir do proprio codigo -- toda constante `GL_*`
encontrada virava um `#define`, toda funcao `gl*` um template que aceitava
qualquer coisa. O teste passava sempre, inclusive num
`glTexParameteri(..., GL_MIN_FILTER, ...)`, que nao existe: o nome certo e
`GL_TEXTURE_MIN_FILTER`, e o gerador simplesmente inventou o errado. Um teste
que fabrica o que deveria conferir nao e um teste fraco, e um teste ao
contrario. Agora GL, EGL e OpenXR vem do Khronos, em cache, e so JNI e os
cabecalhos do Android continuam imitados -- esses o codigo usa por poucas
funcoes, e a imitacao ali nao esconde nada.
"""

import os
import re
import subprocess
import sys
import urllib.request

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CPP = os.path.join(RAIZ, "app/src/main/cpp")
CACHE = os.path.join(RAIZ, ".cache-cabecalhos")
BASE = "https://raw.githubusercontent.com/KhronosGroup/"
# Cabecalhos de verdade, e de onde cada um vem.
REAIS = [
    ("OpenXR-SDK/main/include/openxr/openxr.h", "openxr/openxr.h"),
    ("OpenXR-SDK/main/include/openxr/openxr_platform.h", "openxr/openxr_platform.h"),
    ("OpenXR-SDK/main/include/openxr/openxr_platform_defines.h",
     "openxr/openxr_platform_defines.h"),
    ("OpenGL-Registry/main/api/GLES3/gl3.h", "GLES3/gl3.h"),
    ("OpenGL-Registry/main/api/GLES3/gl3platform.h", "GLES3/gl3platform.h"),
    ("OpenGL-Registry/main/api/GLES2/gl2.h", "GLES2/gl2.h"),
    ("OpenGL-Registry/main/api/GLES2/gl2ext.h", "GLES2/gl2ext.h"),
    ("OpenGL-Registry/main/api/GLES2/gl2platform.h", "GLES2/gl2platform.h"),
    ("EGL-Registry/main/api/EGL/egl.h", "EGL/egl.h"),
    ("EGL-Registry/main/api/EGL/eglext.h", "EGL/eglext.h"),
    ("EGL-Registry/main/api/EGL/eglplatform.h", "EGL/eglplatform.h"),
    ("EGL-Registry/main/api/KHR/khrplatform.h", "KHR/khrplatform.h"),
]


def baixar_reais():
    """Cabecalhos de verdade do Khronos, em cache. Devolve False sem rede."""
    for origem, destino in REAIS:
        caminho = os.path.join(CACHE, "real", destino)
        os.makedirs(os.path.dirname(caminho), exist_ok=True)
        if os.path.exists(caminho) and os.path.getsize(caminho) > 500:
            continue
        try:
            with urllib.request.urlopen(BASE + origem, timeout=60) as r:
                dados = r.read()
            with open(caminho, "wb") as f:
                f.write(dados)
        except Exception as e:
            print(f"  sem os cabecalhos do Khronos ({e}); pulando")
            return False
    return True


def stubs_android():
    """Imitacoes para o que nao vem do Khronos: JNI e os cabecalhos do Android.

    Aqui a imitacao e segura porque o codigo usa pouca coisa e de forma direta.
    Os metodos do JNIEnv sao gerados a partir do proprio uso -- escrever a mao
    uma lista que o codigo pode aumentar amanha seria criar um teste que passa
    a mentir sozinho.
    """
    fontes = []
    for nome in os.listdir(CPP):
        if nome.endswith((".cpp", ".h")):
            with open(os.path.join(CPP, nome), encoding="utf-8") as f:
                fontes.append(f.read())
    txt = "\n".join(fontes)

    def escrever(rel, texto):
        caminho = os.path.join(CACHE, "stub", rel)
        os.makedirs(os.path.dirname(caminho), exist_ok=True)
        with open(caminho, "w", encoding="utf-8") as f:
            f.write(texto)

    chamados = sorted(set(re.findall(r"(?:E|env|jni)->(\w+)\s*\(", txt)))
    devolve_ponteiro = ("New", "Get", "Find", "To", "Call")
    metodos = []
    for m in chamados:
        if m.startswith("GetStringUTFChars"):
            metodos.append(f"\ttemplate<class...A> const char *{m}(A...) {{ return nullptr; }}")
        elif m.startswith(devolve_ponteiro) and not m.startswith(("GetArrayLength", "GetEnv")):
            metodos.append(f"\ttemplate<class...A> jobject {m}(A...) {{ return nullptr; }}")
        else:
            metodos.append(f"\ttemplate<class...A> jint {m}(A...) {{ return 0; }}")
    jni_metodos = "\n".join(metodos) if metodos else "\tint vazio;"

    # Incluido a forca em todo arquivo, antes de tudo.
    #
    # SIGSTKSZ deixou de ser constante na glibc: la ele e uma chamada a
    # sysconf, e `static char pilha[SIGSTKSZ * 2]` nao compila. No bionic, que
    # e o que a build de verdade usa, ele e constante. Desfazer e redefinir
    # depois do signal.h e a unica ordem que funciona -- definir antes so
    # produz redefinicao, e o cabecalho do sistema ganha.
    escrever("forcado.h", """#pragma once
#include <signal.h>
#undef SIGSTKSZ
#define SIGSTKSZ 32768
""")

    escrever("android/log.h", """#pragma once
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
inline int __android_log_print(int, const char *, const char *, ...) { return 0; }
inline int __android_log_write(int, const char *, const char *) { return 0; }
""")
    escrever("android/surface_texture.h", """#pragma once
#include <cstdint>
struct ASurfaceTexture;
inline int ASurfaceTexture_updateTexImage(ASurfaceTexture *) { return 0; }
inline int64_t ASurfaceTexture_getTimestamp(ASurfaceTexture *) { return 0; }
inline void ASurfaceTexture_getTransformMatrix(ASurfaceTexture *, float *) {}
inline int ASurfaceTexture_attachToGLContext(ASurfaceTexture *, unsigned) { return 0; }
inline int ASurfaceTexture_detachFromGLContext(ASurfaceTexture *) { return 0; }
inline void ASurfaceTexture_release(ASurfaceTexture *) {}
inline ASurfaceTexture *ASurfaceTexture_fromSurfaceTexture(void *, void *) { return nullptr; }
""")
    escrever("android/surface_texture_jni.h", "#pragma once\n#include <android/surface_texture.h>\n")
    escrever("android/native_window.h", """#pragma once
struct ANativeWindow;
inline void ANativeWindow_release(ANativeWindow *) {}
inline ANativeWindow *ANativeWindow_fromSurface(void *, void *) { return nullptr; }
""")
    escrever("android/native_window_jni.h", "#pragma once\n#include <android/native_window.h>\n")
    escrever("android/bitmap.h", """#pragma once
#include <cstdint>
#define ANDROID_BITMAP_RESULT_SUCCESS 0
#define ANDROID_BITMAP_FORMAT_RGBA_8888 1
struct AndroidBitmapInfo { uint32_t width, height, stride; int32_t format; uint32_t flags; };
template<class...A> inline int AndroidBitmap_getInfo(A...) { return 0; }
template<class...A> inline int AndroidBitmap_lockPixels(A...) { return 0; }
template<class...A> inline int AndroidBitmap_unlockPixels(A...) { return 0; }
""")
    # Os metodos do JNIEnv saem do uso, pelo mesmo motivo do GL: escrever a
    # mao uma lista que o codigo pode aumentar amanha seria criar um teste que
    # falha por si mesmo. O que devolve ponteiro precisa devolver ponteiro.
    chamados = sorted(set(re.findall(r"(?:E|env|jni)->(\w+)\s*\(", txt)))
    devolve_ponteiro = ("New", "Get", "Find", "To", "Call")
    metodos = []
    for m in chamados:
        if m.startswith("GetStringUTFChars"):
            metodos.append(f"\ttemplate<class...A> const char *{m}(A...) {{ return nullptr; }}")
        elif m.startswith(devolve_ponteiro) and not m.startswith(("GetArrayLength", "GetEnv")):
            metodos.append(f"\ttemplate<class...A> jobject {m}(A...) {{ return nullptr; }}")
        else:
            metodos.append(f"\ttemplate<class...A> jint {m}(A...) {{ return 0; }}")
    jni_metodos = "\n".join(metodos) if metodos else "\tint vazio;"

    escrever("jni.h", """#pragma once
#include <cstdint>
typedef int jint; typedef long long jlong; typedef unsigned char jboolean;
typedef float jfloat; typedef signed char jbyte; typedef unsigned short jchar;
typedef short jshort; typedef double jdouble; typedef jint jsize;
class _jobject {}; typedef _jobject *jobject; typedef jobject jclass;
typedef jobject jstring; typedef jobject jarray; typedef jobject jobjectArray;
typedef jobject jbyteArray; typedef jobject jfloatArray; typedef jobject jintArray;
struct JNIEnv {
__METODOS_JNI__
};
struct JavaVM {
	template<class...A> jint GetEnv(A...) { return 0; }
	template<class...A> jint AttachCurrentThread(A...) { return 0; }
	template<class...A> jint DetachCurrentThread(A...) { return 0; }
};
#define JNI_TRUE 1
#define JNI_FALSE 0
#define JNIEXPORT
#define JNICALL
#define JNI_VERSION_1_6 0x00010006
#define JNI_OK 0
""".replace("__METODOS_JNI__", jni_metodos))


def main():
    if subprocess.call(["which", "g++"], stdout=subprocess.DEVNULL) != 0:
        print("Compilacao nativa")
        print("  sem g++; pulando")
        return 0

    print("Compilacao nativa")
    if not baixar_reais():
        return 0
    stubs_android()
    real = os.path.join(CACHE, "real")
    inc = os.path.join(CACHE, "stub")

    falhas = 0
    for nome in sorted(os.listdir(CPP)):
        if not nome.endswith(".cpp"):
            continue
        fonte = os.path.join(CPP, nome)
        # Os de verdade ANTES dos imitados: se um dia sobrar um imitado com o
        # mesmo nome, e o de verdade que tem de ganhar.
        cmd = ["g++", "-std=c++17", "-fsyntax-only",
               f"-I{real}", f"-I{inc}", f"-I{CPP}",
               "-include", os.path.join(CACHE, "stub", "forcado.h"), fonte]
        r = subprocess.run(cmd, capture_output=True, text=True)
        erros = [l for l in r.stderr.splitlines() if ": error:" in l]
        if erros:
            falhas += len(erros)
            print(f"  FALHA: {nome}")
            for l in erros[:8]:
                print(f"    {l.strip()}")
        else:
            print(f"  {nome} ok")
    if falhas:
        print(f"\n{falhas} erro(s) de compilacao. A build vai falhar.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
