# Continuidade: reconexao, menus e redimensionamento

Base: dev.170 (`0d41306`), testada pelo dono. Trabalho na branch
`claude/quest3-chiaki-app-pntqrn`. O commit local `75455c6` ja habilitava a
conferencia C++ no Windows e foi enviado junto desta rodada. Repositorio publico intacto.

## Build entregue

**dev.173 (`82d815bc27f9d741cd27a7e58f9c6d76c38e0dd8`), CI verde.**
Execucao do CI (repositorio de build privado)
e APK versionado (repositorio de build privado).
Inclui `5648de9` (correcoes/menus), `5f23cb5` (resize preserva views) e
`82d815b` (comparacao da superficie pelo destino nativo).

APK baixado e verificado: 24.517.199 bytes, versao interna 0.1.0-dev.173,
CRC do ZIP valido, classes.dex e 5 bibliotecas arm64 presentes. SHA-256:
`10c0f1ce736411b90d0981e219548ecde48e3bf65d1183dc280e25f2df4fd2a1`.
Corpo do release e run confirmam o mesmo commit. Ha assets antigos psmeta.apk
no release; usar o p5m versionado acima. Nenhum asset antigo foi removido.

Passaram `tools/conferir.py` (25 patches), os cinco C++ da conferencia local,
os tres grupos de regressao de input e o build Android completo. Um harness C
local compilou o ramo real de troca da janela com APIs simuladas e conferiu
10.000 chamadas para o mesmo destino sem rebind/vazamento, troca real, recusa
do MediaCodec e janela nativa invalida. Esse harness nao testa o driver nem
a funcao completa de criacao/destruicao do codec. A build ainda nao foi
testada no headset. Fechamento documental comitado localmente para evitar
outra build identica; o codigo desta rodada ja foi enviado ao privado.

## Relatos e evidencias

- DualSense desligado/religado perdia parte dos comandos ate reabrir o jogo.
- Circle nao retornava da lista de consoles ao lancador depois do imersivo.
- Botoes largos e ausencia de hierarquia; pedido de menus para controle.
- Redimensionar pelo Quest faz o app parar de responder. Ainda nao foi
  informado se ocorre tambem antes do jogo; nao afirmar causa unica comprovada.
- 3D + janela nao deve ser selecionavel. O dono pediu revisar combinacoes afins.

Diario lido do servidor local do app, snapshot privado fora do repo.
Cabecalho confirma `0.1.0-dev.170`, desde 09/09 16:49 ate 10/09 00:55.
Sem FATAL EXCEPTION/ANR explicito no snapshot. A maioria das janelas de dez
segundos tem 935 voltas de haptica; ha picos isolados de envio HID (53,582 ms
na janela, 26,977 ms em trecho imersivo). Nao mudar o periodo de 10.698.000 ns
com base nisso. A entrega sensorial ainda precisa ser comparada pelo dono.

## O que mudou

1. `GamepadMonitor` acompanha IDs Android via InputManager, conserva o HID
   nomeado atual e reavalia ao reconectar. O StreamInput dos dois modos usa
   essa selecao; os Touch nao sobrescrevem os eixos do gamepad. Patch 0024
   limpa botoes/eixos/gatilhos/toque e publica estado neutro ao desconectar ou
   pausar. Gatilhos que chegam apenas como KeyEvent agora publicam seu estado
   imediatamente. VrStreamActivity usa a mesma selecao e cancela acordes,
   Circle segurado e clique de touchpad pendentes.
2. MenuController trata Circle como uma acao de voltar no soltar, exige
   pressionamento anterior e respeita cancelamento. Reinstalacao ocorre no
   post-resume (depois de AppCompat); perder foco cancela repeticao e Circle.
3. Lancador limitado a 960 dp, categorias Play/Picture/3D/Controller/Tools,
   uma pagina por vez. Barra lateral em janela larga; categorias horizontais
   na estreita. Play tem botao compacto destacado, ajustes tem rotulo e valor.
   Ajuda fixa em janela alta, com rolagem em janela baixa. Categoria sobrevive
   a recriacao/configuracao; Circle nas categorias volta a Play. Resize de
   largura/altura reusa as views; so refaz a arvore ao mudar densidade/fonte.
