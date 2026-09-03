#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""
Conferencias baratas que rodam antes do Gradle.

Existem porque este projeto tem tres fronteiras que nenhum compilador
atravessa sozinho, e cada uma delas ja quebrou um build nesta arvore:

  1. JNI. Kotlin declara `external fun` e o C++ define a funcao com o nome
     decorado. Nada liga as duas em tempo de compilacao -- a divergencia so
     aparece como UnsatisfiedLinkError, no aparelho, na hora de usar.

  2. Tabelas e ciclos. Um botao que cicla `% 6` sobre uma lista de quatro
     nomes compila perfeitamente e estoura em indice quando alguem aperta.

  3. Patches do submodulo. Aplicam ou nao aplicam, e quando nao aplicam o
     Gradle falha com uma mensagem que nao diz qual patch nem por que.

Cada verificacao aqui custa milissegundos e substitui uma volta inteira de
CI -- ou, pior, uma sessao de teste inteira gasta com um APK que nao podia
funcionar.
"""

import glob
import os
import re
import shutil
import subprocess
import tempfile
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
falhas = []


def erro(msg):
    falhas.append(msg)
    print(f"  FALHA: {msg}")


def manifesto():
    print("Manifesto Android")
    caminho = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")
    try:
        ET.parse(caminho)
        print("  XML valido")
    except ET.ParseError as e:
        # "--" dentro de comentario ja custou um build inteiro aqui.
        erro(f"AndroidManifest.xml invalido: {e}")


def jni():
    print("Assinaturas JNI")
    kt = {}
    for f in glob.glob(os.path.join(ROOT, "app/src/main/java/io/github/gblandro/p5m/*.kt")):
        classe = os.path.basename(f)[:-3]
        src = open(f, encoding="utf-8").read()
        for m in re.finditer(r"external fun (\w+)\(([^)]*)\)", src, re.S):
            n = len([p for p in m.group(2).split(",") if p.strip()])
            kt[(classe, m.group(1))] = n

    # Todos os .cpp, e nao so o p5m_jni: uma funcao JNI num arquivo que a
    # conferencia nao lia passaria como "sem definicao no C++" e a mensagem
    # mandaria procurar um defeito que nao existe.
    jni_defs = {}
    for arquivo in glob.glob(os.path.join(ROOT, "app/src/main/cpp/*.cpp")):
        cpp = open(arquivo, encoding="utf-8").read()

        # Forma 1: o macro JNI_FCN, redefinido ao longo do arquivo, uma vez por
        # classe. Partir por ele da os blocos e a qual classe cada um pertence.
        partes = re.split(r"#define JNI_FCN\(name\) Java_io_github_gblandro_p5m_(\w+)_##name", cpp)
        classe = None
        for i, bloco in enumerate(partes):
            if i % 2 == 1:
                classe = bloco
            elif classe:
                for m in re.finditer(r"JNI_FCN\((\w+)\)\(JNIEnv \*[^,]*, jobject[^)]*\)", bloco, re.S):
                    args = m.group(0).split("jobject", 1)[1]
                    n = len([a for a in args.split(",")[1:] if a.strip().rstrip(")")])
                    jni_defs[(classe, m.group(1))] = n

        # Forma 2: o nome decorado escrito por extenso, que e o que faz sentido
        # num arquivo com uma funcao so.
        for m in re.finditer(
                r"Java_io_github_gblandro_p5m_(\w+)_(\w+)\s*\(\s*JNIEnv \*[^,]*,\s*jobject[^)]*\)",
                cpp, re.S):
            args = m.group(0).split("jobject", 1)[1]
            n = len([a for a in args.split(",")[1:] if a.strip().rstrip(")")])
            jni_defs[(m.group(1), m.group(2))] = n

    for chave in sorted(set(kt) | set(jni_defs)):
        nome = f"{chave[0]}.{chave[1]}"
        if chave not in kt:
            erro(f"{nome} existe no C++ e nao no Kotlin")
        elif chave not in jni_defs:
            erro(f"{nome} declarada no Kotlin e sem definicao no C++")
        elif kt[chave] != jni_defs[chave]:
            erro(f"{nome}: Kotlin passa {kt[chave]} argumentos, C++ espera {jni_defs[chave]}")
    if not falhas:
        print(f"  {len(kt)} funcoes conferidas")


def _tabelas():
    tabelas = {}
    for f in glob.glob(os.path.join(ROOT, "app/src/main/java/io/github/gblandro/p5m/*.kt")):
        src = open(f, encoding="utf-8").read()
        for m in re.finditer(r"val (\w+)\s*=\s*(listOf|floatArrayOf|arrayOf)\(", src):
            nome, tipo = m.group(1), m.group(2)
            corpo, nivel, i = "", 0, m.end() - 1
            while i < len(src):
                if src[i] == "(":
                    nivel += 1
                elif src[i] == ")":
                    nivel -= 1
                    if nivel == 0:
                        break
                corpo += src[i]
                i += 1
            corpo = corpo[1:]
            if tipo == "arrayOf":
                tabelas[nome] = corpo.count("floatArrayOf")
            else:
                tabelas[nome] = len([x for x in corpo.split(",") if x.strip()])
    return tabelas


def ciclos():
    print("Tabelas e ciclos")
    tabelas = _tabelas()
    # Preferencia -> tabelas que sao indexadas por ela.
    ligacoes = {
        "sharpness": ["SHARPNESS_NAMES", "SHARPEN_AMOUNT"],
        "brightness": ["BRIGHTNESS_NAMES", "BRIGHTNESS_SCALE"],
        "spatialAudio": ["SPATIAL_NAMES", "SPATIAL_STRENGTH"],
        "cinema": ["CINEMA_NAMES", "CINEMA_GRADE"],
    }

    modulos, tetos = {}, {}
    for f in glob.glob(os.path.join(ROOT, "app/src/main/java/**/*.kt"), recursive=True):
        src = open(f, encoding="utf-8").read()
        for m in re.finditer(r"\.(\w+) = \((?:\w+\.)?\w+ \+ 1\) % (\d+)", src):
            modulos.setdefault(m.group(1), set()).add(int(m.group(2)))
        for m in re.finditer(r"var (\w+): Int\s*\n\s*get\(\) = prefs\.getInt\([^)]*\)\.coerceIn\(0, (\d+)\)", src):
            tetos[m.group(1)] = int(m.group(2))

    for pref, nomes in ligacoes.items():
        tamanhos = {n: tabelas.get(n) for n in nomes}
        if None in tamanhos.values():
            erro(f"{pref}: tabela ausente {[n for n, v in tamanhos.items() if v is None]}")
            continue
        if len(set(tamanhos.values())) != 1:
            erro(f"{pref}: tabelas com tamanhos diferentes {tamanhos}")
            continue
        tamanho = next(iter(tamanhos.values()))
        for mod in modulos.get(pref, []):
            if mod != tamanho:
                erro(f"{pref}: ciclo cicla %{mod} mas a tabela tem {tamanho} itens")
        if pref in tetos and tetos[pref] != tamanho - 1:
            erro(f"{pref}: coerceIn(0, {tetos[pref]}) mas a tabela tem {tamanho} itens")
        print(f"  {pref}: {tamanho} degraus, coerentes")


def desempenho():
    print("Array de desempenho")
    cpp = open(os.path.join(ROOT, "app/src/main/cpp/p5m_jni.cpp"), encoding="utf-8").read()
    m = re.search(r"jfloat values\[\] = \{(.*?)\};", cpp, re.S)
    if not m:
        erro("nao achei o array de desempenho no JNI")
        return
    n = len([x for x in m.group(1).replace("\n", " ").split(",") if x.strip()])

    kt = open(os.path.join(ROOT, "app/src/main/java/com/metallic/chiaki/stream/VrStreamActivity.kt"),
              encoding="utf-8").read()
    indices = [int(x) for x in re.findall(r"perf\[(\d+)\]", kt)]
    guarda = re.search(r"perf\.size >= (\d+)", kt)
    if not indices or not guarda:
        erro("nao achei os indices de perf[] ou a guarda de tamanho")
        return
    guarda = int(guarda.group(1))
    if max(indices) >= guarda:
        erro(f"perf[{max(indices)}] lido com guarda de apenas {guarda}")
    elif guarda != n:
        erro(f"guarda espera {guarda} floats e o JNI entrega {n}")
    else:
        print(f"  {n} floats, maior indice {max(indices)}, guarda {guarda}")


def raizes():
    """O caminho do pacote de certificados vive em dois lugares.

    CURL_CA_BUNDLE e macro de compilacao, entao o caminho e decidido no
    build.gradle; quem escreve o arquivo la e o CaBundle.kt, em tempo de
    execucao. Se os dois se separarem -- um applicationId renomeado, um
    caminho ajustado so de um lado -- nada quebra na compilacao: o curl
    simplesmente recusa todo HTTPS em silencio, no aparelho, longe daqui.
    Foi assim que a primeira listagem de consoles da PSN morreu.
    """
    print("Raizes de confianca do curl")
    gradle = open(os.path.join(ROOT, "app/build.gradle"), encoding="utf-8").read()
    kt_path = os.path.join(ROOT, "app/src/main/java/io/github/gblandro/p5m/CaBundle.kt")
    if not os.path.isfile(kt_path):
        erro("CaBundle.kt ausente, mas o build.gradle define CURL_CA_BUNDLE"
             if "CURL_CA_BUNDLE" in gradle else "CaBundle.kt ausente")
        return
    kt = open(kt_path, encoding="utf-8").read()

    m = re.search(r'"-DCURL_CA_BUNDLE=([^"]+)"', gradle)
    if not m:
        erro("build.gradle nao define -DCURL_CA_BUNDLE; o curl fica sem raizes")
        return
    do_gradle = m.group(1)

    m = re.search(r'COMPILED_PATH\s*=\s*"([^"]+)"', kt)
    if not m:
        erro("CaBundle.COMPILED_PATH nao encontrado")
        return
    # O valor tem $FILE_NAME interpolado; resolve antes de comparar.
    do_kt = m.group(1)
    mf = re.search(r'FILE_NAME\s*=\s*"([^"]+)"', kt)
    if mf:
        do_kt = do_kt.replace("$FILE_NAME", mf.group(1))

    if do_gradle != do_kt:
        erro(f"caminhos diferentes:\n  build.gradle: {do_gradle}\n  CaBundle.kt:  {do_kt}")
        return

    m = re.search(r'applicationId\s+"([^"]+)"', gradle)
    if m and m.group(1) not in do_gradle:
        erro(f"o caminho ({do_gradle}) nao contem o applicationId ({m.group(1)})")
        return
    print(f"  build.gradle e CaBundle.kt apontam para {do_gradle}")


def patches():
    print("Patches do submodulo")
    sub = os.path.join(ROOT, "external/chiaki-ng")
    if not os.path.isdir(os.path.join(sub, ".git")) and not os.path.isfile(os.path.join(sub, ".git")):
        print("  submodulo ausente; pulando")
        return
    lista = sorted(glob.glob(os.path.join(ROOT, "patches", "*.patch")))
    if not lista:
        erro("nenhum patch encontrado")
        return
    # Um `git apply --check` com todos de uma vez confere cada patch contra a
    # arvore limpa, e nao contra o resultado do anterior. Enquanto nenhum par
    # de patches tocou o mesmo trecho isso passou despercebido; no primeiro
    # que tocou, a conferencia acusou falha num patch que aplica bem. O que
    # importa e a ordem, entao a ordem e o que se testa: uma worktree
    # descartavel no HEAD do submodulo, os patches aplicados um a um, e o
    # primeiro que recusar diz o proprio nome.
    tmp = tempfile.mkdtemp(prefix="p5m-patches-")
    arvore = os.path.join(tmp, "arvore")
    try:
        r = subprocess.run(["git", "worktree", "add", "--detach", arvore, "HEAD"],
                           cwd=sub, capture_output=True, text=True)
        if r.returncode != 0:
            print(f"  worktree indisponivel; pulando ({r.stderr.strip().splitlines()[-1:]})")
            return
        for caminho in lista:
            r = subprocess.run(["git", "apply", caminho], cwd=arvore,
                               capture_output=True, text=True)
            if r.returncode != 0:
                erro(f"{os.path.basename(caminho)} nao aplica sobre os anteriores:\n"
                     f"{r.stderr.strip()}")
                return
        print(f"  {len(lista)} patches aplicam, na ordem")
    finally:
        subprocess.run(["git", "worktree", "remove", "--force", arvore],
                       cwd=sub, capture_output=True, text=True)
        shutil.rmtree(tmp, ignore_errors=True)


def strings_quebradas():
    """String de uma linha partida por uma quebra de linha de verdade.

    Kotlin nao aceita isso, e o erro que o compilador devolve aponta a linha
    SEGUINTE -- entao procura-se no lugar errado. Custou oito builds seguidas:
    duas linhas de texto de ajuda no lancador, escritas com uma quebra dentro
    das aspas, e nada mais no repositorio compilou desde entao.

    A deteccao e por indentacao, e nao por contagem de aspas. Contar aspas
    acusa toda prosa que cite algo entre aspas, e teste barulhento e pior do que
    teste nenhum: aprende-se a ignora-lo. Aqui a marca e exata -- neste
    repositorio toda continuacao de linha comeca com tabulacao, entao uma linha
    que comeca com aspas na coluna zero e sempre uma string que vazou.
    """
    print("Strings quebradas")
    arquivos = glob.glob(os.path.join(ROOT, "app/src/main/java/**/*.kt"),
                         recursive=True)
    achou = False
    for f in arquivos:
        with open(f, encoding="utf-8") as fh:
            for n, linha in enumerate(fh, 1):
                if linha.startswith('"'):
                    achou = True
                    erro(f"{os.path.relpath(f, ROOT)}:{n} comeca com aspas na "
                         f"coluna zero: string partida por quebra de linha")
    if not achou:
        print(f"  {len(arquivos)} arquivos Kotlin, nenhuma string partida")


def interface_em_ingles():
    """Nenhuma string do app pode estar em portugues.

    A interface e o diario sao em ingles desde o beta. Comentario continua em
    portugues -- e para nos dois, nao para quem usa --, entao a marca e "o
    texto ENTRE ASPAS", e nao "o arquivo".

    A primeira versao procurava acento, e foi um teste que passava sem testar:
    "Video 10s: %u quadros | entrega ..." nao tem um acento sequer, e nem
    "Tecla recebida", nem "Saude 10s", nem metade das linhas dos patches. Todas
    passaram limpas e sairam no primeiro log depois da traducao. Agora e uma
    lista de palavras, que pega o portugues escrito sem acento -- que e
    justamente como se escreve dentro de string.

    Os patches entram na varredura pelo mesmo motivo: metade das linhas do
    diario nasce la, e olhar so o nosso codigo deixaria de fora exatamente as
    que escaparam da primeira vez.
    """
    print("Interface em ingles")
    palavras = (
        "nao|nenhum|nenhuma|falha|falhou|falhar|erro|sessao|quadro|quadros|"
        "linha|linhas|versao|aparelho|tela|rede|vazio|copiado|copiar|limpar|"
        "abrir|abrindo|enviar|servir|atualizar|anterior|diario|desligado|"
        "ligado|pronto|aguardando|conectando|dispositivo|dispositivos|entrega|"
        "entregue|ritmo|adiantado|atraso|decodificacao|portao|estouros|saude|"
        "termico|descartados|cabeca|foton|haptica|hapticos|cadencia|ensaio|"
        "tecla|recebida|indisponivel|recusado|recusada|aceito|aceita|criado|"
        "criada|pedido|pedidos|taxa|taxas|espelhado|desempenho|extensao|"
        "extensoes|conversor|contador|painel|dominio|adiada|habilitado|"
        "caminho|saida|entrada|vibracao|previsto|brilho|espaco|degrau|"
        "amostras|fluxo|primeiro|comeco|meio|motivo|esquentando|esfriando|"
        "estavel|sutil|forte|leve|nitidez|janela|imersivo|zerado|troca")
    # Termos que sao iguais nas duas linguas, ou nome de coisa. Ficam de fora
    # para o teste nao virar barulho -- teste barulhento se aprende a ignorar.
    seguras = {"audio", "video", "console", "forma", "error", "fim", "bytes"}
    alvo = re.compile(r"(?<![A-Za-z])(%s)(?![A-Za-z])" % palavras, re.I)
    acentos = set("\u00e1\u00e0\u00e2\u00e3\u00e9\u00ea\u00ed\u00f3\u00f4"
                  "\u00f5\u00fa\u00fc\u00e7\u00c1\u00c0\u00c2\u00c3\u00c9"
                  "\u00ca\u00cd\u00d3\u00d4\u00d5\u00da\u00c7")
    literal = re.compile(r'"(?:[^"\\\n]|\\.)*"')

    # Interpolacao e codigo, nao texto: "$motivo" e o nome de uma variavel, e
    # acusa-lo faria o teste cobrar traducao de identificador. Sai antes da
    # varredura.
    interpolacao = re.compile(r"\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*")

    def suspeitas(texto):
        texto = interpolacao.sub(" ", texto)
        achados = [m.group(0).lower() for m in alvo.finditer(texto)]
        achados = [a for a in achados if a not in seguras]
        if acentos & set(texto):
            achados.append("acento")
        return achados

    arquivos = (glob.glob(os.path.join(ROOT, "app/src/main/java/**/*.kt"),
                          recursive=True)
                + glob.glob(os.path.join(ROOT, "app/src/main/cpp/*.cpp")))
    achou = 0
    for f in arquivos:
        with open(f, encoding="utf-8") as fh:
            for n, linha in enumerate(fh, 1):
                t = linha.strip()
                if t.startswith("//") or t.startswith("*") or t.startswith("/*"):
                    continue
                for m in literal.finditer(linha):
                    if m.group(0) in IGNORADAS:
                        continue
                    achadas = suspeitas(m.group(0))
                    if achadas:
                        achou += 1
                        erro(f"{os.path.relpath(f, ROOT)}:{n} string em "
                             f"portugues {achadas}: {m.group(0)[:60]}")

    # Nos patches, so as linhas que nos adicionamos.
    for f in sorted(glob.glob(os.path.join(ROOT, "patches/*.patch"))):
        with open(f, encoding="utf-8", errors="replace") as fh:
            for n, linha in enumerate(fh, 1):
                if not linha.startswith("+") or linha.startswith("+++"):
                    continue
                t = linha[1:].strip()
                if t.startswith("//") or t.startswith("*") or t.startswith("/*"):
                    continue
                for m in literal.finditer(linha):
                    if m.group(0) in IGNORADAS:
                        continue
                    achadas = suspeitas(m.group(0))
                    if achadas:
                        achou += 1
                        erro(f"{os.path.basename(f)}:{n} string em "
                             f"portugues {achadas}: {m.group(0)[:60]}")
    if not achou:
        print(f"  {len(arquivos)} arquivos e os patches, nada em portugues")


# Nomes de arquivo e chaves de preferencia. Nao aparecem para ninguem, e
# renomea-los orfanaria o que ja esta gravado no aparelho.
IGNORADAS = {
    '"p5m-diario"', '"versao-do-diario"', '"p5m-trace-anterior.txt"',
    '"ultimo_crash_anterior.txt"', '"ultima_pilha_nativa_anterior.txt"',
    '"ensaio-preparo"', '"ensaio-saida"', '"ensaio-entrada"',
}


for etapa in (manifesto, strings_quebradas, interface_em_ingles, jni, ciclos,
              desempenho, raizes, patches):
    etapa()

print()
if falhas:
    print(f"{len(falhas)} problema(s). O build nao vale a pena.")
    sys.exit(1)
print("Tudo confere.")
