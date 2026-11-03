#!/data/data/com.termux/files/usr/bin/bash
# Liga tudo em segundo plano: um gravador por camera + o envio. Pode rodar de novo para reiniciar.
. "$(dirname "$0")/comum.sh"
mkdir -p "$D/logs"
termux-wake-lock 2>/dev/null   # impede o Android de "dormir" o Termux
sshd 2>/dev/null   # acesso remoto (se ja estiver rodando, so falha)
bash "$D/status-web.sh"   # status da gravacao para o app (porta 8023, so leitura)
su -c "pkill -f 'sh .*protege[.]sh'" < /dev/null   # [.] evita o pkill casar com o proprio comando
su -c "nohup sh $D/protege.sh >/dev/null 2>&1 &" < /dev/null
bash "$D/parar.sh" >/dev/null 2>&1   # tambem mata o limite-carga anterior
su -c "nohup sh $D/limite-carga.sh >> $D/logs/carga.log 2>&1 &" < /dev/null
for id in $CAMERAS; do
  nohup bash "$D/gravar.sh" "$id" >> "$D/logs/$id.log" 2>&1 &
done
nohup bash "$D/enviar.sh" >> "$D/logs/envio.log" 2>&1 &
log "CamBox ligado. Ver o que acontece: bash $D/status.sh"
