# Passagem de bastão

> **Este é o projeto principal.** Livre, sob AGPL-3.0, e assim fica.
>
> A pergunta "dá para vender?" já foi feita, investigada a fundo e respondida:
> **não.** A base é AGPL inteira, o protocolo Remote Play é fechado, e
> reimplementá-lo exigiria clean-room feito por quem nunca leu o chiaki — o que
> exclui todo mundo que já trabalhou aqui. O inventário completo e o raciocínio
> estão em [`docs/O-QUE-E-NOSSO.md`](docs/O-QUE-E-NOSSO.md). **Não reabra essa
> discussão** a menos que o dono peça: ela custou uma sessão inteira e a resposta
> não mudou.
>
> Existe um repositório irmão, o Q3Stream, parado, com a camada de vídeo isolada
> e medida. O que ele descobriu já voltou para cá.

Este arquivo existe para uma IA que nunca viu este projeto conseguir trabalhar
nele sem redescobrir, uma a uma, coisas que já custaram caro. Leia inteiro antes
de tocar em qualquer arquivo. O `README.md` conta o que o app faz; o
`docs/ESTADO-DO-PROJETO.md` é o diário cronológico, longo, com o raciocínio de
cada decisão. Este aqui é o contrato.

## Quem usa e como

O dono do projeto joga com um Quest 3 e um DualSense, e:

**Ele não tem PC.** Isto não é um detalhe de conforto — é a restrição que dá
forma a tudo. Não há `adb`, não há logcat ao vivo, não há Android Studio, não há
depurador. Toda falha precisa ser diagnosticável **de dentro do headset**, e a
única ponte de volta é ele copiar um diário e colar aqui.

Consequências práticas, todas obrigatórias:

- **O diário é por versão.** Ele se apaga sozinho quando o APK instalado muda,
  porque o `filesDir` sobrevive à instalação por cima e o log atravessava
  versões — sintoma de uma build já foi lido como se fosse de outra. Ao pedir um
  diário, confira a linha da troca de versão no topo antes de concluir qualquer
  coisa sobre "a versão anterior": ela não está mais lá.
- **Nunca conclua com um log que só existe em `logcat`.** Se algo importa, tem
  que aparecer no diário do app (a activity `P5M Diagnóstico`) ou não existe.
- **Toda mensagem de erro tem que dizer o motivo, não o sintoma.** "Não achei o
  console" é inútil; "o token venceu e não deu para renovar" resolve.
- **Um caminho de código que pode falhar de três formas precisa de três
  mensagens distintas.** Já se perdeu uma noite porque uma função devolvia
  `null` para "token vencido", "servidor fora" e "conta sem console".
- Quando ele mandar um diário, leia-o de verdade — inclusive os tombstones. As
  respostas costumam estar nos registradores e no `Abort message`, não no
  resumo.
- **"Nenhuma exceção Java registrada" não quer dizer que o app não caiu.** Um
  sinal fatal mata o processo sem passar pelo handler. O motivo das quedas
  nativas é escrito no diário na abertura seguinte, como `Processo anterior
  (pid N) terminou em ...: QUEDA NATIVA`. Procure por essa linha antes de
  concluir que a sessão terminou bem.

Ele responde em português, escreve em português, e o código deste repositório é
comentado em português. Mantenha.

## O que não se negocia

Estes pontos foram escolhidos por ele, testados em hardware, e **não devem ser
trocados por conveniência de implementação**. Se algum atrapalhar, diga por quê
e proponha — não mude por conta própria.

| Requisito | Por quê |
|---|---|
| **Latência mínima acima de tudo** | É o projeto inteiro. O vídeo vai do MediaCodec direto ao swapchain do compositor, sem cópia e sem GPU do app. Qualquer coisa que insira uma passada tem de justificar-se. |
| **DualSense só por Bluetooth** | É como ele joga. Soluções que exijam USB estão fora. |
| **Passthrough é o modo padrão** | Jogar vendo o quarto é a preferência, não a exceção. |
| **120 Hz com fonte de 60 fps** | Múltiplo exato: cada quadro do console ocupa dois do painel. 90 Hz dá razão 1.5 e judder. |
| **Rec.709** (`XR_FB_color_space`) | O padrão do Horizon OS é Display P3 e estoura vermelho e verde. |
| **MQSR disponível** | É o maior ganho de nitidez do projeto. |
| **Tela plana por padrão**, curva sutil quando curva | Curvatura forte distorce o enquadramento do jogo. |
| **Painel de HUD fixo a 1,5 m** | Distância de leitura confortável, decidida em hardware. |
| **Janela com proporção e tamanho livres** | Modo janela é uma alternativa de primeira classe, não um resto. |
| **Releases versionados no git, com link direto para o APK** | Sem zip: o navegador do Quest baixa e instala direto. |
| **Toda a interface e o diário em inglês** | O beta é público, no Reddit. Comentário de código continua em português — é para nós dois, não para quem usa. O `tools/conferir.py` reprova string em português **por lista de palavras, e nos patches também**: a primeira versão procurava acento e passou sem testar nada, porque `Video 10s: %u quadros \| entrega ...` não tem um acento sequer. |
| **Nada de pessoal no que sai do aparelho** | Nem do dono do projeto, nem de quem testa. O diário é redigido na escrita por `Trace.redact`, e o repositório não carrega nome, e-mail nem nome de console. |

