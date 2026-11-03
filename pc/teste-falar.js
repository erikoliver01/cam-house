'use strict';
// Teste unico: 2 s de bip 440 Hz baixo (20%) na 1a camera do config.json via OPTalk (go2rtc pkg/dvrip). Nao imprime senha.
// Uso: node teste-falar.js [numero da camera, comecando em 0]
const path = require('path');
const DIR = __dirname;
const { XMCam } = require(path.join(DIR, 'xmcam'));
const C = require(path.join(DIR, 'config'))(DIR).cameras[Number(process.argv[2]) || 0];

function alaw(s) {               // PCM 16 bits -> G.711 A-law (ITU-T)
  let sign = 0x80;
  if (s < 0) { sign = 0; s = -s - 1; }
  if (s > 32635) s = 32635;
  let exp = 7;
  for (let m = 0x4000; (s & m) === 0 && exp > 0; m >>= 1) exp--;
  const mant = exp === 0 ? (s >> 4) & 0x0f : (s >> (exp + 3)) & 0x0f;
  return ((sign | (exp << 4) | mant) ^ 0x55) & 0xff;
}

(async () => {
  const cam = new XMCam(C.host, C.dvripPort);
  await cam.connect();
  await cam.login(C.user, C.pass);
  const msg = (a) => ({ Name: 'OPTalk', SessionID: cam.sid(), OPTalk: { Action: a, AudioFormat: { EncodeType: 'G711_ALAW' } } });
  const r1 = await cam.send(1434, msg('Claim'), 4000);
  console.log('Claim Ret', r1.json && r1.json.Ret);
  if (!r1.json || r1.json.Ret !== 100) { cam.close(); return; }
  let r2 = null;
  try { r2 = await cam.send(1430, msg('Start'), 3000); } catch (e) { console.log('Start: sem resposta (' + e.message + ')'); }
  if (r2) console.log('Start Ret', r2.json && r2.json.Ret);

  const N = 50, SZ = 320; let t = 0;            // 50 x 40 ms = 2 s
  for (let p = 0; p < N; p++) {
    const pkt = Buffer.alloc(8 + SZ);
    pkt.writeUInt32BE(0x1FA, 0); pkt[4] = 14; pkt[5] = 2; pkt.writeUInt16LE(SZ, 6);
    for (let i = 0; i < SZ; i++, t++) pkt[8 + i] = alaw(Math.round(0.2 * 32767 * Math.sin(2 * Math.PI * 440 * t / 8000)));
    const head = Buffer.alloc(20);
    head[0] = 0xFF; head.writeUInt32LE(cam.session, 4); head.writeUInt32LE(cam.seq++, 8);
    head.writeUInt16LE(1432, 14); head.writeUInt32LE(pkt.length, 16);
    cam.sock.write(Buffer.concat([head, pkt]));
    await new Promise((r) => setTimeout(r, 40));
  }
  console.log('enviados', N, 'pacotes (2 s)');
  await new Promise((r) => setTimeout(r, 500));
  cam.close();
})().catch((e) => { console.log('erro:', e.message); process.exit(1); });
