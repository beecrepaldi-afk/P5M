# P5M

> **Software livre, sob AGPL-3.0. Não é, e não será, um produto à venda** — e
> isso está decidido, não em aberto. Ver
> [`docs/O-QUE-E-NOSSO.md`](docs/O-QUE-E-NOSSO.md).
>
> Este é o projeto principal e recebe mudanças normalmente.

Cliente de PS5/PS4 Remote Play **nativo em VR** para o Meta Quest 3, construído
sobre a libchiaki do [chiaki-ng](https://github.com/streetpea/chiaki-ng).

Projeto pessoal, sideload. Não vai para a Meta Store.

## O que ele faz de diferente

O APK Android do chiaki-ng roda no Quest como painel 2D flutuante, desenhado
para celular. O P5M troca essa saída por uma **tela gigante composta pelo
próprio compositor do Horizon OS**, sobre passthrough.

O ganho não é estético, é de latência. O caminho do vídeo é:

```
PS5 → rede → MediaCodec → swapchain do compositor → olhos
```

Não existe textura intermediária, nem passo de GPU do aplicativo, nem cópia de
frame. Isso é possível porque duas peças se encaixam:

- `XR_KHR_android_surface_swapchain` faz o runtime OpenXR entregar um swapchain
  que **é** um `android.view.Surface`.
- O chiaki já aceita um `Surface` arbitrário via `Session.setSurface()`.

Então o decodificador de hardware escreve direto na camada de composição. De
quebra, a reprojeção do compositor passa a agir sobre a tela do jogo: se um
frame atrasa, a tela não treme junto com a cabeça.

A tela é uma camada de composição do próprio runtime: `XR_CompositionLayerQuad`
por padrão, ou `XR_KHR_composition_layer_cylinder` para quem quiser curvatura —
que sai de graça do compositor, sem geometria nossa. Plana é o padrão porque a
curva distorce o enquadramento do jogo; **L2** alterna em jogo.

**O app não desenha um único triângulo.** O contexto EGL existe só porque o
OpenXR exige um binding gráfico para abrir a sessão.

## O que o app extrai do Quest 3 e do PS5

Como não gastamos um ciclo de GPU próprio, sobra orçamento no compositor. É lá
que estão os ganhos, e cada um responde a um problema concreto:

| Recurso | Problema que resolve |
|---|---|
| **Meta Quest Super Resolution** (`XR_FB_composition_layer_settings`) | 1080p esticado num arco de 70° chega ao olho abaixo da densidade do painel. MQSR é upscale espacial de passo único rodando no compositor. É o maior ganho de nitidez do projeto. |
| **Rec.709** (`XR_FB_color_space`) | O padrão do Horizon OS é Display P3. O Remote Play entrega Rec.709. Sem corrigir, vermelho e verde saem estourados — parece "vivo", é cor errada. |
| **Painel a 120 Hz** (`XR_FB_display_refresh_rate`) | Fonte de 60 fps em painel de 90 Hz dá razão 1.5 e cadência irregular: judder visível em panorâmica, mesmo com rede perfeita. 120 Hz é múltiplo exato — cada frame do console ocupa dois frames do painel. O app escolhe sozinho o maior múltiplo inteiro disponível. |
| **HEVC a 25 Mbps** | A UI 2D do chiaki-ng é a do app de 2019 e oferece presets de celular. Montamos o perfil direto: 1080p60 HEVC, que é o teto do protocolo, no bitrate que um Wi-Fi 6E aguenta. |
| **`KEY_LOW_LATENCY` no MediaCodec** | O decodificador do chiaki só informava mime/largura/altura, deixando o codec livre para bufferizar em nome do throughput. Remote Play não tem B-frames: não há nada para reordenar e a espera é desperdício. Ver `patches/`. |
| **Passthrough ligado por padrão, com opacidade ajustável** (`XrPassthroughStyleFB`) | Jogar vendo o quarto é o modo preferido, não a exceção. A tela é opaca sobre o ambiente; o resto do campo de visão é o mundo real. Reduzir a opacidade escurece o quarto e faz a imagem saltar, quando se quer imersão. |

### O que foi avaliado e deixado de fora, e por quê

- **HDR (HEVC Main10)**: existe, está implementado, e vem **desligado**. O painel
  do Quest 3 é LCD sem HDR real, então não há brilho a ganhar; e o compositor não
  faz tone mapping do sinal PQ, o que costuma deixar a imagem lavada ou escura
  demais. O ganho honesto de 10 bits aqui é menos banding em céu e penumbra.
  Ligue em `p5m_vr_quality` se quiser experimentar.
- **`XR_META_local_dimming`**: o Quest 3 é LCD com backlight único. Local dimming
  é hardware de mini-LED, que ele não tem. A extensão não faria nada.
- **Resolução acima de 1080p60**: não existe. É o teto do protocolo de Remote
  Play, não uma limitação do cliente. Não há 4K a extrair do PS5 por aqui.
- **Supersampling da camada**: implementado e desligado. Numa fonte de resolução
  fixa, MQSR rende mais pelo mesmo custo.

## Como o projeto se relaciona com o chiaki-ng

O chiaki-ng entra como submódulo em `external/chiaki-ng` e o módulo Android
compila as fontes Kotlin dele direto do diretório do submódulo. Nossa
`VrStreamActivity` convive com a `StreamActivity` 2D deles — a 2D fica
compilada e inalcançável — e um patch de uma linha redireciona a `MainActivity`
do submódulo para a nossa.

Consequência prática: descoberta de console, pareamento, banco de consoles
registrados e preferências continuam sendo o código do chiaki-ng, testado e
mantido por gente que entende de Remote Play. Atualizar o upstream é trocar o
commit do submódulo e reaplicar dois patches pequenos.

A primeira tentativa foi diferente: excluir `stream/StreamActivity.kt` do source
set e pôr a nossa no mesmo pacote e nome, sem patch nenhum. Não funciona — esse
arquivo declara no topo o enum `TransformMode`, do qual o `AspectRatioFrameLayout`
depende, e o viewBinding gera código que referencia esse layout. Conviver custa
um patch de uma linha e é bem menos frágil.

## Estrutura

```
app/src/main/cpp/xr_session.{h,cpp}   sessão OpenXR, passthrough, camada de vídeo
app/src/main/cpp/p5m_jni.cpp       ponte JNI
app/src/main/java/io/github/gblandro/p5m/      XrBridge, ScreenPrefs
app/src/main/java/com/metallic/chiaki/stream/VrStreamActivity.kt
                                      activity imersiva
patches/                              dois patches idempotentes no submódulo
external/chiaki-ng/                   submódulo, intocado
.github/workflows/build-apk.yml       gera o APK
```

## Baixar o APK

Duas trilhas, e a diferença importa:

- **[Releases → `dev`](../../releases/tag/dev)** — build rolante, regerado a
  cada push. URL fixa, sempre o mais recente. Versão fica
  `0.1.0-dev.<número do build>`. É o que você usa no dia a dia.
- **[Releases versionados](../../releases)** — criados só quando existe uma tag
  no git. Ficam na lista para sempre, com o APK daquela versão exata anexado.
  É o que permite voltar a uma versão que funcionava.

O artefato do workflow **Build APK** também continua disponível, útil para pegar
o APK de um commit específico. O repositório é privado, então tudo exige estar
logado no GitHub.

### Cortar uma versão

```bash
git tag -a v0.2.0 -m "descrição do que mudou"
git push origin v0.2.0
```

O CI compila com `versionName` igual à tag (sem o `v`), publica o release e
anexa `p5m-0.2.0.apk`, com notas geradas dos commits desde a tag anterior.

Para mudar a versão base dos builds de desenvolvimento, edite
`p5mVersionName` em `gradle.properties`.

### Sobre o `versionCode`

É sempre o número do build do CI, tanto em tag quanto em branch. Como cresce
monotonicamente, você consegue ir e voltar entre uma versão marcada e um build
de desenvolvimento sem o Android recusar a instalação por downgrade. O número
semântico vive no `versionName`.

## Build

Você não precisa de Android Studio.

Para compilar localmente, se um dia quiser:

```bash
git clone --recurse-submodules <repo>
cd P5M
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Requer NDK 26.3.11579264 e CMake 3.22.1 instalados pelo SDK Manager.

## Instalar no Quest 3

Estando logado no GitHub pelo navegador do Quest, baixe o `p5m.apk` do
release `dev` e instale direto no headset. Não precisa de PC.

Pelo PC, se preferir:

1. Ative o Modo de Desenvolvedor na conta Meta e no app Horizon do celular.
2. Conecte o Quest por USB e autorize a depuração dentro do headset.
3. `adb install -r p5m.apk`

O app aparece na biblioteca em **Fontes desconhecidas**, como "P5M".

## Diagnóstico sem PC

O app instala um segundo ícone no lançador: **P5M Diagnóstico**. Ele mostra,
dentro do headset:

- o stack trace do último crash, se houve;
- o log do app, incluindo o do processo anterior — o que caiu;
- versão, modelo e versão do Android.

Três botões de saída, porque o volume varia muito:

- **Copiar crash** — só o stack trace, dezenas de linhas. Cabe em qualquer
  lugar e quase sempre é onde está a resposta.
- **Copiar tudo** — crash mais o log do processo, já filtrado pelas tags que
  importam. O log cru vem com milhares de linhas de driver gráfico.
- **Enviar arquivo** — compartilha como `.txt` por qualquer app do headset.
  Colar texto esbarra no limite de caracteres de uma issue do GitHub; anexar
  arquivo não esbarra em nada.

Ele é deliberadamente construído sem AppCompat, sem layout XML, sem viewBinding
e sem o tema do chiaki-ng, e tem entrada própria no lançador. O motivo é
simples: uma tela de diagnóstico que compartilha os modos de falha daquilo que
investiga não serve para nada. Se o painel principal quebrar, esta continua
abrindo.

Existe porque o Quest costuma estar longe do PC. Sem `adb`, uma falha na
inicialização é indistinguível de "abriu uma janela em branco".

## Usar

O painel 2D (o do chiaki-ng) faz o pareamento e lista os consoles. Ao iniciar o
stream, o app entra em modo imersivo.

### Modo de ajuste

## Profundidade de cor

O botão **Cor** no lançador alterna entre 8 e 10 bits por canal. **O padrão é 8
bits**, e a razão está medida abaixo.

No Remote Play, 10 bits não existem separados do HDR: o único jeito de pedir
HEVC Main10 ao PS5 é o perfil HDR, que traz curva PQ e gamut BT.2020 junto. O
Quest 3 é LCD com pico de 100 nits — o mesmo nível de referência do SDR —, então
não há brilho de HDR a ganhar, e entregar PQ cru deixaria a imagem lavada.

O que se ganha é profundidade: 1024 níveis por canal em vez de 256, que é o que
separa um céu limpo de um céu em faixas. O mapeamento de tons acontece dentro do
decodificador, em hardware, pela chave `color-transfer-request` (patch 0001) —
não no compositor, que não faz esse trabalho.

O gamut acompanha a profundidade. O perfil HDR traz BT.2020 junto, e o gamut
continua BT.2020 depois do mapeamento de tons — mapear tons trata de luminância,
não de primárias. Por isso o modo imersivo declara `XR_COLOR_SPACE_REC2020_FB`
com 10 bits e `REC709` com 8: declarar 709 sobre conteúdo 2020 estica as cores
para um gamut que não é o delas, e o resultado é imagem clara e lavada.

No modo janela quem faz essa conversão é o sistema, a partir dos metadados do
buffer — não há o que declarar.

### O que foi medido no Quest 3

O decodificador registra o formato que negocia. Com 10 bits ligados:

```
color-standard=6  color-transfer=6  color-range=2  color-format=0x7f000789
```

- `color-format=0x7f000789` é `COLOR_FormatYUVP010` — os 10 bits chegam, confirmado
- `color-standard=6` é BT.2020 — o gamut é o esperado
- `color-transfer=6` é **ST2084 (PQ)** — o pedido de mapeamento de tons **foi
  ignorado**

Ou seja: a imagem sai com curva PQ lida como se fosse SDR, e o sintoma é branco
alto em toda cena clara. Dez bits sem mapeamento troca um defeito visível
(faixas em gradiente) por outro pior, porque o segundo aparece o tempo todo e o
primeiro só em céu e penumbra. Por isso o padrão voltou a 8 bits.

### Caminho do vídeo

O botão **Vídeo** no lançador escolhe entre os dois caminhos, e a escolha é real:
nenhum domina o outro.

| | Direto (padrão) | Convertido |
|---|---|---|
| Percurso | rede → MediaCodec → compositor | rede → MediaCodec → SurfaceTexture → shader → compositor |
| Cópias | nenhuma | uma passada de GPU |
| 10 bits com cor correta | não | sim |
| Vale no modo janela | sim | não, só no imersivo |

No **direto**, o MediaCodec escreve no swapchain do próprio compositor e nada
mais toca na imagem. É a menor latência possível e a razão de ser do projeto —
mas entrega ao compositor exatamente o que o decodificador produziu, curva PQ
inclusive.

No **convertido**, o vídeo passa por uma `SurfaceTexture` e um shader que aplica
o EOTF do ST 2084, comprime as altas luzes com um Reinhard estendido (branco de
referência em 200 nits) e converte BT.2020 para BT.709. É o que o decodificador
deveria ter feito. Com 8 bits o shader só lineariza, sem mapear nada, então o
caminho serve às duas profundidades.

O shader escreve luz linear num swapchain sRGB, deixando a codificação final por
conta do GL. E a matriz da `SurfaceTexture` já carrega a orientação do buffer,
então aqui não há inversão vertical a corrigir — ao contrário do caminho direto.

No modo janela quem desenha é a `StreamActivity` do chiaki-ng, com `SurfaceView`
próprio: não há onde encaixar o shader. Convertido só existe no modo imersivo.

Nesse mesmo modo, os controles virtuais do chiaki-ng ficam desligados: eles vêm
ligados por padrão porque num celular são o único jeito de jogar, e aqui só
desenhariam botões sobre a imagem para um dedo que nunca vai encostar na tela. O
switch continua no overlay da própria `StreamActivity` para quem quiser ligar
durante a sessão.

## Como tirar o diagnóstico de dentro do headset

A tela de **Diagnóstico** tem três saídas, para três situações:

- **Copiar resumo** — dezenas de linhas, não centenas: versão, o que o runtime
  ofereceu, o que foi escolhido e todo erro. Resolve quase toda investigação e
  cabe em qualquer campo de texto.
- **Servir na rede** — sobe um servidor HTTP no próprio headset e mostra o
  endereço. Abrindo no celular ou no PC da mesma rede, o log aparece como texto,
  onde copiar e enviar funcionam normalmente. Existe porque o compartilhamento
  de arquivos do Horizon OS não entrega o `.txt` em lugar nenhum, e digitar log
  pelo teclado do headset é inviável. Fica de pé só enquanto a tela está aberta.
- **Copiar tudo** / **Enviar arquivo** — o despejo completo, quando o resumo não
  bastar.

## Latência: o que é feito além do caminho do vídeo

O caminho sem cópia é o ganho principal, mas não é o único:

- **Taxa do painel casada com a fonte.** Escolhe o maior múltiplo inteiro de
  60 Hz que o runtime ofereça. Com as taxas estendidas do Horizon OS v2.7 — que
  liberam qualquer inteiro de 72 a 207 Hz no Quest 3 — isso passa a significar
  **180 Hz** onde antes eram 120: cada frame do console ocupa exatamente três
  frames de painel, e a espera até a próxima apresentação cai de 8,3 ms para
  5,5 ms sem introduzir judder. 144 Hz seria pior apesar de mais alto: 144/60 é
  2,4, cadência irregular.
- **Thread de render declarada ao sistema** (`XR_KHR_android_thread_settings`).
  Sem isso a thread que submete os frames é escalonada como qualquer outra e
  pode perder a fatia de tempo bem na hora de entregar o frame — engasgo
  esporádico, o pior tipo de defeito de latência, porque não aparece em média
  nenhuma.
- **Wi-Fi em modo de baixa latência** (`WIFI_MODE_FULL_LOW_LATENCY`). Fora de
  uso intenso o rádio dorme entre pacotes e acorda nos beacons do roteador; um
  pacote que chega logo depois de ele dormir espera o próximo despertar. Existe
  desde o Android 10 e é pouco usado: a maioria dos apps de streaming ainda pede
  o `WIFI_MODE_FULL_HIGH_PERF`, que é anterior e não trata de latência.
- **Decodificador em modo de baixa latência** (`KEY_LOW_LATENCY`, `PRIORITY`
  realtime, `OPERATING_RATE` máximo) — ver patch 0001.
- **Chave privada da Qualcomm no decodificador**
  (`vendor.qti-ext-dec-low-latency.enable`). O Quest 3 usa um Snapdragon XR2
  Gen 2 e o decodificador HEVC é o `c2.qti.hevc.decoder` — confirmado no log do
  aparelho. Antes de o `KEY_LOW_LATENCY` padrão existir, a Qualcomm já expunha
  esse controle próprio, e em vários drivers é ele, e não a chave padrão, que
  efetivamente desliga o buffer de reordenação. As duas convivem: chave
  desconhecida é ignorada, então em outro aparelho não custa nada.
- **Filtro de camada escolhido pelo runtime** (`XR_META_automatic_layer_filter`).
  Depois do que o MQSR manual fez aqui, faz mais sentido inverter a decisão: o
  compositor sabe o tamanho aparente da camada, a densidade do painel e o que
  está em volta — informação que o app não tem.
- **Resolução recomendada da camada** (`XR_META_recommended_layer_resolution`).
  Não dá para mudar a resolução da fonte, que é o teto do protocolo, mas o
  runtime diz em que resolução a camada deveria estar para casar com a densidade
  do painel no tamanho em que ela aparece. Comparado aos 1920×1080 que chegam, é
  o único número objetivo sobre nitidez nesta cadeia — e vai para o log.
- **Zona morta radial nos analógicos**, que não é latência mas é precisão: o
  controle em repouso reportava até 7,4% de desvio.

## Modo de exibição

O botão **Modo** no lançador escolhe entre dois caminhos de vídeo diferentes, não
uma opção cosmética:

| | Janela | Imersivo |
|---|---|---|
| Composição | do Horizon OS | nossa, direto no compositor |
| Outros apps ao lado | sim | não |
| Formato | painel livre, vídeo 16:9 | tela curva ajustável |
| Ajuste em jogo (L3+R3) | não | sim |
| MQSR, Rec.709, 120 Hz casado | não | sim |
| Passthrough | do sistema | nosso, com opacidade ajustável |

Em janela o app é um painel comum, que o sistema posiciona, redimensiona e mostra
ao lado de navegador, Spotify e o que mais estiver aberto. O vídeo continua 16:9
dentro da janela — o `AspectRatioFrameLayout` do chiaki-ng o mantém proporcional,
então esticar o painel gera barra preta em vez de imagem distorcida.

Em imersivo o app compõe a própria camada, que é onde vivem a tela curva, o MQSR,
a fixação em Rec.709 e o casamento da taxa de atualização com os 60 fps da fonte.
Em troca, toma a tela inteira.

**Só no modo imersivo.** O acorde é tratado pela activity imersiva; no modo
janela quem roda é a `StreamActivity` do chiaki-ng, e não há o que ajustar lá —
posição e tamanho são do sistema, e o filtro de camada não existe.

Durante o jogo, **L3 + R3 juntos** ligam e desligam o modo de ajuste. Enquanto
ele está ligado, um painel flutuante mostra o que cada botão faz e o valor atual
de cada ajuste, e nenhum input chega ao console:

| Botão | Ação |
|---|---|
| D-pad ↑ ↓ | tamanho da tela (arco) |
| D-pad ← → | distância da tela |
| L1 / R1 | altura da tela |
| L2 | forma da tela: plana (padrão) ou curva |
| R2 | curvatura, quando a tela é curva: quase reta → sutil → média → máxima |
| Square | ciclar filtro: nenhum → sharpening |
| Cross | recentralizar a tela à frente |
| Triangle | ligar/desligar passthrough |
| Share / Options | desfazer a correção de espelhamento vertical |
| Circle | sair do modo de ajuste |

Os analógicos passam por uma zona morta radial de 10% com reescala: o DualSense
em repouso reporta até 7,4% de desvio, o que dentro do jogo é a câmera derivando
sozinha. A reescala mantém o curso útil inteiro.

### MQSR: testado e removido

Ligar o filtro de qualidade (`XR_COMPOSITION_LAYER_SETTINGS_QUALITY_SHARPENING_BIT_FB`)
no Quest 3 não alterou a nitidez e produziu um disco esbranquiçado que acompanha
o movimento da cabeça — **com a tela plana e com a curva**. Como o defeito
independe da forma da camada, não é caso de camada não suportada: o filtro
simplesmente não serve para uma camada de vídeo neste runtime.

Saiu do ciclo do botão Square, que agora alterna apenas nenhum e sharpening
simples (`NORMAL_SHARPENING`, que não apresentou o problema). O suporte do lado
nativo continua, atrás da constante `FILTER_SUPER_RESOLUTION`, caso um Horizon OS
futuro mude esse comportamento.

### Curvatura

Com a pose do cilindro na origem, o olho fica no centro e toda a superfície
equidista — é a curva mais fechada possível, e na prática abraça demais quem
assiste. O P5M separa curvatura de distância: o eixo do cilindro recua para
trás do olho, e o raio do arco passa a ser `distância / curvatura`. A tela
continua onde estava, com um arco mais aberto.

O arco encolhe junto (`centralAngle = 2 · curvatura · tan(ângulo/2)`), senão a
tela cresceria ao ser aplainada — o que se quer manter constante é a largura
aparente, não o ângulo do cilindro.

O acorde L3+R3 foi escolhido por ser raro em jogo, para não roubar comando.
Os ajustes ficam salvos entre sessões.

O painel fica ancorado no espaço, a 1,5 m e um pouco abaixo da linha dos olhos,
independente de onde a tela esteja: uma legenda que se move junto com o que ela
explica deixa de servir de referência. Só o yaw acompanha a recentragem, para
ele aparecer à frente de quem abriu o modo.

É uma segunda camada de composição, desenhada com `Canvas` do Android e
enviada para a textura de um swapchain comum. Desenhar texto pelo Android evita
escrever um renderizador de fontes em GLES para mostrar oito linhas.

## Controle

Qualquer gamepad HID que o Horizon OS enxergue chega como evento Android normal,
e o `StreamInput` do chiaki-ng já faz o mapeamento para o estado de controle do
PlayStation. Não reimplementamos nada disso.

**Controle genérico é o caminho recomendado, não o plano B.** O mapeamento do
chiaki é escrito contra os keycodes padrão de gamepad do Android — que é
exatamente o que um HID genérico emite. O DualSense é o caso menos padrão:
depende de o Horizon OS pareá-lo e mapeá-lo corretamente, sem suporte oficial da
Meta, com relatos da comunidade que se contradizem.

O transporte é invisível para o app: Bluetooth, dongle 2.4 GHz e cabo entram
todos como evento de entrada do Android. Nenhuma linha de código muda entre eles.

### Pareando o 8BitDo Ultimate 2 por Bluetooth

O controle de referência deste projeto. Modo **D-input** — é o que se apresenta
como HID genérico, exatamente o que o mapeamento do chiaki espera:

1. Chave de modo em **"D"**
2. **Home** para ligar
3. Segure o **Star** por 3 s até o LED piscar rápido
4. No Quest: Ajustes → Dispositivos → Bluetooth → parear **"8BitDo Ultimate Wireless"**
5. LED sólido = conectado

**Alternativa de menor latência:** o mesmo controle tem dongle 2.4 GHz. Ligado na
USB-C do Quest 3, vira periférico USB via OTG — o risco de pareamento Bluetooth
desaparece e a latência cai de ~8–15 ms para ~1 ms, da ordem de um frame a 60 fps.
Custo: a porta USB-C ocupada. O app não vê diferença entre os dois caminhos.

#### Se o botão PS não funcionar

O Star é o botão de função do 8BitDo, e o Horizon OS costuma interceptar botões
de sistema. No mapeamento do chiaki, `BUTTON_MODE` é o que vira botão PS — o que
você precisa para navegar na interface do PS5.

Se o Star for engolido pelo sistema, ou não emitir nada em modo D, o botão PS
fica inacessível. É o ponto mais provável de precisar de ajuste. Para
diagnosticar, veja a seção do logcat abaixo.

### Clique do touchpad

O chiaki-ng não mapeia o touchpad em gamepad nenhum: a constante existe, mas só
era alimentada pelos controles de toque na tela, que aqui não existem. Sem isso,
jogo que abre mapa ou inventário no touchpad fica sem esse comando.

O P5M aceita como clique de touchpad qualquer um destes:
`BUTTON_1`, `BUTTON_2`, `BUTTON_3`, `BUTTON_4`, `BUTTON_Z`. Nenhum é usado pelo
mapeamento do chiaki, então incluir todos não rouba comando de jogo. Controle
com paddles traseiros costuma emitir um deles, e os programáveis podem ser
configurados para qualquer um.

Não sabe o que o seu botão emite? Aperte-o com o stream rodando e leia o
logcat — toda tecla de gamepad sem mapeamento é registrada com o keycode e o
nome do dispositivo:

```
adb logcat -s P5MVR:I
```

### O que se perde com controle genérico

Giroscópio (mira fina em alguns jogos) e gatilhos adaptativos. Os gatilhos você
perde de qualquer forma: o protocolo de Remote Play não transmite força
adaptativa nem para o DualSense. Rumble o protocolo transmite, mas ainda não
está roteado para o controle.

## Limitações conhecidas do V1

- **PIN de login**: se o console pedir PIN, a activity imersiva encerra. Não há
  superfície 2D dentro dela para digitar. Contorne parear/entrar uma vez pelo
  painel antes.
- **Controles Touch do Quest**: ainda não mapeados. Só gamepad.
- **Rumble**: o evento chega do console mas não é roteado para o DualSense.
- **Áudio**: sai pelo caminho padrão do chiaki (Oboe), sem espacialização.
- **Bitrate e HDR** não têm UI: moram em SharedPreferences (`p5m_vr_quality`)
  e valem a partir do próximo início de stream. Só o filtro de camada é ao vivo.

## Sobre o submódulo e o diretório `patches/`

O submódulo fica intocado no git. As mudanças que só podem ser feitas por dentro
dele entram como patches idempotentes, aplicados em ordem numérica antes de
qualquer compilação — se já estiverem aplicados, a task não faz nada. São
dezesseis hoje, e `tools/conferir.py` verifica que todos aplicam, na ordem.

Quem for mexer neles deve ler o procedimento em [`AGENTS.md`](AGENTS.md): um
patch se aplica sobre o resultado dos anteriores, não sobre a árvore limpa, e
vários tocam o mesmo arquivo.

Os primeiros, para dar o tom:

- `0001-video-decoder-low-latency-and-callback.patch` — duas coisas no
  decodificador: as chaves de baixa latência do MediaCodec, e a **correção da
  assinatura do callback de vídeo**.
- `0002-main-activity-launch-vr.patch` — uma linha na `MainActivity` para o
  stream abrir na activity imersiva.
- `0003-qualify-coordinatorlayout-behaviors.patch` — qualifica os nomes de
  `app:layout_behavior` no layout principal. O `CoordinatorLayout` resolve nome
  que começa com `.` contra o **applicationId**, não contra o namespace do
  módulo; como o nosso applicationId é `io.github.gblandro.p5m` e as classes vivem em
  `com.metallic.chiaki`, o painel quebrava ao inflar.

### O app Android do chiaki-ng está quebrado no upstream

Vale registrar, porque muda o entendimento do projeto: **o app Android do
chiaki-ng não compila contra a própria libchiaki atual.**

O `ChiakiVideoSampleCallback` da lib ganhou dois parâmetros (`frames_lost` e
`frame_recovered`), e a camada JNI do Android nunca acompanhou:

```
chiaki-jni.c:350: incompatible function pointer types
  'bool (uint8_t*, size_t, void*)'
  → 'bool (*)(uint8_t*, size_t, int32_t, bool, void*)'
```

Ou seja: a parte Android do chiaki-ng está abandonada o suficiente para não
buildar. A libchiaki, essa sim, segue viva e mantida. Isso reforça a divisão que
este projeto faz — libchiaki como motor de protocolo, o app Android deles como
código legado que a gente corrige e sobrescreve.

## Licença

O código deste repositório segue a AGPL-3.0, mesma licença da libchiaki que ele
usa.
