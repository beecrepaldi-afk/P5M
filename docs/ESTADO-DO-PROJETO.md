# P5M — estado do projeto

Atualizado em 2026-08-27.

## Decisões tomadas

- **Alvo**: Quest 3, sideload, uso pessoal. Sem Meta Store.
- **Base**: libchiaki do chiaki-ng como submódulo em `external/chiaki-ng`.
  Não é fork; as mudanças necessárias vivem em `patches/`.
- **O app Android do chiaki-ng está bitrotado**: não compila contra a própria
  libchiaki atual (assinatura do callback de vídeo defasada). Reforça a divisão
  do projeto — libchiaki é o motor vivo, o app Android deles é legado que
  corrigimos e sobrescrevemos.
- **Integração**: o módulo Android compila as fontes Kotlin do submódulo direto
  do diretório dele. `VrStreamActivity` convive com a `StreamActivity` 2D, e um
  patch de uma linha redireciona a `MainActivity`. Motivo: herdar descoberta,
  pareamento, banco de consoles e preferências de graça.
  **Revisado.** A ideia original era excluir `StreamActivity.kt` do source set e
  não ter patch nenhum. Quebra: esse arquivo declara o enum `TransformMode`, do
  qual o `AspectRatioFrameLayout` depende, e o viewBinding gera código que o
  referencia. Conviver custa um patch de uma linha e é menos frágil.
- **Vídeo**: `XR_KHR_android_surface_swapchain` entrega um Surface que o
  MediaCodec preenche direto no compositor. Zero cópia, zero GPU do app.
  Motivo: é o menor caminho de latência possível no Horizon OS.
- **Tela**: `XR_KHR_composition_layer_cylinder`. A curvatura sai do compositor,
  não de geometria nossa.
- **Passthrough**: `XR_FB_passthrough`, opcional, alternável em runtime.
- **Build**: GitHub Actions gera o APK. Motivo: o dono do projeto não precisa instalar
  Android Studio nem NDK no PC dele.
- **Distribuição**: release rolante `dev` a cada push (URL fixa, uso diário) e
  releases versionados por tag `v*` (ficam para sempre, permitem voltar a uma
  versão que funcionava). `versionCode` é sempre o número do build do CI —
  monotônico, então alternar entre tag e dev não esbarra em downgrade.
- **Instalação direto no headset**, sem PC: o Quest instala APK baixado pelo
  navegador. `adb` é alternativa, não o caminho.
- **UI de setup**: painel 2D do chiaki-ng, reaproveitado como está. VR só no
  stream. Motivo: UI de pareamento em VR é trabalho grande com ganho baixo.
- **Modo de ajuste**: acorde L3+R3 durante o jogo. Motivo: é o único jeito de
  ajustar a tela sem roubar input do console e sem UI 2D dentro do imersivo.

## Decisões da segunda rodada (extrair o máximo)

- **Separar "libchiaki" de "app Android do chiaki"**. A libchiaki é a única
  implementação aberta do protocolo de Remote Play e está em manutenção ativa —
  fica. O app Android dele é a base de 2019 e é dele que vinham as limitações;
  sobrescrevemos o que ele decide.
- **Perfil de vídeo montado por nós**: 1080p60 HEVC a 25000 kbps, ignorando os
  presets da UI 2D. 1080p60 é o teto do protocolo, então bitrate é a única
  variável que ainda rende imagem.
- **MQSR** (`XR_FB_composition_layer_settings`, quality sharpening) ligado por
  padrão. Maior ganho de nitidez do projeto, e cabe porque o app não usa GPU.
- **Rec.709** via `XR_FB_color_space`. Correção de cor, não preferência.
- **120 Hz** via `XR_FB_display_refresh_rate`, escolhendo o maior múltiplo
  inteiro do framerate da fonte. Motivo: 90 Hz sobre 60 fps dá razão 1.5 e
  judder; 120 Hz é múltiplo exato.
- **Patch idempotente no submódulo** para as chaves de baixa latência do
  MediaCodec. Única exceção à regra de não tocar no upstream, isolada em
  `patches/`.
- **HDR (HEVC Main10) implementado mas desligado.** O Quest 3 tem pico de
  **100 nits**, LCD, sem local dimming (isso é do Quest Pro, que é mini-LED).
  SDR é masterizado para ~100 nits e HDR para 1000–4000: não sobra margem
  nenhuma para realce. O ganho honesto de 10 bits é menos banding, não brilho —
  mas o enum do chiaki acopla profundidade e curva de transferência
  (`CODEC_H265_HDR`), então não dá para pegar só o bit depth.
- **120 Hz é experimental no Quest 3** e pode não vir habilitado. Nosso código
  enumera as taxas, escolhe o maior múltiplo inteiro do framerate da fonte e
  registra no log qual conseguiu.

## Descartado

- **Unity**: adiciona frame de latência, ~400 MB de APK e uma linguagem a mais.
  O ganho seria UI, que aqui quase não existe.
- **Fork do chiaki-ng**: dívida de manutenção permanente sem ganho.
- **Renderizar a tela com geometria própria (quad + shader)**: pior latência e
  pior qualidade que a camada de composição, com mais código.
- **`XR_META_local_dimming`**: Quest 3 é LCD com backlight único. Local dimming
  é hardware de mini-LED. A extensão não faria nada.
- **Resolução acima de 1080p60**: não existe no protocolo de Remote Play. Não é
  limitação do cliente.
- **Reimplementar o protocolo sem a libchiaki**: handshake criptográfico,
  Senkusha, RUDP e OAuth da PSN. Meses de trabalho, sem alternativa aberta.

## Controle — decidido

- **8BitDo Ultimate 2 por Bluetooth, modo D-input, é o alvo.** Decisão do dono do projeto.
  Motivo de ser 8BitDo e não DualSense: o mapeamento do chiaki é escrito contra
  os keycodes padrão do Android, que é o que um HID genérico emite; o DualSense
  é o caso torto, sem suporte oficial da Meta.
  Bluetooth em vez do dongle 2.4 GHz é preferência do dono do projeto, com o custo conhecido
  de ~8–15 ms contra ~1 ms (da ordem de um frame a 60 fps). O dongle fica
  documentado como alternativa. **O transporte não muda uma linha de código.**
- **Risco aberto**: o botão Star do 8BitDo (que vira botão PS no mapeamento, via
  `BUTTON_MODE`) pode ser interceptado pelo Horizon OS ou não emitir nada em modo
  D. Sem ele não há navegação na interface do PS5. Diagnosticável pelo log de
  teclas sem mapeamento.
- Giroscópio e gatilhos adaptativos ficam de fora. O dono do projeto não usa. Gatilhos
  adaptativos o protocolo não transmite nem para o DualSense.
- **Clique do touchpad implementado por nós**: o chiaki não mapeia touchpad em
  gamepad nenhum. Aceitamos BUTTON_1..4 e BUTTON_Z, injetando o bit direto no
  touchControllerState do StreamInput.
- Toda tecla de gamepad sem mapeamento vai para o log, para descobrir o que
  botões extras emitem sem ter o hardware.

## Diagnóstico

- **Tela de diagnóstico no próprio headset**, com ícone separado no lançador.
  Motivo: o dono do projeto usa o Quest longe do PC, e sem `adb` uma falha na inicialização
  é indistinguível de janela em branco.
- Construída sem AppCompat, layout XML, viewBinding ou tema do chiaki, de
  propósito: não pode compartilhar os modos de falha do que investiga.
- Lê o logcat **sem filtrar por PID** — o processo que interessa é o anterior,
  o que caiu. Um app só enxerga as linhas do próprio UID, então não precisa de
  permissão.
- Handler global de exceção grava o stack trace em arquivo, que sobrevive à
  morte do processo.

## Janela em branco no primeiro teste em hardware

Sintoma: o app instala, mas abrir mostra uma janela vazia.

- **Hipótese 1, descartada**: o Horizon OS escolheria a activity imersiva como
  entrada (ela declara MAIN junto da categoria VR) e o `finish()` sem
  `ConnectInfo` apareceria como janela branca. O redirecionamento para o painel
  2D foi implementado e **não resolveu** (testado na dev.10). A blindagem fica,
  por ser correta de qualquer forma.
- **Hipótese 2, descartada**: `android.hardware.vr.headtracking` como
  `required="true"` marcaria o app inteiro como VR, fazendo o Horizon OS
  abri-lo em modo imersivo enquanto a entrada é uma activity 2D que não
  submete frame OpenXR. Passou para `required="false"` e **não resolveu**
  (testado na dev.14). A mudança fica, por estar correta para um app híbrido.
- **Achado que invalidou o plano de diagnóstico**: o Horizon OS mostra **um
  ícone por pacote**, não um por activity de lançamento. A `DiagnosticActivity`
  tinha entrada própria no lançador e simplesmente nunca apareceu na
  biblioteca — ficou inalcançável justamente quando era mais necessária.
- **Em teste**: `LauncherActivity` passa a ser a única entrada. Tela simples,
  sem AppCompat, sem layout XML, sem tema do chiaki e sem biblioteca nativa,
  com botões para o painel e para o diagnóstico. Serve como experimento: se
  ela renderizar, o processo sobe e painel 2D funciona, e o problema está
  adiante; se nem ela renderizar, o problema é anterior a qualquer activity.

## Causa raiz da janela em branco — RESOLVIDA

`java.lang.ClassNotFoundException: io.github.gblandro.p5m.main.FloatingActionButtonBackgroundBehavior`

O `MainActivity` do chiaki-ng **sempre** quebrou ao inflar o layout, desde a
primeira instalação. Nunca chegou a abrir.

Causa: `applicationId` é `io.github.gblandro.p5m`, mas as classes do chiaki vivem em
`com.metallic.chiaki`. O `CoordinatorLayout` resolve `app:layout_behavior` que
começa com `.` **contra o applicationId**, não contra o namespace do módulo.
`.main.FloatingActionButtonBackgroundBehavior` virava
`io.github.gblandro.p5m.main.…`, que não existe.

Corrigido no patch `0003`, qualificando os dois Behaviors com o pacote completo.

**Armadilha a lembrar:** `applicationId` diferente do pacote do código quebra
tudo que resolve nome de classe por caminho relativo. Varredura feita — só
esses dois casos existem. `tools:context` é só para a IDE, os `app:fragment`
das preferências já são qualificados, e a autoridade do FileProvider é literal
no código do chiaki (`com.metallic.chiaki.fileprovider`) e bate com a declarada
no nosso manifesto.

