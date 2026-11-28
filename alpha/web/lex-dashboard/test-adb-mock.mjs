#!/usr/bin/env node
/* Drives the real public/adb-webusb.js against a scripted adbd.

   The point is the wire format, not speed: the fake device answers exactly like
   adbd does (packets from the device carry our id in arg1), so any mistake in
   the client's framing shows up here instead of on the phone. */

import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);

const CLIENT = new URL('./public/adb-webusb.js', import.meta.url).pathname;

const CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
const OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
const TOKEN = 1, SIGNATURE = 2, PUBLICKEY = 3;

function packet(cmd, arg0, arg1, payload = Buffer.alloc(0)) {
  const header = Buffer.alloc(24);
  header.writeUInt32LE(cmd, 0);
  header.writeUInt32LE(arg0, 4);
  header.writeUInt32LE(arg1, 8);
  header.writeUInt32LE(payload.length, 12);
  header.writeUInt32LE(0, 16);
  header.writeUInt32LE((cmd ^ 0xffffffff) >>> 0, 20);
  return Buffer.concat([header, payload]);
}

class FakeAdbd {
  constructor() {
    this.inbox = [];        // packets waiting for the host to read
    this.waiter = null;
    this.outQueue = [];     // what the host wrote, parsed
    this.pending = Buffer.alloc(0);
    this.streams = new Map();
    this.closing = new Map();
    this.connected = false;
    this.opened = [];
    this.hostToken = Buffer.from('0123456789abcdefghij');
  }

  push(buf) {
    this.inbox.push(...buf);
    if (this.waiter && this.inbox.length) { const done = this.waiter; this.waiter = null; done(); }
  }

  readAll() {
    const out = Buffer.from(this.inbox);
    this.inbox = [];
    return out;
  }

  handleHostWrite(buffer) {
    this.pending = Buffer.concat([this.pending, Buffer.from(buffer)]);
    while (this.pending.length >= 24) {
      const cmd = this.pending.readUInt32LE(0);
      const a0 = this.pending.readUInt32LE(4);
      const a1 = this.pending.readUInt32LE(8);
      const len = this.pending.readUInt32LE(12);
      if (this.pending.length < 24 + len) break;
      const payload = this.pending.subarray(24, 24 + len);
      this.pending = this.pending.subarray(24 + len);
      this.onPacket(cmd, a0, a1, Buffer.from(payload));
    }
  }

  onPacket(cmd, a0, a1, payload) {
    if (cmd === CNXN) {
      this.push(packet(AUTH, TOKEN, 0, this.hostToken));
      return;
    }
    if (cmd === AUTH && a0 === SIGNATURE) {
      // Pretend the key is trusted: the next thing we send is CNXN.
      this.connected = true;
      this.push(packet(CNXN, 0x01000000, 4096, Buffer.from('device::ro.product.name=s10;\0')));
      return;
    }
    if (cmd === AUTH) return;               // public key: the device would prompt
    if (cmd === OPEN) {
      const service = payload.toString('utf8').replace(/\0$/, '');
      this.opened.push(service);
      const remote = 100 + this.opened.length;
      this.streams.set(a0, { remote, service });
      // adbd replies OKAY with our id in arg1, its own in arg0.
      this.push(packet(OKAY, remote, a0));
      // A streaming shell (VNC over nc) stays open until the host closes it.
      if (service.startsWith('shell,v2,raw:nc ')) return;
      const output = this.run(service);
      if (output === null) {
        this.push(packet(CLSE, remote, a0));
        return;
      }
      // adbd's shell v2 frames each piece as id + length: one WRTE per piece.
      if (output.length === 0) this.push(packet(CLSE, remote, a0));
      else for (const piece of output) this.push(packet(WRTE, remote, a0, piece));
      this.closing.set(a0, { remote, local: a0 });
      return;
    }
    if (cmd === WRTE) {
      // Streaming stdin: id 0 envelope; echo the payload back as stdout id 1.
      const stream = this.streams.get(a0);
      if (!stream || payload.length < 5 || payload[0] !== 0) return;
      const length = payload.readUInt32LE(1);
      if (payload.length < 5 + length) return;
      const body = payload.subarray(5, 5 + length);
      const head = Buffer.alloc(5);
      head[0] = 1;
      head.writeUInt32LE(body.length, 1);
      this.push(packet(WRTE, stream.remote, a0, Buffer.concat([head, body])));
      return;
    }
    if (cmd === CLSE) {
      const stream = this.streams.get(a0);
      if (!stream) return;
      this.streams.delete(a0);
      this.push(packet(CLSE, stream.remote, a0));
      return;
    }
    if (cmd === OKAY) {
      const closing = this.closing.get(a0);
      if (closing) { this.push(packet(CLSE, closing.remote, closing.local)); this.closing.delete(a0); }
      return;
    }
  }