Ele **prefere melhorias modernas à fidelidade estrita ao chiaki**. Se o upstream
faz algo de um jeito antigo e há um jeito melhor no Horizon OS, use o melhor e
registre o porquê.

## Arquitetura em cinco frases

1. `external/chiaki-ng` é submódulo e **fica intocado no git**. Não é fork.
2. Mudanças dentro dele vivem em `patches/*.patch`, aplicadas em ordem numérica
   antes de qualquer compilação, de forma idempotente (`app/build.gradle`).
3. A libchiaki é o motor de protocolo, vivo e mantido; **o app Android do
   chiaki-ng é legado** — nem compila contra a própria lib deles — e nós o
   corrigimos e sobrescrevemos.
4. `app/src/main/cpp/xr_session.cpp` é a sessão OpenXR (passthrough, camada de
   vídeo, frame loop). O app **não desenha um triângulo**; o contexto EGL existe
   só porque o OpenXR exige um binding gráfico.
5. `app/src/main/java/io/github/gblandro/p5m/` é o código nosso; `com/metallic/chiaki/`
   é código do submódulo compilado a partir do diretório dele, mais a
   `VrStreamActivity`, que é nossa e mora nesse pacote por conveniência.

### Mapa

```
app/src/main/cpp/xr_session.{h,cpp}        sessão OpenXR, passthrough, camada de vídeo
app/src/main/cpp/p5m_jni.cpp            ponte JNI
app/src/main/cpp/tone_mapper.cpp           PQ/BT.2020 → SDR, para o caminho com shader
app/src/main/cpp/spatializer.cpp           alto-falantes virtuais
app/src/main/java/io/github/gblandro/p5m/           XrBridge, prefs, Rumble, PSN, diagnóstico
app/src/main/java/com/metallic/chiaki/stream/VrStreamActivity.kt
                                           activity imersiva (nossa)
patches/                                   mudanças no submódulo, em ordem
external/chiaki-ng/                        submódulo, intocado
tools/conferir.py                          conferências que rodam sem SDK
.github/workflows/build-apk.yml            gera o APK e publica o release
docs/ESTADO-DO-PROJETO.md                  diário cronológico com o raciocínio
```

## Como mexer nos patches (leia antes de tentar)

Um patch nunca se aplica contra a árvore limpa: ele se aplica **sobre o
resultado dos anteriores**. Vários patches tocam o mesmo arquivo
(`takion.c` aparece em 0006, 0008, 0009, 0013; `chiaki-jni.c` em 0009, 0010,
0016). Regenerar um patch "de cabeça" quase sempre produz um que não aplica.

Procedimento que funciona:

```bash
SUB=external/chiaki-ng
# 1. duas worktrees descartáveis no HEAD do submódulo
git -C $SUB worktree add --detach /tmp/base HEAD
git -C $SUB worktree add --detach /tmp/alvo HEAD
# 2. base = todos os patches ATÉ o anterior ao que você quer mexer
for p in patches/00{01..NN}-*.patch; do git -C /tmp/base apply "$p"; done
# 3. alvo = todos, inclusive o que você quer mexer
for p in patches/*.patch;            do git -C /tmp/alvo apply "$p"; done
# 4. edite os arquivos em /tmp/alvo
# 5. gere o patch, um diff por arquivo, com rótulos a/ e b/
diff -u --label a/<caminho> --label b/<caminho> /tmp/base/<caminho> /tmp/alvo/<caminho>
```

Depois **sempre**:

```bash
python3 tools/conferir.py
```

Ele aplica os patches um a um numa worktree descartável e acusa o primeiro que
recusar, pelo nome. Também confere assinaturas JNI, tabelas indexadas por ciclo,
o array de desempenho e o caminho do bundle de CA. **Não comite sem ele passar.**

## Build e distribuição

- Não existe build local aqui: o APK sai do GitHub Actions a cada push.
- **Todo push na branch de trabalho gera uma build.** Se o dono do projeto disser "não gere
  APK ainda", comite localmente e **não empurre** — mesmo que um hook peça.
- Release rolante `dev`: URL fixa, sempre o build mais recente
  (`releases/download/dev/p5m.apk`). Releases versionados só em tag `v*`.
- **A tag `dev` não acompanha o release rolante.** Ela ficou parada num commit
  antigo enquanto o APK era atualizado a cada push, então não serve para saber
  de onde veio um APK. Quem responde isso é o `versionCode`, que é o número da
  execução da CI. Não conclua nada sobre a origem de um APK pela tag `dev`.
- **Marcar uma tag `v*` é a única forma de ter um APK ao qual voltar.** Por
  muito tempo não houve nenhuma, e a consequência prática era que voltar a uma
  versão que funcionava exigia recompilar. Quando um estado for validado no
  hardware, marque-o.
- `versionCode` é o número do build do CI, monotônico, para alternar entre tag
  e `dev` sem esbarrar em downgrade.
- A **chave de depuração é cacheada** no Actions (`debug-keystore-v1`). Sem
  isso, cada APK tinha assinatura diferente, o Quest exigia desinstalar, e o
  login da PSN se perdia a cada iteração. A chave **não** é versionada.

## Fatos já pagos (não redescubra)

Cada linha aqui custou pelo menos uma iteração de hardware.

**Rede e PSN**

- Descoberta local é broadcast UDP; **não atravessa a internet**. Fora de casa,
  o único caminho é a furação de NAT pela PSN (`lib/src/remote/holepunch.c`),
  cuja ordem obrigatória está no topo de `lib/include/chiaki/remote/holepunch.h`.
