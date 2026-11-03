#!/usr/bin/env node
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const crypto = require('crypto');
const { XMCam } = require('./xmcam');
const { Recorder } = require('./recorder');
const sons = require('./sons');

const ROOT = __dirname;
const cfg = require('./config')(ROOT);   // config.json + segredos.json
const FF = path.join(ROOT, 'bin', 'ffmpeg.exe');
// Varias cameras: config.cameras = [{ id, name, host, user, pass, ... }]. O formato antigo
// (um bloco "camera") continua valendo como uma camera so.
const CAMS = (cfg.cameras || [Object.assign({ id: 'cam1', name: 'Camera' }, cfg.camera)]).map((c, i) => ({
  i, C: c, id: c.id || 'cam' + (i + 1), name: c.name || 'Camera ' + (i + 1),
  cam: null, camReady: false, failAt: 0, ptzMoving: null, ptzTimer: null, talk: null,
}));
// login e senha ainda nao preenchidos: a camera fica desligada (sem tentativas de login)
const configured = (k) => !!(k.C.user && k.C.pass && !/PREENCHA/i.test(k.C.user + k.C.pass));
function pickCam(u) {
  const k = CAMS[Number(u.searchParams.get('cam')) || 0];
  if (!k) throw new Error('camera invalida');
  return k;
}

function rtspUrl(k, stream) {
  const C = k.C, s = stream == null ? C.liveStream : stream;
  return `rtsp://${C.host}:${C.rtspPort}/user=${C.user}&password=${C.pass}&channel=${C.rtspChannel}&stream=${s}.sdp`;
}

// ---- conexao de controle (DVRIP) com reconexao + keepalive ----
// Depois de uma falha de login espera 60 s: senha errada repetida pode bloquear a camera.
const LOGIN_RETRY_MS = 60000;
async function ensureCam(k) {
  if (k.camReady && k.cam && k.cam.sock) return k.cam;
  if (!configured(k)) throw new Error(k.name + ': login/senha nao configurados');
  if (Date.now() - k.failAt < LOGIN_RETRY_MS) throw new Error(k.name + ': sem conexao (nova tentativa em 1 min)');
  try {
    k.cam = new XMCam(k.C.host, k.C.dvripPort);
    await k.cam.connect();
    await k.cam.login(k.C.user, k.C.pass);
    k.camReady = true;
    console.log('[ctl] ' + k.name + ' conectado, SessionID=' + k.cam.sid());
  } catch (e) {
    k.camReady = false; k.failAt = Date.now();
    if (k.cam) k.cam.close();
    console.log('[ctl] ' + k.name + ' falha login:', e.message); throw e;
  }
  return k.cam;
}
setInterval(() => {
  for (const k of CAMS) if (k.camReady && k.cam) k.cam.keepalive().catch(() => { k.camReady = false; });
}, 20000);

// ---- PTZ: gira enquanto a seta estiver apertada ----
// Seguranca: se o navegador parar de avisar (aba fechada, rede caiu), para sozinho.
const PTZ_CMDS = ['DirectionUp', 'DirectionDown', 'DirectionLeft', 'DirectionRight', 'ZoomTile', 'ZoomWide'];
const PTZ_AUTOSTOP_MS = 1500;
function stopPtz(k) {
  clearTimeout(k.ptzTimer);
  const m = k.ptzMoving; k.ptzMoving = null;
  if (!m || !k.cam) return Promise.resolve();
  return k.cam.ptzStop(m.cmd, m.step).catch((e) => console.log('[ptz] falha ao parar:', e.message));
}
function armPtzStop(k) {
  clearTimeout(k.ptzTimer);
  k.ptzTimer = setTimeout(() => { console.log('[ptz] ' + k.name + ' parada automatica'); stopPtz(k); }, PTZ_AUTOSTOP_MS);
}

