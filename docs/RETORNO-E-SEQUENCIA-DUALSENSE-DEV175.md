# Retorno da dev.175 e correcao seguinte

Teste no Quest: a navegacao pelos menus passou a funcionar. Depois de encerrar
uma sessao, porem, a lista de consoles reaparecia sem aceitar D-pad, Cross ou
Circle do DualSense. A vibracao continuou funcionando durante todo o teste;
o defeito era atraso nos dois modos. Desligar e religar o controle retirou o
atraso uma vez. Depois de abrir e fechar varias sessoes, ele voltou.

Diario da dev.175: processo 28430, seis sessoes entre 10/09 23:55 e 11/09
00:04. O transporte nao recusou relatorios. Os intervalos medidos ficaram em
geral em 928..936 voltas por dez segundos, e a fila Kotlin continuou limitada
a 192 bytes. Portanto o diario nao mostra uma fila crescente no produtor nem
uma perda da vibracao; mostra que o estado que sobrevive entre sessoes fica
depois do ponto observavel pelo app.

## Sequencia Bluetooth

Cada `DualSenseHaptics` comecava seus contadores em zero. `DualSenseHid`
mantinha outro contador, tambem novo por instancia, para os relatorios 0x31 de
potencia. Ao fechar, o ultimo relatorio 0x32 de silencio voltava novamente a
zero. Isso produzia recuos e colisoes de sequencia dentro da mesma conexao HID,
especialmente na curta sobreposicao entre o fim de uma thread e a abertura da
seguinte.

Agora o contador de quatro bits do cabecalho e unico para 0x31 e 0x32 durante
toda a vida do processo. O contador de blocos de audio de 0x32 tambem atravessa
sessoes. Numerar, recalcular o CRC e enviar ocorrem sob a mesma exclusao, para
duas threads nao inverterem a ordem. O formato e a separacao dos contadores
seguem a implementacao publica do
[DS5Dongle](https://github.com/awalol/DS5Dongle/blob/master/src/audio.cpp).

Esta e uma correcao fundamentada para o unico estado incorreto encontrado no
nosso transporte, mas o efeito fisico ainda depende do teste no Quest. Cadencia
fixa de 10.698.000 ns, ganho, fila, repouso e PCM nao mudaram.

## Retorno ao menu

Encerrar uma activity apenas revelava a tela 2D que estava embaixo. Em alguns
retornos o Horizon a desenhava, mas nao voltava a rotear os eventos do gamepad
para sua janela; nesses casos nem o `MenuController` aparecia novamente no
diario. O app agora grava a activity que abriu o stream e a reativa com
`REORDER_TO_FRONT | SINGLE_TOP` antes de terminar o jogo. A origem e validada
contra as telas conhecidas do P5M. O caminho vale para janela e imersivo e tem
guarda contra os varios pedidos de `finish()` observados no diario.

## Verificacao local

- `tools/test_haptics.py`: dez casos; PCM, fila, repouso e inicio curto
  preservados, mais continuidade dos dois contadores entre 0x31, 0x32 e dois
  donos do transporte.
- `tools/test_stream_return.py`: reativa a tela gravada com as duas flags e
  rejeita origem ausente ou externa.
- `tools/test_menu.py`: os tres grupos anteriores continuam passando.
- `tools/conferir.py`: 27 patches aplicam em ordem; contratos Kotlin/JNI,
  strings, manifesto, tabelas e certificados conferidos.

Teste no Quest: abrir e fechar varias sessoes alternando janela e imersivo;
confirmar D-pad, Cross e Circle assim que a lista de consoles reaparecer. Para
a vibracao, comparar pulos seguidos antes e depois dessas trocas sem desligar o
DualSense. O diario deve conter `Returning control to MainActivity` em cada
retorno.

## Resultado no Quest (11/09): o atraso nao foi resolvido

A dev.176 foi testada no Quest em 11/09 (diario do processo 6736, 00:32..00:36)
e **nao resolveu o atraso da vibracao**. O sintoma continua identico ao de
antes dela: a sessao comeca com atraso, uns 4 s quando muitas vibracoes se
acumulam; desligar e religar o DualSense o tira por completo, mesmo em
sequencias longas; encerrar a sessao e abrir outra o traz de volta.

A correcao de sequencia descrita acima continua fundamentada -- os recuos e
colisoes de contador eram reais --, mas atacou outro estado. Nao descreva a
dev.176 como "atraso corrigido".

A linha `Haptics timing` dessa rodada exonera o caminho que o app enxerga: 935
voltas em 10.001..10.010 ms, `consumed` acompanhando `source`, `0 refused` em
todos os intervalos, e a fila Kotlin com media maxima de 127 B contra teto de
192. O unico numero fora do lugar e o `send peak`, entre 9.913 e 21.860 us. A
build seguinte acrescenta a essa linha o histograma dos tempos de envio, para
testar a hipotese do intervalo do enlace Bluetooth antes de qualquer correcao.

A mesma rodada mostrou que o retorno ao menu, no imersivo, abria um segundo
painel do app em vez de trazer o existente; a causa e a troca por
`moveToFront()` estao no `StreamReturn.kt` da build seguinte.
