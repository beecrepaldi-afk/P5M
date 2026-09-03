// SPDX-License-Identifier: AGPL-3.0-only
//
// Pilha de chamada de uma queda nativa, escrita pelo proprio app.
//
// Por que isto existe: cinco quedas com `pc = 0` apareceram no diario, todas
// com tres quadros dentro de libchiaki-jni.so e nenhum nome. O tombstone do
// sistema traz offsets, e offsets so viram nomes com o .so nao removido e um
// addr2line -- ou seja, com um PC. Este projeto nao tem PC.
//
// O `dladdr` resolve o simbolo exportado mais proximo em tempo de execucao, no
// aparelho. Nao e tao preciso quanto um addr2line com simbolos completos: para
// uma funcao `static` ele devolve a funcao exportada anterior. Mas a diferenca
// entre "offset 0x1c2000" e "perto de chiaki_audio_receiver_av_packet" e a
// diferenca entre nao saber e saber por onde comecar.
//
// Nada aqui substitui o tombstone: o handler antigo continua sendo chamado no
// fim, entao o sistema registra tudo o que registrava.

#include <cstddef>
#include <cstring>
#include <csignal>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <unistd.h>
#include <unwind.h>

#include "log.h"

namespace {

constexpr size_t kMaxQuadros = 32;
char g_caminho[512];
struct sigaction g_anterior[6];
const int kSinais[] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT, SIGTRAP};

struct Coleta
{
	uintptr_t pc[kMaxQuadros];
	size_t n;
};

_Unwind_Reason_Code Colher(struct _Unwind_Context *ctx, void *arg)
{
	auto *c = static_cast<Coleta *>(arg);
	uintptr_t ip = _Unwind_GetIP(ctx);
	if(ip && c->n < kMaxQuadros)
		c->pc[c->n++] = ip;
	return c->n >= kMaxQuadros ? _URC_END_OF_STACK : _URC_NO_REASON;
}

// Escrita crua, sem printf.
//
// Dentro de um handler de sinal quase nada e seguro de chamar: o processo pode
// ter morrido no meio de um malloc, e printf aloca. write() e open() estao na
// lista curta do que da para usar; os formatadores abaixo existem so para nao
// precisar de mais nada.
void Escreve(int fd, const char *s)
{
	if(s)
		(void)!write(fd, s, strlen(s));
}

void EscreveHex(int fd, uintptr_t v)
{
	char buf[19] = "0x";
	const char *d = "0123456789abcdef";
	int i = 2;
	bool comecou = false;
	for(int shift = 60; shift >= 0; shift -= 4)
	{
		int nib = (int)((v >> shift) & 0xF);
		if(nib || comecou || shift == 0)
		{
			comecou = true;
			buf[i++] = d[nib];
		}
	}
	buf[i] = '\0';
	Escreve(fd, buf);
}

void EscreveDec(int fd, long v)
{
	char buf[24];
	int i = sizeof(buf) - 1;
	buf[i] = '\0';
	if(v == 0)
		buf[--i] = '0';
	bool neg = v < 0;
	unsigned long u = neg ? (unsigned long)(-v) : (unsigned long)v;
	while(u && i > 0)
	{
		buf[--i] = (char)('0' + (u % 10));
		u /= 10;
	}
	if(neg && i > 0)
		buf[--i] = '-';
	Escreve(fd, buf + i);
}

const char *NomeDoSinal(int s)
{
	switch(s)
	{
		case SIGSEGV: return "SIGSEGV";
		case SIGBUS: return "SIGBUS";
		case SIGILL: return "SIGILL";
		case SIGFPE: return "SIGFPE";
		case SIGABRT: return "SIGABRT";
		case SIGTRAP: return "SIGTRAP";
		default: return "sinal";
	}
}

