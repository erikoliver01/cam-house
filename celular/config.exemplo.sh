# CamBox no celular: configuracao (sem segredos; senhas e token ficam em segredos.sh)
# Copie para config.sh e preencha:  cp config.exemplo.sh config.sh
# Os IPs mudam se o roteador trocar o endereco da camera: reserve um IP fixo para cada camera no roteador.

CAMERAS="porta camera2"          # ids das cameras que este celular grava (um por camera, separados por espaco)

PORTA_HOST=PREENCHA_IP_DA_CAMERA_1     # ex.: 192.168.1.XX
PORTA_NOME="Porta de entrada"          # nome que aparece na legenda do clipe

CAMERA2_HOST=PREENCHA_IP_DA_CAMERA_2   # apague estas linhas (e tire camera2 de CAMERAS) se tiver so uma
CAMERA2_NOME="Câmera 2"

CHAT_ID=PREENCHA_ID_DO_CANAL     # ID do canal do Telegram (numero negativo que comeca com -100)

STREAM=1          # 1 = substream (leve); 0 = principal UHD, pesado demais para celular fraco
VIDEO=copy        # copy = nao recodifica (leve). x264 = recodifica em H.264 (pesado)
SEG=300           # duracao de cada clipe, em segundos (5 min)
CICLO=600         # um clipe comeca a cada CICLO segundos (pausa = CICLO - SEG); CICLO=SEG grava sem pausa
MIN_LIVRE_MB=500  # abaixo disso apaga o clipe mais antigo para nao lotar o celular
