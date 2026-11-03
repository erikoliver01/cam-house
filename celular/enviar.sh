#!/data/data/com.termux/files/usr/bin/bash
# Envia ao Telegram, um por vez, os clipes ja fechados e apaga cada um depois do "ok".
. "$(dirname "$0")/comum.sh"

enviar_clipe() {  # $1 = arquivo, $2 = nome da camera
  local f="$1" nome="$2" sz cap resp
  sz=$(stat -c %s "$f") || return
  [ "$sz" -lt 1024 ] && { log "clipe vazio, apagado: $(basename "$f")"; rm -f "$f"; return; }
  # clipe sem indice (gravacao cortada no meio) nao toca: apaga
  if ! ffprobe -v error -show_entries format=duration -of csv=p=0 "$f" >/dev/null 2>&1; then
    log "clipe ilegivel, apagado: $(basename "$f")"; rm -f "$f"; return
  fi
  if [ "$sz" -gt $((50 * 1024 * 1024)) ]; then
    mkdir -p "$D/grandes"; mv "$f" "$D/grandes/"
    log "passou de 50 MB (o bot nao envia), guardado em grandes/: $(basename "$f")"; return
  fi
  cap="$nome $(basename "$f" .mp4 | sed 's/^rec_//')"
  # o token vai pela entrada padrao (-K -), nao aparece na lista de processos
  resp=$(printf 'url = "https://api.telegram.org/bot%s/sendVideo"\n' "$BOT_TOKEN" |
    curl -sS -K - --max-time 600 -F "chat_id=$CHAT_ID" -F supports_streaming=true \
      -F "video=@$f;type=video/mp4" --form-string "caption=$cap" 2>&1)
  if echo "$resp" | grep -q '"ok":true'; then
    log "enviado: $cap ($((sz / 1024)) KB)"; rm -f "$f"
  else
    log "falha ao enviar $(basename "$f"), tento de novo depois: $(echo "$resp" | tr -d '\n' | cut -c1-200)"
    sleep 30
  fi
}

# Se o espaco livre ficar baixo (Telegram fora do ar por muito tempo), apaga o clipe mais antigo.
liberar_espaco() {
  local livre velho
  while true; do
    livre=$(df -k "$D" | awk 'NR==2 {print int($4/1024)}')
    [ -z "$livre" ] || [ "$livre" -ge "$MIN_LIVRE_MB" ] && return
    # candidatos: clipes prontos (o que esta gravando e .part e fica de fora)
    velho=$( { for p in "$D"/gravacoes/*/; do ls "$p"rec_*.mp4 2>/dev/null; done
               ls "$D"/grandes/*.mp4 2>/dev/null; } | awk -F/ '{print $NF" "$0}' | sort | head -n1 | cut -d' ' -f2-)
    [ -n "$velho" ] || return
    log "pouco espaco (${livre} MB), apagando o mais antigo: $(basename "$velho")"; rm -f "$velho"
  done
}

# Logs: guarda so o final quando passam de 1 MB (cat > mantem o mesmo arquivo aberto pelos outros)
encurtar_logs() {
  local f
  for f in "$D"/logs/*.log; do
    [ -f "$f" ] && [ "$(stat -c %s "$f")" -gt 1048576 ] && { tail -c 200000 "$f" > "$f.tmp"; cat "$f.tmp" > "$f"; rm -f "$f.tmp"; }
  done
}

log "envio ao Telegram ligado (cameras: $CAMERAS)"
while true; do
  for id in $CAMERAS; do
    dados_camera "$id" || continue
    pasta="$D/gravacoes/$id"; [ -d "$pasta" ] || continue
    lista=$(ls "$pasta"/rec_*.mp4 2>/dev/null | sort)
    echo "$lista" | grep . | while read -r f; do enviar_clipe "$f" "$NOME"; done
  done
  liberar_espaco
  encurtar_logs
  sleep 15
done
