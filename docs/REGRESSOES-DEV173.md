# Investigacao do teste da dev.173

Diario servido em 10/09/2026, processo 5445, versao confirmada pela rotacao
170 -> 173. O dono relata muitos bugs; o detalhamento visual e dos comandos
ainda foi solicitado. Nao considerar a dev.173 validada no Quest.

## Evidencias

- Primeira sessao imersiva abriu 13:20:28 e foi encerrada pelo painel 13:21:05.
  A sessao nativa registrou `Stopped`. Nao ha prova de que o PS5 tenha confirmado
  o pacote de desconexao.
- A tentativa de 13:21:51 recebeu `Ctrl failed to connect`; as seguintes
  receberam `Remote Play on Console is already in use`.
- Na janela, especialmente entre 13:24:09 e 13:24:27, repetem-se recusas do
  console e inicializacoes completas do HID. O codigo ligava a haptica ate
  para o estado Quit porque testava apenas a existencia do objeto Session.
- O snapshot nao registra `UI stalled`, ANR ou nova queda nativa. Isto nao
  descarta os problemas percebidos pelo dono. A morte por CPU do processo
  1017 e de 09/09, anterior a instalacao da dev.173; nao e desta build.
- Nao ha `Gamepad connection changed` nem `Circle back` nesse snapshot.
  Nao inferir que reconexao do controle/Circle foram testados com sucesso.

## Correcao delimitada: patch 0026

1. StreamViewModel.onCleared chamava `_session?.shutdown()`; `_session` nunca
   era preenchido. Agora encerra a propriedade `session` efetivamente usada.
   A pausa local normalmente ja a encerrava, mas esse fallback era inoperante,
   inclusive no caminho PSN que sobrevive a pausa enquanto faz NAT traversal.
2. StreamSession desassocia a sessao antes de stop/dispose. Eventos sao
   entregues na thread principal apenas se ainda pertencem a mesma instancia.
   A verificacao acontece ao executar a fila, impedindo Quit/PIN/rumble antigos
   de atingir uma reconexao. Inclui erro da thread de NAT. Mantem cancel/join.
   Na pausa, o Quit produzido pelo proprio stop tambem podia substituir Idle;
   a activity trata esse Quit sem erro como pedido de finish. A correcao impede
   esse encerramento tardio, sem afirmar que era o unico defeito no resize.
3. Haptica da janela inicia somente em Connected e para nos demais estados.
   Nao inicializa Bluetooth para uma conexao recusada. Cadencia, ganho, filas
   e algoritmo da trilha nao mudaram.
4. Diario registra pausa/retomada da janela, pedido de reconexao e motivo de
   encerramento. Essas linhas distinguirao repeticao por ciclo de vida de
   pedidos explicitos; o snapshot antigo nao permite provar essa origem.

Nao afirmar que isto resolve todas as recusas do PS5: a primeira ocorreu
depois do imersivo, cujo encerramento nao passa pelo StreamViewModel. Tambem
nao afirmar que resolveu menus/resize sem nova evidencia do Quest.

## Verificacao

- tools/test_session.py compila StreamSession e StreamViewModel reais com os
  26 patches, simulando JNI, LiveData e a fila principal. Verifica eventos
  atuais, rejeicao de eventos antigos ja enfileirados ou em voo, encerramento
  pelo ViewModel, idempotencia e pausa sem Quit posterior sobrepondo Idle.
- tools/test_input.py continua passando os tres grupos de reconexao/entrada.
- Controle negativo: o mesmo teste de sessao falha com o StreamSession da
  dev.173 ao conferir que a reconexao ainda esta Connecting depois de drenar
  eventos antigos. Com o patch 0026, os tres grupos passam.
- tools/conferir.py: 26 patches aplicam; contratos JNI, manifesto e strings OK.
- Isso nao simula Activity/Dialog/AppCompat, Bluetooth, MediaCodec ou PS5.

## Proximo teste

Repetir fechar/reabrir nos dois modos e servir o diario. Registrar o que o
usuario viu quando ocorreu o bug (tela, comando, resultado). Priorizar a
primeira falha da sequencia, antes das tentativas seguintes. Se o console
recusar, o proximo diario mostrara se cada tentativa nasceu de resume ou de
um pedido de reconexao. Menus, Circle e resize continuam pendentes.

## Publicacao

- dev.174, commit e4b834c297cfe0ff1a02abb89429e61e04f56209.
- CI verde: repositorio de build privado
- APK: repositorio de build privado
- 24.521.291 bytes; SHA-256
  `5e214fce4e3073a1370c733bf0dbf8dd042f8303418c394915990aeb5e6bd085`.
- Download verificado: versao interna, ZIP/CRC, classes.dex e 5 libs arm64.
- Registro final e refinamento do teste ficam comitados localmente, sem push
  adicional para gerar um APK igual. Nenhuma validacao nova no Quest recebida.
