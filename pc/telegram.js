'use strict';
// Envio ao Telegram via Bot API. Sem dependencias npm.
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

// Envio pelo curl.exe (vem no Windows 10/11): o fetch do Node travou em clipes grandes
// (conexao aberta sem enviar nada, 29/10/2026). O token vai pelo stdin (-K -), nao pela linha de comando.
const CURL = process.platform === 'win32' ? path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'curl.exe') : 'curl';
const SEND_TIMEOUT_S = 600;   // desiste do envio depois de 10 min; tenta de novo no proximo sweep

function sendVideo(token, chatId, filePath, caption) {
  return new Promise((resolve, reject) => {
    const args = ['-sS', '-K', '-', '--max-time', String(SEND_TIMEOUT_S),
      '-F', 'chat_id=' + chatId, '-F', 'supports_streaming=true',
      '-F', 'video=@' + filePath + ';type=video/mp4'];
    if (caption) args.push('--form-string', 'caption=' + caption);
    const p = spawn(CURL, args, { windowsHide: true });
    let out = '', err = '';
    p.stdout.on('data', (d) => { out += d; });
    p.stderr.on('data', (d) => { err += d; });
    p.on('error', (e) => reject(new Error('curl: ' + e.message)));
    p.on('close', (code) => {
      let js = {};
      try { js = JSON.parse(out); } catch (e) {}
      if (js.ok) return resolve(js.result);
      reject(new Error('telegram: ' + (js.description || ('curl saiu com codigo ' + code + ' ' + err.trim()))));
    });
    p.stdin.end('url = "https://api.telegram.org/bot' + token + '/sendVideo"\n');
  });
}

// Lista os chats que o bot ja viu (para descobrir o ID do canal).
async function getUpdates(token) {
  const resp = await fetch(`https://api.telegram.org/bot${token}/getUpdates`);
  const js = await resp.json();
  if (!js.ok) throw new Error('telegram: ' + (js.description || resp.status));
  const chats = new Map();
  for (const u of js.result) {
    const m = u.message || u.channel_post || u.my_chat_member;
    const c = m && m.chat;
    if (c) chats.set(c.id, { id: c.id, type: c.type, title: c.title || c.username || c.first_name || '' });
  }
  return [...chats.values()];
}

module.exports = { sendVideo, getUpdates };
