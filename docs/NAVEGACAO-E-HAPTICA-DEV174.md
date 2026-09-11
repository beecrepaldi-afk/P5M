# Retorno da dev.174 e correcao seguinte

O dono confirmou: resize funciona; no lancador o controle so seleciona a
categoria Play, sem sair dela nem ativar o botao de jogar; vibracao atrasa
nos dois modos. Diario 173->174, processo 19743, 10/09 18:35..18:40.

## Navegacao

O analogico gerava teclas direcionais chamando diretamente Window.Callback.
A busca direcional padrao do Android ocorre posteriormente no ViewRootImpl,
na etapa performFocusNavigation; chamar apenas o callback pula essa etapa.
Referencia primaria consultada: [ViewRootImpl do AOSP](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/view/ViewRootImpl.java).

MenuController agora usa focusSearch e requestFocusFromTouch para mover o
foco, tanto por analogico quanto D-pad. Cross confirma por performClick ao
soltar o mesmo item, sem repeticao ao segurar; cancela ao perder foco ou trocar
de controle. Containers com acoes descendentes nao prendem o foco inicial.
Linhas somente informativas continuam podendo receber foco para mostrar ajuda.
GamepadMonitor filtra os eixos dos Touch quando ha um HID nomeado: seus zeros
nao interrompem a repeticao do DualSense. Layout/resize nao foram alterados.

## Haptica

No imersivo, predominam 935 voltas/10 s; um intervalo registrou 931 voltas,
7 deadlines perdidos e pico de envio de 27.405 us. Nas janelas medidas, 935/936
voltas, zero deadlines perdidos e picos de envio de 3.765..6.639 us. A fila
Kotlin atingiu 192 bytes; media maxima de 110 bytes. Essas medidas NAO medem
o instante em que a bobina vibra nem provam ausencia de atraso no Bluetooth.

Correcao delimitada: depois de estacionado por silencio, um pacote curto de
60 bytes esperava duas voltas vazias para completar o relatorio de 64 bytes.
Agora sai no ciclo de chegada, completando apenas esse inicio com zeros.
Isso retira aproximadamente 21 ms de espera nesse caso; nao promete retirar
21 ms de toda vibracao, nem solucionar toda a latencia percebida. Sao dois
pares estereo de preenchimento (0,67 ms) no inicio curto; o fluxo seguinte
retoma o enquadramento normal, sem preencher todos os pacotes continuamente.

Cadencia fixa 10.698.000 ns, ganho, teto da fila e periodo de repouso mantidos.
O diario passa a contar `early onsets` para identificar quando o caminho novo
foi usado. Os dois modos compartilham essa alteracao.

## Testes e limites

- tools/test_menu.py: codigo real com Android/arvore de foco simulados; sair de
  Play, entrar no conteudo, Cross unico, cancelamento, Circle e eixos Touch.
  Nao testa a geometria real dos menus nem a distribuicao de eventos do Quest.
- tools/test_haptics.py: nove casos, incluindo pacote curto enviado no ciclo
  de chegada apos repouso e continuidade sem padding repetido por pacote.
  Nao simula o firmware/radio. A sensacao no Quest continua pendente.
- Patches externos permanecem 26; nenhum codigo de resize alterado.
- Controle negativo com fontes da dev.174: teste de menu falha com foco preso;
  teste de haptica falha ao exigir o pulso curto no ciclo da chegada apos idle.
  As mesmas verificacoes passam com as fontes corrigidas.

Proximo teste: D-pad/analogico entre categorias, direita para o conteudo,
Cross no botao Play; depois pulsos isolados apos silencio e varios pulos
seguidos, separadamente em janela e imersivo. Servir novo diario.

## Publicacao verificada

- dev.175, commit `157c1b76d80e940099f5ed9b1c231918948129d8`.
- CI verde (repositorio de build privado).
- APK (repositorio de build privado):
  24.521.295 bytes; SHA-256
  `dae44db0ab6cd1e330533745a409f9fd82e97ed44586177124631c46c3a78dc5`.
- Download conferido: versao interna, ZIP/CRC, classes.dex, cinco libs arm64.
- Fechamento documental comitado localmente para evitar APK identico.
