'use strict';
// Sons para a camera tocar: os prontos ja convertidos em sons\*.al (A-law 8 kHz mono), ou um arquivo enviado (ffmpeg).
// Para criar um .al a partir de um mp3: node converter-som.js "meu som.mp3"
// A entrega e em ritmo para a fila do "Falar": nao passa de 0,4 s de folga.
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');

const TAXA = 8000, LOTE = 1600, MAX_SEG = 60;   // LOTE = 0,2 s de A-law
const PASTA = path.join(__dirname, 'sons');
// os sons prontos sao os arquivos .al da pasta sons\ (lidos na hora: pode pôr ou tirar sem reiniciar)
function prontos() {
  try { return fs.readdirSync(PASTA).filter((f) => /^[\w-]+\.al$/.test(f)).map((f) => f.slice(0, -3)).sort(); } catch (e) { return []; }
}
const pronto = (nome) => fs.readFileSync(path.join(PASTA, nome + '.al'));

// qualquer audio -> A-law 8 kHz mono com volume normalizado, ate MAX_SEG segundos
function converter(ff, arquivo) {
  return new Promise((resolve, reject) => {
    const p = spawn(ff, ['-nostdin', '-hide_banner', '-loglevel', 'error', '-protocol_whitelist', 'file,pipe', '-t', String(MAX_SEG), '-i', arquivo,
      '-vn', '-af', 'loudnorm=I=-14:TP=-1', '-ac', '1', '-ar', String(TAXA), '-f', 'alaw', 'pipe:1'], { windowsHide: true });
    const saida = []; let erro = '';
    const t = setTimeout(() => p.kill(), 60000);
    p.stdout.on('data', (d) => saida.push(d));
    p.stderr.on('data', (d) => { if (erro.length < 500) erro += d; });
    p.on('error', (e) => { clearTimeout(t); reject(e); });
    p.on('close', () => {
      clearTimeout(t);
      const a = Buffer.concat(saida);
      if (a.length) resolve(a);
      else reject(new Error('arquivo de audio nao reconhecido' + (erro ? ': ' + erro.trim().split(/\r?\n/).pop() : '')));
    });
  });
}
async function paraAlaw(ff, dados) {
  const tmp = path.join(os.tmpdir(), 'camhouse-som-' + crypto.randomBytes(6).toString('hex') + '.bin');
  await fs.promises.writeFile(tmp, dados);
  try { return await converter(ff, tmp); } finally { fs.promises.unlink(tmp).catch(() => {}); }
}

// entrega alaw em lotes so quando a fila tem folga; devolve a funcao que cancela
function tocarEmRitmo(alaw, o) {
  let pos = 0, timer = null;
  const parar = () => { clearInterval(timer); timer = null; };
  timer = setInterval(() => {
    const fila = o.fila();
    if (fila === null) { parar(); return o.fim(false); }   // conversa caiu
    if (fila >= LOTE) return;
    if (pos >= alaw.length) { parar(); return o.fim(true); }
    o.push(alaw.subarray(pos, pos + LOTE)); pos += LOTE;
  }, 50);
  return parar;
}

module.exports = { PASTA, prontos, pronto, converter, paraAlaw, tocarEmRitmo, LOTE };
