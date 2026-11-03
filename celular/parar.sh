#!/data/data/com.termux/files/usr/bin/bash
# Para tudo. O ffmpeg recebe Ctrl+C (SIGINT) e fecha o clipe atual direito.
pkill -f "cambox/gravar.sh"
pkill -f "cambox/enviar.sh"
su -c "pkill -f 'sh .*limite-carga[.]sh'; echo 1 > /sys/class/power_supply/battery/charging_enabled" < /dev/null   # parado = carga normal
pkill -INT -x ffmpeg
sleep 3
pkill -x ffmpeg
echo "CamBox parado."