// ---- ajustes da camera (painel "Ajustes") ----
// Cada ajuste aponta para um campo de um bloco de config DVRIP. Para gravar: le o bloco inteiro,
// muda so esse campo e devolve o bloco (mesmo jeito do set_info da OpenIPC python-dvr).
// Valores confirmados lendo a camera depois de mudar no iCSee (29/10/2026).
const HEX = (b) => (b ? '0x00000001' : '0x00000000');
const SETTINGS = {
  luzModo:     { block: 'Camera.WhiteLight', path: ['WorkMode'], options: ['Auto', 'Close', 'Intelligent'] },
  rastreio:    { block: 'Detect.DetectTrack', path: ['Enable'], bool: (v) => (v ? 1 : 0) },
  movimento:   { block: 'Detect.MotionDetect', path: [0, 'Enable'], bool: (v) => !!v },
  humano:      { block: 'Detect.HumanDetection', path: [0, 'Enable'], bool: (v) => !!v },
  girarVert:   { block: 'Camera.Param', path: [0, 'PictureFlip'], bool: HEX },
  girarHoriz:  { block: 'Camera.Param', path: [0, 'PictureMirror'], bool: HEX },
  volume:      { block: 'fVideo.Volume', path: [0, 'LeftVolume'], also: [[0, 'RightVolume']], min: 1, max: 100 },
  // escalas calibradas mudando no iCSee e relendo a Porta (calib\diff.js, 29/10/2026)
  luzSensib:    { block: 'Camera.WhiteLight', path: ['MoveTrigLight', 'Level'], choices: [1, 3, 5] },
  luzDuracao:   { block: 'Camera.WhiteLight', path: ['MoveTrigLight', 'Duration'], choices: [5, 10, 15, 30, 60] },
  rastrSensib:  { block: 'Detect.DetectTrack', path: ['Sensitivity'], choices: [0, 1, 2] },
  rastrRetorno: { block: 'Detect.DetectTrack', path: ['ReturnTime'], choices: [3, 5, 10, 15, 30] },
  movNivel:     { block: 'Detect.MotionDetect', path: [0, 'Level'], choices: [1, 3, 6] },
};
// So leitura: a escala do brilho nao foi confirmada (o iCSee nao mostra esse ajuste).
const SETTINGS_RO = {
  luzBrilho:    { block: 'Camera.WhiteLight', path: ['Brightness'] },
};
const getPath = (o, p) => p.reduce((a, k) => (a == null ? undefined : a[k]), o);
function setPath(o, p, v) { const last = p[p.length - 1]; getPath(o, p.slice(0, -1))[last] = v; }
async function readBlock(c, block) {
  const r = await c.getConfig(block);
  if (!r.json || r.json.Ret !== 100 || r.json[block] == null) throw new Error(block + ': Ret=' + (r.json && r.json.Ret));
  return r.json[block];
}
const backedUp = new Set();
function backupBlock(id, block, data) {   // copia do bloco antes da 1a gravacao desta execucao
  if (backedUp.has(id + block)) return;
  const dir = path.join(ROOT, 'backup-camera'); fs.mkdirSync(dir, { recursive: true });
  const ts = new Date().toISOString().replace(/[:.]/g, '-');
  fs.writeFileSync(path.join(dir, id + '_' + block + '_' + ts + '.json'), JSON.stringify(data, null, 2));
  backedUp.add(id + block);
}
async function readSettings(c) {
  const out = {}, blocks = {};
  const all = Object.assign({}, SETTINGS, SETTINGS_RO);
  for (const b of new Set(Object.values(all).map((s) => s.block))) {
    try { blocks[b] = await readBlock(c, b); } catch (e) { blocks[b] = null; console.log('[ajustes] ' + e.message); }
  }
  for (const [k, s] of Object.entries(all)) {
    const v = blocks[s.block] == null ? undefined : getPath(blocks[s.block], s.path);
    out[k] = { value: v === undefined ? null : v, editable: k in SETTINGS && v !== undefined };
  }
  return out;
}
async function writeSetting(k, c, key, raw) {
  const s = SETTINGS[key];
  if (!s) throw new Error('ajuste invalido');
  let v;
  if (s.options) { if (!s.options.includes(raw)) throw new Error('valor invalido'); v = raw; }
  else if (s.choices) { v = Number(raw); if (!s.choices.includes(v)) throw new Error('valor invalido'); }
  else if (s.bool) { if (raw !== '0' && raw !== '1') throw new Error('valor invalido'); v = s.bool(raw === '1'); }
  else { v = Number(raw); if (!Number.isInteger(v) || v < s.min || v > s.max) throw new Error('valor invalido'); }
  const data = await readBlock(c, s.block);
  if (getPath(data, s.path) === undefined) throw new Error('campo nao existe nesta camera');
  backupBlock(k.id, s.block, data);
  setPath(data, s.path, v);
  for (const p of s.also || []) setPath(data, p, v);
  const r = await c.setConfig(s.block, data);
  const ret = r.json && r.json.Ret;
  console.log('[ajustes] ' + k.name + ' ' + key + ' = ' + JSON.stringify(v) + ' -> Ret ' + ret);
  const now = getPath(await readBlock(c, s.block), s.path);   // confere lendo de novo
  return { ret, value: now };
}

