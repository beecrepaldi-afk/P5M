#!/usr/bin/env bash
# Tabela por sessao: bitrate pedido x tempo de chegada do quadro (rede) x decodificacao.
# Uso: tools/bitrate-latencia.sh [DIRETORIO_COM_LOGS_.log]
#   Sem argumento: le os logs do Quest via adb (ANDROID_SERIAL; sem ele, o unico aparelho ligado).
#   Com argumento: le os .log ja baixados nesse diretorio (sem adb).
set -u

PKG="io.github.gblandro.p5m"
SERIAL="${ANDROID_SERIAL:-}"
MIN_JANELAS=3

DIR="${1:-}"
TMP=""
if [ -z "$DIR" ]; then
  # Busca os logs no aparelho para uma pasta temporaria
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  DIR="$TMP"
  nomes="$(adb ${SERIAL:+-s "$SERIAL"} shell run-as "$PKG" ls files/session_logs 2>/dev/null | tr -d '\r')" || nomes=""
  if [ -z "$nomes" ]; then
    echo "error: no session logs found on ${SERIAL:-the device} (is the device reachable?)" >&2
    exit 1
  fi
  for n in $nomes; do
    case "$n" in *.log) adb ${SERIAL:+-s "$SERIAL"} exec-out run-as "$PKG" cat "files/session_logs/$n" > "$TMP/$n" 2>/dev/null ;; esac
  done
fi

if [ ! -d "$DIR" ]; then
  echo "error: $DIR is not a directory" >&2
  exit 1
fi

# Primeira etapa: um registro por arquivo com somas brutas.
# Campos: nome asked target rx_soma rx_n fa_soma fm_soma fr_n dec_soma dec_n
brutos="$(for f in "$DIR"/*.log; do
  [ -f "$f" ] || continue
  awk -v nome="$(basename "$f")" '
    # extrai o primeiro numero que vem logo depois do texto "chave"
    function pega(s, chave,   p, r) {
      p = index(s, chave)
      if (p == 0) return ""
      r = substr(s, p + length(chave))
      if (match(r, /^[ ]*[0-9.]+/)) return substr(r, RSTART, RLENGTH) + 0
      return ""
    }
    /P5M: bitrate 10s:/ {
      v = pega($0, "asked ");         if (v != "") asked = v / 1000
      v = pega($0, "(last ");         if (v != "") alvo = v / 1000000
      v = pega($0, "received ");      if (v != "") { rx += v; rxn++ }
    }
    /\[frames\] 10s:/ {
      v = pega($0, "unit avg ");      if (v != "") { fa += v; fr++
        v2 = pega($0, " max ");       # primeiro " max " apos "unit avg"
        q = index($0, "unit avg "); t = substr($0, q)
        v2 = pega(t, " max "); if (v2 != "") fm += v2 }
    }
    /Video 10s:/ {
      q = index($0, "| decode ")
      if (q > 0) { t = substr($0, q + 9)
        if (match(t, /^[0-9.]+\/[0-9.]+/)) { s = substr(t, RSTART, RLENGTH); split(s, a, "/"); dec += a[2]; decn++ } }
    }
    END { printf "%s %s %s %s %d %s %s %d %s %d\n", nome, (asked==""?"-":asked), (alvo==""?"-":alvo), rx+0, rxn+0, fa+0, fm+0, fr+0, dec+0, decn+0 }
  ' "$f"
done)"

echo "$brutos" | awk -v minj="$MIN_JANELAS" '
  function f1(x) { return (x == "-" ) ? "-" : sprintf("%.1f", x) }
  BEGIN {
    printf "%-34s %8s %8s %8s %9s %9s %8s %7s\n", "session", "asked", "target", "recv", "arr_avg", "arr_max", "decode", "windows"
    printf "%-34s %8s %8s %8s %9s %9s %8s %7s\n", "", "(Mbps)", "(Mbps)", "(Mbps)", "(ms)", "(ms)", "(ms)", ""
  }
  NF == 10 && $8 >= minj {
    rx  = ($5 > 0) ? $4 / $5 : "-"
    fa  = $6 / $8
    fm  = $7 / $8
    dec = ($10 > 0) ? $9 / $10 : "-"
    printf "%-34s %8s %8s %8s %9s %9s %8s %7d\n", substr($1, 1, 34), f1($2), f1($3), f1(rx), f1(fa), f1(fm), f1(dec), $8
    # acumula por bitrate pedido; cada janela pesa igual
    k = $2; if (!(k in seen)) { seen[k] = 1; ordem[++n] = k }
    w[k] += $8; sfa[k] += $6; sfm[k] += $7
    rxw[k] += $5; srx[k] += $4; dw[k] += $10; sd[k] += $9
    tg[k] += ($3 == "-" ? 0 : $3 * $8); tgw[k] += ($3 == "-" ? 0 : $8)
    ns[k]++
  }
  END {
    if (n == 0) { print "(no sessions with at least " minj " [frames] windows)"; exit }
    print ""
    print "Summary by asked bitrate (each window weighs the same)"
    printf "%8s %8s %8s %8s %9s %9s %8s %8s\n", "asked", "sessions", "target", "recv", "arr_avg", "arr_max", "decode", "windows"
    # ordena por bitrate pedido (insercao simples; "-" fica por ultimo)
    for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) {
      a = ordem[i]; b = ordem[j]
      if (a == "-" || (b != "-" && b + 0 < a + 0)) { ordem[i] = b; ordem[j] = a }
    }
    for (i = 1; i <= n; i++) {
      k = ordem[i]
      printf "%8s %8d %8s %8s %9s %9s %8s %8d\n", f1(k), ns[k],
        f1(tgw[k] > 0 ? tg[k] / tgw[k] : "-"),
        f1(rxw[k] > 0 ? srx[k] / rxw[k] : "-"),
        f1(sfa[k] / w[k]), f1(sfm[k] / w[k]),
        f1(dw[k] > 0 ? sd[k] / dw[k] : "-"), w[k]
    }
  }'
