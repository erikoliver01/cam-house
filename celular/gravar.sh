#!/data/data/com.termux/files/usr/bin/bash
# Grava uma camera: um clipe de SEG segundos a cada CICLO segundos (config.sh), para sempre. Uso: bash gravar.sh porta
. "$(dirname "$0")/comum.sh"
id="$1"; dados_camera "$id" || exit 1
out="$D/gravacoes/$id"; mkdir -p "$out"

# .part = clipe em gravacao; o enviar.sh so pega .mp4. Sobra de um parar.sh: libera para envio (o ffprobe de la descarta se estiver cortado)
for p in "$out"/rec_*.mp4.part; do [ -e "$p" ] && mv "$p" "${p%.part}"; done

if [ "$VIDEO" = x264 ]; then
  v=(-c:v libx264 -preset ultrafast -g 25)
else
  v=(-c:v copy)
fi

while true; do
  ini=$(date +%s)
  f="$out/rec_$(date +%Y%m%d_%H%M%S).mp4"
  log "[$id] gravando $NOME ($HOST, stream $STREAM, video $VIDEO)"
  # -timeout: sem ele uma conexao travada para a gravacao em silencio (igual ao PC)
  ffmpeg -nostdin -hide_banner -loglevel error \
    -rtsp_transport tcp -timeout 8000000 -i "$(rtsp_url)" \
    "${v[@]}" -c:a aac -b:a 32k -t "$SEG" \
    -f mp4 -movflags +faststart "$f.part"
  rc=$?
  [ -e "$f.part" ] && mv "$f.part" "$f"
  if [ "$rc" -ne 0 ]; then
    log "[$id] ffmpeg saiu (codigo $rc), tento de novo em 10 s"
    sleep 10
    continue
  fi
  resto=$((ini + CICLO - $(date +%s)))
  [ "$resto" -gt 0 ] && { log "[$id] clipe pronto, pausa de $resto s"; sleep "$resto"; }
done
