# O que é deles, o que é nosso, e por onde recomeçar

Documento de rumo, escrito em 2026-09-02. Não é opinião jurídica — nenhum de nós
é advogado, e como envolve dinheiro, uma leitura profissional é obrigatória antes
de vender qualquer coisa.

Ele existe por uma pergunta do dono: dá para publicar e vender um app assim?

## O veredito curto

O P5M, como está, **não é vendável por chave de acesso**. A base é AGPL-3.0
inteira — a `lib/` do chiaki, não só a interface — e o chiaki original de onde
o chiaki-ng deriva também é. Não existe a saída fácil de a biblioteca ser LGPL.

AGPL deixa cobrar. O que ela não deixa é impedir que o comprador redistribua: ele
recebe o código-fonte completo correspondente, inclusive o nosso, e pode publicar
tudo de graça. O modelo de chaves não morre num tribunal — morre no dia em que
um cliente for generoso.

Substituir "a parte do chiaki" significaria reimplementar o protocolo Remote Play
inteiro, e ainda por cima em clean-room, por alguém que nunca tenha lido a fonte
deles. Quem leu este repositório está impedido de fazer isso. **Não é o caminho.**

O caminho é outro, e a boa notícia é que ele não exige reimplementar nada:
**a parte valiosa do P5M não tem uma linha de chiaki dentro.**

## A conta

| | linhas |
|---|---|
| `lib/` do chiaki (o motor de protocolo) | 30.319 |
| — só o núcleo do protocolo (takion, ctrl, regist, holepunch, senkusha, receivers, fec) | 14.703 |
| app Android do chiaki | 6.742 |
| **nosso** | **9.853** |

Nove mil e oitocentas linhas nossas. É um app inteiro.

## O que é deles

Tudo em `external/chiaki-ng/`, e **os patches**. Um patch é modificação de código
AGPL: é obra derivada, e não vem junto.

Também é deles, na prática, qualquer decisão de formato ditada pela API deles —
os enums de codec, os perfis de vídeo, o formato do `ConnectInfo`.

## O que é nosso, e sai inteiro

Estes arquivos **não têm nenhuma dependência de compilação com o chiaki**. As
únicas ocorrências da palavra são em comentários. Verificado arquivo por arquivo.

### Camada nativa — 4.023 linhas, zero acoplamento

| arquivo | linhas | o que é |
|---|---|---|
| `xr_session.{h,cpp}` | 2.577 | a sessão OpenXR inteira: passthrough, camada cilíndrica, swapchain-Surface, frame loop, MQSR, taxa de atualização, níveis de desempenho, métricas |
| `tone_mapper.{h,cpp}` | 749 | PQ/BT.2020 → SDR e nitidez, em shader |
| `spatializer.{h,cpp}` | 335 | alto-falantes virtuais posicionados pela pose da tela |
| `p5m_jni.cpp` | 354 | ponte JNI |
| `log.h` | 8 | |

Esta é a parte difícil do projeto e é 100% original. Ela não sabe o que é um
PS5: recebe uma Surface e um fluxo de vídeo, e entrega isso dentro do compositor
com latência mínima. Serve para **qualquer** fonte.

### Kotlin sem acoplamento — 2.751 linhas

| arquivo | linhas | observação |
|---|---|---|
| `XrBridge.kt` | 292 | a fachada Kotlin da camada nativa |
| `DiagnosticActivity.kt` | 402 | a tela de diagnóstico |
| `Trace.kt` | 315 | o diário em arquivo, com aparo por sessão e limpeza por versão |
| `CrashCatcher.kt` | 149 | |
| `LogServer.kt` | 136 | servir o diário na rede local |
| `Rumble.kt` | 305 | vibração no gamepad, com forma de onda |
| `ScreenPrefs.kt` / `VideoFilter.kt` / `AudioRoute.kt` | 233 | |
| `SharpVideoView.kt` | 119 | |
| `WifiLowLatency.kt` | 71 | |
| `PsnLoginRelay.kt` | 293 | **o código é da PSN, a ideia não é**: publicar uma página na rede local para logar pelo celular resolve login dentro de headset em geral |
| `CaBundle.kt` | 436 | existe porque o curl da libchiaki nasce sem raízes; num projeto sem curl, não é preciso |

## O que é nosso mas nasceu amarrado

Estes são nossos, mas usam tipos do chiaki. Não se copiam: reescrevem-se contra
a fonte nova, que é trabalho pequeno porque a lógica é conhecida.

| arquivo | linhas | o que amarra |
|---|---|---|
| `VrStreamActivity.kt` | 1.309 | mora no pacote deles e orquestra a sessão deles |
| `PsnRemoteActivity.kt` | 494 | furação de NAT pela PSN |
| `LauncherActivity.kt` | 420 | abre a MainActivity deles |
| `PsnAuth.kt` | 321 | OAuth da PSN |
| `StreamQualityPrefs.kt` | 302 | `Codec`, `ConnectVideoProfile` |
| `DisplayMode.kt` | 122 | |
| `WindowVideo.kt` | 111 | `StreamSession` |

## O ponto que muda tudo

