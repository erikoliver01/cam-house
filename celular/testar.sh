#!/data/data/com.termux/files/usr/bin/bash
# Teste rapido antes de ligar: ve o video de cada camera e manda uma mensagem ao canal.
. "$(dirname "$0")/comum.sh"
for id in $CAMERAS; do
  dados_camera "$id" || continue
  echo "== $NOME ($HOST)"
  ffprobe -v error -rtsp_transport tcp -timeout 8000000 \
    -show_entries stream=codec_type,codec_name,width,height -of compact=p=0 "$(rtsp_url)" \
    || echo "   FALHOU: confira IP, usuario e senha em config.sh / segredos.sh"
done
echo "== Telegram"
printf 'url = "https://api.telegram.org/bot%s/sendMessage"\n' "$BOT_TOKEN" |
  curl -sS -K - --max-time 30 -F "chat_id=$CHAT_ID" --form-string "text=Teste do CamBox no celular" |
  grep -q '"ok":true' && echo "   mensagem enviada ao canal" || echo "   FALHOU: confira BOT_TOKEN e CHAT_ID"
