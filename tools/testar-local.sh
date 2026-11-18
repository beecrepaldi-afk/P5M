#!/usr/bin/env bash
# Roda localmente o que a CI nao roda, antes de gastar uma build:
#
#   bash tools/testar-local.sh          conferir.py + todos os tools/test_*.py
#   bash tools/testar-local.sh kotlin   isso tudo + compilar o Kotlin do app no Gradle
#
# Escrito para o Mac (desde 29/09/2026). Os caminhos das ferramentas vem de
# ~/projetos/_ferramentas/env.sh quando ele existe; fora disso, exporte:
#   JAVA_HOME      um JDK 17
#   KOTLINC_HOME   o kotlinc 1.8.0 do zip oficial (kotlin-compiler-1.8.0.zip):
#                  os testes usam os jars da pasta lib dele, e o do Homebrew
#                  e outra versao
#   ANDROID_HOME   o SDK Android (padrao ~/Library/Android/sdk)
set -u
[ -f "$HOME/projetos/_ferramentas/env.sh" ] && . "$HOME/projetos/_ferramentas/env.sh"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
K="${KOTLINC_HOME:-$HOME/ferramentas/kotlinc}/lib"
CP="$K/kotlin-compiler.jar:$K/kotlin-stdlib.jar:$K/kotlin-script-runtime.jar:$K/kotlin-reflect.jar:$K/trove4j.jar:$K/kotlinx-coroutines-core-jvm.jar:$K/annotations-13.0.jar"
WORK="${TMPDIR:-/tmp}/p5m-testes"
PY=python3
falhas=0

# O checkout desfaz o que os patches mudaram, mas nao apaga os arquivos que
# eles criaram; sem o clean, o submodulo fica sujo para a proxima build.
limpar_submodulo() {
  git -C external/chiaki-ng checkout -- . && git -C external/chiaki-ng clean -fdq
}

cd "$REPO" || exit 1
mkdir -p "$WORK"
echo "== conferir.py"
$PY tools/conferir.py | tail -1 || falhas=$((falhas+1))

rodar() {
  $PY "tools/$1.py" --compiler-classpath "$CP" --stdlib "$K/kotlin-stdlib.jar" \
    --work-dir "$WORK/$1" > "$WORK-$1.log" 2>&1
  echo $? > "$WORK-$1.status"
}

relatar() {
  echo "== $1"
  if [ "$(cat "$WORK-$1.status" 2>/dev/null)" != "0" ]; then
    falhas=$((falhas+1))
    grep -E "error:|FAIL" "$WORK-$1.log" | head -15
  else
    echo "   ok ($(grep -c PASS "$WORK-$1.log") PASS)"
  fi
}

echo "== test_compilar_nativo"
$PY tools/test_compilar_nativo.py 2>&1 | tail -2 || falhas=$((falhas+1))

# Os curtos em paralelo: cada um e um JVM que passa quase todo o tempo
# subindo e compilando, e somados davam uns 11 s em fila. O da haptica roda
# depois e sozinho, porque mede tempo de envio e ja falha as vezes sem ninguem
# disputando a CPU.
curtos=()
for t in tools/test_*.py; do
  nome=$(basename "$t" .py)
  case "$nome" in test_compilar_nativo|test_haptics) continue ;; esac
  curtos+=("$nome")
  rodar "$nome" &
done
wait
for nome in "${curtos[@]}"; do relatar "$nome"; done

rodar test_haptics
relatar test_haptics

if [ "${1:-}" = "kotlin" ]; then
  echo "== limpando o submodulo e compilando o Kotlin"
  limpar_submodulo || { echo "submodulo nao limpou"; exit 1; }
  ./gradlew --no-daemon -q :app:compileDebugKotlin 2>&1 | grep -E "^e: |What went wrong" -A3 | head -30
  [ "${PIPESTATUS[0]}" = "0" ] && echo "   Kotlin compila" || falhas=$((falhas+1))
  limpar_submodulo
fi

echo
[ "$falhas" = "0" ] && echo "Tudo passou." || echo "$falhas etapa(s) falharam."
exit "$falhas"
