#!/data/data/com.termux/files/usr/bin/bash
# Mostra se esta rodando, quantos clipes esperam envio e o fim dos logs.
. "$(dirname "$0")/comum.sh"
echo "ffmpeg rodando: $(pgrep -x ffmpeg | wc -l)   (esperado: $(echo $CAMERAS | wc -w))"
echo "envio rodando:  $(pgrep -f cambox/enviar.sh | wc -l)"
for id in $CAMERAS; do echo "clipes em $id: $(ls "$D/gravacoes/$id" 2>/dev/null | wc -l)"; done
echo "espaco livre: $(df -h "$D" | awk 'NR==2 {print $4}')"
for f in "$D"/logs/*.log; do [ -f "$f" ] && { echo "--- $(basename "$f")"; tail -n 5 "$f"; }; done