## Swapchain de vídeo — causa e correção

`xrCreateSwapchainAndroidSurfaceKHR: XR_ERROR_VALIDATION_FAILURE`

A especificação de `XR_KHR_android_surface_swapchain` exige que `format`,
`sampleCount`, `faceCount`, `arraySize` e `mipCount` sejam **zero** — não são
ignorados, são obrigatoriamente zero. Eu passava `1` nos quatro últimos, que é
o valor natural para um swapchain de textura comum. Quem produz as imagens aqui
é o MediaCodec pelo lado Surface, então o runtime não tem formato nem níveis
para alocar.

Antes disso, a sessão OpenXR já era criada com sucesso no Quest 3 — instância,
sistema, EGL e sessão. A extensão está presente e habilitada.

### Pendência aberta da mesma especificação — RESOLVIDA depois, ver o fim do arquivo

> "When the application receives XR_SESSION_STATE_STOPPING, it must ensure that
> no threads are writing to any of the Android surfaces created with this
> extension before calling xrEndSession."

Hoje `HandleSessionStateChange` chama `xrEndSession` direto no STOPPING,
enquanto o MediaCodec pode estar escrevendo na Surface. A especificação diz que
o efeito é indefinido. Precisa de um callback para o lado Kotlin desanexar a
Surface (`session.setSurface(null)`) antes de encerrar. Não corrigido ainda:
priorizei destravar o primeiro frame.

## SIGSEGV dentro do xrEndFrame — em investigação

Depois da correção do swapchain, o caminho inteiro sobe: sessão OpenXR,
swapchain 1920x1080, decodificador HEVC, conexão com o console e
`Ctrl received Login message: success`. O crash passa a ser na primeira
submissão de frame.

```
SIGSEGV, fault addr 0x0, pc 0x0
#04 xrEndFrame+124   libopenxr_loader.so
#05 XrVideoSession::RenderFrame()+1340
```

`pc = 0` é salto para **ponteiro de função nulo**, não leitura de dado nulo —
o runtime não tinha handler para algo na camada submetida.

Dois suspeitos, ambos neutralizados nesta rodada para isolar:

1. `XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT` no cilindro. O vídeo é
   opaco, e com swapchain-Surface quem escolhe o formato é o runtime — pedir
   mistura por alfa sobre formato possivelmente sem canal alfa é errado de
   qualquer forma. Removido.
2. `XrCompositionLayerSettingsFB` encadeado no `next` (o MQSR). Desligado por
   padrão até a submissão simples ficar estável.

Acrescentado o diagnóstico que faltava: log das extensões efetivamente
habilitadas e da configuração da primeira submissão. Sem isso não dá para saber
se `XR_FB_composition_layer_settings` estava sequer presente.

### A contradição na especificação

`XR_KHR_android_surface_swapchain` exige `faceCount = 0` na criação do
swapchain. `XR_KHR_composition_layer_cylinder` exige que o swapchain
referenciado **tenha sido criado com `faceCount = 1`**. Não dá para satisfazer
as duas — qual forma o runtime da Meta aceita com swapchain-Surface é questão
empírica, não de leitura.

### Fallback automático em vez de mais uma rodada

Implementado `XrCompositionLayerQuad` como forma alternativa (tela plana, mesmo
tamanho aparente: largura = 2·raio·tan(arco/2)), e uma detecção de crash que
troca sozinha:

- Antes do primeiro frame, grava `layer_attempt_pending`.
- Depois de 4 s de submissão viva, apaga.
- Se na abertura seguinte o marcador ainda estiver lá, o app morreu naquela
  forma — troca cilindro → quad automaticamente.

Necessário porque SIGSEGV dentro do runtime não passa pelo handler de exceção
do Java: sem o marcador em disco não há como detectar.

## Pendências

- Build do CI, duas rodadas até aqui:
  1. `Could not find protoc` — a libchiaki gera os stubs nanopb no host.
     Corrigido instalando `protobuf-compiler` e o pacote Python `protobuf`.
  2. `EGL_OPENGL_ES3_BIT_KHR` indefinido (vive em `EGL/eglext.h`, não no
     `egl.h` do NDK) e `Unresolved reference: TransformMode`. Ambos corrigidos.
     O mecanismo de patch funcionou já nessa rodada.
  3. `chiaki-jni.c:350: incompatible function pointer types`. **Erro do
     upstream, não nosso**: o `ChiakiVideoSampleCallback` da libchiaki ganhou
     `frames_lost` e `frame_recovered`, e a camada JNI do Android nunca
     acompanhou. O app Android do chiaki-ng não compila contra a própria lib
     atual. Corrigido no patch 0001.
  Quarta rodada ainda não validada. OpenSSL, curl, json-c, miniupnpc, jerasure e
  a própria libchiaki compilam sob o NDK sem erro — o build chegou em 149/284
  antes de parar.
- Pareamento do DualSense no Quest 3: **risco aberto e não resolvível por
  código**. Precisa de teste no hardware.
- PIN de login encerra a sessão imersiva. Sem superfície 2D para digitar.
- Controles Touch do Quest não mapeados. Prioridade caiu: o alvo agora é
  gamepad genérico, que não depende deles.
- Rumble não roteado.
- Bitrate e HDR não têm UI: moram em SharedPreferences e valem a partir do
  próximo início de stream.

## Próximo passo

1. Rodar o workflow **Build APK** e corrigir o que o build acusar.
2. Sideload no Quest 3 e testar o pareamento do DualSense — se falhar, decidir
   entre mapear os controles Touch ou abandonar o projeto.
3. Só depois disso, medir latência de verdade e mexer em otimização.

## Vibração: por que o console nunca mandou nada

O diário de dev.76 varrido inteiro (três sessões) não tinha um único pacote de
tipo 7. Concluí daí que o PS5 não manda vibração para o Remote Play. Estava
errado, e a fonte do chiaki-ng diz onde.

O DualSense não tem motor de rotação: tem duas bobinas, que são alto-falantes.
O console não manda "esquerdo 200, direito 40" — manda **áudio**, um segundo
fluxo PCM estéreo de 16 bits marcado com `is_haptics`, pela mesma via do som do
jogo (`lib/src/takion.c:1796`, `lib/src/audioreceiver.c:158`). Um grep por tipos
de dado do takion nunca acharia isso: háptica não é um tipo de dado, é uma
trilha de áudio.

E o console só abre essa trilha se o cliente se apresentar como DualSense. A
porta Android do chiaki nunca preencheu `enable_dualsense`: o `ConnectInfo` saía
zerado, o `CONTROLLERCONNECTION` ia com tipo `DUALSHOCK4`
(`lib/src/streamconnection.c:1129`), e o `ENABLE_DUALSENSE_FEATURES` do ctrl
nunca era mandado (`lib/src/ctrl.c:853`). O silêncio era coerente com o pedido.

O patch 0009 fecha os dois lados: liga `enable_dualsense` e registra um
`haptics_sink` que converte o áudio em intensidade de 0 a 255. É a mesma conta
do "rumble emulado" que o chiaki-ng usa no desktop para controle por Bluetooth
— média do valor absoluto, piso contra ruído, média de três pacotes para não
trepidar a cada 10 ms — porque Bluetooth não carrega áudio até o controle, só o
comando antigo de vibrar. Ganho e piso (`P5M_HAPTICS_GAIN`,
`P5M_HAPTICS_FLOOR`) são os dois números a mexer se sair forte ou fraco
demais.

## As falhas de FEC com a rede impecável

132 falhas de FEC concentradas na janela de jogo (15–23/min jogando, 1–5/min
parado), com 21 pedidos de IDR no meio, numa rede que não engasgou.

A perda não estava no ar. O chiaki pede `SO_RCVBUF` igual ao `a_rwnd` que
anuncia no protocolo — `0x19000`, 100 KB (`lib/src/takion.c:41`). São dois
números com donos diferentes tratados como um só: o `a_rwnd` é promessa feita ao
console, o `SO_RCVBUF` é o balde do kernel aqui dentro. 100 KB a 25 Mbps são
32 ms. Um quadro-chave de 1080p passa fácil de 200 KB e chega todo de uma vez;
se a thread que lê o socket ficar fora do processador por um instante — e num
headset ela disputa com composição, decodificador e o nosso shader — o kernel
descarta o excedente sem avisar ninguém.

Isso fecha o círculo: falha de FEC → pedido de IDR → quadro-chave grande em
rajada → estouro do balde → falha de FEC. O `enable_idr_on_fec_failure` que eu
liguei não é a causa, mas é o que mantém o círculo girando.

O patch 0009 pede 1 MB (~320 ms na mesma conta) sem tocar no `a_rwnd`, e
registra no diário o valor que o kernel **concedeu** — o teto é
`net.core.rmem_max` e não há como forçá-lo sem capacidade de rede. Se o próximo
diário mostrar concessão perto de 1 MB e as falhas de FEC caírem, era isso.

## Conexão remota via PSN

Fora de casa o console some da lista, e isso não é defeito: a descoberta é
broadcast UDP na porta 9302, e broadcast não atravessa a internet.

Os três caminhos possíveis eram encaminhamento de portas no roteador (depende
de acesso remoto ao roteador e de não haver CGNAT), VPN de volta para casa
(depende de um nó rodando lá) e a conexão remota da própria PSN, que fura o NAT
dos dois lados usando os servidores da Sony e não depende de nada disso. É o
terceiro que está implementado.

O trabalho foi menor do que parecia: **todo o protocolo já estava compilado
dentro do APK**. `lib/src/remote/holepunch.c` entra na lista de fontes da
libchiaki sem condição nenhuma, e o build Android já linka curl, json-c e
miniupnpc. O que faltava era a ponte — a porta Android nunca expôs nada disso
ao Kotlin.

O encaixe também é limpo: `ChiakiConnectInfo` tem um campo `holepunch_session`,
e quando ele está preenchido a libchiaki pula a resolução de endereço e faz o
registro por dentro do túnel, usando só o id da conta PSN
(`lib/src/session.c`, `regist_cb`). Ou seja: **nada do caminho de vídeo, áudio
ou nitidez muda**. A conexão remota entra antes de tudo que já funcionava.

