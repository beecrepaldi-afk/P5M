#!/usr/bin/env bash
# Segue o diario do P5M ao vivo, do Mac, enquanto o dono testa no Quest.
#
# O app (build -dev, ou "Live diary on network" ligado no Diagnostico) se
# anuncia por mDNS como _p5m._tcp e serve /follow na porta 8787. Este script
# acha o headset sozinho e imprime cada linha nova; quando o app fecha ou o
# Quest dorme, espera e volta a seguir (repetindo as ultimas 30 linhas).
#
# Uso: tools/seguir-quest.sh [ip]     (sem ip, procura pelo mDNS)
#      P5M_FOLLOW=/follow/all tools/seguir-quest.sh   (tudo o que esta na memoria)
set -u

ROTA="${P5M_FOLLOW:-/follow}"

procurar() {
	# dns-sd nao termina sozinho: roda em segundo plano e sai depois de 3 s.
	local nome host
	nome=$( (dns-sd -B _p5m._tcp local. & p=$!; sleep 3; kill $p) 2>/dev/null \
		| awk '/Add/ { for(i=7;i<=NF;i++) printf "%s%s", $i, (i<NF?" ":""); print ""; exit }')
	[ -z "$nome" ] && return 1
	host=$( (dns-sd -L "$nome" _p5m._tcp local. & p=$!; sleep 3; kill $p) 2>/dev/null \
		| awk '/can be reached at/ { split($(NF-2), a, ":"); print a[1]; exit }' | sed 's/\.$//')
	[ -z "$host" ] && return 1
	(dns-sd -G v4 "$host" & p=$!; sleep 3; kill $p) 2>/dev/null \
		| awk -v h="$host" '$0 ~ h && $NF ~ /^[0-9]+$/ { print $(NF-1); exit }'
}

IP="${1:-}"
while true; do
	alvo="$IP"
	if [ -z "$alvo" ]; then
		alvo=$(procurar)
	fi
	if [ -z "$alvo" ]; then
		echo "[seguir] Quest nao encontrado na rede; tentando de novo em 10 s" >&2
		sleep 10
		continue
	fi
	echo "[seguir] seguindo http://$alvo:8787$ROTA" >&2
	# As linhas vazias sao o sinal de vida do servidor (a cada 15 s); fora.
	curl -sN --connect-timeout 5 "http://$alvo:8787$ROTA" | grep --line-buffered -v '^$'
	echo "[seguir] conexao caiu; tentando de novo em 5 s" >&2
	ROTA="/follow"
	sleep 5
done