  run(service) {
    if (!service.startsWith('shell,v2,raw:')) return null;
    const command = service.slice('shell,v2,raw:'.length);
    const frame = (id, data) => {
      const head = Buffer.alloc(5);
      head[0] = id;
      head.writeUInt32LE(data.length, 1);
      return Buffer.concat([head, Buffer.from(data)]);
    };
    const pieces = [];
    if (command === 'echo ok') pieces.push(frame(1, Buffer.from('ok\n')));
    else if (command.includes('ro.build.flavor')) pieces.push(frame(1, Buffer.from('lineage_lambda_beyond1lte-userdebug\n')));
    else if (command.includes('screencap')) pieces.push(frame(1, Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 9, 9, 9])));
    else if (command.includes('scrot')) pieces.push(frame(1, Buffer.alloc(63893, 0xab)));
    else pieces.push(frame(1, Buffer.from('')));
    pieces.push(frame(3, Buffer.from([0])));   // exit status
    return pieces;
  }
}

const adbd = new FakeAdbd();
const device = {
  vendorId: 0x04e8,
  productId: 0x685d,
  productName: 'SM-G973F',
  configuration: null,
  async open() { this.openedDevice = true; },
  async selectConfiguration() {
    this.configuration = {
      interfaces: [{
        interfaceNumber: 0,
        alternate: {
          interfaceClass: 0xff,
          interfaceSubclass: 0x42,
          interfaceProtocol: 0x01,
          endpoints: [
            { endpointNumber: 1, direction: 'out' },
            { endpointNumber: 2, direction: 'in' },
          ],
        },
      }],
    };
  },
  async claimInterface() {},
  async close() {},
  async transferOut(endpoint, buffer) {
    adbd.handleHostWrite(buffer);
    return { status: 'ok', bytesWritten: buffer.byteLength };
  },
  async transferIn(endpoint) {
    // Like the browser: block until the device has something to say.
    if (!adbd.inbox.length) await new Promise((done) => { adbd.waiter = done; });
    const data = Uint8Array.from(adbd.readAll());
    return { status: 'ok', data: { buffer: data.buffer, byteOffset: 0, byteLength: data.length } };
  },
};

// The module expects a window-ish global with navigator.usb and crypto.subtle.
Object.defineProperty(globalThis, 'navigator', {
  configurable: true,
  value: { usb: { requestDevice: async () => device, getDevices: async () => [device], addEventListener() {}, removeEventListener() {} } },
});
globalThis.isSecureContext = true;

const LexAdb = require(CLIENT);

const results = [];
const check = (name, ok, extra = '') => {
  results.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? ' — ' + extra : ''}`);
  return ok;
};

await LexAdb.attach(device);
check('handshake completes', true);

const echo = await LexAdb.shell('echo ok');
check('shell echo', echo.trim() === 'ok', JSON.stringify(echo));

const identity = await LexAdb.shell('getprop ro.build.flavor');
check('identity read', identity.trim() === 'lineage_lambda_beyond1lte-userdebug', JSON.stringify(identity.trim()));

check('services use shell v2 like adb', adbd.opened.every((s) => s.startsWith('shell,v2,raw:')), adbd.opened.join(', '));

const raw = await LexAdb.shellBytes('screencap -p');
check('binary frame survives', raw.length > 8 && raw[0] === 0x89 && raw[1] === 0x50, `${raw.length} bytes`);

// The bug that ate clicks: a shell opened while another is in flight must get its
// own packets, not race the other one for them.
const [one, two, three] = await Promise.all([
  LexAdb.shellBytes('echo ok'),
  LexAdb.shell('echo ok'),
  LexAdb.shellBytes('screencap -p'),
]);
const frame = await LexAdb.shellBytes('scrot -q 75 -o /tmp/lex-web.jpg && cat /tmp/lex-web.jpg');
check('63 KB frame arrives whole', frame.length === 63893, `${frame.length} bytes`);

check('concurrent shells do not steal packets',
  one.length === 3 && two.trim() === 'ok' && three.length === 11,
  `${one.length}/${JSON.stringify(two.trim())}/${three.length}`);

// A streaming link (VNC over nc): one WRTE per chunk, each echoed as stdout.
const stream = await LexAdb.openStream('nc 127.0.0.1 5901');
check('openStream opens a channel', stream.readyState === 1, `state ${stream.readyState}`);

const echoed = [];
stream.onmessage = (event) => echoed.push(Buffer.from(event.data));
stream.send(new TextEncoder().encode('hello'));
stream.send(new Uint8Array([0, 1, 2, 255]));
const echoDeadline = Date.now() + 2000;
while (echoed.length < 2 && Date.now() < echoDeadline) await new Promise((done) => setTimeout(done, 5));
check('stream echoes chunks in order, byte-identical',
  echoed.length === 2 && echoed[0].equals(Buffer.from('hello')) && echoed[1].equals(Buffer.from([0, 1, 2, 255])),
  echoed.map((chunk) => chunk.toString('hex')).join(' '));

const parallel = await LexAdb.shell('echo ok');
check('shell runs while the stream is open', parallel.trim() === 'ok', JSON.stringify(parallel));

let closeEvent = null;
stream.onclose = (event) => { closeEvent = event; };
stream.close();
check('stream close reports CLOSED once',
  stream.readyState === 3 && closeEvent !== null && closeEvent.code === 1000,
  `state ${stream.readyState} code ${closeEvent ? closeEvent.code : '-'}`);

console.log(results.join('\n'));
const failed = results.filter((r) => r.startsWith('FAIL')).length;
console.log(failed ? `\n${failed} failing` : '\nall good');
process.exit(failed ? 1 : 0);
