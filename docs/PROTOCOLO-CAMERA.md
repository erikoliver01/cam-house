# Protocolo da câmera (Xiongmai DVRIP / NetSurveillance)

Análise do app **iCSee / VMS** (`VMS_Win64_V2.0.28.1`) para falar direto com a câmera,
sem o app. Baseada na **NetSdk.dll** (decompilada no Ghidra via REA) e **verificada contra
câmeras reais** (modelo X3S-WQ-BL).

- Hash da senha e o uso de MD5: **confirmados por decompilação** (`H264_DVR_EncryptPassword`).
- Cabeçalho, message-IDs e formato JSON: **confirmados em teste ao vivo** (login Ret=100).

Cliente pronto e funcionando: **`xmcam.js`** (Node, sem dependências).

---

## 1. Como a conexão funciona

- **Transporte:** TCP puro na porta **34567** (a mesma que achamos aberta). Não é HTTP.
- **Fluxo:** abre o socket → **login** (recebe um `SessionID`) → envia comandos usando esse
  `SessionID` → manda `KeepAlive` a cada ~20s para não cair.
- **Cada mensagem** = cabeçalho binário de 20 bytes + corpo (quase sempre JSON terminado em `\n\0`).

### Cabeçalho de 20 bytes

| Offset | Tam | Campo | Observação |
|-------:|----:|-------|-----------|
| 0  | 1 | `0xFF` | marca de início |
| 1  | 1 | versão | `0x00` |
| 2  | 2 | reservado | `0x0000` |
| 4  | 4 | **SessionID** | little-endian; `0` antes do login |
| 8  | 4 | sequência | little-endian; incrementa a cada envio |
| 12 | 1 | total de pacotes | `0` (fragmentação, quase sempre 0) |
| 13 | 1 | pacote atual | `0` |
| 14 | 2 | **Message ID** | little-endian (ver tabela) |
| 16 | 4 | tamanho do corpo | little-endian, inclui o `\n\0` final |

Tudo em little-endian. O corpo vem logo depois.

---

## 2. Login (o único ponto com "criptografia")

A senha **não** vai em texto puro. Vai o "hash Sofia" da Xiongmai, que o `H264_DVR_EncryptPassword`
monta assim (confirmado na decompilação):

```
md5 = MD5(senha)                      # 16 bytes
para i de 0 a 7:
    soma = md5[2i] + md5[2i+1]
    c    = soma % 62
    hash[i] = "0..9 A..Z a..z"[c]     # 8 caracteres no total
```

Teste de sanidade: `hash("")` == `tlJwpbo6` (valor conhecido). O cliente checa isso ao iniciar.

**Mensagem de login** (Message ID `1000`):
```json
{ "EncryptType":"MD5", "LoginType":"DVRIP-Web", "UserName":"USUARIO", "PassWord":"<hash de 8 chars>" }
```
**Resposta:** `{ "Ret":100, "SessionID":"0x0000001b", ... }` — `Ret:100` = sucesso. Guarde o `SessionID`.

A partir daí, todo comando leva `"SessionID":"0x........"` no corpo.

---

## 3. Catálogo de comandos

Os nomes (`OP...`) saíram direto da NetSdk.dll. O padrão é: a resposta usa Message ID = pedido+1,
e o corpo é `{ "Name":"<bloco>", "SessionID":"0x..", ... }`. `Ret:100` = ok.

### Message IDs principais (o que o app usa)

| Message ID | Função |
|-----------:|--------|
| 1000 | Login |
| 1006 | KeepAlive (heartbeat) |
| 1020 | SystemInfo (modelo, serial, versão) |
| 1042 / 1040 | **GetConfig / SetConfig** (lê/grava qualquer bloco por nome) |
| 1360 | Ability (recursos suportados pelo aparelho) |
| 1400 | `OPPTZControl` — PTZ (girar/zoom, se houver) |
| 1410 / 1413 | `OPMonitor` — abrir fluxo de vídeo ao vivo |
| 1424 / 1425 | `OPPlayBack` — reprodução de gravação |
| 1434 | `OPTalk` — áudio bidirecional |
| 1440 | `OPFileQuery` — buscar gravações no cartão |
| 1472 | lista de usuários / permissões (`AuthorityList`, `Users`) |
| 1452 / 1453 | `OPTimeQuery` / `OPTimeSetting` — relógio |

### Comandos `OP...` encontrados na DLL (lista completa)

