# Retorno da dev.177 e medidas seguintes

Teste no Quest em 11/09, diario do processo 17598, sessoes entre 01:36 e
01:42, alternando imersivo e janela. No meio da sessao das 01:39 o dono
desligou e religou o Bluetooth do Quest; o DualSense desliga junto quando o
Bluetooth do Quest cai.

## Janela duplicada: nao corrigida

Encerrar a sessao continuou abrindo outro painel do app. O diario mostra por
que: quase todo retorno registrou `no other P5M task among 0, launching it in
its own task`. Nao havia tarefa para trazer, e o ultimo recurso -- lancar com
`NEW_TASK | CLEAR_TOP | SINGLE_TOP` -- criou o painel novo. Nos dois retornos
que acharam tarefa, ela era a `6291`, so com o `LauncherActivity`, sem o menu.

A mensagem da dev.177 contava so as tarefas alem da do stream, e por isso nao
separa "o sistema nao lista as tarefas dos paineis" de "o menu mora na mesma
tarefa do stream". Era um caminho com duas explicacoes e uma mensagem so.

Correcao desta build: **nunca iniciar activity**. Com tarefa, `moveToFront()`;
sem tarefa, encerrar e deixar o painel de baixo assumir, como a dev.175, a
ultima que nao duplicava. O custo conhecido e o foco do controle as vezes nao
voltar ao menu. Cada retorno agora lista todas as tarefas do app, com base,
topo, numero de activities e a do stream marcada, e a lista vazia tem texto
proprio. O proximo diario diz onde o menu mora.

## Histograma de envio: espalhado, e isso nao decide

Em todos os intervalos com envio, a distribuicao tem um pico so, entre 2,25 e
2,75 ms, cauda ate 5..10 ms e um caso isolado de 31 ms. Nenhum agrupamento em
multiplos de um valor fixo.

A comparacao que importa veio de graca: **antes e depois de religar o
Bluetooth os histogramas sao praticamente iguais**. Religar e o que cura o
atraso, e o tempo de envio nao muda com isso. O cronometro de envio mede so
quanto o `sendData` leva para entregar o relatorio a pilha Bluetooth; fila da
pilha e radio ficam depois dele. Espalhado quer dizer que ele nao enxerga o
enlace, e nao que o enlace esteja bem.

Com isso ha dois suspeitos que ele nao separa: o enlace falando so em janelas
(sniff, em BR/EDR) e uma fila crescendo dentro da pilha Bluetooth do Quest,
que aceitaria na hora e sairia mais devagar do que entra. Religar o Bluetooth
esvazia os dois.

## Medida nova: chegada da entrada

`Input timing:` a cada dez segundos, no imersivo, com o intervalo entre
amostras dos analogicos do controle no mesmo formato de histograma. A entrada
vem pelo mesmo enlace no sentido contrario, e o horario de cada amostra e o do
kernel quando a pilha a entregou. Enlace ativo junta os intervalos no periodo
de relatorio do controle; enlace em janelas os separa em rajadas, um monte
perto de zero e outro perto da janela.

Limites: analogico parado nao gera evento, entao a medida so vale com um
analogico em movimento, e o que passar de 50 ms e mao parada. So o modo
imersivo mede. A rota `/timing` do diario servido traz as duas linhas de tempo
juntas.

## Ponte HID depois de religar o Bluetooth

A sessao das 01:39 perdeu a vibracao ate o fim: 775 envios recusados. Ao
religar, o sistema reconectou o proxy do perfil sozinho as 01:39:37 e a
escolha do controle achou zero dispositivos -- o controle so voltou as
01:39:40 -- e ninguem procurou outra vez. Agora uma recusa faz a ponte
procurar o controle de novo, no maximo uma vez por segundo, e reler a
calibragem ao acha-lo.

## Verificacao local

- `tools/test_stream_return.py`: nunca lanca; move a tarefa certa; lista todas
  as tarefas com a do stream marcada; lista vazia e recusa com textos proprios.
- `tools/test_haptics.py`: doze casos, inclusive o histograma de envio e a
  procura pelo controle a cada recusa.
- `tools/test_input_arrival.py`: enlace ativo numa faixa so, enlace em janelas
  de 11,25 ms em dois montes, amostra fora de ordem, mao parada e ausencia de
  amostra.
- `tools/test_menu.py`, `tools/test_input.py`, `tools/test_session.py` e
  `tools/conferir.py` continuam passando.

## Teste no Quest

- Encerrar varias sessoes no imersivo e contar os paineis. Ler o `tasks:` de
  cada `Returning control to`.
- Jogar com um analogico em movimento. Comparar `Input timing` numa sessao com
  atraso e numa logo depois de religar o controle.
- Religar o Bluetooth no meio de uma sessao e procurar `DualSense found again`.
