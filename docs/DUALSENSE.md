# DualSense no P5M: referência completa

Estudo de 24/09/2026 (dev.203–204). Reúne o que o console manda e recebe, o
que o controle aceita por Bluetooth e o que o app faz com cada coisa. Tudo
tem fonte: o código do chiaki-ng em `external/chiaki-ng`, o driver do kernel
Linux (`drivers/hid/hid-playstation.c`), o SDL (`SDL_hidapi_ps5.c`), a
tabela de gatilhos do Nielk1 e os diários das dev.196–202. Leia antes de
mexer no controle: é aqui que se evita pagar de novo o que já foi pago.

## 1. O que o console manda (PS5 → cliente)

Três caminhos, todos anunciados só quando o cliente se apresenta como
**DualSense** (`enable_dualsense` → `controller_type = DUALSENSE` e a
mensagem `ENABLE_DUALSENSE_FEATURES = 0x13`). Anunciado como DualShock 4, o
console não manda gatilhos nem háptica, e a vibração clássica chegou zerada
nas nossas medições.

| Caminho | Formato | No chiaki | No P5M |
|---|---|---|---|
| Takion tipo 7, rumble | `buf[1]` esquerdo, `buf[2]` direito (0–255) | `CHIAKI_EVENT_RUMBLE` | `Rumble` (motor do Android) × intensidade do PS5 |
| Takion tipo 11, gatilhos | 25 bytes: `buf[1]` modo esq., `buf[2]` modo dir., `buf[5..14]` params esq., `buf[15..24]` params dir. | `CHIAKI_EVENT_TRIGGER_EFFECTS` | `EspelhoDualSense` → 0x31, bytes 13–34 |
| Áudio, `is_haptics` | PCM do segundo fluxo de áudio, 3 kHz depois de reamostrado | `haptics_sink` | `DualSenseHaptics` → relatório 0x32 |
| Takion tipo 9, *pad info* | ver tabela abaixo | vários eventos | `EspelhoDualSense`, `MovimentoDualSense` |

### Pacote *pad info* (tipo 9)

Dois formatos: 0x11 (17 bytes) e 0x19 (25 bytes, os mesmos campos deslocados
de +8 e com número de sequência e tempo na frente).

| Campo | 0x11 | 0x19 | Valores |
|---|---|---|---|
| sequência do feedback respondido | — | 0–1 | u16 BE |
| tempo | — | 4–7 | u32 |
| jogador | 0 | 8 | base zero (0 = jogador 1) |
| cor da barra | 1–3 | 9–11 | RGB; **0 0 0 = o jogo não escolheu cor** |
| *motion reset* | 4 | 12 | 1 = zerar a orientação do controle |
| intensidade da vibração | 12 | 20 | 0 off, 1 forte, 2 média, 3 fraca |
| intensidade dos gatilhos | 13 | 21 | idem |
| brilho dos indicadores | 14 | 22 | 0 claro, 1 médio, 2 escuro (decifrado por nós, patch 0030) |

O resto dos bytes não tem nome em lugar nenhum.

**Cor 0 0 0.** A dev.202 viu: um jogo pôs vermelho, e ao voltar para o jogo
anterior o console mandou 0 0 0. O PS5 nessa hora acende a cor do sistema do
jogador (azul, vermelho, verde, rosa). A dev.203 faz o mesmo; antes o preto
era ignorado e a barra ficava presa na cor do jogo anterior.

## 2. O que o cliente manda (cliente → PS5)

`ChiakiControllerState`: botões, L2/R2 analógicos, dois analógicos, **até dois
toques** (x 0–1919, y 0–1079, id de 7 bits), giroscópio (rad/s), acelerômetro
(g) e orientação (quatérnio).

- Botões e toques vão no *feedback history* (eventos de 2–5 bytes).
- Analógicos e movimento vão no *feedback state* (v9/v12). O envio sai a cada
  mudança, com no máximo um a cada 8 ms, ou a cada 200 ms parado.
- A orientação não vem do controle: o cliente a calcula com Madgwick
  (`lib/src/orientation.c`), e o *motion reset* do console zera o acelerômetro
  e reinicia o filtro.

**Até a dev.203 o P5M mandava os sensores do headset como se fossem do
controle.** O `StreamInput.observe()` do chiaki registra os sensores padrão
do aparelho. Num celular isso está certo, porque o celular é o controle; no
Quest esses sensores são os do headset. O DualSense se declara com
`SOURCE_SENSOR` (fontes `0x510251b` no diário), e a API 31 entrega os
sensores dele por `InputDevice.getSensorManager()`. A dev.204 usa esse
caminho (`MovimentoDualSense`) nos dois modos.

**Até a dev.203 o touchpad só mandava o clique.** Sem captura, o Android
transforma o touchpad em mouse. Com `requestPointerCapture()` ele passa a
entregar os dedos em coordenada absoluta (`SOURCE_TOUCHPAD`). A dev.204
captura no imersivo e manda até dois dedos. No modo janela continua só o
clique, porque lá o raio da mão precisa do ponteiro.

## 3. O relatório de saída por Bluetooth (0x31, 78 bytes)