Peças:

- `patches/0010`: `psnDuid`, `psnListDevices`,
  `sessionConnectPsn` e `sessionCancelPsn` no JNI, mais os três campos novos do
  `ConnectInfo` (`duid`, `psnToken`, `psnAccountId`) e o objeto público
  `ChiakiPsn`. Duid vazio significa conexão local, e aí nada disso roda.
- `patches/0011`: o modo janela fura o NAT numa thread antes do `start()`.
- `app/.../PsnAuth.kt`: login OAuth, renovação do token e id da conta. As
  credenciais vivem em preferências privadas do app e em lugar nenhum mais —
  não vão para o repositório, não entram no diário, não aparecem no
  diagnóstico.
- `app/.../PsnRemoteActivity.kt`: entrar, escolher o console, conectar.
- O modo imersivo fura o NAT numa thread com o loop de quadro já rodando: são
  dezenas de segundos, e uma tela preta por esse tempo é indistinguível de
  travamento.

O que ainda não foi testado: nada disso passou por hardware. O roteiro dos
cinco passos veio do `ConnectPsnConnection` do chiaki-ng desktop e a ordem é a
documentada no topo de `lib/include/chiaki/remote/holepunch.h`, mas cada passo
registra o próprio sucesso no diário justamente porque, quando falhar longe de
casa, saber até onde chegou é a diferença entre "a PSN não respondeu" e "o
console não atendeu".

## O primeiro login remoto, e o que ele ensinou

Duas coisas quebraram no primeiro teste de verdade, e as duas eram sobre
**informação que não chegava**, não sobre protocolo.

**A senha não existe mais.** A Sony apaga a senha da conta quando você cria uma
passkey. Não é a página escondendo o campo: não há campo. Sobra escanear um QR
com o app PlayStation, ou recuperação por e-mail. O QR até vai na direção certa
— quem escaneia é o celular, já autenticado, e o que ele autoriza é a sessão
que está na tela — mas a tela está dentro do headset. Passkey também não
resolve de dentro: exige gerenciador de credenciais e biometria que o Horizon
OS não oferece a um WebView.

A saída é a `PsnLoginRelay`: o headset publica uma página na rede local, o
celular abre digitando um endereço curto, o login acontece **no celular** e só
o código atravessa. Com um campo para colar o endereço à mão quando a rede
isola os aparelhos entre si.

**A lista vazia não dizia nada.** `psnListDevices` devolvia `null` em qualquer
falha, e do lado Kotlin `null` e lista vazia viravam a mesma frase — "nenhum
console apareceu". São três desfechos diferentes: token recusado, servidor
fora, e conta que realmente não tem console visível. Só o terceiro é sobre o
console.

Pior: o motivo real *existia*, registrado por `CHIAKI_LOGE` dentro do
`chiaki_holepunch_list_devices` — com o código HTTP e tudo. Mas o log da
libchiaki no Android vai para o **logcat**, e o diário do P5M é outro
arquivo. Quem está longe de casa, sem PC, não lê logcat: a mensagem que
explicava a falha morria a dois metros de quem precisava dela.

Agora a chamada recebe um `ChiakiLog` próprio, com um callback que recolhe as
linhas de erro num buffer e as repassa ao logcat na passagem. O texto volta
pelo JNI e sobe até a tela. `psnListDevices` passou a devolver um array cujo
primeiro elemento é o estado (`ok` ou `erro\t<motivo>`), e o Kotlin ganhou
`PsnDeviceList(error, devices)` — porque lista vazia e falha não são a mesma
coisa.

De quebra: `chiaki_holepunch_list_devices` recusa PS4 na primeira linha. A
chamada para PS4 era uma ida à rede para receber um erro previsível, e saiu.

## O curl sem nada em que confiar

O diário do primeiro teste com a tela nova deu a resposta em uma linha:

```
chiaki_holepunch_list_devices: ... failed with CURL error
SSL peer certificate or SSH remote key was not OK
```

Não era o console, nem o token, nem a rede. Era o libcurl que vai dentro do
APK **sem nenhuma raiz de confiança**.

O `CMakeLists.txt` do curl só procura um pacote de certificados no sistema
quando não está compilando cruzado — `if(NOT CMAKE_CROSSCOMPILING AND NOT
WIN32)` — e compilar para Android é exatamente isso. Então `CURL_CA_BUNDLE` e
`CURL_CA_PATH` saem indefinidos. O recurso de último caso, `CURL_CA_FALLBACK`,
vem desligado por padrão. A biblioteca sobe sem uma raiz sequer e recusa todo
HTTPS no aperto de mão.

Isso explica exatamente o que se via: **o login funcionava e a listagem não**.
O login é HTTP do lado Kotlin, que usa a pilha TLS do Android e a loja de
confiança do sistema. Só as chamadas de dentro da libchiaki passam pelo curl —
e são todas as da conexão remota, listagem e furação de NAT incluídas.

O conserto tem duas metades, porque `CURL_CA_BUNDLE` é **macro de compilação**,
não variável de ambiente:

- `app/build.gradle` passa `-DCURL_CA_BUNDLE=<caminho fixo>`. Um valor só cobre
  os doze `curl_easy_init` do `holepunch.c` sem tocar em nenhum.
- `CaBundle.kt` escreve esse arquivo em tempo de execução, a partir do
  `AndroidCAStore` — a mesma loja que o navegador do sistema usa. Exportar em
  vez de embutir um `cacert.pem`: um arquivo congelado envelhece, e envelhecer
  aqui significa falhar longe de casa.

Fica no `P5MApp`, e não numa activity: a furação de NAT também fala por curl
e roda na activity de stream, não na tela de conexão remota.

Os dois caminhos não podem se separar em silêncio — um `applicationId`
renomeado bastaria, e a falha apareceria só no aparelho, como TLS recusado sem
explicação. `conferir.py` passou a comparar os dois e a exigir que o caminho
contenha o `applicationId`.

## O TLS que passa num host e falha no outro

Com as raízes no lugar (134 exportadas do `AndroidCAStore`), a listagem passou a
funcionar: `PS5-CONSOLE (remote play ligado)`. O console sempre esteve visível —
duas suposições minhas sobre "console desligado" estavam erradas.

Mas o websocket de notificações continua recusado:

```
websocket_thread_func: Connecting to push notification WebSocket
wss://...-pushcl.np.communication.playstation.net/np/pushNotification
failed with CURL error SSL peer certificate or SSH remote key was not OK
```

E isso **é o bloqueio**, não um detalhe: `chiaki_holepunch_session_create` e
`chiaki_holepunch_session_start` bloqueiam os dois em `wait_for_notification`,
que só é alimentada por esse websocket. Sem ele a conexão remota espera 30 s +
30 s e desiste — que é exatamente o tempo de tela preta observado.

O que torna o caso estranho é que os dois hosts usam o **mesmo** arquivo de
raízes: não há `CURLOPT_CAINFO`, `CURLOPT_CAPATH` nem `CURLOPT_SSL_VERIFYPEER`
em lugar nenhum do `holepunch.c`, e o `curl_share` é criado sem nenhum
`curl_share_setopt`, então não compartilha nada. `web.np.playstation.com`
verifica; `*.np.communication.playstation.net` não.

Três defeitos diferentes cabem nessa mesma frase de erro, e cada um pede um
conserto diferente: raiz desconhecida, cadeia incompleta, ou nome que não bate.
Como os dois hosts são infra da Sony e provavelmente saem da mesma autoridade,
"falta uma raiz" é o menos provável — mas isso é inferência, não dado, e chutar
custaria outra noite de teste.

Então o `patch 0012` faz a falha responder por si:

1. `CURLINFO_SSL_VERIFYRESULT` — o código X509 da verificação, que já separa
   "nome não bate" (0) de "não achei quem assinou" (20).
2. Uma sonda que reabre a conexão só para **ler a cadeia** que o servidor manda
   (`CURLOPT_CERTINFO`), e registra quantos certificados vieram e o titular e o
   emissor de cada um. É isso que separa cadeia incompleta de raiz desconhecida.

A sonda desliga a verificação, o que pede cuidado: ela não manda nem lê byte
nenhum de protocolo (`CONNECT_ONLY`), não devolve socket para lugar nenhum, o
handle morre no fim da função, e ela só roda **depois** de a conexão real já ter
sido recusada. Nenhum caminho de conexão de verdade passa por ali.

## Cada APK chegava como um aplicativo diferente

Sintoma: o login da PSN precisava ser refeito a cada iteração.

Causa: o CI gera um APK **debug**, e o Gradle o assina com o
`~/.android/debug.keystore` do runner. O workflow guardava `~/.gradle` no
cache, mas não a chave — e o runner é descartável, então **uma chave nova
nascia a cada build**. Assinaturas diferentes fazem o Android recusar instalar
por cima; para trocar de versão é preciso desinstalar, e desinstalar leva junto
todo o estado do app: login da PSN, registro do console no chiaki, modo de
exibição, nitidez.

Não era o login que estava frágil. Era que cada APK chegava como um aplicativo
diferente que por acaso tinha o mesmo nome.

A chave passou a viver no cache do Actions, e **não** no repositório: material
de assinatura não se versiona. O cache é por repositório e cada acerto renova
os 7 dias de validade, então enquanto houver um build por semana ela sobrevive
indefinidamente. Se um dia expirar, um passo do workflow avisa em vez de a
surpresa aparecer no aparelho.

Uma desinstalação ainda é necessária **uma última vez**, para sair da chave
antiga. Depois disso, instalar por cima preserva tudo.

## Cadeia incompleta: o servidor manda só a folha

O diagnóstico do `patch 0012` respondeu em quatro linhas:

```
codigo X509 20 (nao achei localmente quem assinou)
o servidor mandou 1 certificado(s)
  [0] Subject: CN = *.np.communication.playstation.net
  [0] Issuer:  ... COMODO RSA Domain Validation Secure Server CA
```

**Um certificado só.** O host de notificações da Sony manda a folha e não manda
o intermediário que a assinou. O OpenSSL não tem como ligar a folha à raiz que
temos e recusa. O Android não se importa porque a pilha dele guarda
intermediários vistos antes; o OpenSSL não guarda nem busca.

Não é raiz faltando — era a hipótese que eu tinha descartado por inferência, e
o dado confirmou.