// ---- helpers HTTP ----
function sendJson(res, obj, code = 200) {
  const b = Buffer.from(JSON.stringify(obj));
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': b.length });
  res.end(b);
}
function serveFile(res, file, type) {
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); res.end('nao encontrado'); return; }
    res.writeHead(200, { 'Content-Type': type });
    res.end(data);
  });
}

// ---- video ao vivo: ffmpeg -> MJPEG multipart ----
function liveMjpeg(k, req, res) {
  const args = ['-rtsp_transport', 'tcp', '-timeout', '8000000', '-i', rtspUrl(k),
    '-an', '-r', '12', '-q:v', '4', '-f', 'mpjpeg', '-'];   // q:v menor = mais nitido (stream principal)
  let ff = null, closed = false, headerSent = false, pending = Buffer.alloc(0);
  // reabre o ffmpeg se a camera cortar o RTSP (ex.: ao girar para um preset)
  const start = () => {
    ff = spawn(FF, args, { windowsHide: true });
    ff.stdout.on('data', (chunk) => {
      if (headerSent) { res.write(chunk); return; }
      pending = Buffer.concat([pending, chunk]);
      const nl = pending.indexOf('\r\n');
      if (nl < 0) return;
      const boundary = pending.subarray(0, nl).toString('ascii').trim().replace(/^--/, '');
      res.writeHead(200, {
        'Content-Type': 'multipart/x-mixed-replace; boundary=' + boundary,
        'Cache-Control': 'no-cache', 'Connection': 'close',
      });
      res.write(pending); pending = null; headerSent = true;
    });
    ff.stderr.on('data', () => {});
    ff.on('exit', () => { ff = null; if (!closed) setTimeout(() => { if (!closed) start(); }, 1500); });
  };
  req.on('close', () => {
    closed = true;
    if (ff) { try { ff.kill('SIGINT'); } catch (e) {} }
    try { res.end(); } catch (e) {}
  });
  start();
}

// ---- som ao vivo: ffmpeg -> MP3 continuo (o MJPEG nao leva audio) ----
// Usa o substream (recordStream): o audio e o mesmo e a camera aguenta melhor.
function liveAudio(k, req, res) {
  const args = ['-rtsp_transport', 'tcp', '-timeout', '8000000', '-fflags', 'nobuffer', '-i', rtspUrl(k, k.C.recordStream),
    '-vn', '-ac', '1', '-c:a', 'libmp3lame', '-b:a', '64k', '-flush_packets', '1', '-f', 'mp3', '-'];
  const ff = spawn(FF, args, { windowsHide: true });
  res.writeHead(200, { 'Content-Type': 'audio/mpeg', 'Cache-Control': 'no-cache', 'Connection': 'close' });
  ff.stdout.pipe(res);
  ff.stderr.on('data', () => {});
  ff.on('exit', () => { try { res.end(); } catch (e) {} });
  req.on('close', () => { try { ff.kill('SIGINT'); } catch (e) {} });
}