CRC32 sobre `0xA2` mais os primeiros 74 bytes, gravado nos 4 últimos.

| Byte | Campo | Notas |
|---|---|---|
| 0 | 0x31 | |
| 1 | sequência << 4 | |
| 2 | 0x10 | rótulo de saída |
| 3 | valid0 | 0x01 vibração compatível, 0x02 seleção da háptica, **0x04 gatilho direito, 0x08 esquerdo** |
| 4 | valid1 | 0x01 LED do mic, 0x02 economia, **0x04 barra, 0x08 liberar LEDs, 0x10 LEDs de jogador**, 0x40 potência dos motores |
| 5–6 | vibração direita/esquerda | |
| 13–23 | gatilho direito | 1 byte de modo + 10 de parâmetros |
| 24–34 | gatilho esquerdo | idem |
| 39 | redução de potência | **nibble em disputa**, ver abaixo |
| 41 | valid2 | 0x01 brilho dos LEDs, 0x02 *lightbar setup*, 0x04 vibração compatível v2 |
| 44 | *lightbar setup* | 0x02 = *light out* (tira a barra do firmware) |
| 45 | brilho dos LEDs | 0/1/2; confirmado em hardware nos LEDs de jogador |
| 46 | LEDs de jogador | 5 bits; 0x20 = sem fade |
| 47–49 | RGB da barra | |

**Byte 39.** O chiaki-ng põe os gatilhos no nibble alto (média 0x60,
fraca 0x90, off 0xF0) e a vibração no baixo (0x02/0x03/0x0F). A struct do
wiki diz o contrário. Na dev.199, com o PS5 em gatilhos "média", o app
escreveu 0x60 e a vibração ficou pior: é o que se espera se o nibble alto for
o da vibração. Por isso o P5M **não escreve a redução**, e o byte fica em 0.
As intensidades são aplicadas por conta própria:

- **Vibração:** as amostras da háptica e a força do motor são multiplicadas
  por 1 / 0,5 / 0,33 / 0, os fatores do chiaki-ng.
- **Gatilhos:** `ForcaGatilhos` reduz a força dentro do efeito, conforme o
  modo:
  - 0x01, 0x02, 0x06, 0x11 e 0x12: o byte de força;
  - 0x21 e 0x26: os campos de 3 bits por zona;
  - 0x22, 0x25 e 0x27: os campos de força.
  - Modo desconhecido passa como veio.

**Barra de luz.** Funciona desde a dev.202 (confirmado: trocou para o
vermelho do jogo). O firmware precisa soltar a barra antes, com um *light
out* enviado uma vez. Ler o relatório de calibragem (0x05) também muda o
controle para o modo de relatórios completos, mas custou atraso na háptica e
saiu na dev.179.

**Cores do sistema.** O SDL usa (0,0,0x40) azul, (0x40,0,0) vermelho,
(0,0x40,0) verde e (0x20,0,0x20) rosa. São valores baixos, porque o SDL
escala pelo brilho. O P5M usa a cor cheia e deixa o brilho com o byte 45.

## 4. Estado por recurso (dev.204)

| Recurso | Estado | Onde |
|---|---|---|
| Botões, analógicos, L2/R2 analógicos | funciona | Android → `StreamInput` |
| Clique do touchpad | funciona | `TouchpadPointer` |
| Dedos no touchpad | **novo na dev.204**, só no imersivo | captura de ponteiro → `TouchpadPointer` |
| Giroscópio, acelerômetro, orientação | **novo na dev.204** | `MovimentoDualSense` |
| *Motion reset* do console | **novo na dev.204** | patch 0031 → `MovimentoDualSense.zerar` |
| Háptica (bobinas) | funciona | `DualSenseHaptics`, relatório 0x32 |
| Intensidade da vibração do PS5 | **novo na dev.204** | ganho nas amostras e no motor |
| Gatilhos adaptativos | funciona | `EspelhoDualSense` |
| Intensidade dos gatilhos do PS5 | **novo na dev.204** | `ForcaGatilhos` |
| Barra de luz | funciona (dev.202); 0 0 0 → cor do sistema (dev.203) | `EspelhoDualSense` |
| LEDs de jogador, brilho | funciona | `EspelhoDualSense` |
| Espelho no modo janela | falta | o `StreamSession` da janela descarta os eventos |
| LED do mic, microfone | falta | nem o console manda o estado do LED por aqui |
| Alto-falante do controle | falta | por BT exige o relatório de áudio (o DSX faz com 0x36); sem fonte primária ainda |
| Bateria | falta | |

## 5. O que o diário mostra para cada coisa

- `Controller motion: reading '...'`: achou os sensores do controle.
  `no gamepad with motion sensors` quer dizer que não achou.
- `Controller motion 10s: N gyro and M accel samples ... orient ...`: a
  cada 10 s. N deve ficar perto de 2500.
- `Touchpad pointer capture: on`: captura concedida.
  `Touchpad fingers: absolute, x a..b`: os dedos chegaram em coordenada
  absoluta.
- `Controller mirror 10s: ... lightbar #RRGGBB` ou `system colour #...`.
- `PS5 trigger effect intensity: medium`: a partir daqui os efeitos saem
  com metade da força.
