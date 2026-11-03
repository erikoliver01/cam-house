# Funcoes usadas pelos outros scripts.
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$D/config.sh"
if [ ! -f "$D/segredos.sh" ]; then echo "Falta $D/segredos.sh (copie de segredos-modelo.sh e preencha)"; exit 1; fi
. "$D/segredos.sh"

log() { echo "$(date '+%d/%m %H:%M:%S') $*"; }

# dados_camera porta  ->  define HOST USER PASS NOME
dados_camera() {
  local up; up=$(echo "$1" | tr a-z A-Z)
  eval "HOST=\${${up}_HOST}; USER_=\${${up}_USER}; PASS=\${${up}_PASS}; NOME=\${${up}_NOME}"
  [ -n "$HOST" ] || { log "camera desconhecida: $1"; return 1; }
}

rtsp_url() { echo "rtsp://$HOST:554/user=$USER_&password=$PASS&channel=1&stream=$STREAM.sdp"; }