// ---- falar pela camera: OPTalk Claim 1434, Start 1430, dados 1432 (formato do go2rtc pkg/dvrip) ----
// Conexao DVRIP propria para a conversa; envia 1 pacote de 320 bytes A-law (40 ms) por vez.
const TALK_PKT = 320, TALK_IDLE_MS = 1500;
function talkStop(k) {
  const t = k.talk; if (!t) return;
  k.talk = null; clearInterval(t.timer); clearTimeout(t.idle);
  if (t.cam) t.cam.close();
  console.log('[talk] ' + k.name + ' parou');
}
async function talkOpen(k) {
  const cam = new XMCam(k.C.host, k.C.dvripPort);
  await cam.connect(); await cam.login(k.C.user, k.C.pass);
  const msg = (a) => ({ Name: 'OPTalk', SessionID: cam.sid(), OPTalk: { Action: a, AudioFormat: { EncodeType: 'G711_ALAW' } } });
  const r = await cam.send(1434, msg('Claim'), 4000);
  if (!r.json || r.json.Ret !== 100) { cam.close(); throw new Error('camera recusou a conversa (Ret ' + (r.json && r.json.Ret) + ')'); }
  await cam.send(1430, msg('Start'), 3000).catch(() => {});   // a camera responde sem JSON
  return cam;
}
async function talkPush(k, data) {
  if (!k.talk) {
    const t = k.talk = { cam: null, q: Buffer.alloc(0), timer: null, idle: null, ticks: 0 };
    t.ready = talkOpen(k).then((cam) => {
      if (k.talk !== t) return cam.close();
      t.cam = cam; t.timer = setInterval(() => talkTick(t), 40);
      console.log('[talk] ' + k.name + ' falando');
    });
    t.ready.catch((e) => { console.log('[talk] ' + k.name + ': ' + e.message); if (k.talk === t) talkStop(k); });
  }
  const t = k.talk;
  t.q = Buffer.concat([t.q, data]);
  if (t.q.length > 3200) t.q = t.q.subarray(-1600);   // fila acima de 0,4 s vira atraso fixo: fica so com os ultimos 0,2 s
  clearTimeout(t.idle); t.idle = setTimeout(() => talkStop(k), TALK_IDLE_MS);
  await t.ready;
}
function talkEnd(k) {   // soltou o botao: toca o que ainda esta na fila e fecha
  const t = k.talk; if (!t) return;
  clearTimeout(t.idle); t.idle = setTimeout(() => talkStop(k), t.q.length / 8 + 300);
}
// ---- sons: mesma conversa do Falar; o arquivo entra na fila aos poucos ----
const SOM_MAX_BYTES = 10 * 1024 * 1024;
function somParar(k) {
  if (!k.som) return;
  k.som.parar(); k.som = null;
  talkStop(k);
}
async function somTocar(k, alaw) {
  if (k.som) somParar(k);
  const s = k.som = { parar: () => {} };
  await talkPush(k, alaw.subarray(0, sons.LOTE));
  if (k.som !== s) return;
  s.parar = sons.tocarEmRitmo(alaw.subarray(sons.LOTE), {
    fila: () => (k.talk ? k.talk.q.length : null),
    push: (b) => talkPush(k, b).catch(() => {}),
    fim: () => { if (k.som === s) k.som = null; if (k.talk) k.talk.deSom = true; talkEnd(k); },   // deSom: o som ja acabou, so falta esvaziar a fila
  });
}

function talkTick(t) {
  if (++t.ticks % 50 === 0) console.log('[talk] fila ' + Math.round(t.q.length / 8) + ' ms');   // a cada 2 s
  if (t.q.length < TALK_PKT || !t.cam || !t.cam.sock) return;
  const pkt = Buffer.alloc(8 + TALK_PKT);
  pkt.writeUInt32BE(0x1FA, 0); pkt[4] = 14; pkt[5] = 2; pkt.writeUInt16LE(TALK_PKT, 6);   // 14 = G.711A, 2 = 8 kHz
  t.q.copy(pkt, 8, 0, TALK_PKT); t.q = t.q.subarray(TALK_PKT);
  const head = Buffer.alloc(20);
  head[0] = 0xFF; head.writeUInt32LE(t.cam.session, 4); head.writeUInt32LE(t.cam.seq++, 8);
  head.writeUInt16LE(1432, 14); head.writeUInt32LE(pkt.length, 16);
  t.cam.sock.write(Buffer.concat([head, pkt]));
}

// ---- snapshot: 1 frame -> JPEG ----
function snapshot(k, res) {
  const args = ['-rtsp_transport', 'tcp', '-i', rtspUrl(k),
    '-frames:v', '1', '-q:v', '3', '-f', 'mjpeg', '-'];
  const ff = spawn(FF, args, { windowsHide: true });
  const chunks = [];
  ff.stdout.on('data', (c) => chunks.push(c));
  ff.stderr.on('data', () => {});
  ff.on('exit', () => {
    const buf = Buffer.concat(chunks);
    if (!buf.length) { res.writeHead(502); res.end('sem imagem'); return; }
    res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Content-Length': buf.length });
    res.end(buf);
  });
}