O conserto não embute o intermediário no APK, pelo mesmo motivo que as raízes
não são embutidas: arquivo congelado envelhece, e a Sectigo roda os
intermediários dela. O certificado da folha **diz onde buscar o próprio
emissor** — é a extensão AIA, e é o que os navegadores fazem. Então
`CaBundle.ensureNetwork` baixa dessa URL, e só acrescenta ao pacote depois de
provar que **o próprio Android já confia em quem assinou**.

Essa prova é o que separa isto de "baixar um certificado por HTTP e passar a
confiar nele", que seria inaceitável. São duas tentativas:

1. Validação PKIX contra o `AndroidCAStore`.
2. Se o PKIX recusar por formalidade — ele foi feito pensando em folhas, e aqui
   o alvo do caminho é uma autoridade —, achar entre as raízes aquela cujo
   titular é o emissor deste certificado e **verificar a assinatura** com a
   chave dela.

A segunda não afrouxa a primeira: continua sendo "assinado por uma raiz em que
este aparelho confia". O que ela dispensa são regras de política e uso que não
se aplicam a um intermediário isolado. Se as duas falharem, o certificado é
descartado e o diário diz por quê.

### O que a busca na internet acrescentou

Três confirmações que mudaram o desenho:

- **Não é a nossa porta.** O mesmo erro está aberto no chiaki-ng upstream
  ([#798](https://github.com/streetpea/chiaki-ng/issues/798)), atingindo Fedora
  e Void Linux, AppImage e Flatpak, em máquinas e redes diferentes — sem
  correção e sem resposta dos mantenedores. Foi a Sony que mudou o servidor, e
  o defeito atinge todo cliente que use libcurl com OpenSSL.
- **O app oficial continua funcionando**, o que bate com o diagnóstico: ele usa
  a pilha TLS da plataforma, que resolve o intermediário que falta.
- **Buscar pela AIA nunca foi implementado no curl**
  ([curl#2793](https://github.com/curl/curl/issues/2793), aberto desde 2018).
  A solução aceita é fornecer o intermediário por conta própria — que é o
  caminho já escolhido aqui.

O que a busca expôs foi uma fraqueza: buscar só em tempo de execução deixa tudo
dependendo de a rede onde o headset está alcançar o CDN da autoridade, e é
longe de casa que isso é menos garantido.

Então o download passou a acontecer também **na CI**, onde a rede é livre, e o
resultado vai embarcado no APK. Baixado a cada build, então não envelhece como
envelheceria um arquivo versionado.

O ponto importante: o que vem no APK é um **candidato**, não um certificado
confiado. O `CaBundle` refaz a mesma prova no aparelho — contra as raízes
daquele aparelho — antes de usar. A conferência na CI existe só para não
embarcar lixo. A decisão de confiança nunca sai do aparelho, e a busca pela AIA
continua como reserva se o candidato faltar ou não validar.

## O TLS passou, e apareceu um defeito meu por baixo

Com o intermediário no lugar, a conexão remota avançou de verdade:

```
sessao remota criada na PSN
IPV6 NOT supported by your PlayStation console
console acordado pela PSN, furando o NAT
Sent response to 191.193.2.202:9303
Sent response to 191.193.2.202:9303
```

Estava trocando pacotes com o console. E então:

```
Stop JNI Session          ← no meio da furação
Holepunch session deleted
Failed to find reachable candidate for control connection (Canceled)
```

`Canceled`, não falha. Foi interrompida. E o log de crash do processo anterior
mostra o mesmo padrão terminando pior:

```
FORTIFY: pthread_mutex_lock called on a destroyed mutex
  pthread_cond_timedwait
  chiaki_holepunch_session_start+720
  Java_..._sessionConnectPsn+796
  StreamSession.resume$lambda$0
```

São **dois defeitos**, e o primeiro é meu.

**Uso de memória liberada.** Eu pus a furação de NAT numa thread e não impedi o
`dispose()` de liberar a sessão nativa por baixo dela. O `cancelPsn` que eu
tinha posto no `onDestroy` pede para parar, mas entre o pedido e a parada existe
uma janela — e foi nela que o processo morreu. Agora cancela **e espera**
(`join`), e se a thread não sair a tempo a sessão é deixada para trás em vez de
liberada: vazar é ruim, usar memória liberada é pior.

**`onPause` derrubando a conexão.** No headset, um painel 2D perde o foco por
qualquer coisa — olhar para outro painel basta — e o `onPause` do chiaki chama
`shutdown()`. Numa conexão local isso é barato, reconecta em segundos. Na
remota custa a furação inteira, que leva quase um minuto e recomeça do zero na
PSN. Enquanto a furação corre, o `pause` não desliga mais nada; o `onDestroy`
continua desligando.

## A furação funcionou; um ICMP atrasado derrubou o resto

A dev.88 chegou muito mais longe:

```
caminho de controle aberto        ← furou o NAT
Regist successfully received response
PS5-CONSOLE successfully registered for Remote Play
Starting session request for PS5
Rudp recv failed: Connection refused    ← 5 ms depois
SESSION START THREAD - Failed to init rudp
```

A furação de NAT deu certo e o registro **pelo túnel** deu certo — a libchiaki
refez o registro usando só o id da conta, como previsto. O que quebrou foi a
primeira leitura da sessão, cinco milissegundos depois, no **mesmo socket** que
acabara de funcionar.

`ECONNREFUSED` num socket UDP *conectado* não quer dizer que o outro lado
recusou agora: no Linux é como o kernel entrega um ICMP port-unreachable de um
**envio anterior**, na próxima leitura. E a furação manda sondas para vários
candidatos antes de escolher um, então sobrar um desses erros na fila é o
normal, não a exceção.

O laço de `chiaki_rudp_send_recv` já tinha orçamento de três tentativas, mas só
repetia em timeout:

```c
if(err == CHIAKI_ERR_TIMEOUT)
    continue;
if(err != CHIAKI_ERR_SUCCESS)
    return err;      // um erro assíncrono de um pacote antigo aborta tudo
```

O `patch 0013` faz erro de rede gastar uma tentativa em vez de encerrar. O erro
é consumido pela leitura que falhou, então a tentativa seguinte encontra o
caminho limpo — provado fora do aparelho com dois sockets UDP: primeira leitura
`Connection refused`, segunda leitura entrega os dados. Se o console tiver mesmo
sumido, as tentativas se esgotam e a falha aparece igual, só que depois de ter
tentado.

### Ainda aberto

Depois da falha o app tentou de novo, e a segunda tentativa se enrolou porque a
sessão anterior ainda existia no servidor da PSN (`Holepunch session was deleted
on PSN server`). É consequência da primeira falha, não causa — mas se
reaparecer, vale limpar a sessão antiga antes de recomeçar.

## Até o login no console — e o crash era meu de novo

A dev.89 foi a mais longe de todas:

```
caminho de controle aberto
PS5-CONSOLE successfully registered for Remote Play
Sending session request
Session request successful          ← a correção do RUDP funcionou
Ctrl connected
Ctrl received Login message: success
Punching hole for data connection
>> Punched hole for data connection!
Takion enabled Don't Fragment Bit
SIGSEGV em chiaki_takion_connect+2028
```

Sessão iniciada, **login feito no console**, e o segundo furo de NAT (o do
canal de dados) também. Então o processo morreu com endereço de falha diferente
a cada tentativa — a assinatura de ponteiro-lixo.

A causa está em `senkusha.c`:

```c
ChiakiTakionConnectInfo takion_info;   // struct de pilha, NAO inicializada
if(!socket) { ...preenche sa e sa_len... }
else
    takion_info.close_socket = false;  // sa e sa_len ficam como lixo
```

Na conexão remota o socket já chega furado do holepunch, então cai no `else` e
**`sa` nunca é preenchido**. O código original só lê `info->sa` dentro do ramo
que o preenche. A marcação de prioridade do `patch 0006` — minha — ficou
**fora** desse ramo e lia `info->sa->sa_family`. Localmente o campo existe e
tudo funcionava; remotamente era lixo de pilha.

O conserto tira a dependência de `info->sa`: a família do endereço vem do
próprio socket, por `getsockname`. Ele já existe nos dois caminhos a essa
altura. Se a chamada falhar, IPv4 é o palpite certo — é o único que o console
aceita, como o próprio log da furação diz.

Lição que vale registrar: um bloco novo colocado no fim de uma função herda
**todas** as pré-condições dos ramos anteriores sem avisar. `info->sa` era
válido em todos os caminhos que o liam antes; passou a ser lido num caminho
onde nunca fora preenchido.

## Conectou

Primeira sessão remota completa, do hotel para casa, pela conexão remota da PSN:

```
P5M: socket marcado com DSCP AF41 (categoria video)
P5M: buffer de recepcao pedido 1048576 B, concedido 2097152 B
StreamConnection successfully received streaminfo
Switched to profile 0, resolution: 1920x1080
decodificador 'c2.qti.hevc.decoder': color-standard=1 color-transfer=3 color-range=2
Cadencia: 600 de 600 passadas tinham quadro novo (100%)
```

A linha do decodificador é a prova mais dura: nas sessões que falhavam ela vinha
`color-standard=-1 color-transfer=-1 color-format=0xffffffff` — os valores de
"nunca decodifiquei nada". Agora são valores reais. E 600 de 600 passadas com
quadro novo: nenhum quadro repetido em dez segundos, atravessando a internet.
Nenhuma falha de FEC na sessão.

### O que apareceu de novo junto

```
Set haptic intensity to: Medium
Set adaptive trigger intensity to: Medium
P5M: primeiro pacote de tipo 11 (trigger_effects), 25 bytes
```

**Tipo 11 nunca tinha aparecido em log nenhum.** O `enable_dualsense = true` do
patch 0009 está valendo: o console manda efeitos de gatilho adaptativo. Se manda
tipo 11, a trilha de háptica provavelmente também vem — e o caminho de vibração
construído lá atrás nunca pôde ser testado porque o console nunca mandava nada.

### As sete paredes

Em ordem, o que separava o console de aparecer na tela:

1. Descoberta por broadcast não atravessa a internet — conexão remota via PSN.
2. Senha apagada pela passkey — login pelo celular via ponte na rede local.
3. libcurl sem nenhuma raiz de confiança — `CURL_CA_BUNDLE` + `AndroidCAStore`.
4. Servidor manda só a folha, sem o intermediário — busca pela AIA, na CI.
5. `dispose()` liberando a sessão sob a thread da furação — cancelar **e** esperar.
6. `onPause` derrubando uma conexão de quase um minuto — não desliga durante a furação.
7. Um ICMP atrasado tratado como fatal — erro de rede gasta uma tentativa.
8. `info->sa` lido fora do ramo que o preenche — família vem do socket.

Quatro delas eram defeitos meus; três desses introduzidos neste projeto.

Os consertos 3, 4, 7 e 8 não são específicos do P5M: valem para qualquer
cliente que use a libchiaki com libcurl e OpenSSL. O 4 em particular é a causa
do [chiaki-ng#798](https://github.com/streetpea/chiaki-ng/issues/798), que
derrubou a conexão remota no Linux de mesa e segue aberto lá.

## A vibração chegou

```
09-01 00:09:13.916 E/Chiaki: P5M: primeiro pacote de tipo 7 (RUMBLE), 3 bytes
```

Tipo 7 é o rumble legado — dois bytes de intensidade, esquerdo e direito. Nunca
tinha aparecido em nenhum log deste projeto. É a resposta à pergunta que abriu
toda esta linha de trabalho: **o PS Remote Play manda vibração, sim**, desde que
o cliente se anuncie como DualSense. O que faltava era o `enable_dualsense`, que
a porta Android nunca tinha ligado.

Com isso o `Rumble.set()` passa a ser chamado com valores de verdade pela
primeira vez desde que foi escrito.

## O SIGSEGV em `ANativeWindow_release`

```
00:09:56.882 E/Chiaki: Failed to get input buffer for shutting down Video Decoder!
00:09:57.168 I/P5MVR: Janela com shader: nitidez 2 (média), fonte SDR 8 bits
00:09:57.264 I/P5MVR: Conversor de tons pronto (textura externa 1)
00:09:57.389 F/libc: Fatal signal 11 (SIGSEGV) ... fault addr 0x30
  #00 ANativeWindow_release+0
  #01 android_chiaki_video_decoder_set_surface+268
```

Uma sessão tinha acabado de morrer e outra abriu 300 ms depois. O registrador
`x0` no tombstone é zero: o argumento do `ANativeWindow_release` era nulo.

O `video-decoder.c` do chiaki-ng tem quatro defeitos que se encadeiam, e o
crash é o quarto:

1. `android_chiaki_video_decoder_init` nunca zerava `decoder->window`. Passou
   despercebido enquanto o `malloc` da sessão calhou de devolver página zerada.
2. `kill_decoder` apagava o codec e deixava o `window` para trás — vazando a
   referência e deixando um ponteiro vivo mais tempo que o codec que o usava.
3. `ANativeWindow_fromSurface` devolve **NULL** para uma Surface abandonada, e
   ninguém conferia. Pior: o `AMediaCodec_configure` aceita janela nula — é o
   modo de buffer —, então o codec sobe, decodifica para lugar nenhum, a tela
   fica preta e nenhuma linha de log diz o motivo.
4. A troca seguinte de superfície solta esse NULL. É o SIGSEGV.

E, de quebra, um quinto que ainda não tinha se manifestado: o ramo
`if(!surface)` do `set_surface` chamava `kill_decoder` **com o mutex na mão**, e
o `kill_decoder` tranca o mesmo mutex, que não é recursivo. Depois saía por
`return` sem destrancar. Era travamento certo no `surfaceDestroyed` do modo
janela.

Consertados os cinco no patch 0014, junto com uma guarda no `Session.setSurface`
do Kotlin: depois do `dispose()` o ponteiro nativo é zero, e a view que produz a
superfície tem ciclo de vida próprio — ela pode chamar depois da sessão ter ido
embora, e foi exatamente o que aconteceu.

## O modo imersivo e a furação de NAT

Duas tentativas remotas em modo Imersivo falharam onde o modo janela tinha
acabado de funcionar. A hipótese óbvia — falta no `VrStreamActivity` a guarda
que o `StreamSession.pause()` ganhou no patch 0011 — **está errada**: o
`onPause` do modo imersivo só chama `xr?.stop()`, nunca derrubou sessão nenhuma.

O que o diário mostra é outra coisa:

```
00:15:35.499 P5M: sessao remota criada na PSN
00:15:43.995 Foco de janela: false
00:15:45.489 websocket_thread_func: Select canceled.
00:15:45.489 P5M: o console nao atendeu o chamado da PSN (Canceled)
00:15:55.833 LauncherActivity aberta   <- pid novo: 12282 (era 10524)
```

`Select canceled` só sai do `cancelPsn`, que só o `onDestroy` chama. Ou seja: a
activity foi destruída no meio da furação, e o processo morreu logo depois. **O
que a destruiu não está escrito em lugar nenhum** — entre o "Foco de janela:
false" e o cancelamento há um segundo e meio de silêncio absoluto.

A segunda tentativa (00:16:03) morreu diferente: `session_create` esperou 40 s
por uma notificação de criação que a PSN nunca mandou. Isso é coerente com o
servidor da Sony ainda achando que a conta tem sessão remota aberta — a
tentativa anterior morreu com o processo, sem `session_fini`, então nada foi
apagado do lado de lá.

Não dá para consertar às cegas o que não se consegue ver. Foram acrescentadas as
marcas que faltavam:

- `onPause`, `onStop`, `onDestroy` e `finish()` no `VrStreamActivity`, com
  `isFinishing` e, no `finish()`, o nosso primeiro quadro da pilha — para o
  diário dizer **quem** mandou encerrar.
- todas as trocas de estado da sessão OpenXR pelo nome (`IDLE`, `READY`,
  `STOPPING`, `EXITING`, `LOSS_PENDING`), que antes eram completamente mudas:
  o runtime podia mandar a sessão para `EXITING`, o loop de quadro parava, e o
  diário não registrava uma linha.

E o `Estado -> console:` deixou de repetir a mesma linha de zeros a cada
segundo: agora só sai quando o estado muda. Com o controle parado ele estava
empurrando para fora do diário justamente o que se ia ler.

## Por que a vibração só funcionava no modo imersivo

Mesmo console, mesmos eventos, dois destinos diferentes.

O modo imersivo é código nosso e já usava o [Rumble], que entrega ao vibrador do
**gamepad**. O modo janela é o `StreamActivity` do chiaki, e ele faz o que faz
sentido num celular:

```kotlin
val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
```

Esse é o vibrador **do aparelho**. Num celular as duas coisas coincidem. Aqui o
aparelho é um Quest 3, que não tem vibrador nenhum — o comando saía e não chegava
a lugar algum, sem erro, sem log, sem nada para investigar.

O patch 0015 troca esse trecho pelo `Rumble`, e com ele vêm três coisas que o
caminho antigo também não fazia:

- `CombinedVibration` quando o controle tem dois motores. O DualSense tem, e o
  caminho antigo aciona só o primeiro.
- `VibrationAttributes` com uso de mídia. Sem atributo o sistema pode filtrar a
  vibração conforme as preferências de feedback do usuário — comando engolido em
  silêncio.
- descarte de pedido repetido. Durante vibração contínua os eventos chegam sem
  parar, e cada um deles é uma chamada de binder na thread que também entrega
  vídeo.

A escolha do controle saiu para `Rumble.gamepadDeviceId()`, com a mesma regra que
a activity imersiva já usava para os analógicos — nomeado ganha de anônimo, e os
controles Touch do headset entram anônimos, como `Device 0x...`. Os dois modos
precisavam concordar sobre qual controle é *o* controle, e agora concordam por
construção, e não por coincidência.

E o motor para junto com a tela: `stop()` no `onPause` e no `onDestroy`.

## O modo imersivo remoto rodou — e a queda foi na saída

Sete minutos de sessão remota em modo Imersivo (00:43:55 → 00:51:23), com
troca de nitidez em jogo, modo de ajuste entrando e saindo, e o console
respondendo. As marcas novas de ciclo de vida fizeram o serviço: o diário
mostra `IDLE → READY → SYNCHRONIZED → VISIBLE → FOCUSED` na abertura e
`VISIBLE → SYNCHRONIZED → STOPPING → IDLE` no fim, e o `onDestroy` com
`isFinishing=true` — encerramento normal, pedido pelo usuário.

A queda foi ao encerrar, e o tombstone diz tudo:

```
Abort message: 'incStrong() called on 0xb4... too many times, strong refs = 0'
  #05 android::RefBase::incStrong
  #06 android::MediaCodec::dequeueInputBuffer
  #07 AMediaCodec_dequeueInputBuffer
  #08 android_chiaki_video_decoder_video_sample
  #10 chiaki_video_receiver_av_packet
```

A thread de vídeo usou um `AMediaCodec` já destruído. Duas causas, uma minha e
uma do chiaki:

**Minha.** O `onDestroy` fazia `setSurface(null)` **antes** do `stop()`.
Desligar a superfície mata o decodificador, e o stream continuava entregando
quadros a ele. Pior: era redundante — o `dispose()` faz `join` na sessão (que
encerra a thread de vídeo) e só então o `sessionFree`, que já derruba o
decodificador. Fazer a mesma coisa mais cedo, à mão, só servia para criar a
corrida. Agora é `stop()` e `dispose()`, nessa ordem, e mais nada.

**Do chiaki.** O `kill_decoder` soltava o mutex e **só então** chamava
`AMediaCodec_delete`. Nessa fresta a thread de vídeo entrava em `video_sample`,
pegava o mutex, via `decoder->codec` ainda diferente de `NULL` — porque quem o
zera vem depois do `delete` — e chamava `dequeueInputBuffer` sobre memória
morta. E o ramo de erro (`Failed to get input buffer for shutting down Video
Decoder!`) nem esperava a thread de saída: ela seguia girando sobre
`decoder->codec` enquanto o `delete` acontecia. Os dois ramos agora esperam, e
o `delete` acontece debaixo do mutex.

Isso soma seis defeitos consertados no `video-decoder.c`, e nenhum é específico
do P5M.

### O que sobrou visível nesta sessão, e ainda não foi tratado

- `Cadencia: 301 de 600 passadas tinham quadro novo (50%)` — metade das passadas
  do compositor a 120 Hz não tinham quadro novo. Numa fonte de 60 fps isso é
  exatamente o esperado; o número só vira problema se cair abaixo disso.
- `color-transfer=6 (PQ): o pedido de mapeamento de tons foi ignorado, os
  brancos vao sair altos` — o perfil HDR está sendo pedido e o decodificador
  entrega PQ sem mapear. É uma decisão de cor pendente, não um defeito novo.
- `Takion dropping data` em rajadas, seguido de `Audio Output Buffer Overflow!`
  às dezenas. Rede de hotel, e o áudio transborda porque o vídeo atrasa e o
  buffer enche. Merece medição antes de qualquer conserto.

## dev.94: encerrar deixou de derrubar

Nenhum sinal fatal depois das 00:59:05, que ainda era dev.93. Tudo da dev.94
(01:05 em diante) saiu limpo, e o `=== ÚLTIMO CRASH ===` do diário continua
mostrando o de 00:16:42, da dev.90.

A prova está no encerramento da sessão imersiva das 01:21:

```
01:24:16.080  Foco de janela: false
01:24:16.218  Estado da sessao OpenXR: VISIBLE
01:24:17.527  Ciclo de vida: onPause (isFinishing=false)
01:24:17.538  Estado da sessao OpenXR: SYNCHRONIZED
01:24:17.538  Estado da sessao OpenXR: STOPPING
01:24:17.565  Estado da sessao OpenXR: IDLE
01:24:17.788  Ciclo de vida: onStop (isFinishing=false)
01:24:17.796  Ciclo de vida: onDestroy (isFinishing=true)
01:24:17.798  Sessao encerrada: Stopped
01:24:18.227  websocket_thread_func: Select canceled.
```

É a mesma sequência que na dev.93 terminava em `incStrong() ... strong refs = 0`.
Agora termina.

E a troca de modo Janela → Imersivo às 01:21, com uma sessão de onze minutos
atrás dela, passou sem o `SIGSEGV` em `ANativeWindow_release` que era certo
antes.

### A marca do `finish()` respondeu à pergunta para a qual foi feita

```
Encerrando a activity, pedido por
  VrStreamActivity$onCreate$2.invoke$lambda$2(VrStreamActivity.kt:323)
```

Linha 323 é o `QuitEvent -> finish()`. O app fechou porque a sessão avisou que
tinha parado, e não por algo invisível. Era exatamente a dúvida que ficou sem
resposta em 00:15:45, quando entre "Foco de janela: false" e o cancelamento não
havia uma linha sequer.

### A vibração no modo janela está ligada

```
01:10:03.333  Vibração: 2 motor(es) pelo gerenciador em 'DualSense Wireless Controller'
01:10:03.334  Vibração: comando entregue ao sistema sem erro
01:13:53.689  P5M: primeiro pacote de tipo 7 (RUMBLE), 3 bytes
```

O `Rumble` está anexado ao DualSense no modo janela — antes o comando ia para o
vibrador do headset, que não existe — e o console mandou rumble legado durante a
sessão. O caminho está completo; falta só a confirmação de quem estava com o
óculos na cabeça.

### O que continua aparecendo

`Takion dropping data` seguido de `Audio Output Buffer Overflow!` às dezenas, em
rajadas, nas duas sessões longas. Não derruba nada, mas é a única coisa nos logs
que corresponde a algo que se sente jogando. É o próximo a medir.

## Como a Sony serve vibração, e por que a nossa saiu estranha

Pesquisa e leitura do protocolo. O Remote Play manda vibração de **duas formas
mutuamente exclusivas**, e qual delas chega depende de como o cliente se anuncia
no `ControllerConnectionPayload` (`streamconnection.c:1129`):

| anúncio | o que o console manda |
|---|---|
| `DUALSHOCK4` | o console **reduz ele mesmo** a háptica do jogo a dois motores de massa excêntrica e manda o resultado como pacote de tipo 7 — três bytes, dois de intensidade |
| `DUALSENSE` | o console para de reduzir e manda a háptica **crua**, como uma segunda trilha de áudio PCM (`is_haptics`), mais os efeitos de gatilho adaptativo |

O `enable_dualsense` também faz o `ctrl` mandar `ENABLE_DUALSENSE_FEATURES`
(`ctrl.c:853`), que é o que liga os gatilhos do lado do console.

### O que dá para entregar num Quest

Nada da trilha crua. O DualSense recebe háptica por **relatório HID de saída**
pelo Bluetooth (report `0x31`), e o Android não abre esse caminho a aplicativo
nenhum — não há API pública, com ou sem permissão. É a mesma parede que faz o
chiaki-ng no desktop exigir USB para háptica de verdade, e que no Linux só se
resolve por um caminho de PipeWire a 3000 Hz que não existe aqui. Os gatilhos
adaptativos esbarram no mesmo relatório.

Ou seja: **anunciar-se como DualSense custa a redução boa e compra duas coisas
que não temos como entregar.** O que sobrava era o que este projeto fez enquanto
nenhum pacote de tipo 7 aparecia — pegar a envoltória da trilha e adivinhar a
intensidade. É exatamente por isso que vibra em momentos que o jogo não pediu:
a trilha de háptica carrega passos, chuva, cliques de menu e textura de
superfície junto com o que era para sacudir, e uma envoltória não sabe
distinguir. A mesma queixa existe no chiaki-ng de mesa ("enabling haptics makes
other feedback intrusive").

### O que mudou

- **Preferência no lançador**: `Vibração: clássica` (padrão) ou `háptica`.
  A clássica anuncia DualShock 4 e usa a redução do próprio console, feita por
  quem escreveu o jogo. A háptica mantém o comportamento anterior, para
  comparar. Aplicada nos dois caminhos de abertura — lista local e conexão
  remota — no `DisplayMode.startStream`, que é por onde ambos passam.
- **O efeito deixou de ser um disparo de um segundo.** O console não manda
  "vibre por tanto tempo": manda *a intensidade agora*, e manda de novo quando
  ela muda, inclusive o zero que encerra. Um disparo de duração fixa erra dos
  dois lados — para sozinho no meio de uma vibração longa, ou nunca para quando
  a intensidade oscila, que é a outra metade do "vibra onde não era pra
  vibrar". Agora é uma forma de onda que se repete no índice 0: o motor segura
  a intensidade até alguém trocá-la ou cancelar. Nenhum temporizador nosso,
  nenhuma chamada de binder por quadro.

## Dez bits no modo janela: funcionava, e estava errado num caso

Não há nada no código que force SDR na janela — a preferência é a mesma nos dois
modos, e a `SharpVideoView` já converte PQ. O que havia era uma combinação
silenciosamente quebrada:

```kotlin
val toneMap = quality.tenBit && quality.toneMapped
if((sharpen <= 0f && !toneMap) || owner == null)   // caminho direto
```

Com 10 bits ligados, sem conversão pedida e sem nitidez, a janela caía no
caminho direto: o decodificador escrevia PQ e BT.2020 numa superfície comum, que
o sistema trata como sRGB. Saía imagem — com as cores deslocadas, sem erro e sem
log, com a aparência de um ajuste de brilho ruim. No modo imersivo isso não
acontece porque lá o gamut é declarado ao compositor; uma janela do Android não
tem onde declarar nada.

Agora o shader entra sempre que os 10 bits estiverem ligados. A janela paga a
passada de GPU que o imersivo não paga — mas paga por cor certa, e não por nada.

## A Surface que ninguém podia estar escrevendo

A pendência mais antiga ainda aberta, do dia em que o primeiro quadro subiu. A
especificação do `XR_KHR_android_surface_swapchain` diz:

> "When the application receives XR_SESSION_STATE_STOPPING, it must ensure that
> no threads are writing to any of the Android surfaces created with this
> extension before calling xrEndSession."

Nós chamávamos o `xrEndSession` direto no `STOPPING`, com o MediaCodec ainda
entregando quadros na Surface. Efeito indefinido, por escrito.

A saída anotada na época era `session.setSurface(null)` antes de encerrar. Ela
está errada, e o próprio projeto descobriu por quê meses depois: derrubar a
Surface derruba o decodificador, e derrubar o decodificador com a thread de
vídeo ainda entregando quadros é literalmente o `SIGSEGV` em
`ANativeWindow_release` que custou o `patches/0014`. Consertar uma pendência
ressuscitando o defeito que a antecede não é conserto.

**O que a especificação pede não é que a Surface morra — é que ninguém escreva
nela.** São coisas diferentes, e a segunda é muito mais barata.

`patches/0017` acrescenta um portão. A thread de saída do decodificador entrega
o quadro assim:

```c
chiaki_mutex_lock(&decoder->render_mutex);
bool render = info.size != 0 && decoder->render_to_surface;
AMediaCodec_releaseOutputBuffer(decoder->codec, (size_t)status, render);
chiaki_mutex_unlock(&decoder->render_mutex);
```

O mutex não sincroniza nada com o codec — esse já tem o dele. Ele existe para
que `set_render_to_surface(false)`, ao **retornar**, seja uma afirmação sobre o
presente e não sobre o futuro: nenhuma escrita em curso, e nenhuma próxima. Sem
o mutex, fechar o portão só prometeria que a *próxima* entrega não renderiza, e
a que já estava dentro do `releaseOutputBuffer` continuaria valendo.

Com o portão fechado o quadro continua sendo consumido, só não vai para a
Surface. A fila do codec drena, e a sessão do console não percebe diferença.

### Onde fechar, e por que no `onPause`

O `xrEndSession` sai do `STOPPING`, que é lido na thread do frame loop. Fechar o
portão de lá exigiria uma chamada de volta ao Kotlin, numa thread não anexada à
JVM, no meio do encerramento — máquinas de estado demais para uma garantia de
ordenação.

O ciclo de vida do Android já dá a ordem de graça: o runtime só manda `STOPPING`
depois que a activity perde a visibilidade, e isso é sempre **depois** do
`onPause`. Então o portão fecha na primeira linha útil do `onPause`, e reabre no
`onResume`.

### E o `xrEndSession` que só acontecia por sorte

Ao investigar isto apareceu um segundo defeito, que estava escondido atrás de um
agendamento favorável. O `onPause` chama `xr.stop()`, que é `StopFrameLoop`:
marca `running_ = false` e dá `join` na thread. Só que **quem lê eventos é essa
thread**. Se o `STOPPING` chegasse um instante depois do join, ninguém o leria,
o `xrEndSession` nunca aconteceria, e a sessão seria destruída rodando — sem uma
linha no diário dizendo isso.

No log da dev.94 os três estados aparecem 11 ms depois do `onPause`, dentro da
última passada do loop. Funcionava por um fio.

Agora, depois do join, o `StopFrameLoop` drena os eventos ele mesmo por até
200 ms — o suficiente para o runtime, pouco o bastante para a thread principal —
e, se o `STOPPING` não vier, escreve no diário que não veio. A pergunta deixa de
depender de sorte, e a resposta passa a existir.

## O diário atravessava versões

O `filesDir` sobrevive a uma instalação por cima. Isso é proposital e vale caro:
é o que preserva o login da PSN entre builds, e foi por isso que a chave de
depuração passou a ser cacheada no Actions.

O efeito colateral não era proposital. O diário mora no `filesDir`, então depois
de cada sideload ele continuava lá, e o `Copiar tudo` passou a trazer linhas de
duas ou três versões misturadas — sem nada, a olho, que dissesse onde uma
terminava e a outra começava. O teto de 512 KB não ajuda nisso: ele apara por
tamanho, e sessões de versões diferentes são todas sessões.

O `Limpar diário` já existia na tela de diagnóstico e resolve — desde que alguém
lembre de apertá-lo. E é aí que a solução por botão falha: **esquecer uma vez
produz um diário que mente, e a única forma de descobrir é desconfiando dele.**
Um diário que precisa ser conferido contra a memória de quem testou não serve
para o que este projeto pede dele.

Então quem dispara a limpeza passou a ser o evento real: o `versionCode` mudou.
`Trace.rotateOnNewVersion` roda no `P5MApp.onCreate`, antes de qualquer linha
desta execução ser escrita — se rodasse depois, apagaria a abertura da própria
sessão que estava abrindo. Compara a versão instalada com a guardada, e quando
diferem apaga o diário, o último crash e os logs de sessão do chiaki.

O crash entra na limpeza pelo mesmo motivo do diário, e é o caso mais perigoso
dos três: um `=== ÚLTIMO CRASH ===` da versão passada, exibido sob o cabeçalho
da versão nova, é a mesma mentira — só que mais convincente, porque ninguém
duvida de um stack trace.

A primeira linha do diário novo diz o que foi descartado:

```
=== diario zerado na troca de versao: dev.94 (94) -> dev.95 (95) ===
```

Sem ela, uma limpeza automática seria indistinguível de um diário que nunca
gravou nada — e trocar um modo de mentir por outro não seria progresso.

Se a leitura da versão falhar, o rótulo é o mesmo em toda execução e nada é
apagado. A falha por omissão custa um diário grande; a falha pelo outro lado
custaria um diário apagado sem motivo, que é a única coisa aqui que não tem
volta.

## O meio do caminho estava no escuro

Pergunta do dono: o que ainda dá para otimizar — frame pacing, trocar para
Vulkan?

**Vulkan não compraria nada.** No modo imersivo com o caminho direto o app não
desenha coisa alguma: o MediaCodec escreve dentro do swapchain do compositor, e
o contexto EGL existe só porque o OpenXR exige um binding gráfico. Trocar de API
mudaria zero pixel. A única parte que usa GPU é o shader de conversão, uma
passada de tela cheia, e Vulkan não torna uma passada de tela cheia mais barata
de forma que se meça. O jeito de essa passada custar zero não é trocar de API: é
não precisar dela, que é o que já acontece no imersivo, onde o gamut é declarado
ao compositor. (Metal é da Apple e não existe no Horizon OS.)

**E as alavancas óbvias já estavam todas puxadas**: `KEY_LOW_LATENCY` mais a
extensão da Qualcomm, 120 Hz como múltiplo exato de 60, `SUSTAINED_HIGH` nos
dois domínios em vez de `BOOST`, `WIFI_MODE_FULL_LOW_LATENCY`,
`packet_loss_max`, IDR em falha de FEC, zero cópia no vídeo, e motion-to-photon
lido do próprio runtime.

O que sobrou é mais interessante do que qualquer uma delas: **do pacote ao
fóton, mediam-se as duas pontas e nada no meio.** Estatísticas de rede de um
lado, latência do compositor do outro; entre elas, decodificação e
enfileiramento sem um número sequer.

E frame pacing, especificamente, não era medido no caminho que o dono usa. A
linha `Cadencia: ... tinham quadro novo (50%)` mora no `tone_mapper.cpp` — ou
seja, só existe no caminho com shader. No caminho direto, que é o padrão,
ninguém contava nada.

Pior: mesmo essa linha responde à pergunta errada. Cinquenta por cento é
exatamente o esperado de 60 fps num painel de 120 Hz — ela confirma o trivial. A
pergunta que importa é se **cada quadro do console segurou o mesmo tempo**, ou
se às vezes segurou um a mais. É isso que se sente como judder, e uma média
perfeita não distingue os dois casos.

### O que passou a ser medido

`patches/0018`. Duas medidas por quadro, tiradas onde as duas pontas do trecho
existem — a entrada sabe quando o quadro foi entregue ao codec, a saída sabe
quando ele voltou, e ninguém mais vê as duas:

- **Decodificação**: da entrega ao codec até a saída, casada por PTS. O PTS anda
  de um em um, então o resto da divisão pelo anel de 256 endereça o carimbo
  direto. Um quadro cujo carimbo já foi sobrescrito aparece como "sem par", que
  é informação e não ruído.
- **Entrega**: o intervalo entre um quadro e o anterior, em quatro faixas em
  torno dos 16,7 ms — adiantado, no ritmo, um atraso, dois ou mais. As faixas
  não dependem da taxa do painel de propósito: a pergunta é se o console
  entregou no ritmo dele, e ela é a mesma a 72, 90 ou 120 Hz.

Uma linha a cada dez segundos. Dez é meio-termo deliberado: curto o bastante
para uma travada aparecer isolada numa janela em vez de diluída na média da
sessão, longo o bastante para o diário não virar uma linha por segundo.

```
Video 10s: 601 quadros | entrega 8.1/16.6/41.2 ms (min/med/max) | ritmo 3 adiantado, 592 no ritmo, 5 um atraso, 1 dois ou mais | decodificacao 4.2/6.8/19.5 ms | 0 sem par, 0 com o portao fechado
```

### O áudio, que nem chegava ao diário

`Audio Output Buffer Overflow!` é um `CHIAKI_LOGW`, e o `captureNativeLines`
pega do submódulo só os erros. O sintoma mais citado deste projeto era
justamente o que não chegava ao arquivo.

Agora é contado e resumido na mesma cadência de dez segundos, inclusive quando o
número é zero. A janela silenciosa é o que dá sentido às outras: sem ela não dá
para distinguir áudio bom de resumo que parou de sair.

E é a cadência comum que permite a única pergunta que importa aqui: quando o
áudio transborda, o vídeo daquela mesma janela tinha atrasado? A hipótese sempre
foi que sim. Agora ela é verificável — e nada foi consertado ainda, de
propósito.

## A volta, e o que a saída trouxe de bom

O projeto saiu daqui por um dia inteiro. A pergunta era vender, a resposta foi
não, e o caminho de volta trouxe duas coisas medidas que ficam.

O experimento foi isolar a camada de vídeo num repositório sem AGPL nenhuma e
alimentá-la com uma fonte sintética gerada dentro do próprio aparelho — 60 fps
com cadência de relógio, sem rede e sem console. Uma régua. O que ela mediu:

```
entrega        14,6 / 16,7 / 18,9 ms   (min/med/max)
decodificacao   3,6 /  4,4 /  6,8 ms
ritmo          600 de 600
```

**O caminho de vídeo deste projeto é bom sozinho.** Isso não era sabido: até
então todo número vinha misturado com rede e com o encoder do console.

### O que voltou como conserto

**O tick do diário saiu da thread principal.** Ele executa `logcat -d` a cada
dez segundos e lê o buffer inteiro, e isso estava acontecendo na thread
principal desde sempre. Com ele lá, uma janela de dez segundos teve seis
entregas atrasadas e pico de 39,9 ms; com ele fora, três janelas seguidas com
600 de 600 no ritmo e pico de 18,9 ms.

O número que prova não é o máximo ter caído — é o **mínimo ter subido**, de 4,4
para 14,6 ms. Um quadro entregue 4,4 ms depois do anterior, num ritmo de 16,7,
só pode existir se o anterior atrasou: é a fila se recompondo depois de um
bloqueio, dois quadros saindo colados. Adiantado e atrasado são o mesmo defeito
visto dos dois lados, e sumiram juntos.

E o máximo da decodificação caiu de 24,7 para 6,8 ms. **O decodificador nunca
esteve lento.** Estava esperando CPU, junto com todo o resto.

**Pausa da fonte deixou de contar como atraso.** Um intervalo acima de meio
segundo não é jitter: é a fonte parada — reconexão, pausa, console dormindo.
Contar como intervalo faz a média mentir e o máximo virar anedota. Agora conta à
parte, e o resumo diz `houve pausa da fonte`.

## A primeira sessão real medida, e o que ela encerrou

dev.105, 02/09, rede de casa, HEVC 10 bits a 25 Mbps. Primeira build verde em
três dias, e a primeira vez que a instrumentação rodou contra o console.

**Modo janela**

```
600 quadros | entrega  9.5/16.7/21.4 ms | 4 adiantado, 594 no ritmo, 2 um atraso, 0 dois ou mais | decodificacao 4.6/6.6/8.4 ms | 0 sem par
600 quadros | entrega 11.8/16.7/21.1 ms | 2 adiantado, 596 no ritmo, 2 um atraso, 0 dois ou mais | decodificacao 5.6/6.5/8.0 ms | 0 sem par
```

**Modo imersivo**

```
598 quadros | entrega 12.0/16.7/32.6 ms | 1 adiantado, 593 no ritmo, 2 um atraso, 2 dois ou mais | decodificacao 5.1/6.3/7.4 ms | 0 sem par
595 quadros | entrega  8.7/16.8/33.5 ms | 5 adiantado, 582 no ritmo, 3 um atraso, 5 dois ou mais | decodificacao 4.9/6.4/8.5 ms | 0 sem par
```

**600 quadros por janela são 60,0 fps exatos, e `0 sem par` em todas.** Nada
entra no decodificador e some.

### O item mais antigo da lista morreu

**Zero `Takion dropping data`. Zero `Audio Output Buffer Overflow!`. Zero
`Missing reference frame`.** E o contador novo confirma pelo lado positivo, com
a janela silenciosa que existe justamente para isso:

```
Audio 10s: 0 estouros de buffer
```

Em cinco janelas seguidas. O sintoma mais citado deste projeto **não acontece em
rede boa** — era a rede do hotel, e a hipótese de que o áudio transbordava
porque o vídeo atrasava fica sem caso para testar. O item sai da lista.

### O preço da rede, agora que dá para cobrá-lo

| | banco de ensaio | sessão real |
|---|---|---|
| entrega, máximo | 18,9 ms | 21–34 ms |
| decodificação, mediana | 4,4 ms | 6,4 ms |
| no ritmo | 600 de 600 | ~592 de 600 |

A rede custa uma cauda na entrega e cerca de **1% dos quadros fora do ritmo**.
É pouco, e agora é um número em vez de uma impressão.

A decodificação subiu de 4,4 para 6,4 ms, e isso **não é a rede**: o ensaio
decodifica H.264 de 8 bits e a sessão real decodifica HEVC de 10 bits. Dois
milissegundos é o preço do codec melhor, num orçamento de 16,7 — barato.

### O que o histórico de saídas revelou de brinde

A primeira coisa que a função nova achou não foi uma queda, foi um **ANR**:

```
Processo anterior (pid 11508) terminou em 12:04:20: ANR --
  Input dispatching timed out ... StreamActivity is not responding.
  Waited 5000ms for FocusEvent(hasFocus=true)
```

O modo janela travou cinco segundos na abertura. Isso aconteceu na dev.95, que
ainda tinha o `logcat -d` do diário na thread principal — o mesmo bloqueio que
custava 39,9 ms de pico de entrega, aqui custando um travamento inteiro. A
dev.105 já o tirou de lá; se o ANR não voltar, foi isso.

E as cinco quedas nativas apareceram como `motivo 2`, um número cru. O Horizon
OS relata `SIGSEGV` como `REASON_SIGNALED`, e não como `REASON_CRASH_NATIVE`, que
era o que a primeira versão da função esperava. Corrigido: agora sai
`QUEDA NATIVA (morto por sinal)`.

### O que ficou de lição para ler o `Video 10s:`

- **Adiantado indica bloqueio, não fonte irregular.** Se eles voltarem, procure
  o que está travando uma thread — não procure no decodificador.
- **A linha de base acima é a régua.** Uma sessão real que fique longe dela tem
  a diferença explicada pela rede e pelo console, porque o caminho daqui para
  dentro já foi medido sem nenhum dos dois.

## A nitidez virou adaptativa ao contraste

Pedido do dono, junto de outros quatro itens. Este é o que dava para fazer, e a
razão está no que a nitidez era antes.

Era uma máscara de desfoque: `c + (c - blur) * ganho`, com a média dos quatro
vizinhos da cruz. Realça tudo com a mesma força, e é aí que ela erra em dois
lugares opostos:

- **Borda de alto contraste** — legenda branca sobre fundo escuro, mira sobre o
  céu — ganha halo, a orla clara de um lado e escura do outro que denuncia o
  filtro.
- **Área quase lisa** ganha realce no ruído do compressor junto com o detalhe,
  porque para a máscara os dois são a mesma diferença.

O CAS resolve os dois com uma conta só: mede o contraste local e **reduz o
realce onde ele já é alto**.

```glsl
vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / max(mx, 1e-4), 0.0, 1.0));
vec3 peso = amp * -(1.0 / mix(8.0, 5.0, uSharpen));
```

Numa vizinhança lisa e clara `mn ≈ mx ≈ 1`, e `amp` vai a 1: realce cheio. Numa
borda dura `mn ≈ 0` e `mx ≈ 1`, e `amp` vai a 0: não mexe. A raiz suaviza a
transição, para o filtro não ligar e desligar de um pixel para o outro e criar
uma borda que a imagem não tinha.

**O custo é o mesmo.** São os mesmos cinco toques de textura de antes — centro e
os quatro vizinhos da cruz. Mudou o peso, não o número de amostras. Num caminho
que já paga uma passada de GPU, trocar a conta sai de graça.

### Onde isto vale, e onde não

Só no caminho com shader: modo janela, e imersivo com 10 bits. **No caminho
direto não há shader nosso** — quem faz nitidez lá é o MQSR do compositor, de
graça e melhor. Os degraus 4 e 5 continuam zero na tabela porque ali não há o
que intensificar.

### A escala mudou de significado

`SHARPEN_AMOUNT` era o ganho da máscara, sem teto natural, e o degrau forte
valia 1,10. Agora é a dureza do CAS entre 0 e 1, e acima de 1 o shader satura.
O forte passou a ser exatamente 1,0 — mesma intenção, escala honesta.

### O que ficou de fora, e por quê

Quatro dos cinco itens pedidos não entram, e três deles pela mesma razão:
**gatilhos adaptativos, LEDs e intensidade de háptica crua dependem de mandar um
relatório HID de saída ao DualSense**, e o Android não abre esse caminho a
aplicativo nenhum. O diário já mostra o console mandando os efeitos de gatilho
(`primeiro pacote de tipo 11`) e nós sem onde entregar.

A intensidade seguir a configuração da PlayStation tem uma sutileza que muda a
resposta: no modo **clássica** o próprio console reduz antes de mandar, então a
preferência do usuário já vem aplicada na origem. É o modo háptica que a ignora,
porque lá o ganho é nosso. Não é recurso a implementar; é mais um argumento a
favor da clássica.

E o SDL 3 para sensores seria peça a mais pelo mesmo resultado: giroscópio e
acelerômetro do controle já saem pela `InputDevice.getSensorManager()` do
próprio Android desde a API 31, e o Quest 3 é API 34.

## Sete minutos de jogo, e o crash apareceu com o diário ligado

dev.108, sessão de verdade — o diário mostra R2, analógicos, botão A. Sete
minutos em janela, depois uma tentativa em imersivo.

**A instrumentação pagou por si no primeiro dia.** O processo caiu às 14:07:57,
e o processo seguinte escreveu no diário por quê:

```
Processo anterior (pid 28377) terminou em 2026-09-02 14:07:58: QUEDA NATIVA (morto por sinal)
```

Antes disso, essa linha não existia e o diagnóstico dizia "nenhum crash
registrado".

### A hipótese favorita morreu

As quedas anteriores tinham aparecido com a vibração em **háptica**, e o
suspeito era a trilha de áudio de háptica do `patches/0009`. **Estas duas foram
em `clássica`**, que não abre essa trilha. A hipótese cai.

O que sobrou é a assinatura, e ela é consistente demais para ser coincidência:
`pc = 0`, três quadros dentro de `libchiaki-jni.so` sobre `__pthread_start`, com
o mesmo espaçamento entre eles em todas as ocorrências. É o mesmo caminho de
código, sempre. E `pc = 0` não é ler ponteiro nulo — é **chamar** um.

Uma das duas veio meio segundo depois de abrir o stream imersivo; a outra, sete
minutos adentro, logo depois da pior janela de medição da sessão: `155
adiantado, 275 no ritmo, 150 um atraso`. Vale registrar a coincidência sem
transformá-la em causa.

### Por isso o app passou a resolver a própria pilha

O tombstone do sistema traz offsets. Offset vira nome com o `.so` não removido e
um `addr2line` — ou seja, com um PC, que este projeto não tem. Então o app faz
isso sozinho, na hora da queda, com `dladdr`.

A precisão é menor que a de um `addr2line` com símbolos completos: para uma
função `static`, o `dladdr` devolve a exportada anterior. Mas a diferença entre
`offset 0x1c2000` e `perto de chiaki_...` é a diferença entre não saber e saber
por onde começar.

Detalhes que a implementação precisou respeitar: pilha própria via
`sigaltstack`, porque se a queda for estouro de pilha não há espaço para o
handler rodar; escrita com `write()` cru, sem `printf`, porque dentro de um
handler quase nada é seguro de chamar; e encadeamento no handler anterior no
fim, para o sistema continuar gerando o tombstone — somar as duas fontes, e não
trocar uma pela outra.

## A linha de saúde nasceu com dois defeitos, e os dois eram reveladores

**`gpu 3526%`**. Os contadores do `XR_META_performance_metrics` já vêm em
porcentagem, e eu multipliquei por cem. O número absurdo não engana ninguém, mas
é inútil — e o mesmo erro estava no painel de ajuste desde sempre, invisível
porque ninguém fica olhando o painel.

**E não havia térmico nenhum.** A extensão de temperatura do OpenXR não existe
neste runtime: o `xrGetInstanceProcAddr(xrThermalGetTemperatureTrendEXT)` falha
na abertura, e já falhava — a linha estava lá no diário o tempo todo, e ninguém
tinha ligado uma coisa à outra. A saúde saía sem justamente o número que ela foi
criada para trazer.

Trocado pelo `getThermalHeadroom` do Android, que existe desde a API 30 e não
depende de extensão. A escala é ao contrário: **1.0 é o ponto de
estrangulamento**, não a folga. Vai escrito por extenso na linha, porque um
número que significa o oposto do que o nome sugere é pior do que não ter número.

### O que a saúde já disse, mesmo torta

- `app 0.7–1.8 ms`, `compositor 1.5–1.8 ms` — o trabalho de GPU é minúsculo e
  **estável a sessão inteira**. Não é carga nossa que cresce.
- `cabeca-foton 17.6–19.4 ms` — estável também.
- `descartados` foi de 4 a 30 em sete minutos. Poucos, mas subindo.

Ou seja: o que degrada não está no nosso trabalho de GPU nem na latência do
compositor. Sobra rede, térmico, ou algo bloqueando uma thread.

## Uma perda de rede, e o áudio junto

Um único evento, às 14:02:44, dentro de um segundo:

```
Missing reference frame 2908 ... 2911 ... 2914 ... 2916
Takion dropping data with seq num 0x1ed0e1e3
Audio Output Buffer Overflow!  (x14)
```

Vídeo e áudio quebrando **no mesmo segundo**. Não é o áudio transbordando
*porque* o vídeo atrasou: é um estouro de perda na rede atingindo os dois ao
mesmo tempo. Uma ocorrência em sete minutos, com rede de casa.
