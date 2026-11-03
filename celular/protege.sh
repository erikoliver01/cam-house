#!/system/bin/sh
# Roda como root (via su): marca o Termux e tudo que ele roda como "nao matar" (oom -1000).
# Repete a cada 30 s porque o Android reajusta o valor do app e surgem processos novos (ffmpeg, curl).
U=$(stat -c %u /data/data/com.termux)
while true; do
  for d in /proc/[0-9]*; do
    [ "$(stat -c %u "$d" 2>/dev/null)" = "$U" ] && echo -1000 > "$d/oom_score_adj" 2>/dev/null
  done
  sleep 30
done