// ---- painel de gravacao: dados do Moto G (quem grava e envia ao Telegram) ----
// Opcional (config.json "celular"). O PC entra no celular por SSH com a chave celular\ssh\id_ed25519 e roda um script curto
// mandado pela entrada padrao (nada e instalado no celular). So le pastas e o log de envio.
const CEL = Object.assign({ host: '', port: 8022, key: path.join('celular', 'ssh', 'id_ed25519'), seg: 300, cacheMs: 15000 }, cfg.celular || {});
const PAINEL_SH = [
  'cd ~/cambox 2>/dev/null || { echo "ERRO sem pasta ~/cambox"; exit 0; }',
  'echo "AGORA $(date +%s)"',
  'grep -E "^(SEG|CICLO)=" config.sh 2>/dev/null | sed "s/ *#.*//"',
  // .part = clipe ainda gravando (o gravar.sh renomeia para .mp4 ao terminar)
  'for p in gravacoes/*/; do [ -d "$p" ] || continue; id=$(basename "$p"); ls "$p" 2>/dev/null | grep "^rec_.*\\.mp4\\(\\.part\\)\\?$" | sort | while read -r f; do echo "ARQ $id $f $(stat -c %s "$p$f" 2>/dev/null)"; done; done',
  'grep " enviado: " logs/envio.log 2>/dev/null | tail -n 400',
  'echo "PROC $(pgrep -x ffmpeg | wc -l) $(pgrep -f cambox/enviar.sh | wc -l)"',
  '',
].join('\n');
let painelCache = null, painelAt = 0, painelRun = null;
// rec_20261031_135000 -> Date na hora local (PC e celular no mesmo fuso)
function carimbo(s) {
  const m = /(\d{4})(\d\d)(\d\d)_(\d\d)(\d\d)(\d\d)/.exec(s || '');
  return m ? new Date(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6]).getTime() : null;
}
function lerPainel(out) {
  const linhas = out.split(/\r?\n/);
  const erro = linhas.find((l) => l.startsWith('ERRO '));
  if (erro) return { error: erro.slice(5) };
  const agoraL = linhas.find((l) => l.startsWith('AGORA '));
  const agora = agoraL ? Number(agoraL.split(' ')[1]) * 1000 : Date.now();
  const hoje = new Date(agora); hoje.setHours(0, 0, 0, 0);
  const vars = {};
  for (const l of linhas) { const m = /^(SEG|CICLO)=(\d+)\s*$/.exec(l); if (m) vars[m[1]] = Number(m[2]); }
  const seg = vars.SEG || CEL.seg, ciclo = Math.max(seg, vars.CICLO || 600);
  const arqs = {}, enviados = {};
  for (const l of linhas) {
    let m;
    if ((m = /^ARQ (\S+) (rec_\S+\.mp4(?:\.part)?) (\d*)$/.exec(l))) (arqs[m[1]] = arqs[m[1]] || []).push({ ini: carimbo(m[2]), bytes: Number(m[3]) || 0 });
    else if ((m = /^(\d\d\/\d\d \d\d:\d\d:\d\d) enviado: (.+) (\d{8}_\d{6}) \((\d+) KB\)/.exec(l))) {
      const k = CAMS.find((c) => c.name === m[2]);
      if (k) (enviados[k.id] = enviados[k.id] || []).push({ ini: carimbo(m[3]), kb: Number(m[4]), quando: m[1] });
    }
  }
  const proc = (linhas.find((l) => l.startsWith('PROC ')) || 'PROC 0 0').split(' ').map(Number);
  const cams = {};
  for (const k of CAMS) {
    // inicio no futuro = nome do clipe com fuso errado no celular (horario de verao antigo); ignora
    const valido = (x) => x.ini && x.ini <= agora + 60000;
    const lista = (arqs[k.id] || []).filter(valido).sort((a, b) => a.ini - b.ini);
    const env = (enviados[k.id] || []).filter(valido).sort((a, b) => a.ini - b.ini);
    // o clipe enviado e apagado do celular, entao o mais novo pode estar so no log de envio
    const ultArq = lista[lista.length - 1], ultEnv = env[env.length - 1];
    const atual = (ultArq && (!ultEnv || ultArq.ini >= ultEnv.ini)) ? ultArq : (ultEnv ? { ini: ultEnv.ini, bytes: ultEnv.kb * 1024 } : null);
    const fim = atual ? atual.ini + seg * 1000 : null;
    // passou 2 min da volta prevista sem clipe novo = gravacao travada ou camera fora
    const estado = !atual ? 'parado' : agora < fim ? 'gravando' : agora < atual.ini + (ciclo + 120) * 1000 ? 'pausa' : 'parado';
    cams[k.id] = {
      estado,
      gravando: estado !== 'parado',
      atual: atual && { ini: atual.ini, fim, pct: Math.max(0, Math.min(100, Math.round((agora - atual.ini) / (seg * 10)))), mb: +(atual.bytes / 1048576).toFixed(1) },
      proximoInicio: atual && atual.ini + ciclo * 1000,
      proximoEnvio: atual && atual.ini + (ciclo + seg) * 1000,
      esperando: lista.filter((a) => a !== ultArq || estado !== 'gravando').length,
      hoje: env.filter((e) => e.ini >= hoje.getTime()).length,
      ultimos: env.slice(-3).reverse().map((e) => ({ ini: e.ini, fim: e.ini + seg * 1000, mb: +(e.kb / 1024).toFixed(1) })),
    };
  }
  return { agora, seg, ciclo, ffmpeg: proc[1] || 0, envio: (proc[2] || 0) > 0, cams };
}
function painel() {
  if (!CEL.host || /PREENCHA/i.test(CEL.host)) return Promise.resolve({ error: 'celular nao configurado (config.json > celular)' });
  if (painelCache && Date.now() - painelAt < CEL.cacheMs) return Promise.resolve(painelCache);
  if (painelRun) return painelRun;
  painelRun = new Promise((resolve) => {
    const args = ['-i', path.join(ROOT, CEL.key), '-p', String(CEL.port), '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=5',
      '-o', 'StrictHostKeyChecking=accept-new', CEL.host, 'bash -s'];
    let out = '', err = '', done = false;
    const fim = (r) => { if (done) return; done = true; clearTimeout(t); painelCache = r; painelAt = Date.now(); painelRun = null; resolve(r); };
    let p;
    try { p = spawn('ssh', args, { windowsHide: true }); } catch (e) { return fim({ error: 'ssh nao encontrado: ' + e.message }); }
    const t = setTimeout(() => { try { p.kill(); } catch (e) {} fim({ error: 'celular nao respondeu (' + CEL.host + ')' }); }, 12000);
    p.on('error', (e) => fim({ error: 'ssh nao encontrado: ' + e.message }));
    p.stdout.on('data', (d) => { out += d; if (out.length > 200000) p.kill(); });
    p.stderr.on('data', (d) => { err += d; });
    p.on('close', (code) => {
      if (code !== 0 && !out.includes('AGORA ')) return fim({ error: 'celular: ' + (err.trim().split(/\r?\n/).pop() || 'ssh saiu com codigo ' + code) });
      try { fim(lerPainel(out)); } catch (e) { fim({ error: 'resposta do celular ilegivel: ' + e.message }); }
    });
    p.stdin.end(PAINEL_SH);
  });
  return painelRun;
}

