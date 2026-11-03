#!/system/bin/sh
# Roda como root (via su): segura a bateria entre MIN e MAX para nao estufar ligada 24h no carregador.
# Teste 31/10: com a carga cortada o celular descarrega ~200 mA (a USB nao alimenta sozinho), por isso faixa alta.
MIN=85; MAX=90; B=/sys/class/power_supply/battery; CTL=$B/charging_enabled
[ -w "$CTL" ] || exit 1
trap 'echo 1 > $CTL; exit 0' INT TERM HUP
while true; do cap=$(cat $B/capacity); est=$(cat $CTL)
 if [ "$cap" -ge "$MAX" ] && [ "$est" = 1 ]; then echo 0 > $CTL; echo "$(date '+%d/%m %H:%M') $cap%: carga desligada"
 elif [ "$cap" -le "$MIN" ] && [ "$est" = 0 ]; then echo 1 > $CTL; echo "$(date '+%d/%m %H:%M') $cap%: carga ligada"; fi
 sleep 60; done