Operação / controle:
`OPMonitor` (vídeo ao vivo), `OPPlayBack` (reprodução), `OPTalk` (áudio), `OPPTZControl` (PTZ),
`OPFileQuery` (lista de gravações), `OPLogQuery` / `OPLogManager` (logs), `OPStorageManager`
(cartão/HD), `OPTimeQuery` / `OPTimeSetting` / `OPTimeSettingNewWay` / `OPUTCTimeSetting` (hora),
`OPMachine` (reboot/shutdown), `OPDefaultConfig` (restaurar padrão), `OPSystemUpgrade` /
`OPStartUpgradeReq` / `OPFileUpgradeIPCReq` / `OPCloudUpgradeIPCReq` (firmware),
`OPSetOSD` / `OPSetOSDInfo` / `OPLogoSetting` (texto/logo na imagem), `OPSplitControl` (mosaico),
`OPNetAlarm` / `OPNetKeyboard` (alarme/teclado em rede), `OPSnap`/`OPCompressPic`/`OPBreviaryPic`
(foto/miniatura), `OPSendLearnCode` (aprendizado de controle), `OPFishEyeModifyCenter` (olho-de-peixe).

Busca / descoberta / rede:
`OPLocalSearch` / `OPNetSerach` (procurar aparelhos), `OPDIGSetIP` (setar IP), `OPNetModeSwitch`,
`OPDDNSAPPLY`, `OPMailTest`, `OPFTPTest`, `OPPhone`, `OPNetFileContral`.

Inteligência / placas (LPR):
`OPIntellAbilty` / `OPIntellInfo` / `OPIntelAnalysePushImg`, `OPLprForceRecognize` /
`OPLprBlackWhiteOpt` / `OPLprLedShow`, `OPPlateDetectLiftBar`, `OPGetPgsState` / `OPPgsGetImg`.

Outros: `OPVersionList`/`OPReqVersion`, `OPGetSensorAbility`/`OPSetSensorAbility`,
`OPGetCustomData`/`OPSetCustomData`, `OPGetActivationCode`, `OPOnlineUsrList`, `OPIPCLogExport`,
`OPMusicFileQuery`/`OPMusicPlay`, `OPCameraVisca`, `OPNetCoaxialCtrl`.

### Blocos de configuração (usados com GetConfig/SetConfig 1042/1040)

`General`, `General.Location`, `NetWork.NetCommon` (IP/portas), `NetWork.OnvifPwdCheckout`,
`Camera.Param` / `CameraPARAM` (imagem), `Simplify.Encode` (resolução/bitrate do vídeo),
`AVEnc.VideoWidget` (OSD), `Detect.MotionDetect` (detecção de movimento), `Alarm` / `AlarmConfig`,
`Record` (agenda de gravação), `Storage` / `StorageInfo` (cartão), `Uart` (serial/PTZ),
`fVideo`, `Users` / `AuthorityList` (contas e permissões).

---

## 4. Como usar o cliente (`xmcam.js`)

Pré-requisito: Node.js. Sem instalar nada. Rode dentro da pasta `pc/`.

```bash
# login + info do sistema + um bloco de config
node xmcam.js IP_DA_CAMERA USUARIO SENHA

# ler outro bloco (ex.: configuração de vídeo)
node xmcam.js IP_DA_CAMERA USUARIO SENHA Simplify.Encode
```

Para programar em cima dele:

```js
const { XMCam } = require('./xmcam');
const cam = new XMCam('IP_DA_CAMERA');
await cam.connect();
await cam.login('USUARIO', 'SENHA');
console.log((await cam.getConfig('Camera.Param')).json);
// comando cru: cam.send(MESSAGE_ID, { ...corpo..., SessionID: cam.sid() })
```

Para **vídeo ao vivo** o caminho mais simples continua sendo RTSP no VLC
(`rtsp://IP_DA_CAMERA:554/user=USUARIO&password=SENHA&channel=1&stream=0.sdp`).
O `OPMonitor` (1410) deste protocolo entrega o fluxo bruto H.264, que precisa ser remontado —
dá bem mais trabalho do que o RTSP e só compensa se você quiser tudo num app só.

---

## 5. Limites desta análise

- Cobri **o canal de controle** (porta 34567): login, config e comandos. O suficiente para ler e
  mudar ajustes da câmera a partir do seu código.
- **Não** implementei o decodificador do fluxo de vídeo do `OPMonitor` nem o pareamento pela
  nuvem/P2P (serial) — são bem maiores. O vídeo você já tem via RTSP.
- Os message-IDs de login/info/config foram testados e funcionam. Os demais `OP...` estão
  catalogados a partir da DLL; o corpo exato de cada um, se você for usar, dá para confirmar
  rodando contra a câmera (o `Ret` diz se o formato estava certo).
