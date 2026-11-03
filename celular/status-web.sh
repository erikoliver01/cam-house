#!/data/data/com.termux/files/usr/bin/bash
# Mini servidor de status (so leitura) para o app CAM house: porta 8023, so rede de casa e Tailscale, com senha.
# Usa o busybox httpd que veio com o Magisk (nada instalado). Ele e de 2018 e o Android barra uma chamada dele
# dentro do app Termux ("Bad system call"), por isso sobe pelo su e larga o root na hora (-u = usuario do Termux).
# Desligar: su -c "pkill -f 'httpd -p 802[3]'"
D="$(cd "$(dirname "$0")" && pwd)"
[ -f "$D/status/httpd.conf" ] || { echo "falta $D/status/httpd.conf"; exit 1; }
U=$(id -u)
# em dois su separados: se o pkill estivesse junto, casaria com a propria linha que liga o servidor e se mataria
su -c "pkill -f 'httpd -p 802[3]'" < /dev/null >/dev/null 2>&1
su -c "/sbin/.core/busybox/busybox httpd -p 8023 -u $U:$U -h $D/status/www -c $D/status/httpd.conf >/dev/null 2>&1" < /dev/null >/dev/null 2>&1
