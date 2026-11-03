'use strict';
// Grava o RTSP em segmentos de N segundos e, se o Telegram estiver ligado,
// envia cada segmento fechado para o canal e apaga o local (opcional).
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const telegram = require('./telegram');

let sweeping = false;   // compartilhado entre os gravadores

class Recorder {
  constructor(cfg, ffmpegPath, rtspUrl, recDir, log = console.log, label = 'Camera') {
    this.cfg = cfg; this.ff = ffmpegPath; this.url = rtspUrl;
    this.dir = recDir; this.log = log; this.label = label;
    this.proc = null; this.sent = new Set(); this.stopping = false; this.poller = null;
  }

  start() {
    fs.mkdirSync(this.dir, { recursive: true });
    this._spawn();
    // um sweep por vez: um envio lento ao Telegram nao pode ser disparado de novo em paralelo
    // um envio por vez, mesmo com varias cameras: o upload e lento e nao pode repetir em paralelo
    this.poller = setInterval(() => {
      if (sweeping) return;
      sweeping = true;
      this._sweep().catch((e) => this.log('[rec] sweep:', e.message)).finally(() => { sweeping = false; });
    }, 15000);
  }

  // Fecha o clipe em andamento: 'q' no stdin faz o ffmpeg gravar o indice final do MP4.
  // (No Windows kill('SIGINT') mata abruptamente e deixa o clipe ilegivel.)
  stop() {
    this.stopping = true;
    if (this.poller) clearInterval(this.poller);
    const p = this.proc;
    if (!p) return Promise.resolve();
    return new Promise((resolve) => {
      const force = setTimeout(() => { p.kill(); resolve(); }, 8000);
      p.once('exit', () => { clearTimeout(force); resolve(); });
      try { p.stdin.write('q'); } catch (e) { p.kill(); }
    });
  }

  _spawn() {
    const secs = this.cfg.recording.segmentSeconds || 600;
    const pattern = path.join(this.dir, 'rec_%Y%m%d_%H%M%S.mp4');
    // transcode para H.264+AAC (compat Telegram); clipe de 10min ~ 9 MB no substream.
    const args = [
      '-rtsp_transport', 'tcp', '-timeout', '8000000', '-i', this.url, // timeout: sem ele uma conexao travada para a gravacao em silencio
      '-c:v', 'libx264', '-preset', 'veryfast', '-g', '25',
      '-force_key_frames', `expr:gte(t,n_forced*${secs})`,
      '-c:a', 'aac', '-b:a', '64k',
      '-f', 'segment', '-segment_time', String(secs),
      '-reset_timestamps', '1', '-strftime', '1', '-segment_format_options', 'movflags=+faststart', // +faststart direto nao vale para o muxer segment
      pattern,
    ];
    this.log('[rec] ' + this.label + ': iniciando ffmpeg, segmentos de', secs, 's em', this.dir);
    this.proc = spawn(this.ff, args, { windowsHide: true });
    this.proc.stderr.on('data', () => {}); // silencioso; ffmpeg loga muito
    this.proc.on('exit', (code) => {
      this.proc = null;
      if (this.stopping) return;
      this.log('[rec] ffmpeg saiu (code', code + '), reiniciando em 5s');
      setTimeout(() => { if (!this.stopping) this._spawn(); }, 5000);
    });
  }

  async _sweep() {
    const files = fs.readdirSync(this.dir).filter((f) => f.endsWith('.mp4')).sort();
    if (files.length <= 1) return;              // o mais novo ainda esta gravando
    const finished = files.slice(0, -1);        // todos menos o ultimo
    for (const f of finished) {
      if (this.sent.has(f)) continue;
      const full = path.join(this.dir, f);
      try {
        const sz = fs.statSync(full).size;
        if (sz < 1024) continue;                // ainda vazio
        const tg = this.cfg.telegram;
        if (tg.enabled && tg.token && tg.chatId) {
          if (sz > 50 * 1024 * 1024) {
            this.log('[rec]', f, 'passou de 50MB, Telegram recusa; mantido local.');
            this.sent.add(f); continue;
          }
          await telegram.sendVideo(tg.token, tg.chatId, full,
            this.label + ' ' + f.replace('rec_', '').replace('.mp4', ''));
          this.sent.add(f);                       // marca ja: se apagar falhar, nao reenvia em loop
          this.log('[rec] enviado ao Telegram:', f, '(' + Math.round(sz / 1024) + ' KB)');
          if (!this.cfg.recording.keepLocalCopies) {   // so apaga depois de enviar
            try { fs.unlinkSync(full); }
            catch (e) { if (e.code !== 'ENOENT') this.log('[rec] nao consegui apagar', f + ':', e.message); }
          }
        }
        this.sent.add(f);
      } catch (e) {
        this.log('[rec] falha em', f + ':', e.message); // tenta de novo no proximo sweep
      }
    }
  }
}

module.exports = { Recorder };