- Com `ChiakiConnectInfo.holepunch_session` preenchido, a libchiaki **ignora o
  endereço** e re-registra pelo túnel usando só o id de conta de 8 bytes. Os
  caminhos de vídeo, áudio e nitidez não mudam nada.
- **A Sony apaga a senha da conta quando existe passkey.** A página de login não
  tem campo de senha, e o QR dela só serve para quem consegue apontar um celular
  para a tela — o que dentro de um headset não acontece. Por isso existe o
  `PsnLoginRelay`: o headset publica uma página na rede local, o celular faz o
  login, e só o código volta.
- **libcurl compilado para Android não tem raiz de confiança nenhuma**: o
  `CMakeLists.txt` do curl pula a autodetecção quando `CMAKE_CROSSCOMPILING`, e
  `CURL_CA_FALLBACK` vem desligado. `CURL_CA_BUNDLE` é **macro de compilação**,
  não variável de ambiente. Daí o `CaBundle.kt` e o `-DCURL_CA_BUNDLE` no
  `app/build.gradle` — e o `conferir.py` verifica que os dois apontam para o
  mesmo caminho.
- `*.np.communication.playstation.net` **manda só a folha do certificado**, sem
  o intermediário. O Android tolera; o OpenSSL não, e o curl nunca implementou
  busca por AIA. O intermediário é baixado na CI e embarcado como asset. Mesmo
  defeito do [chiaki-ng#798](https://github.com/streetpea/chiaki-ng/issues/798).
- `ECONNREFUSED` num socket UDP conectado é um ICMP **atrasado** de um datagrama
  anterior, entregue na leitura seguinte. Não é fatal: a próxima leitura
  funciona.

**Controle e vibração**

- **Gatilhos adaptativos, LEDs e háptica crua estão fora, e não por falta de
  trabalho**: os três exigem mandar um relatório HID de saída ao DualSense, e o
  Android não abre esse caminho a aplicativo nenhum. O console manda os efeitos
  (`primeiro pacote de tipo 11`); nós não temos onde entregar. Já foi conferido
  mais de uma vez — não reproponha.
- **Sensores do controle não precisam de SDL.** Giroscópio e acelerômetro saem
  pela `InputDevice.getSensorManager()` do Android desde a API 31, e o Quest 3
  é API 34. Trazer o SDL seria um segundo caminho de entrada pelo mesmo
  resultado.

- O Remote Play manda vibração de duas formas exclusivas, escolhidas pelo
  anúncio em `ControllerConnectionPayload` (`streamconnection.c:1129`):
  **DualShock 4** faz o console reduzir a háptica do jogo a dois motores e
  mandar pacote de tipo 7; **DualSense** manda a háptica crua como trilha de
  áudio PCM (`is_haptics`, em `takion.c`) mais os gatilhos adaptativos.
- **Háptica crua não tem como chegar ao controle num Quest.** O DualSense a
  recebe por relatório HID de saída pelo Bluetooth, e o Android não abre esse
  caminho a aplicativo nenhum — não há API. O mesmo vale para gatilhos
  adaptativos. É por isso que o padrão hoje é anunciar DualShock 4.
- A vibração vai para o **vibrador do gamepad**, nunca para o do aparelho: o
  aparelho aqui é um headset, e não tem vibrador. O código original do chiaki
  usava `getSystemService(VIBRATOR_SERVICE)`, o que num celular coincide.
- O console manda **intensidade agora**, não duração. Traduzir isso em disparo
  de duração fixa erra dos dois lados. Use forma de onda que se repete.

**Vídeo**

- **A nitidez do caminho com shader é CAS**, adaptativa ao contraste: o realce
  cai onde o contraste local já é alto, que é o que evita halo em legenda e
  mira. Mesmos cinco toques de textura da máscara de desfoque que ela
  substituiu. `SHARPEN_AMOUNT` agora é a dureza do CAS entre 0 e 1, e não mais
  um ganho sem teto.
- **Shader que não compila não quebra o build** — ele é compilado no aparelho.
  A falha aparece no diário como `Falha ao compilar shader:` com o log do
  driver. Se a imagem sumir depois de mexer no `tone_mapper.cpp`, é a primeira
  linha a procurar.
- **A linha de base do caminho de vídeo, medida sem rede e sem console**, é
  `entrega 14,6/16,7/18,9 ms`, `decodificação 3,6/4,4/6,8 ms`, 600 de 600 no
  ritmo. Uma sessão real que fique longe disso tem a diferença explicada pela
  rede e pelo console — o que está entre o decodificador e o compositor já foi
  medido sozinho.
- **Um quadro "adiantado" só existe se o anterior atrasou.** É a fila se
  recompondo depois de um bloqueio, e por isso adiantados e atrasados aparecem
  em pares. Se eles voltarem, procure algo travando uma thread; não procure no
  decodificador.
- **Nada de trabalho bloqueante na thread principal durante uma sessão.** O tick
  do diário executava `logcat -d` a cada dez segundos e custava 39,9 ms de pico
  de entrega e 24,7 ms de decodificação. Roda em `HandlerThread` própria.
- `external/.../cpp/video-decoder.c` tinha seis defeitos de ciclo de vida
  (janela não inicializada, janela não solta, `ANativeWindow_fromSurface`
  devolvendo NULL sem ninguém conferir, `release` sobre NULL, `kill_decoder`
  chamado com o mutex na mão, e `AMediaCodec_delete` fora do mutex). Estão
  consertados em `patches/0014`. Se aparecer crash em `ANativeWindow_*` ou
  `incStrong() ... strong refs = 0`, comece por lá.
- **Antes do `xrEndSession`, ninguém pode estar escrevendo na Surface** do
  swapchain (`XR_KHR_android_surface_swapchain`). Quem escreve é a thread de
  saída do MediaCodec. A saída **não** é `setSurface(null)` — isso mata o codec
  com a thread de vídeo entregando quadros. É o portão do `patches/0017`:
  `Session.setVideoRenderToSurface(false)`, que fecha a escrita sob um mutex e
  só retorna quando nenhuma entrega está em curso, sem tocar no codec.
- **Ordem ao encerrar**: `stop()` e depois `dispose()`. Nunca `setSurface(null)`
  antes: o `dispose` já derruba o decodificador depois de dar `join` na sessão,
  e antecipar isso à mão mata o decodificador com a thread de vídeo ainda
  entregando quadros.
- No modo janela **não existe onde declarar o gamut**. Com 10 bits ligados a
  imagem precisa passar pelo shader, sempre — o decodificador entrega PQ e
  BT.2020, e uma superfície comum do Android é tratada como sRGB.
- O decodificador do Quest **ignora o pedido de mapeamento de tons** e entrega
  PQ de todo jeito (`color-transfer=6`). Quem mapeia é o nosso shader.

## Combinados de trabalho

- **Escreva o porquê, não o quê.** Os comentários deste repositório explicam a
  razão de uma decisão e o defeito que ela evita. Um comentário que repete a
  linha de código abaixo dele não passa na revisão do dono.
- Mensagens de commit em português, no imperativo, explicando a causa. Sem
  identificar modelo de IA em nada que vá para o repositório.
- **Segurança**, e isto é rígido: nunca peça nem aceite token, chave, senha ou
  JSON de conta de serviço colado no chat. Se ele colar um, avise na hora e
  mande revogar. Credenciais vivem em `.env`, fora de qualquer repositório.
  Nada de segredo no repo, nem em briefing (use `SEU_TOKEN_AQUI`).
- O `chiaki-settings.json` exportado contém `rp_regist_key` e `rp_key`, que são
  as credenciais de registro do PS5. **Não** transporte esse arquivo por issue,
  repositório ou pasta compartilhada.
- **String de uma linha nunca pode ter quebra de linha dentro das aspas.** O
  Kotlin recusa, e o erro que ele devolve aponta a linha SEGUINTE — então se
  procura no lugar errado. Isso segurou oito builds seguidas por duas linhas de
  texto de ajuda. O `conferir.py` agora acusa, pela indentação: linha que começa
  com aspas na coluna zero.
- **Confira o resultado da build antes de dizer que ela rodou.** O release
  rolante `dev` só é atualizado por build que passa; se o APK lá continua com a
  versão antiga, a build falhou. Um push aceito não é uma build verde.
- Antes de dizer que algo funciona, mostre a linha do diário que prova. Este
  projeto já teve várias hipóteses plausíveis que o código desmentiu — inclusive
  minhas. Confira no código antes de afirmar.

## Onde o trabalho vive

Tudo acontece na branch **`claude/quest3-chiaki-app-pntqrn`**. Não há `main`
neste repositório: essa branch é o tronco, e é dela que a CI publica o release
rolante `dev`. Continue nela, a menos que o dono diga o contrário.

## O que acabou de mudar e ainda não foi testado em hardware

Estas três coisas estão comitadas e vão sair na próxima build, mas **ninguém
ainda pôs o óculos com elas**. Se o comportamento parecer estranho, comece por
aqui antes de procurar longe:

1. **Escolha clássica/háptica na vibração**, com padrão na clássica — o cliente
   passa a se anunciar como DualShock 4 e a vibração vem já reduzida pelo
   console. Muda o que o console manda, então também muda quais pacotes
   aparecem no diário: espere tipo 7 com frequência e **nenhum** tipo 11
   (gatilhos adaptativos) nem trilha de háptica.
2. **A vibração voltou ao disparo curto, agora rearmado por relógio próprio.**
   A forma de onda que se repete saiu: é a parte da API que os drivers HID
   genéricos menos implementam, e um driver que não a implementa não devolve
   erro — simplesmente não vibra. Agora é `createOneShot` de 200 ms, reemitido a
   cada 150 ms enquanto o jogo pedir. Se o motor ficar preso ligado, o suspeito
   é um caminho de saída que não chama `Rumble.stop()`.
3. **Dez bits no modo janela agora forçam o shader.** Quem usava 10 bits na
   janela sem conversão e sem nitidez pagava cor errada e passou a pagar uma
   passada de GPU. Se a janela ficar mais pesada do que era, é isto.
4. **Portão de escrita na Surface, fechado no `onPause`.** O decodificador
   deixa de entregar quadros ao swapchain antes de a sessão OpenXR terminar.
   Espere a linha `P5M: escrita na Surface fechada` no diário, logo antes do
   `STOPPING`. Se a imagem congelar ao voltar de uma pausa, o suspeito é o
   `onResume` não ter reaberto o portão.
5. **O diário se zera sozinho quando o `versionCode` muda.** Primeira linha do
   diário de uma build nova: `=== diario zerado na troca de versao: ... ===`.
   Se um diário parecer curto demais, é isto — e não uma sessão que não gravou.
   O diário e o crash da versão que saiu **não são apagados**: viram
   `p5m-trace-anterior.txt` e `ultimo_crash_anterior.txt`, e o botão
   **Versão anterior** no diagnóstico os copia. É o caminho para quando algo
   quebra, a saída é voltar de APK, e a prova está na versão que acabou de sair.
6. **Duas linhas de medição a cada dez segundos**, `Video 10s:` e `Audio 10s:`.
   São medição, não conserto: existem para responder se o judder e o estouro de
   áudio têm a mesma causa. Se sumirem do diário, o caminho parou — janela
   silenciosa também imprime.
7. **Banco de ensaio**, no botão do lançador. Roda o caminho de vídeo com uma
   fonte gerada no próprio aparelho — sem console, sem rede. A linha é
   `Ensaio 10s:`, mesmo formato da `Video 10s:`. Se as duas discordarem muito,
   a diferença é rede. Ver `docs/O-QUE-E-NOSSO.md`.
8. **`StopFrameLoop` espera o `STOPPING` por até 200 ms.** Se aparecer
   `o STOPPING nao chegou`, o `xrEndSession` não aconteceu e a sessão foi
   destruída rodando — era o que acontecia sempre que o agendamento não
   ajudasse.
9. **A vibração varre todos os gamepads, e revarre.** A versão anterior
   perguntava só ao controle de onde vêm os analógicos e aceitava a primeira
   resposta como final — num Quest isso erra duas vezes, porque há sempre três
   gamepads presentes e o controle do jogador pode acordar depois da abertura do
   stream. Agora cada gamepad presente aparece no diário com o que respondeu
   (`Rumble scan: '<nome>' (id=N) — ...`), e um evento que chega sem destino
   dispara nova varredura. Os Touch do headset entram no diário e nunca viram
   destino. **Se o 8BitDo continuar dizendo `no motor exposed to Android`, a
   resposta está fechada: o driver não entrega, e não há o que fazer deste
   lado.**
10. **Interface e diário em inglês, do lançador ao HUD e ao painel de ajuste.**
   Se alguma linha do diário parecer não existir mais, provavelmente só mudou de
   texto — o filtro do "Copy summary" (`SUMMARY_PATTERNS`) foi atualizado junto,
   e é o primeiro lugar a conferir se o resumo sair vazio.
11. **`Trace.redact` na escrita do diário e do crash.** Token, chave de
   registro, `duid`, id de conta, e-mail, IP público e IPv6/MAC saem trocados
   por `<redacted>`/`<ip>`/`<addr>`. Endereço de rede local **fica** — é o que
   torna um problema de rede diagnosticável. Se um valor que você precisa ver
   sumir do diário, é aqui.
12. **Calibração dos analógicos, no botão `Stick calibration` do lançador.**
   A zona morta fixa de 10% saiu. Agora ela é medida por controle: com o
   controle largado, cinco segundos de amostragem dão o ponto de repouso de
   cada analógico e o maior desvio em torno dele, e a zona morta sai daí com
   folga. Guarda **centro e raio por analógico** — um que descansa 8% para o
   lado não precisa de 16% de zona morta, precisa de centro corrigido e 3%.
   Duas coisas a saber ao investigar:
   - **Analógico parado não gera evento.** Silêncio total na medição é resposta
     separada de "medi e deu zero", e a tela diz isso: mantém o valor que
     havia. Se ela disser `no reading`, o analógico não falou — o que pode ser
     um controle perfeito ou um que parou de reportar.
   - **A aplicação mudou de lugar.** Saiu da activity imersiva e foi para o
     `StreamInput` (patch 0019), que é o único ponto por onde os dois modos
     passam — o modo janela não tinha zona morta nenhuma. Se aparecer zona
     morta dobrada (analógico curto demais), o suspeito é alguém ter
     reintroduzido o `applyDeadzone` na activity.
13. **A vibração escolhe a forma do comando pelo que o driver sabe fazer.**
   Com controle de amplitude (DualSense): onda que se repete, lisa, sem
   relógio. Sem controle de amplitude (pad HID simples): disparo curto rearmado
   a cada 150 ms. A versão que usava o disparo rearmado para todo mundo foi
   paga em hardware -- no DualSense, um `createOneShot` novo a cada 150 ms
   reinicia o efeito e o motor pulsa quase sete vezes por segundo. **E a troca
   tinha sido feita pelo 8BitDo, que a varredura depois mostrou não expor motor
   nenhum: não consertou nada e estragou o controle que funcionava.** A buzina
   de teste é sempre disparo único; sustentada, ela giraria o motor até o fim
   da sessão.

14. **Submissão por olho, sem botão no lançador.** A mesma swapchain entra
   duas vezes no `xrEndFrame`, cada vez com metade da imagem e um
   `eyeVisibility`. Nenhuma cópia, nenhuma passada de GPU, nenhuma latência --
   só uma camada a mais na lista.
   - **O botão saiu porque não há conteúdo.** Isto nasceu para vídeo que já vem
     estéreo, e o Remote Play da Sony **recusa abrir app de mídia**: YouTube,
     Netflix e afins não sobem no stream, só jogo. Não existe imagem que chegue
     com dois olhos por este caminho, e uma opção que não faz nada em nenhum
     conteúdo alcançável é pior que nenhuma opção. **Consequência maior: para
     3D neste app só resta sintetizar a profundidade.**
   - O mecanismo fica porque é o que o olho sintetizado vai usar: ele escreve
     os dois olhos lado a lado na mesma textura e liga `stereoMode` por dentro.
   - **Num headset não existe estéreo alternado no tempo.** As duas vistas são
     mostradas no mesmo instante e o `eyeVisibility` escolhe quem vê o quê.
     Alternar olho a cada quadro de painel é técnica de TV com óculos
     obturador, onde os dois olhos dividem a mesma tela; aqui cortaria a taxa
     por olho pela metade em troca de nada.
   - As cópias `cylinder_right`/`quad_right` vivem fora do bloco de propósito:
     o `xrEndFrame` lê as estruturas depois, e uma cópia local num escopo que
     fecha viraria ponteiro morto na hora exata da leitura.

15. **3D emulado, no botão `3D (emulated)` do lançador.** O console manda
   imagem plana; o app estima profundidade dela e constrói o segundo olho.
   **É palpite, e a tela diz isso** -- o objetivo é volume, não medida.
   - **A estimativa** são três pistas somadas no shader, com pesos fixos:
     altura no quadro (o chão fica embaixo e se afasta subindo -- a mais forte
     das três), detalhe local (perto tem textura, longe tem neblina) e
     saturação (perspectiva aérea lava a cor do que está longe). As amostras
     são **espalhadas de propósito**: mapa de profundidade com borda dura
     produz halo em volta de tudo, e borrado o erro vira curvatura suave.
   - **A deformação** é amostragem inversa, meia disparidade para cada olho, e
     escreve os dois lado a lado. **O alvo tem o dobro da largura**, senão cada
     olho ficaria com metade da resolução horizontal.
   - **O teto é físico, não de gosto.** Disparidade maior que a distância
     interpupilar obriga os olhos a divergir, o que é impossível. O limite sai
     da IPD lida do runtime **e do tamanho angular da tela**, e vale metade
     dele -- a divergência incomoda antes de ser impossível. Por isso "força
     100%" não promete quanto salta.
   - **Liga o caminho com shader por dependência**, e é decidido antes da
     criação do swapchain: ligar depois não teria efeito na sessão.
   - `Options` no painel de ajuste (L3+R3) muda a força em jogo. Ele fazia a
     mesma coisa que o `Share`; separá-los deu um botão sem tirar nada.
   - **O que ainda vai estar errado**: HUD, mira e legenda estão no plano da
     tela, e a estimativa vai dar profundidade arbitrária a eles. É o artefato
     mais cansativo e aparece no que se olha o tempo todo. Jogo 2D e de câmera
     fixa quebram a pista do chão. Se a cena vier com a profundidade
     **invertida**, o suspeito é o `uGroundSign`, que vem do `vertical_flip_`.

16. **A profundidade tem passada própria, a um quarto da resolução.** Ela era
   calculada por pixel dentro da passada principal -- que no 3D tem o dobro dos
   pixels --, então nove amostras rodavam em 3840x1080. Num alvo pequeno o mesmo
   trabalho custa um dezesseis avos, e a leitura de volta com filtragem linear
   dá de graça o borrão que o mapa precisa ter. **É o lugar onde uma rede
   neural entraria**, se um dia entrar: ela preenche este alvo e nada mais muda.
   - Duas texturas em rodízio: a passada lê a anterior e escreve na outra. Ler
     e escrever a mesma é indefinido em GL. R guarda a profundidade suavizada,
     G a luminância do quadro -- é ela que diz, no quadro seguinte, o que ficou
     parado.
   - **Texto e HUD vão para o plano da tela.** A pista de detalhe empurrava
     para perto o que tem alta frequência, e texto é a coisa de maior
     frequência num quadro -- foi o "texto fica estranho" do primeiro teste.
     Agora contraste de escala fina (um texel da fonte, medido na resolução da
     fonte e não na do alvo pequeno) **mais** imobilidade entre quadros
     identificam interface. As duas juntas: textura fina também tem borda dura,
     e céu também fica parado.
   - **Suavização temporal com passo adaptativo.** Cena parada suaviza forte;
     corte de cena afrouxa, senão o mapa arrastaria o enquadramento antigo.
   - **MQSR e automático não valem no 3D.** A camada entra duas vezes pegando
     metade de uma textura, e o filtro do compositor sobre meia textura produziu
     um X discreto na tela em hardware. O realce cai para o shader -- e como a
     tabela de intensidades tem zero nessas duas posições, há um desvio
     explícito, senão a nitidez sumiria em silêncio.
   - **O térmico é o número a vigiar, não o fps.** Com 3D, cinco minutos levaram
     de 0.63 a 0.79 (sem 3D a sessão inteira ficava em 0.41), `app` foi de 2.2 a
     3.2 ms e os quadros descartados pelo compositor de 14 para 47. Nada disso
     aparece como engasgo ainda, e é justamente por isso que precisa estar
     escrito.

17. **`tools/compilar_nativo.py` confere o C++ antes do push.** Compila os
   cinco arquivos com `g++ -fsyntax-only` contra os cabeçalhos **de verdade**
   do Khronos (GL, EGL, OpenXR, baixados e em cache) mais imitações só de JNI e
   Android. Roda em segundos, local e no CI, antes do NDK -- lá o mesmo erro
   levaria quinze minutos para aparecer, e sem arquivo nem linha.
   - **Por que os cabeçalhos têm de ser de verdade.** A primeira versão gerava
     os do GL a partir do próprio código: toda `GL_*` encontrada virava
     `#define`, toda `gl*` um template que aceitava qualquer coisa. Ela passava
     num `glTexParameteri(..., GL_MIN_FILTER, ...)` -- que não existe, o certo é
     `GL_TEXTURE_MIN_FILTER` -- porque **inventou a constante errada**. Um teste
     que fabrica o que deveria conferir não é fraco, é ao contrário. Com os
     reais, pega também número e tipo de argumento.
   - **O log do CI é inalcançável daqui**, e é por isso que esta ferramenta
     existe: o download do log redireciona para um servidor de blobs que a rede
     não alcança, e as anotações do check pedem uma permissão que o token não
     tem. Sem ela, um erro de compilação só aparece como "Build debug APK
     failed", sem arquivo nem linha.

18. **O lançador foi redesenhado, e as regras dele valem para o que vier.**
   Ele tinha virado quatorze botões de largura inteira, idênticos, cada um com
   um parágrafo cinza embaixo -- "Abrir P5M" com o mesmo peso visual que
   "profundidade da tela no 3D". Três regras consertaram, e **ajuste novo entra
   por elas, não como mais um botão no fim**:
   - **Ação não é ajuste.** Jogar é um bloco colorido no topo; o resto é lista.
   - **Ajuste é linha**, com rótulo à esquerda e valor à direita, agrupado em
     seções (Picture / 3D / Controller and sound / Tools). Valor desligado sai
     apagado, ativo sai em azul -- é o que deixa a lista legível de relance.
   - **Uma explicação só, no rodapé, sobre o que se acabou de tocar.** Oito
     parágrafos simultâneos eram ruído: texto de ajuda só interessa sobre o que
     se está mexendo.
   - Controle que depende de outro **some** quando o outro está desligado (força
     e convergência sem o 3D). Dependência mostrada custa menos que explicada.
   - Cada linha registra em `atualizacoes` como se redesenhar, e um toque
     redesenha tudo. Antes cada botão tinha um `lateinit` e atualizava os
     vizinhos na mão -- foi assim que o painel passou a mostrar valor velho
     quando um ajuste mexia noutro.
   - Continua sem AppCompat, sem XML e sem biblioteca: a tela é o teste de que
     o processo sobe.
   - **Diagnóstico e calibração ainda não seguem estas regras.**

19. **O ícone é `ic_p5m`, e o nome não é acidente.** Os recursos do
   submódulo são fundidos com os nossos (`res.srcDirs` tem os dois), e recurso
   de nome repetido é **erro de fusão** -- por isso o ícone novo não pode se
   chamar `ic_launcher`, que é o do chiaki. O `android:roundIcon` foi removido:
   o logo é quadrado com fundo próprio, e uma máscara redonda cortaria o "PSM".

20. **`LICENSE`, `NOTICE.md` e o README público existem porque a AGPL obriga.**
   Distribuir o APK obriga a oferecer a fonte correspondente, com os mesmos
   direitos. O README antigo, técnico e em português, virou
   `docs/COMO-FUNCIONA.md`; o novo é para quem vai instalar, em inglês, e
   credita o chiaki-ng na primeira tela de texto.

21. **O acorde L3+R3 agora se anuncia sozinho.** Ele era invisível: nada na
   tela imersiva sugeria que existisse, e quem instalasse o app não tinha onde
   descobrir. Agora aparece um lembrete de sete segundos logo depois que a
   camada de vídeo é liberada (`showStartHint`, que reaproveita a textura do
   painel sem ligar o `adjustMode`, então o console continua recebendo tudo),
   um cartão fixo no lançador quando o modo é imersivo, e uma seção própria no
   README, fora da lista de recursos.

22. **`Report a problem` é o caminho de um toque para receber log.** Dentro do
   óculos não há cabo nem `adb`, e a lista de alvos de compartilhamento do
   Horizon OS costuma estar vazia, então "manda o arquivo" nunca foi um pedido
   razoável. O que sempre existe é navegador e área de transferência: o resumo
   vai para o clipboard, o servidor local sobe, e o navegador abre a página de
   nova issue com corpo pronto. O corpo na URL é truncado em 4000 caracteres
   porque o limite prático é do caminho, não do GitHub; o texto inteiro fica no
   clipboard. `copySummary` passou a reusar `buildReport`, para os dois não
   divergirem.

23. **O clique do touchpad não chega como tecla de gamepad.** O driver do
   kernel expõe o touchpad do DualSense como **dispositivo separado**, e o
   clique físico sai como botão primário de mouse nesse outro dispositivo. Era
   por isso que `TOUCHPAD_KEYCODES` nunca pegava nada, e era por isso que o
   diário não mostrava nem sinal: `logInputDevices` só contava quem se declara
   gamepad, e o touchpad não se declara. Agora `handlePointer` escuta as três
   portas (`dispatchGenericMotionEvent`, `dispatchTouchEvent`, botões de
   ponteiro), o diário anota cada fonte nova uma vez, e o painel de ajuste
   ganhou o clique no botão PS como saída de emergência. **Confirmado em hardware
   no log de 03/09**: o DualSense manda o clique como `source=0x2002`
   (`SOURCE_MOUSE`), pelo mesmo `deviceId` do gamepad, e o `Touchpad click:
   down/up` aparece no diário. Outro controle pode mandar por outra porta;
   por isso as três continuam escutando.

24. **O acorde do painel virou preferência, e o padrão mudou para L3+R3+R1.**
   Não existe combinação que jogo nenhum use; L3+R3 sozinho é usado, e o painel
   abria no meio de uma ação. As três opções são `L3+R3`, `L3+R3+R1` e segurar
   `L3+R3` por 700 ms. Os botões do acorde continuam chegando ao jogo quando o
   acorde não fecha -- R1 é botão de tiro, e engolir R1 seria pior que não ter
   atalho. `releaseChordButtons` passou a soltar R1 também.

25. **Dois streams podiam abrir ao mesmo tempo, e era isso o "Remote is already
   in use".** No log de 03/09 duas sessões abriram com cinco segundos de
   diferença sem a primeira ter sido fechada; o console recusou a segunda, e
   quem estava usando a sessão recusada era o próprio app. Logo depois a
   Surface da primeira morria embaixo da segunda (`ANativeWindow_fromSurface()
   devolveu NULL`) e a tela ficava preta. `DisplayMode.startStream` agora
   recusa a segunda abertura, e a contagem vive num
   `ActivityLifecycleCallbacks` registrado no `P5MApp` -- por fora, porque uma
   das duas telas de stream é do chiaki-ng e assim não precisa de patch.

26. **O ponteiro do controle atrapalhava o modo janela inteiro.** O mesmo
   dispositivo de ponteiro que entrega o clique do touchpad entrega também o
   deslizar do dedo. No modo janela isso acordava o cursor do sistema por cima
   do jogo e chegava aos controles de toque do chiaki-ng como dedo na tela: uma
   passada no touchpad e todos os botões passavam a responder errado, porque o
   `touchControllerState` estava sendo escrito por quem não devia.
   `TouchpadPointer` (nosso, compartilhado pelos dois modos) engole tudo que vem
   do ponteiro **do próprio controle** e traduz só o clique. O ponteiro do
   headset continua passando -- o raio da mão também chega como mouse, e
   engoli-lo tiraria o único jeito de tocar na interface do modo janela. A
   separação é por dispositivo, não por fonte: só entra quem também se declara
   gamepad. O modo janela recebe isso pelo patch `0020`.

27. **A imagem no modo janela voltou, mas pelo caminho direto.** O log de
   03/09 03:18 mostra `Window without shader` -- nitidez em zero e 10 bits
   desligado. Ou seja, o `glDrawBuffers` explícito do build 128 **ainda não foi
   testado**: o caminho com shader na janela continua sem confirmação.

## Onde as coisas estão paradas

Aberto, em ordem de quanto incomoda:

1. **Quedas nativas com `pc = 0` numa thread da libchiaki.** Sete até agora,
   sendo **duas na dev.108, em sessão real, com a vibração em `clássica`** — o
   que **descarta** a hipótese da trilha de háptica, que era a favorita. `pc = 0`
   não é leitura de ponteiro nulo: é **chamada a ponteiro de função nulo**.
   Sempre três quadros dentro de `libchiaki-jni.so` sobre `__pthread_start`, com
   espaçamento constante entre eles — o mesmo caminho de código todas as vezes.
   Uma delas veio meio segundo depois de abrir o stream imersivo; outra, sete
   minutos adentro de uma sessão em janela, logo após uma janela de medição
   catastrófica (155 adiantados, 150 atrasados). Desde a dev.109 o app resolve
   os símbolos sozinho e escreve `=== ÚLTIMA QUEDA NATIVA ===` no diagnóstico:
   **é a primeira linha a ler no próximo diário.**
2. **O "no ritmo" cai ao longo da sessão.** Em quatro minutos, de ~596 para
   ~549 de 600, com adiantados e atrasados crescendo **em pares** — assinatura
   de bloqueio, não de fonte irregular — enquanto a decodificação fica parada em
   4 ms. Algo piora com o tempo. Térmico é o primeiro suspeito, e desde a
   dev.107 existe a linha `Saude 10s:` na mesma cadência do `Video 10s:` para
   responder: leia as duas lado a lado antes de mexer em qualquer coisa.
3. **ANR no modo janela**, visto uma vez em 02/09 12:04: `Input dispatching
   timed out ... StreamActivity is not responding. Waited 5000ms for
   FocusEvent(hasFocus=true)`. Aconteceu na dev.95, que ainda tinha o tick do
   diário na thread principal — o suspeito imediato é esse, e a dev.105 já o
   tirou de lá. Se não voltar, foi isso; se voltar, procure outro bloqueio na
   abertura do stream.
3. **Vibração**: o veredito continua faltando, e o teste de 02/09 não serve —
   foi feito com um **8BitDo Ultimate 2 Wireless**, que não expõe vibrador ao
   Android. O app diz isso em uma linha, e o console estava mandando: o caminho
   chega até a borda e não tem onde entregar. Só o DualSense responde essa
   pergunta.
4. **Conexões remotas consecutivas se atrapalham**: a sessão anterior na PSN nem
   sempre é apagada (`Timed out waiting for holepunch session deletion`), e a
   seguinte pode receber `session_create: Timed out`.
5. **PIN de login não pode ser digitado dentro do modo imersivo** — a sessão
   encerra e manda usar o painel 2D.
6. **Bitrate não tem UI**; está fixo em 25 Mbps.
7. **Controles Touch do Quest não são mapeados** para o console.

Contribuições genéricas que valeria mandar para o chiaki-ng, e que ainda não
foram: o intermediário de certificado (#798), o erro de rede não-fatal no RUDP,
a família de endereço vinda do `getsockname`, e os consertos do
`video-decoder.c`.