void Manipulador(int sinal, siginfo_t *info, void *contexto)
{
	int fd = open(g_caminho, O_WRONLY | O_CREAT | O_TRUNC, 0600);
	if(fd >= 0)
	{
		Escreve(fd, NomeDoSinal(sinal));
		Escreve(fd, " (");
		EscreveDec(fd, sinal);
		Escreve(fd, "), endereco ");
		EscreveHex(fd, info ? (uintptr_t)info->si_addr : 0);
		Escreve(fd, ", thread ");
		EscreveDec(fd, (long)gettid());
		Escreve(fd, "\n");
		// pc = 0 e a assinatura de chamada a ponteiro de funcao nulo, e nao de
		// leitura de ponteiro nulo. Vale dito por extenso: os dois aparecem como
		// SIGSEGV no endereco zero, e so este texto os separa.
		if(info && info->si_addr == nullptr && sinal == SIGSEGV)
			Escreve(fd, "Probably a call through a null function pointer.\n");
		Escreve(fd, "\n");

		Coleta c;
		c.n = 0;
		_Unwind_Backtrace(Colher, &c);
		for(size_t i = 0; i < c.n; i++)
		{
			Escreve(fd, "  #");
			EscreveDec(fd, (long)i);
			Escreve(fd, "  ");
			EscreveHex(fd, c.pc[i]);
			Dl_info d;
			if(dladdr((void *)c.pc[i], &d))
			{
				if(d.dli_fname)
				{
					const char *barra = strrchr(d.dli_fname, '/');
					Escreve(fd, "  ");
					Escreve(fd, barra ? barra + 1 : d.dli_fname);
				}
				if(d.dli_sname)
				{
					Escreve(fd, "  ");
					Escreve(fd, d.dli_sname);
					Escreve(fd, "+");
					EscreveDec(fd, (long)((uintptr_t)c.pc[i] - (uintptr_t)d.dli_saddr));
				}
				else if(d.dli_fbase)
				{
					// Sem nome de simbolo: pelo menos o deslocamento dentro da
					// biblioteca, que e o que o tombstone tambem mostra.
					Escreve(fd, "  offset ");
					EscreveHex(fd, (uintptr_t)c.pc[i] - (uintptr_t)d.dli_fbase);
				}
			}
			Escreve(fd, "\n");
		}
		close(fd);
	}

	// Encadeia no handler anterior para o sistema seguir gerando o tombstone.
	// Sem isto o processo morreria aqui e o registro do Android desapareceria --
	// trocariamos uma fonte por outra em vez de somar as duas.
	for(size_t i = 0; i < sizeof(kSinais) / sizeof(kSinais[0]); i++)
	{
		if(kSinais[i] == sinal)
		{
			sigaction(sinal, &g_anterior[i], nullptr);
			break;
		}
	}
	raise(sinal);
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_gblandro_p5m_PilhaNativa_nativeInstalar(JNIEnv *env, jobject, jstring caminho)
{
	const char *c = env->GetStringUTFChars(caminho, nullptr);
	if(!c)
		return JNI_FALSE;
	strncpy(g_caminho, c, sizeof(g_caminho) - 1);
	g_caminho[sizeof(g_caminho) - 1] = '\0';
	env->ReleaseStringUTFChars(caminho, c);

	// Pilha propria para o handler: se a queda for estouro de pilha, a thread
	// nao tem espaco para rodar mais nada, e o handler morreria antes de
	// escrever a primeira linha.
	static char pilha[SIGSTKSZ * 2];
	stack_t ss{};
	ss.ss_sp = pilha;
	ss.ss_size = sizeof(pilha);
	sigaltstack(&ss, nullptr);

	struct sigaction sa{};
	sa.sa_sigaction = Manipulador;
	sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
	sigemptyset(&sa.sa_mask);
	for(size_t i = 0; i < sizeof(kSinais) / sizeof(kSinais[0]); i++)
		sigaction(kSinais[i], &sa, &g_anterior[i]);

	LOGI("Native stack capture installed at %s", g_caminho);
	return JNI_TRUE;
}
