'use strict';
// Confere os sons sem tocar nada na camera: node teste-sons.js
const assert = require('assert');
const path = require('path');
const { prontos, pronto, paraAlaw, tocarEmRitmo, LOTE } = require('./sons');
const FF = path.join(__dirname, 'bin', 'ffmpeg.exe');

(async () => {
  const PRONTOS = prontos();
  if (!PRONTOS.length) console.log('(pasta sons\\ sem arquivos .al: pulando a checagem dos sons prontos)');
  for (const n of PRONTOS) {
    const a = pronto(n), seg = a.length / 8000;
    assert(seg >= 2 && seg <= 60, n + ' dura ' + seg);
    assert(new Set(a).size > 50, n + ' parece silencio');   // A-law: silencio repete poucos valores
    console.log(n, seg.toFixed(1) + ' s');
  }
  await assert.rejects(paraAlaw(FF, Buffer.from('isto nao e audio')), /nao reconhecido/);

  // ritmo: com a camera consumindo 8 bytes/ms, a fila nunca pode passar de 0,4 s e tudo tem de sair
  const alaw = Buffer.alloc(8000);
  let fila = 0, total = 0, maior = 0;
  const consumo = setInterval(() => { fila = Math.max(0, fila - 80); }, 10);
  await new Promise((resolve) => {
    tocarEmRitmo(alaw, {
      fila: () => fila,
      push: (b) => { fila += b.length; total += b.length; maior = Math.max(maior, fila); },
      fim: (ok) => { assert(ok); resolve(); },
    });
  });
  clearInterval(consumo);
  assert.strictEqual(total, alaw.length);
  assert(maior <= 3200, 'fila chegou a ' + maior);
  assert(LOTE === 1600);

  // conversa caiu no meio: avisa e para
  await new Promise((resolve) => {
    tocarEmRitmo(alaw, { fila: () => null, push: () => assert.fail('nao deveria enviar'), fim: (ok) => { assert(!ok); resolve(); } });
  });
  console.log('ok');
})().catch((e) => { console.error(e); process.exit(1); });
