# Continuidade da dev.168

## Evidência e decisão

Base de hardware: dev.168, `9d99ee4`. O dono aprovou a háptica imersiva,
reproduziu a bola clara com MQSR e relatou atraso na janela em pulos repetidos.
Autorizou escolher a solução e acrescentou navegação pelo DualSense e revisão
dos menus. As mudanças abaixo ainda exigem validação no Quest.

O diário servido em 09/09 contém várias sessões, todas após o marcador de
troca dev.165 -> dev.168. A atribuição anterior de `queue 113..131 B avg` à
janela estava errada: essa sequência pertence ao imersivo de 15:42:26.
A primeira janela começa às 15:55:30; às 15:55:50 e 15:56:45 o vídeo usa
`Window without shader`. Há acúmulo também nessas sessões: às 15:56:24,
`queue 91 B avg, 192 B peak`. Isso não prova que GPU cause a demora.

## Háptica

`DualSenseHaptics.kt` mantém cadência, fila, sinal, ganho, empacotamento e
estacionamento. A nova linha `Haptics timing:` mede cada intervalo por relógio
monotônico e contém:

- `loops`: voltas reais; cerca de 935 por dez segundos no período de
  10.698.000 ns. A taxa nominal do controle seria 937,5, mas o período escolhido
  deliberadamente entrega menos. Não reintroduzir relógio adaptativo.
- `active sent + zero sent + idle + refused = loops`: categorias exclusivas.
  `active` significa pelo menos um byte PCM diferente de zero, sem gate de ganho.
- `source`, `consumed`, `dropped`: bytes lidos do JNI, retirados para relatórios
  e descartados no teto Java. Não medem a taxa que chegou pelo rádio nem
  descartes no anel nativo. `consumed` inclui PCM zero e tentativas recusadas.
- `missed deadlines`, `read peak`, `send peak`: trabalho/agendamento que perdeu
  o prazo e picos das chamadas JNI/HID. O fechamento do prazo ocorre depois da
  linha de diagnóstico; esse último prazo entra no intervalo seguinte.

Os contadores antigos continuam cumulativos; `sent` e `silent` se sobrepõem.
RMS/pico e os contadores novos são por intervalo. O resumo do diagnóstico agora
inclui ambas as linhas de háptica. Leitura pela rota `/haptics` também as inclui.

Sete regressões de JVM passaram, executando o laço Kotlin real com transporte
simulado. Cobrem efeito curto, silêncio contínuo, retomada com amplitude 1,
ordem estéreo, estouro de fila, CRC, RMS e partição dos contadores. A bancada
não testa rádio, driver Android, firmware do DualSense ou agendamento do Quest.
Não foi aplicada uma correção especulativa à cadência para mascarar o atraso.

## MQSR

A escolha explícita 4 resolve para automático 5 na preferência e na ponte
JNI. O switch nativo também trata o índice legado como automático. A interface
cicla `off -> light -> medium -> strong -> automatic -> off`; os seis índices
continuam existindo para preservar preferências, tabelas e ABI.

MQSR continua candidato do automático, acompanhado pelo bit AUTO e demais
candidatos existentes. Sem a extensão automática, permanece o fallback normal
anterior. A janela não possui essa camada OpenXR; a ajuda agora explica que
nitidez nela requer light/medium/strong. Não prometer que automático remove o
disco antes do próximo teste. Se ele persistir, retirar QUALITY_SHARPENING do
conjunto é a próxima hipótese isolável.

## Menus pelo controle

`MenuController.kt`, instalado nos callbacks de ciclo de vida do `P5MApp`,
adapta os menus próprios e as activities herdadas sem alterar o submódulo:

- Cross/A vira confirmação Android; Circle/B vira voltar.
- Direcional e analógico esquerdo movem o foco. O analógico tem zona de 0,6,
  repetição inicial de 380 ms e subsequente de 140 ms.
- Itens clicáveis entram na navegação; o foco recebe contorno e pede rolagem
  para ficar visível. O foreground original é restaurado ao perder foco.
- A repetição para ao pausar e não roda sem foco da janela.
- Activities de stream e bancada ficam excluídas. A calibração recebe os
  analógicos originais. Ponteiro, teclado e toques continuam no caminho Android.
- O diário registra `Menu control:` no primeiro uso de cada instalação do
  adaptador. Diálogos e páginas externas possuem suas próprias janelas e ainda
  precisam de teste; o adaptador atua na janela da activity.

O lançador ganhou cabeçalho e legenda de comandos fixos, lista rolável entre
eles e a ajuda fixa abaixo. Focar uma ação/ajuste mostra sua explicação antes
de ativá-lo; linhas de ajuste anunciam rótulo e valor para acessibilidade.

## Verificação e próximos passos

**Dev.170 publicada com sucesso**, commit `0d41306`, execução
`34395814130`. APK `p5m-0.1.0-dev.170.apk`, 24.488.515 bytes, SHA-256
`4f14edc339911d3a7a84879a379ed67723dc9d571c81a71969d15971ebe9f64e`.
Compilação Android e C++ confirmadas na CI. `tools/conferir.py` passou,
incluindo os 23 patches.

O dono pediu também corrigir a falta do compilador local. Foi instalado o
w64devkit 2.9.1 (GCC 16.2.0) em `%LOCALAPPDATA%/Programs/w64devkit`, com hash
SHA-256 do pacote conferido contra o release oficial. O diretório `bin` foi
acrescentado ao PATH do usuário; terminais já abertos podem precisar reabrir.
A ferramenta encontra essa instalação mesmo sem reiniciar o terminal.

`tools/compilar_nativo.py` passou nos cinco arquivos no Windows. Usa
`shutil.which`, respeita `CXX` explícito e encontra a instalação portátil.
Falta de compilador/cabeçalhos e qualquer retorno não zero agora reprovam,
em vez de parecer sucesso. Quatro testes em `tools/test_compilar_nativo.py`
cobrem retorno de erro sem mensagem convencional, sem saída, falta de
cabeçalhos e sucesso. Uma prova com GCC e o GL real aceitou
`GL_TEXTURE_MIN_FILTER` e rejeitou `GL_MIN_FILTER`.

No Windows, declarações fixas de POSIX (`sigaction`, `dladdr`, `gettid`) suprem
APIs Android ausentes no CRT; não validam o layout ABI dessas estruturas.
GL/EGL/OpenXR continuam reais. A geração do APK Android continua na CI.
O commit de ferramentas/documentação ficou local para não disparar outra
build com o mesmo código de aplicativo da dev.170.

No Quest: comparar pulos repetidos nos dois modos; conferir automático sem a
bola; percorrer lançador, lista de consoles, PSN, diagnóstico e calibração;
entrar/sair de jogo e confirmar que o gamepad controla o console normalmente.

O processamento único de nitidez/tom no 3D permanece adiado para isolar esta
rodada de testes. O origin local foi reconfigurado sem credencial na URL.
Isso não revoga o PAT que já vazou; a revogação continua pendente no GitHub.
