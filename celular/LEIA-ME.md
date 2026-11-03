# CamBox no celular (Termux)

Grava as câmeras em clipes e manda cada clipe ao canal do Telegram, um por vez, apagando do celular
depois do "ok". Mesma lógica do gravador do PC, em scripts de shell para o Termux.
O passo a passo completo está no [README principal](../README.md#6-opcional-celular-android-gravando-24-h).

## Arquivos
| Arquivo | Para quê |
|---|---|
| `config.exemplo.sh` → `config.sh` | IPs, nomes, canal, duração e ciclo dos clipes (preencha) |
| `segredos.exemplo.sh` → `segredos.sh` | usuário/senha das câmeras e token do bot (preencha, `chmod 600`) |
| `testar.sh` | confere o vídeo das câmeras e manda uma mensagem de teste ao canal |
| `iniciar.sh` / `parar.sh` / `status.sh` | ligar, parar e ver como está |
| `gravar.sh` (um por câmera) e `enviar.sh` | rodam sozinhos em segundo plano |
| `boot-iniciar-cambox` | vai em `~/.termux/boot/` para ligar tudo quando o celular reinicia |
| `limite-carga.sh` | **root**: segura a bateria entre 85% e 90% (só em aparelhos com `charging_enabled`) |
| `protege.sh` | **root**: impede o Android de matar a gravação |
| `status-web.sh` + `status/` | **root, opcional**: status só de leitura na porta 8023 para o app Android |

## Sem root
O `iniciar.sh` tenta ligar as partes com root (`su`). Sem root essas linhas só falham e o resto
(gravar e enviar) funciona normalmente. Se preferir, apague as linhas com `su` do `iniciar.sh`.

## Observações
- O vídeo é copiado como vem da câmera (`VIDEO=copy`). Celular fraco não aguenta recodificar.
- `status-web.sh` usa o busybox que vem com o Magisk antigo (`/sbin/.core/busybox`). Em outra versão, ajuste o caminho.
- Se faltar espaço (Telegram fora do ar por muito tempo), apaga o clipe mais antigo (`MIN_LIVRE_MB`).
- Clipe cortado no meio (sem índice) é apagado em vez de enviado.