// ---- acesso pela rede (app Camhouse) ----
// config.server.lan = true escuta na rede; quem nao vem do proprio PC precisa da senha (segredos.json app.senha).
const LAN = !!cfg.server.lan;
const SENHA = (cfg.app && cfg.app.senha) || '';
// cookie = HMAC da senha: sobrevive a reinicios e cai sozinho se a senha mudar
const TOKEN = SENHA && crypto.createHmac('sha256', SENHA).update('camhouse-sessao').digest('hex');
const isLocal = (req) => /^(127\.0\.0\.1|::1|::ffff:127\.0\.0\.1)$/.test(req.socket.remoteAddress);
const sameText = (a, b) => crypto.timingSafeEqual(crypto.createHash('sha256').update(a).digest(), crypto.createHash('sha256').update(b).digest());
function hasSession(req) {
  const m = /(?:^|;\s*)camhouse=([0-9a-f]{64})/.exec(req.headers.cookie || '');
  return !!(TOKEN && m && sameText(m[1], TOKEN));
}
const LOGIN_HTML = (msg) => `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Camhouse</title><body style="font-family:sans-serif;background:#111;color:#eee;display:grid;place-items:center;height:90vh">
<form method="post" action="/login" style="display:grid;gap:12px;width:260px"><h2 style="margin:0">Camhouse</h2>
<input type="password" name="senha" placeholder="Senha" autofocus required style="padding:12px;font-size:16px">
<button style="padding:12px;font-size:16px">Entrar</button><div style="color:#f77">${msg}</div></form>`;
async function handleLogin(req, res) {
  if (req.method !== 'POST') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); return res.end(LOGIN_HTML('')); }
  let body = '';
  for await (const c of req) { body += c; if (body.length > 1000) { res.writeHead(413); return res.end(); } }
  const senha = new URLSearchParams(body).get('senha') || '';
  if (!SENHA || !sameText(senha, SENHA)) {
    await new Promise((r) => setTimeout(r, 1500));   // freia tentativa de adivinhar
    res.writeHead(401, { 'Content-Type': 'text/html; charset=utf-8' }); return res.end(LOGIN_HTML('Senha errada'));
  }
  res.writeHead(303, { Location: '/', 'Set-Cookie': `camhouse=${TOKEN}; Max-Age=31536000; Path=/; HttpOnly; SameSite=Strict` });
  res.end();
}

