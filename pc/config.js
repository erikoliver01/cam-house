'use strict';
// Carrega config.json e junta os segredos de segredos.json (por id de camera).
// Sem segredos.json, ou com campo faltando, vale o que estiver no config.json.
const fs = require('fs');
const path = require('path');

function carregarConfig(dir = __dirname) {
  const cfg = JSON.parse(fs.readFileSync(path.join(dir, 'config.json'), 'utf8'));
  const f = path.join(dir, 'segredos.json');
  const seg = fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : {};
  for (const c of cfg.cameras || []) {
    const s = (seg.cameras || {})[c.id] || {};
    if (s.user) c.user = s.user;
    if (s.pass) c.pass = s.pass;
  }
  if (seg.telegram && seg.telegram.botToken) (cfg.telegram = cfg.telegram || {}).token = seg.telegram.botToken;
  if (seg.app && seg.app.senha) (cfg.app = cfg.app || {}).senha = seg.app.senha;
  return cfg;
}

module.exports = carregarConfig;
