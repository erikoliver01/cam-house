#!/usr/bin/env node
/*
 * Cliente do protocolo DVRIP / NetSurveillance da Xiongmai (porta 34567).
 * Reconstruido da NetSdk.dll (iCSee/VMS) e verificado contra a camera real.
 * Sem dependencias.
 */
'use strict';
const net = require('net');
const crypto = require('crypto');

const MSG = {
  LOGIN: 1000, KEEPALIVE: 1006, SYSINFO: 1020,
  CONFIG_SET: 1040, CONFIG_GET: 1042, ABILITY_GET: 1360,
  PTZ: 1400, MONITOR_CLAIM: 1413, MONITOR_START: 1410, USERS: 1472,
};

// Hash de senha da Xiongmai: MD5 dobrado em 8 chars. (H264_DVR_EncryptPassword)
function sofiaHash(password) {
  const md5 = crypto.createHash('md5').update(password, 'utf8').digest();
  let out = '';
  for (let i = 0; i < 8; i++) {
    const n = (md5[2 * i] + md5[2 * i + 1]) % 62;
    if (n < 10) out += String.fromCharCode(48 + n);
    else if (n < 36) out += String.fromCharCode(65 + n - 10);
    else out += String.fromCharCode(97 + n - 36);
  }
  return out;
}

class XMCam {
  constructor(host, port = 34567) {
    this.host = host; this.port = port;
    this.session = 0; this.seq = 0;
    this.sock = null; this.buf = Buffer.alloc(0); this.waiters = [];
  }
  connect() {
    return new Promise((resolve, reject) => {
      this.sock = net.createConnection({ host: this.host, port: this.port }, resolve);
      this.sock.on('error', reject);
      this.sock.on('data', (d) => this._onData(d));
      this.sock.on('close', () => { this.sock = null; });
    });
  }
  close() { if (this.sock) { this.sock.end(); this.sock = null; } }

  _onData(d) {
    this.buf = Buffer.concat([this.buf, d]);
    while (this.buf.length >= 20) {
      const len = this.buf.readUInt32LE(16);
      if (this.buf.length < 20 + len) break;
      const msgid = this.buf.readUInt16LE(14);
      const session = this.buf.readUInt32LE(4);
      const body = this.buf.subarray(20, 20 + len);
      this.buf = this.buf.subarray(20 + len);
      const text = body.toString('utf8').replace(/[\n\x00]+$/g, '');
      let js = null; const t = text.trim();
      if (t.startsWith('{') || t.startsWith('[')) { try { js = JSON.parse(t); } catch (e) {} }
      const w = this.waiters.shift();
      if (w) w({ msgid, session, raw: text, json: js });
    }
  }
  send(msgid, payload, timeoutMs = 6000) {
    let body = typeof payload === 'string'
      ? Buffer.from(payload + '\n\x00', 'utf8')
      : Buffer.from(JSON.stringify(payload) + '\n\x00', 'utf8');
    const head = Buffer.alloc(20);
    head.writeUInt8(0xFF, 0);
    head.writeUInt32LE(this.session, 4);
    head.writeUInt32LE(this.seq, 8);
    head.writeUInt16LE(msgid, 14);
    head.writeUInt32LE(body.length, 16);
    this.seq++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        const i = this.waiters.indexOf(res); if (i >= 0) this.waiters.splice(i, 1);
        reject(new Error('timeout'));
      }, timeoutMs);
      const res = (r) => { clearTimeout(timer); resolve(r); };
      this.waiters.push(res);
      if (!this.sock) { clearTimeout(timer); return reject(new Error('sem conexao')); }
      this.sock.write(Buffer.concat([head, body]));
    });
  }

  async login(user, password) {
    const r = await this.send(MSG.LOGIN, {
      EncryptType: 'MD5', LoginType: 'DVRIP-Web',
      PassWord: sofiaHash(password), UserName: user,
    });
    if (r.json && r.json.SessionID != null) {
      this.session = parseInt(String(r.json.SessionID), 16) || 0;
    }
    if (!r.json || r.json.Ret !== 100) throw new Error('login falhou Ret=' + (r.json && r.json.Ret));
    return r;
  }
  sid() { return '0x' + this.session.toString(16).padStart(8, '0').toUpperCase(); }
  getConfig(name) { return this.send(MSG.CONFIG_GET, { Name: name, SessionID: this.sid() }); }
  setConfig(name, obj) {
    return this.send(MSG.CONFIG_SET, { Name: name, SessionID: this.sid(), [name]: obj });
  }
  systemInfo() { return this.send(MSG.SYSINFO, { Name: 'SystemInfo', SessionID: this.sid() }); }
  keepalive() { return this.send(MSG.KEEPALIVE, { Name: 'KeepAlive', SessionID: this.sid() }); }

  // PTZ. cmd: DirectionUp/Down/Left/Right, ZoomTile(+)/ZoomWide(-), FocusNear/FocusFar
  // O movimento continua ate chegar o "parar": Preset 65535 = comecar, Preset -1 = parar.
  // (mesmo formato do ptz_step da OpenIPC python-dvr; ainda nao confirmado nesta camera)
  ptz(cmd, start, step = 5, ch = 0) {
    return this.send(MSG.PTZ, {
      Name: 'OPPTZControl', SessionID: this.sid(),
      OPPTZControl: {
        Command: cmd,
        Parameter: { AUX: { Number: 0, Status: 'On' }, Channel: ch, MenuOpts: 'Enter',
          POINT: { bottom: 0, left: 0, right: 0, top: 0 },
          Pattern: 'SetBegin', Preset: start ? 65535 : -1, Step: step, Tour: 0 },
      },
    });
  }
  ptzStart(cmd, step, ch) { return this.ptz(cmd, true, step, ch); }
  ptzStop(cmd, step, ch) { return this.ptz(cmd, false, step, ch); }
}

module.exports = { XMCam, sofiaHash, MSG };

// CLI direto: node xmcam.js <ip> <user> <pass> [config]
if (require.main === module) {
  if (sofiaHash('') !== 'tlJwpbo6') { console.error('selftest hash FALHOU'); process.exit(2); }
  (async () => {
    const [host, user, pw, name] = process.argv.slice(2);
    if (!host) { console.log('uso: node xmcam.js <ip> <user> <pass> [config]'); return; }
    const cam = new XMCam(host); await cam.connect();
    const r = await cam.login(user, pw);
    console.log('login Ret=' + r.json.Ret + ' SessionID=' + cam.sid());
    console.log('\n== SystemInfo =='); console.log(JSON.stringify((await cam.systemInfo()).json, null, 2));
    console.log('\n== ' + (name || 'General') + ' =='); console.log(JSON.stringify((await cam.getConfig(name || 'General')).json, null, 2));
    cam.close();
  })().catch((e) => { console.error('erro:', e.message); process.exit(1); });
}