// ---- roteador ----
const HOSTS = [`127.0.0.1:${cfg.server.port}`, `localhost:${cfg.server.port}`];
// na rede aceita Host so como IP numerico: DNS rebinding precisa de um nome, nao de IP
const hostOk = (h) => HOSTS.includes(h) || (LAN && new RegExp(`^\\d{1,3}(\\.\\d{1,3}){3}:${cfg.server.port}$`).test(h || ''));
const server = http.createServer(async (req, res) => {
  // Barra outros sites do navegador: Host barra DNS rebinding; Sec-Fetch-Site barra pedidos de outra origem.
  const site = req.headers['sec-fetch-site'];
  if (!hostOk(req.headers.host) || (site && site !== 'same-origin' && site !== 'none')) {
    res.writeHead(403); return res.end('proibido');
  }
  const u = new URL(req.url, 'http://x');
  if (!isLocal(req)) {
    if (!SENHA) { res.writeHead(403); return res.end('acesso pela rede sem senha configurada'); }
    if (u.pathname === '/login') return handleLogin(req, res);
    if (!hasSession(req)) {
      if (u.pathname === '/') { res.writeHead(303, { Location: '/login' }); return res.end(); }
      res.writeHead(401); return res.end('faca login');
    }
  }
  try {
    if (u.pathname === '/' ) return serveFile(res, path.join(ROOT, 'public', 'index.html'), 'text/html; charset=utf-8');
    if (u.pathname === '/api/status') {
      return sendJson(res, { recording: cfg.recording.enabled, telegram: cfg.telegram.enabled,
        cameras: CAMS.map((k) => ({ id: k.id, name: k.name, camReady: k.camReady, configured: configured(k), presets: k.C.presets || null })) });
    }
    if (u.pathname === '/api/sons') return sendJson(res, { sons: sons.prontos() });
    if (u.pathname === '/api/gravacao') return sendJson(res, await painel());
    if (u.pathname === '/api/local') {   // local da casa no mapa (local.json)
      const f = path.join(ROOT, 'local.json');
      if (req.method === 'POST') {
        const lat = Number(u.searchParams.get('lat')), lon = Number(u.searchParams.get('lon'));
        if (!u.searchParams.get('lat') || !u.searchParams.get('lon') || !(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180)) return sendJson(res, { error: 'lat/lon invalidos' }, 400);
        const loc = { lat, lon, zoom: 19 };
        fs.writeFileSync(f, JSON.stringify(loc));
        return sendJson(res, loc);
      }
      return sendJson(res, fs.existsSync(f) ? Object.assign(JSON.parse(fs.readFileSync(f, 'utf8')), { fonte: 'salvo' }) : {});
    }
    const k = pickCam(u);
    if (u.pathname === '/api/som' && req.method === 'POST') {
      if (!configured(k)) return sendJson(res, { error: 'camera sem login/senha' }, 503);
      if (u.searchParams.get('stop')) { somParar(k); return sendJson(res, { ok: true }); }
      if (k.talk && !k.som && !k.talk.deSom) return sendJson(res, { error: 'pare de falar antes de tocar um som' }, 409);
      const nome = u.searchParams.get('nome');
      let alaw;
      if (nome) {
        if (!sons.prontos().includes(nome)) return sendJson(res, { error: 'som desconhecido' }, 400);
        alaw = sons.pronto(nome);
      } else {
        if (Number(req.headers['content-length']) > SOM_MAX_BYTES) { req.destroy(); return; }
        const partes = []; let n = 0;
        for await (const c of req) {
          n += c.length;
          if (n > SOM_MAX_BYTES) return sendJson(res, { error: 'arquivo maior que 10 MB' }, 413);
          partes.push(c);
        }
        if (!n) return sendJson(res, { error: 'arquivo vazio' }, 400);
        try { alaw = await sons.paraAlaw(FF, Buffer.concat(partes)); } catch (e) { return sendJson(res, { error: e.message }, 400); }
      }
      try { await somTocar(k, alaw); } catch (e) { somParar(k); return sendJson(res, { error: 'camera: ' + e.message }, 502); }
      return sendJson(res, { ok: true, ms: Math.round(alaw.length / 8) });
    }
    if (u.pathname === '/api/talk' && req.method === 'POST') {
      if (!configured(k)) return sendJson(res, { error: 'camera sem login/senha' }, 503);
      if (k.som) return sendJson(res, { error: 'a câmera está tocando um som' }, 409);
      if (u.searchParams.get('stop')) { talkEnd(k); return sendJson(res, { ok: true }); }
      const chunks = []; let n = 0;
      for await (const c of req) {
        n += c.length;
        if (n > 16000) return sendJson(res, { error: 'pacote de audio grande demais' }, 413);
        chunks.push(c);
      }
      await talkPush(k, Buffer.concat(chunks));
      return sendJson(res, { ok: true });
    }
    if (u.pathname === '/live.mjpeg' || u.pathname === '/snapshot.jpg' || u.pathname === '/live.mp3') {
      if (!configured(k)) { res.writeHead(503); return res.end('camera sem login/senha'); }
      if (u.pathname === '/live.mp3') return liveAudio(k, req, res);
      return u.pathname === '/live.mjpeg' ? liveMjpeg(k, req, res) : snapshot(k, res);
    }

    if (u.pathname === '/api/sysinfo') {
      const c = await ensureCam(k); const r = await c.systemInfo();
      return sendJson(res, (r.json && r.json.SystemInfo) || {});
    }
    if (u.pathname === '/api/ptz') {
      let cmd = u.searchParams.get('cmd'); const action = u.searchParams.get('action') || 'start';
      if (!PTZ_CMDS.includes(cmd)) return sendJson(res, { error: 'comando invalido' }, 400);
      // imagem espelhada (PictureMirror): sem trocar, a seta da direita gira para a esquerda (igual no iCSee e no app)
      if (k.C.invertePan !== false) cmd = { DirectionLeft: 'DirectionRight', DirectionRight: 'DirectionLeft' }[cmd] || cmd;
      const step = Math.min(8, Math.max(1, Number(u.searchParams.get('step')) || 3));   // 3 = mesma velocidade do app
      if (action === 'hold') {   // navegador avisa que a seta continua apertada
        if (k.ptzMoving && k.ptzMoving.cmd === cmd) armPtzStop(k);
        return sendJson(res, { ok: !!k.ptzMoving });
      }
      const c = await ensureCam(k);
      if (action === 'stop') {
        clearTimeout(k.ptzTimer); k.ptzMoving = null;
        const r = await c.ptzStop(cmd, step);
        return sendJson(res, { ret: r.json && r.json.Ret });
      }
      if (k.ptzMoving && k.ptzMoving.cmd !== cmd) await stopPtz(k);
      const r = await c.ptzStart(cmd, step);
      k.ptzMoving = { cmd, step }; armPtzStop(k);
      return sendJson(res, { ret: r.json && r.json.Ret });
    }
    if (u.pathname === '/api/preset') {
      const n = Number(u.searchParams.get('n'));
      if (!Number.isInteger(n) || n < 1 || n > 255) return sendJson(res, { error: 'preset invalido' }, 400);
      const c = await ensureCam(k);
      const r = await c.send(1400, { Name: 'OPPTZControl', SessionID: c.sid(), OPPTZControl: { Command: 'GotoPreset',
        Parameter: { AUX: { Number: 0, Status: 'On' }, Channel: 0, MenuOpts: 'Enter', POINT: { bottom: 0, left: 0, right: 0, top: 0 },
          Pattern: 'SetBegin', Preset: n, Step: 5, Tour: 0 } } });
      return sendJson(res, { ret: r.json && r.json.Ret });
    }
    if (u.pathname === '/api/config' && req.method === 'GET') {
      const c = await ensureCam(k); const name = u.searchParams.get('name');
      const r = await c.getConfig(name);
      return sendJson(res, (r.json && r.json[name]) || r.json || {});
    }
    if (u.pathname === '/api/settings' && req.method === 'GET') {
      return sendJson(res, await readSettings(await ensureCam(k)));
    }
    if (u.pathname === '/api/settings' && req.method === 'POST') {
      const r = await writeSetting(k, await ensureCam(k), u.searchParams.get('key'), u.searchParams.get('value'));
      return sendJson(res, r);
    }
    res.writeHead(404); res.end('nao encontrado');
  } catch (e) {
    sendJson(res, { error: e.message }, 500);
  }
});

server.listen(cfg.server.port, LAN ? '0.0.0.0' : '127.0.0.1', () => {
  console.log('[web] http://127.0.0.1:' + cfg.server.port + (LAN ? ' (rede liberada' + (SENHA ? ', com senha)' : ', SEM senha: acesso pela rede bloqueado)') : ''));
  const recs = [];
  for (const k of CAMS) {
    if (!configured(k)) { console.log('[ctl] ' + k.name + ': preencha login e senha no segredos.json'); continue; }
    ensureCam(k).catch(() => {});
    if (!cfg.recording.enabled) continue;
    // cada camera grava na sua subpasta; legenda no Telegram leva o nome da camera
    const recDir = path.join(ROOT, 'gravacoes', k.C.dir || k.id);
    const rec = new Recorder(cfg, FF, rtspUrl(k, k.C.recordStream), recDir, console.log, k.name);
    rec.start(); recs.push(rec);
  }
  for (const sig of ['SIGINT', 'SIGHUP', 'SIGTERM']) {
    process.on(sig, () => Promise.all(recs.map((r) => r.stop())).then(() => process.exit(0)));
  }
});