4. Patch 0025: resize com a mesma ANativeWindow nao reconfigura o
   MediaCodec; uma troca recusada preserva o destino anterior. Em pausa,
   para e aguarda a haptica, pausa a sessao e SO ENTAO
   notifica os observadores que liberam a SurfaceTexture. Antes havia leitura
   de sessao liberada e liberacao do consumidor antes do produtor. Reconectar
   manualmente tambem para a haptica antes do shutdown. A pausa silencia
   rumble sem destruir o relogio que precisara voltar; destroy ainda o para.
   WindowVideo limpa a referencia a Surface liberada; SharpVideoView ignora
   callback atrasado de uma superficie invalida/substituida.
5. Manifesto cobre smallestScreenSize nos menus principais e stream, evitando
   recriacao desnecessaria ao mudar proporcao; lancador recalcula seu layout.
   Captura final de logcat saiu da thread principal. UiWatchdog registra a
   pilha principal se ficar sem responder por pelo menos quatro segundos,
   usando a thread de diagnostico existente. Procurar `UI stalled` no diario.
6. Regras de configuracao:
   - 3D implica imersivo inclusive para preferencias antigas; janela fica
     bloqueada no menu enquanto 3D estiver ligado.
   - Janela so oferece off/light/medium/strong. Automatico antigo resolve
     para medium na janela, preservando a escolha para o imersivo.
   - Caminho de video da janela mostra a decisao real (nitidez/10-bit), sem
     oferecer previsao de quadros que nao existe ali.
   - Audio da janela mostra System e o acorde imersivo fica oculto.
   - 10-bit implica shader tambem no imersivo, evitando PQ direto sem tone
     mapping. 3D/10-bit nao oferecem caminho direto; previsao segue opcional.

## Validacao e limites

A tentativa dev.172 falhou na compilacao Kotlin: `Surface.generationId`
nao pertence ao SDK publico. O patch 0025 agora compara o destino no C,
equilibrando a referencia adquirida por `ANativeWindow_fromSurface`. A
[implementacao AOSP](https://android.googlesource.com/platform/frameworks/base/+/d1eb5f5d345d/native/android/native_window_jni.cpp)
devolve a janela nativa com uma referencia adquirida. Nao usar reflexao para
restaurar o campo oculto. Logs completos puderam ser baixados pela API do
Actions nesta sessao, sem encaminhar Authorization ao host do redirecionamento;
o endpoint de annotations devolveu 403, mas o de logs funcionou.

`tools/test_input.py` compila StreamInput apos todos os patches e
ControllerState real, simulando InputManager/KeyEvent/MotionEvent. Confere
reconexao com novo ID, neutralizacao de estados, bloqueio de eixos Touch,
gatilhos por tecla, desconexao durante pausa e registro idempotente. Usa JDK,
classpath do compilador Kotlin, stdlib e pasta temporaria nos mesmos parametros
do `tools/test_haptics.py`. Nao simula radio, UI Android ou compositor Quest.

Conferencias obrigatorias: `tools/conferir.py`, `tools/compilar_nativo.py`,
compilacao Android no CI e verificacao do APK. Nao confundir CI verde com
validacao dos gestos ou do redimensionamento no headset.

Roteiro de hardware: desligar/religar DS nos dois modos com gatilho/analogico
pressionado; testar D-pad/L2/R2/L3/R3/touchpad e acorde; sair do imersivo e usar
Circle na lista; alternar janelas larga/estreita/baixa nos menus e no jogo,
inclusive shader/direct e pausas; conferir restricoes de 3D/10-bit/automatico.
Se ainda congelar, servir o diario apos recuperar o app e procurar `UI stalled`.

## Fora desta rodada

Nao se implementou a pesquisa de outros motores 2D->3D. O shader existente
tem adaptacao propria inspirada no CAS, com cinco amostras; nao e uma
integracao do FidelityFX SDK nem uma equivalencia verificada com CAS oficial.
O compositor continua com seus filtros independentes. Preservar o swapchain
por olho e nao reintroduzir MQSR explicito ou relogio adaptativo da haptica.