Este rumo **não reimplementa nada do chiaki**. Não há protocolo a copiar, não há
clean-room a montar, não há contaminação a contornar. Tira-se o que já é nosso e
alimenta-se com outra fonte de vídeo. O problema jurídico do documento anterior
simplesmente não aparece aqui.

## Os passos

### 0. Não desmontar o P5M

Ele continua AGPL, livre e publicável. É onde experimentar sai barato, e é o que
já funciona. Nada aqui pede que ele pare.

### 1. Repositório novo, limpo desde o primeiro commit

Não é fork, não é branch: repositório novo, sem submódulo, sem `patches/`,
licença própria escolhida antes do primeiro commit. Copiam-se só os arquivos das
duas listas "sem acoplamento", sempre do **nosso** lado da fronteira.

O teste que prova a separação, e que deve entrar no `conferir.py` do repo novo:

```bash
grep -ri chiaki .        # tem que voltar vazio
./gradlew assembleDebug  # tem que compilar sem o submódulo existir
```

Enquanto esses dois não passarem, a separação não aconteceu.

### 2. Escolher a fonte de vídeo

Sem PS5. Três caminhos, e o segundo é o que eu escolheria:

- **Remetente próprio no PC**: controle total, nada de terceiros, mais trabalho.
- **WebRTC**: padrão aberto, implementações sob licença permissiva (BSD),
  latência boa, ecossistema pronto e gente para contratar depois.
- **Fonte local**: vídeo, 180/360, arquivo. Vira um player XR — produto muito
  mais simples, e o único que dá para lançar sozinho em pouco tempo.

**Cuidado**: Moonlight e Sunshine são GPLv3. É a mesma armadilha com outro nome.

### 3. Provar o valor antes de escolher qualquer fonte

Um banco de ensaio: um arquivo local decodificado pelo MediaCodec entrando no
mesmo caminho de vídeo, e as linhas `Video 10s:` que já existem medindo o
resultado. Se os números baterem com os do P5M, **o produto existe
independentemente da fonte** — e essa é a única prova que interessa antes de
investir tempo em protocolo.

### 4. Decidir o que é o produto

Três respostas honestas, e nenhuma é errada:

- **App de usuário final**: player ou cliente de streaming em XR.
- **SDK para outros desenvolvedores**: a camada de vídeo de baixa latência em
  OpenXR é difícil e pouca gente fez bem. Vende-se para quem precisa dela e não
  quer aprender.
- **Portfólio**: não vender nada, e usar isto como prova de competência. Nove mil
  linhas de OpenXR, MediaCodec, shader de tons e áudio espacial, com diário de
  diagnóstico próprio, é uma credencial rara.

### 5. Só então: loja, preço, chaves

Nada disso importa antes do passo 3, e discutir preço antes de ter número é a
forma mais confiável de perder o interesse pelo projeto.

---

## Passo 3, feito: o banco de ensaio

Três arquivos novos, e o que importa deles é o que **não** têm dentro.

| arquivo | linhas | o que faz |
|---|---|---|
| `MedidorDeQuadros.kt` | 180 | a medição do `patches/0018` reescrita em Kotlin, sem chiaki |
| `FonteSintetica.kt` | 379 | gera 60 fps de vídeo dentro do aparelho e reproduz em laço |
| `BancoDeEnsaioActivity.kt` | 154 | sobe a sessão OpenXR, liga uma coisa na outra e mede |

Somados com a camada nativa que eles acionam, são o projeto novo rodando aqui
dentro. Nenhum `import` de `com.metallic.chiaki`. As três ocorrências da palavra
são comentários explicando justamente isso.

### Por que uma fonte gerada, e não um arquivo

Um arquivo exigiria o dono baixar alguma coisa para dentro do headset, e ainda
carregaria a cadência de quem o codificou. A fonte gerada não depende de nada e
é uma **régua**: os quadros nascem com cadência de relógio, calculada a partir do
início e não somando esperas, então um atraso numa entrega não empurra as
seguintes.

Codificar ao vivo estragaria a medição — gastaria CPU e GPU no instante exato que
se quer medir, e a medição criaria o defeito que procura. Então codifica-se uma
vez (dois segundos, alguns megabytes de unidades de acesso comprimidas) e
reproduz-se em laço. O padrão é uma barra varrendo a tela: dá trabalho ao
codificador e dá ao olho o que acompanhar, porque judder se julga olhando também.

### O que a leitura vai dizer

A linha sai igual à do caminho real, com outro rótulo:

```
Ensaio 10s: 601 quadros | entrega ... | ritmo ... | decodificacao ... | ...
```

E é a comparação entre as duas que responde a pergunta do passo 3:

- **Ensaio bom, sessão real ruim** → o caminho está provado. O que sobra é rede,
  e o produto novo não herda o problema.
- **Ensaio ruim** → o defeito é nosso, está aqui dentro, e nenhuma troca de fonte
  vai resolver. Melhor descobrir agora do que depois de escolher um protocolo.
- **Os dois bons** → o `Takion dropping data` é mais raro do que parecia, e a
  próxima medição é numa rede boa.

Nenhum desses três resultados é ruim de receber. O ruim seria continuar sem saber
qual deles é o verdadeiro.
