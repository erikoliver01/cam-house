#!/usr/bin/env node
'use strict';
// SOMENTE LEITURA: le recursos e configuracoes da camera e salva em recursos-camera.json.
// Nao altera nada (usa so GetConfig 1042, Ability 1360 e SystemInfo 1020).
// Nao imprime senha nem token. Uso: node ler-recursos.js [numero da camera, comecando em 0]
// Atencao: o arquivo gerado tem numero de serie e dados de rede da camera. Nao publique.
const fs = require('fs');
const path = require('path');
const { XMCam } = require('./xmcam');

const C = require('./config')(__dirname).cameras[Number(process.argv[2]) || 0];

const ITENS = [
  ['SystemInfo', 1020],
  ['SystemFunction', 1360],
  ['EncodeCapability', 1360],
  ['Camera', 1042],
  ['Simplify.Encode', 1042],
  ['Detect', 1042],
  ['AVEnc.VideoColor.[0]', 1042],
  ['General.General', 1042],
  ['Camera.WhiteLight', 1042],
  ['Detect.DetectTrack', 1042],
  ['Detect.HumanDetection', 1042],
  ['Uart.PTZ', 1042],
  ['fVideo.Volume', 1042],
];

async function abrir() {
  const cam = new XMCam(C.host, C.dvripPort || 34567);
  await cam.connect();
  await cam.login(C.user, C.pass);
  return cam;
}

(async () => {
  const out = {};
  let cam = await abrir();
  for (const [name, id] of ITENS) {
    try {
      const r = await cam.send(id, { Name: name, SessionID: cam.sid() }, 5000);
      const j = r.json || { raw: r.raw };
      out[name] = j;
      console.log(`${name.padEnd(24)} Ret=${j.Ret}`);
    } catch (e) {
      out[name] = { erro: e.message };
      console.log(`${name.padEnd(24)} erro: ${e.message}`);
      cam.close(); cam = await abrir();
    }
  }
  cam.close();
  const f = path.join(__dirname, 'recursos-camera.json');
  fs.writeFileSync(f, JSON.stringify(out, null, 2));
  console.log('\nSalvo em ' + f);
})().catch((e) => { console.error('erro:', e.message); process.exit(1); });
