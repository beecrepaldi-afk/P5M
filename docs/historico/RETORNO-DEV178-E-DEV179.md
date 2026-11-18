# Retorno da dev.178 e da dev.179: a causa do atraso da vibracao

Testes no Quest em 11/09. Diarios dos processos 24071 (dev.178, 02:04..02:10)
e o da dev.179 (02:27..02:32).

## dev.178: o atraso nasce na abertura da sessao

Fluxo do dono: DualSense desligado, build instalada, app aberto, controle
ligado e jogo iniciado em janela. **A vibracao ja veio com atraso.** Ele
desligou e religou o controle as 02:06:36 e **o atraso sumiu ate o fim**,
inclusive na sessao imersiva seguinte.

Isso derrubou a hipotese do enlace Bluetooth que "relaxa depois de uma
sessao": o enlace era novo e o atraso estava la. A comparacao entre os dois
casos, pelo diario:

| | Controle ligado antes (atraso) | Controle religado na sessao (sem atraso) |
|---|---|---|
| Driver do kernel configura o controle ao conectar | sim | sim |
| **App le o relatorio de calibragem (feature 0x05)** | **sim, 02:05:49** | **nao** |
| Potencia cheia (0x31), reenviada a cada 2 s | sim | sim |

A unica acao nossa presente num caso e ausente no outro era a leitura da
calibragem em `DualSenseHid.conectar`. Ela tambem explicava o sintoma antigo:
cada sessao nova refazia a leitura, e o atraso voltava.

Medidas da mesma rodada:

- `Haptics timing`: histograma de envio espalhado, pico unico em ~2,5 ms, igual
  antes e depois de religar. O cronometro de envio nao enxerga o enlace.
- `Input timing`, so no imersivo e so no estado bom: pente em multiplos de
  ~1,25 ms, que e o enlace em modo ativo. Nao houve medida no estado com
  atraso, que aconteceu em janela.
- Retorno ao menu: sem janela duplicada. Durante o imersivo, `appTasks` so
  listou a tarefa do stream (`#6326`, n=1); a do menu (`#6324`, lancador e
  menu) existia e era invisivel ali. O DualSense nao voltou a navegar.

## dev.179: sem a leitura, sem atraso

A dev.179 tirou a leitura da calibragem ao conectar e ao reencontrar o
controle. Com o controle ligado antes da sessao, **nao houve mais atraso**, e o
dono confirmou em varias sessoes seguidas, em janela e imersivo, sem religar o
controle. O driver `hid-playstation` do kernel ja le a calibragem quando o
controle conecta; repeti-la pelo `BluetoothHidHost` deixava a entrega da
trilha 0x32 segundos atrasada, por um mecanismo que nao investigamos.
`acordarModoEstendido` ficou no codigo, sem chamada.

O "fato pago" do `AGENTS.md` que chamava essa leitura de indispensavel vinha
da dev.141 e da lightbar. Para a haptica, era a causa do defeito.

Retorno ao menu: a dev.179 grava o numero da tarefa do menu na abertura e o
traz com `moveTaskToFront` (`REORDER_TASKS`). O diario mostra
`moved recorded task 6338 to front`, sem erro, e mesmo assim o DualSense nao
navega no menu: o Horizon nao devolve o foco do gamepad ao painel 2D ao sair
do imersivo. Continua como problema conhecido.

## Publicacao

O estado da dev.179 virou a `v0.2.0-beta` do P5M em 11/09.
