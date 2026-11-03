'use strict';
// Teste do gravador. Rode: node test-recorder.js   (o passo 2 usa a camera de verdade, ~15 s)
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const { Recorder } = require('./recorder');
const cfgFile = require('./config')();
const FF = path.join(__dirname, 'bin', 'ffmpeg.exe');
const FP = path.join(__dirname, 'bin', 'ffprobe.exe');
const mk = () => fs.mkdtempSync(path.join(os.tmpdir(), 'cambox-test-'));
const quiet = () => {};

(async () => {
  // 1) Telegram ligado sem chatId: o clipe NAO pode ser apagado.
  const d1 = mk();
  fs.writeFileSync(path.join(d1, 'rec_20260101_000000.mp4'), Buffer.alloc(2048)); // clipe antigo
  fs.writeFileSync(path.join(d1, 'rec_20260101_001000.mp4'), Buffer.alloc(2048)); // o mais novo (em gravacao)
  const r1 = new Recorder({ recording: { keepLocalCopies: false }, telegram: { enabled: true, token: 'x', chatId: '' } }, FF, 'x', d1, quiet);
  await r1._sweep();
  assert.ok(fs.existsSync(path.join(d1, 'rec_20260101_000000.mp4')), 'clipe foi apagado sem ser enviado');
  console.log('ok 1: clipe nao e apagado quando o envio esta incompleto');

  // 2) stop() deve fechar o clipe: o arquivo precisa ser legivel (ffprobe acha a duracao).
  const d2 = mk();
  const c = cfgFile.cameras[0];
  const url = `rtsp://${c.host}:${c.rtspPort}/user=${c.user}&password=${c.pass}&channel=${c.rtspChannel}&stream=${c.recordStream}.sdp`;
  const r2 = new Recorder({ recording: { segmentSeconds: 10, keepLocalCopies: true }, telegram: { enabled: false } }, FF, url, d2, quiet);
  r2.start();
  await new Promise((r) => setTimeout(r, 14000));
  await r2.stop();
  const files = fs.readdirSync(d2).filter((f) => f.endsWith('.mp4')).sort();
  assert.ok(files.length >= 1, 'nenhum clipe gravado (camera acessivel?)');
  const last = path.join(d2, files[files.length - 1]);
  const dur = parseFloat(execFileSync(FP, ['-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', last]).toString());
  assert.ok(dur > 0, 'ultimo clipe ilegivel apos stop()');
  console.log('ok 2: ultimo clipe legivel apos stop() (' + dur.toFixed(1) + ' s, ' + files.length + ' arquivo(s))');
  fs.rmSync(d1, { recursive: true, force: true }); fs.rmSync(d2, { recursive: true, force: true });
})().catch((e) => { console.error('FALHOU:', e.message); process.exit(1); });
