#!/bin/bash
# Compila o APK no Mac, assina com a chave fixa de depuracao e instala no
# Quest pelo adb, sem passar pela CI. Uso:
#   bash tools/instalar-local.sh            # build + instala + abre o app
#   bash tools/instalar-local.sh --bancada  # ...e abre o test bench imersivo
# Precisa: ~/projetos/_ferramentas/venv-build (protobuf<6, grpcio-tools),
# ~/projetos/PSMeta-docs/p5m-debug.keystore e o Quest no adb
# (QUEST=ip:porta; sem ele, o unico aparelho ligado).
set -u
cd "$(dirname "$0")/.."
source ~/projetos/_ferramentas/env.sh
export PATH=~/projetos/_ferramentas/venv-build/bin:$PATH
export P5M_VERSION_CODE=${P5M_VERSION_CODE:-245}
export P5M_VERSION_NAME=${P5M_VERSION_NAME:-0.1.0-dev.${P5M_VERSION_CODE}-local}
[ -n "${QUEST:-}" ] && export ANDROID_SERIAL=$QUEST
SAIDA=${TMPDIR:-/tmp}/p5m-local
mkdir -p "$SAIDA"
LOG=$SAIDA/build.log

limpar() { git -C external/chiaki-ng checkout -- . && git -C external/chiaki-ng clean -fdq; }
limpar
./gradlew --no-daemon :app:assembleDebug > "$LOG" 2>&1
st=$?
limpar
if [ $st -ne 0 ]; then
  echo "BUILD FALHOU (log: $LOG)"; grep -E "^e: |error:|FAILED" "$LOG" | head -15; exit 1
fi
SIGN=$(ls ~/Library/Android/sdk/build-tools/*/apksigner | tail -1)
"$SIGN" sign --ks ~/projetos/PSMeta-docs/p5m-debug.keystore --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out "$SAIDA/p5m-local.apk" app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r "$SAIDA/p5m-local.apk" 2>&1 | grep -E "Success|Failure|complete" || exit 1
adb logcat -c
if [ "${1:-}" = "--bancada" ]; then
  adb shell am start -n io.github.gblandro.p5m/io.github.gblandro.p5m.BancoDeEnsaioActivity >/dev/null
  echo "instalado: $P5M_VERSION_NAME, test bench aberto"
else
  adb shell monkey -p io.github.gblandro.p5m -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  echo "instalado: $P5M_VERSION_NAME"
fi
