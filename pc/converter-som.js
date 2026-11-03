'use strict';
// Converte um audio (mp3, wav, ogg...) para o formato que a camera toca (A-law 8 kHz mono, ate 60 s)
// e salva em sons\<nome>.al. O botao aparece no menu "Sons" do app.
// Uso: node converter-som.js "meu som.mp3" [nome-do-botao]
const fs = require('fs');
const path = require('path');
const { PASTA, converter } = require('./sons');
const FF = path.join(__dirname, 'bin', process.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg');

const arq = process.argv[2];
if (!arq) { console.log('Uso: node converter-som.js "meu som.mp3" [nome-do-botao]'); process.exit(1); }
const nome = (process.argv[3] || path.parse(arq).name).normalize('NFD').replace(/[\u0300-\u036f]/g, '')
  .toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'som';
converter(FF, path.resolve(arq)).then((alaw) => {
  fs.mkdirSync(PASTA, { recursive: true });
  const f = path.join(PASTA, nome + '.al');
  fs.writeFileSync(f, alaw);
  console.log('ok: ' + f + ' (' + (alaw.length / 8000).toFixed(1) + ' s)');
}).catch((e) => { console.error('erro: ' + e.message); process.exit(1); });
