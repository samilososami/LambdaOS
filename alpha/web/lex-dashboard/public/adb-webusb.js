'use strict';
/**
 * Minimal ADB client over WebUSB for the LEX dashboard.
 *
 * Web Serial cannot carry ADB: the phone exposes a vendor-specific USB
 * interface (class 0xff / subclass 0x42 / protocol 0x01), not a serial port.
 * WebUSB can claim that interface; this file implements just enough of the
 * ADB protocol for `shell:` services (CNXN/AUTH/OPEN/WRTE/OKAY/CLSE).
 *
 * First connection asks Android to authorize this browser's RSA key with the
 * normal "Allow USB debugging?" dialog. The key stays in localStorage.
 * Keep this file dependency-free; it is served as a classic script.
 */
(function (root) {
  const CMD = {
    CNXN: 0x4e584e43,
    AUTH: 0x48545541,
    OPEN: 0x4e45504f,
    OKAY: 0x59414b4f,
    CLSE: 0x45534c43,
    WRTE: 0x45545257,
  };
  const AUTH_TOKEN = 1;
  const AUTH_SIGNATURE = 2;
  const AUTH_PUBLICKEY = 3;
  const KEY_STORAGE = 'lambda-adb-key';

  class AdbError extends Error {}

  function concat(a, b) {
    const out = new Uint8Array(a.length + b.length);
    out.set(a);
    out.set(b, a.length);
    return out;
  }

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  function decode(bytes) {
    return new TextDecoder('utf-8', { fatal: false }).decode(bytes);
  }

  function shellQuote(value) {
    return `'${String(value).replaceAll("'", "'\\''")}'`;
  }

  function concatAll(chunks) {
    let total = 0;
    for (const chunk of chunks) total += chunk.length;
    const out = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      out.set(chunk, offset);
      offset += chunk.length;
    }
    return out;
  }

  function parseCoreResult(text) {
    const marker = 'Result: Bundle[{json=';
    const start = text.indexOf(marker);
    if (start < 0) throw new AdbError(`Core returned no JSON result: ${text.slice(0, 160)}`);
    const tail = text.slice(start + marker.length);
    const end = tail.lastIndexOf('}]');
    if (end < 0) throw new AdbError('Core result is truncated.');
    return JSON.parse(tail.slice(0, end));
  }

  // ---- RSA public key blob required by adb AUTH(3) -------------------------

  function readTlv(bytes, pos) {
    const tag = bytes[pos];
    let length = bytes[pos + 1];
    let start = pos + 2;
    if (length & 0x80) {
      const count = length & 0x7f;
      length = 0;
      start = pos + 2 + count;
      for (let i = 0; i < count; i += 1) length = length * 256 + bytes[pos + 2 + i];
    }
    return { tag, start, length, end: start + length };
  }

  function parseRsaSpki(spki) {
    const root = readTlv(spki, 0);
    if (root.tag !== 0x30) throw new AdbError('Unexpected RSA SPKI.');
    const algorithm = readTlv(spki, root.start);
    const bits = readTlv(spki, algorithm.end);
    if (bits.tag !== 0x03) throw new AdbError('RSA SPKI without BIT STRING.');
    const sequence = readTlv(spki, bits.start + 1); // skip unused-bits octet
    if (sequence.tag !== 0x30) throw new AdbError('Unexpected RSA key sequence.');
    const modulusTlv = readTlv(spki, sequence.start);
    const exponentTlv = readTlv(spki, modulusTlv.end);
    let modulus = spki.slice(modulusTlv.start, modulusTlv.end);
    while (modulus.length > 1 && modulus[0] === 0) modulus = modulus.slice(1);
    let exponent = 0;
    for (let i = exponentTlv.start; i < exponentTlv.end; i += 1) exponent = exponent * 256 + spki[i];
    return { modulus, exponent };
  }

  function bytesToBigIntLE(bytes) {
    let value = 0n;
    for (let i = bytes.length - 1; i >= 0; i -= 1) value = (value << 8n) | BigInt(bytes[i]);
    return value;
  }

  function bigIntToLeBytes(value, length) {
    const out = new Uint8Array(length);
    let rest = value;
    for (let i = 0; i < length; i += 1) {
      out[i] = Number(rest & 0xffn);
      rest >>= 8n;
    }
    return out;
  }

  function modInverse32(value) {
    // Newton iteration for the inverse modulo 2^32; value must be odd.
    const modulus = 1n << 32n;
    let x = 1n;
    const a = BigInt(value >>> 0);
    for (let i = 0; i < 5; i += 1) {
      x = (x * ((2n - ((a * x) % modulus) + modulus) % modulus)) % modulus;
    }
    return Number(x);
  }

  function buildAdbPublicKey(modulus, exponent) {
    const words = Math.ceil(modulus.length / 4);
    const padded = new Uint8Array(words * 4); // big-endian, right aligned
    padded.set(modulus, words * 4 - modulus.length);
    const le = padded.slice().reverse();
    const n = bytesToBigIntLE(le);
    const n0 = (le[0] | (le[1] << 8) | (le[2] << 16) | (le[3] << 24)) >>> 0;
    const inverse = modInverse32(n0);
    const n0inv = (0x100000000 - inverse) >>> 0;
    const rr = (1n << BigInt(64 * words)) % n; // R^2 mod n, R = 2^(32*words)
    const struct = new Uint8Array(8 + 8 * words + 4);
    const view = new DataView(struct.buffer);
    view.setUint32(0, words, true);
    view.setUint32(4, n0inv, true);
    for (let i = 0; i < words; i += 1) {
      const offset = 8 + 4 * i;
      const word = (le[4 * i] | (le[4 * i + 1] << 8) | (le[4 * i + 2] << 16) | (le[4 * i + 3] << 24)) >>> 0;
      view.setUint32(offset, word, true);
    }
    const rrLe = bigIntToLeBytes(rr, 4 * words);
    const rrOffset = 8 + 4 * words;
    for (let i = 0; i < words; i += 1) {
      const word = (rrLe[4 * i] | (rrLe[4 * i + 1] << 8) | (rrLe[4 * i + 2] << 16) | (rrLe[4 * i + 3] << 24)) >>> 0;
      view.setUint32(rrOffset + 4 * i, word, true);
    }
    view.setUint32(rrOffset + 4 * words, exponent, true);
    let binary = '';
    for (const byte of struct) binary += String.fromCharCode(byte);
    return btoa(binary);
  }

  // ---- WebUSB / ADB link ---------------------------------------------------

  // The ADB interface is vendor-specific 0xff/0x42/0x01. Samsung phones expose
  // it as the only interface (verified on the S10: 04e8:685d, iface 0).
  function adbInterfaces(device) {
    const all = (device.configuration && device.configuration.interfaces) || [];
    const exact = all.filter(
      (entry) =>
        entry.alternate.interfaceClass === 0xff &&
        entry.alternate.interfaceSubclass === 0x42 &&
        entry.alternate.interfaceProtocol === 0x01,
    );
    const vendor = all.filter(
      (entry) => entry.alternate.interfaceClass === 0xff && !exact.includes(entry),
    );
    return [...exact, ...vendor];
  }

  function claimHelp(error) {
    const userAgent = root.navigator || {};
    const platform = userAgent.userAgentData
      ? userAgent.userAgentData.platform
      : userAgent.platform || '';
    const lines = [
      'Could not claim the ADB interface: another program on this computer holds it.',
      'Stop the adb server:  adb kill-server',
      'Then press Connect again. Chrome keeps the USB permission, so it will not show the device chooser again.',
    ];
    if (/win/i.test(platform)) {
      lines.push('Windows: the ADB interface must be bound to WinUSB (Zadig); the Samsung driver blocks WebUSB.');
    } else if (/mac/i.test(platform)) {
      lines.push('macOS: quit Android Studio and every adb process, then retry.');
    } else {
      lines.push('Linux: add a udev rule granting your user access to vendor 04e8 (TAG+="uaccess"), then replug the phone.');
    }
    lines.push('On the phone, USB debugging must be on and the “Allow USB debugging?” prompt accepted.');
    if (error && error.message) lines.push(`(${error.message})`);
    return lines.join('\n');
  }

  async function claimAdb(device) {
    const candidates = adbInterfaces(device);
    if (!candidates.length) {
      throw new AdbError('This device does not expose the ADB USB interface. Enable USB debugging on the phone.');
    }
    let claimed = null;
    let lastError = null;
    for (const entry of candidates) {
      // A just-killed adb server releases the interface a moment later.
      for (let attempt = 0; attempt < 3 && !claimed; attempt += 1) {
        try {
          await device.claimInterface(entry.interfaceNumber);
          claimed = entry;
        } catch (error) {
          lastError = error;
          if (attempt < 2) await sleep(400);
        }
      }
      if (claimed) break;
    }
    if (!claimed) throw new AdbError(claimHelp(lastError));
    return claimed;
  }

  class AdbUsb {
    constructor(device) {
      this.device = device;
      this.buffer = new Uint8Array(0);
      this.packets = [];
      this.localId = 1;
      this.running = false;
      this.dead = null;
      this.keyPair = null;
      this.publicPayload = null;
      this.maxPayload = 4096;
      this.onDisconnect = (event) => {
        if (event.device === this.device) this.fail(new AdbError('USB disconnected.'));
      };
    }

    async open() {
      const device = this.device;
      await device.open();
      if (device.configuration === null) await device.selectConfiguration(1);
      const iface = await claimAdb(device);
      const endpoints = iface.alternate.endpoints;
      this.inEndpoint = endpoints.find((endpoint) => endpoint.direction === 'in');
      this.outEndpoint = endpoints.find((endpoint) => endpoint.direction === 'out');
      if (!this.inEndpoint || !this.outEndpoint) throw new AdbError('ADB USB endpoints missing.');
      this.running = true;
      if (root.navigator && root.navigator.usb) root.navigator.usb.addEventListener('disconnect', this.onDisconnect);
      this.readLoop();
      await this.authenticate();
    }

    fail(error) {
      if (this.dead) return;
      this.dead = error;
      this.lastError = error.message;
      this.running = false;
      // close() rejects while a transfer is still unwinding; it is harmless.
      void this.device.close().catch(() => {});
    }

    async readLoop() {
      while (this.running) {
        let result;
        try {
          result = await this.device.transferIn(this.inEndpoint.endpointNumber, 16384);
        } catch (error) {
          console.error('LexAdb USB read failed:', error);
          this.fail(new AdbError(`USB read failed: ${error.message}`));
          return;
        }        if (!this.running) return;
        if (result.status !== 'ok') {
          this.fail(new AdbError(`USB read interrupted (${result.status}).`));
          return;
        }
        if (result.data && result.data.byteLength) {
          const chunk = new Uint8Array(result.data.buffer, result.data.byteOffset, result.data.byteLength);
          this.feed(chunk);
        }
      }
    }

    feed(chunk) {
      this.buffer = concat(this.buffer, chunk);
      while (this.buffer.length >= 24) {
        const view = new DataView(this.buffer.buffer, this.buffer.byteOffset, 24);
        const length = view.getUint32(12, true);
        if (this.buffer.length < 24 + length) break;
        const packet = {
          cmd: view.getUint32(0, true),
          arg0: view.getUint32(4, true),
          arg1: view.getUint32(8, true),
          payload: this.buffer.slice(24, 24 + length),
        };
        this.buffer = this.buffer.slice(24 + length);
        this.packets.push(packet);
      }
    }

    async nextPacket(deadline) {
      for (;;) {
        if (this.packets.length) return this.packets.shift();
        if (this.dead) throw this.dead;
        if (Date.now() > deadline) throw new AdbError('Timed out talking to ADB.');
        await sleep(40);
      }
    }

    async send(cmd, arg0, arg1, payload) {
      if (this.dead) throw this.dead;
      const data =
        payload === undefined || payload === null
          ? new Uint8Array(0)
          : payload instanceof Uint8Array
            ? payload
            : new TextEncoder().encode(payload);
      if (data.length > this.maxPayload) throw new AdbError('ADB payload too large.');
      const buffer = new ArrayBuffer(24 + data.length);
      const view = new DataView(buffer);
      view.setUint32(0, cmd >>> 0, true);
      view.setUint32(4, arg0 >>> 0, true);
      view.setUint32(8, arg1 >>> 0, true);
      view.setUint32(12, data.length, true);
      view.setUint32(16, 0, true);
      view.setUint32(20, (cmd ^ 0xffffffff) >>> 0, true);
      new Uint8Array(buffer, 24).set(data);
      const result = await this.device.transferOut(this.outEndpoint.endpointNumber, buffer);
      if (result.status !== 'ok') throw new AdbError(`USB write interrupted (${result.status}).`);
    }

    async ensureKey() {
      if (this.keyPair) return;
      const algorithm = { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-1' };
      let pair = null;
      try {
        const stored = JSON.parse(localStorage.getItem(KEY_STORAGE) || 'null');
        if (stored && stored.privateKey && stored.publicKey) {
          pair = {
            privateKey: await crypto.subtle.importKey('jwk', stored.privateKey, algorithm, true, ['sign']),
            publicKey: await crypto.subtle.importKey('jwk', stored.publicKey, algorithm, true, ['verify']),
          };
        }
      } catch {
        pair = null;
      }
      if (!pair) {
        const generated = await crypto.subtle.generateKey(
          { ...algorithm, modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]) },
          true,
          ['sign', 'verify'],
        );
        pair = generated;
        try {
          localStorage.setItem(
            KEY_STORAGE,
            JSON.stringify({
              privateKey: await crypto.subtle.exportKey('jwk', generated.privateKey),
              publicKey: await crypto.subtle.exportKey('jwk', generated.publicKey),
            }),
          );
        } catch {
          // Private mode: the key lives for this page only.
        }
      }
      this.keyPair = pair;
      const spki = new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey));
      const { modulus, exponent } = parseRsaSpki(spki);
      this.publicPayload = `${buildAdbPublicKey(modulus, exponent)} lambdaos-web@chrome`;
    }

    async authenticate() {
      await this.send(CMD.CNXN, 0x01000000, 4096, new TextEncoder().encode('host::\0'));
      const deadline = Date.now() + 180000; // the native USB-debugging prompt waits for a human
      let sentSignature = false;
      for (;;) {
        const packet = await this.nextPacket(deadline);
        if (packet.cmd === CMD.CNXN) {
          this.maxPayload = Math.max(4096, Math.min(packet.arg1 || 4096, 1024 * 1024));
          return;
        }
        if (packet.cmd === CMD.AUTH && packet.arg0 === AUTH_TOKEN) {
          await this.ensureKey();
          if (!sentSignature) {
            const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', this.keyPair.privateKey, packet.payload);
            await this.send(CMD.AUTH, AUTH_SIGNATURE, 0, new Uint8Array(signature));
            sentSignature = true;
          } else {
            await this.send(CMD.AUTH, AUTH_PUBLICKEY, 0, new TextEncoder().encode(this.publicPayload));
          }
        }
      }
    }

    async shellBytes(command, timeout = 30000) {
      if (this.dead) throw this.dead;
      if (!this.running) throw new AdbError('ADB is not connected.');
      const local = this.localId++;
      await this.send(CMD.OPEN, local, 0, new TextEncoder().encode(`${command}\0`));
      const deadline = Date.now() + timeout;
      const chunks = [];
      for (;;) {
        const packet = await this.nextPacket(deadline);
        if (packet.cmd === CMD.OKAY && packet.arg0 === local) continue;
        if (packet.cmd === CMD.WRTE && packet.arg0 === local) {
          chunks.push(packet.payload);
          await this.send(CMD.OKAY, packet.arg1, local);
          continue;
        }
        if (packet.cmd === CMD.CLSE && packet.arg0 === local) {
          if (packet.arg1) await this.send(CMD.CLSE, packet.arg1, local);
          break;
        }
      }
      return concatAll(chunks);
    }

    async shell(command, timeout = 30000) {
      return decode(await this.shellBytes(command, timeout));
    }

    async close() {
      this.running = false;
      try {
        root.navigator.usb.removeEventListener('disconnect', this.onDisconnect);
      } catch {
        // No WebUSB in this environment.
      }
      try {
        await this.device.close();
      } catch {
        // Already closed.
      }
    }
  }

  let active = null;

  const FILTERS = [
    { classCode: 0xff, subclassCode: 0x42, protocolCode: 0x01 },
    { vendorId: 0x04e8 },
  ];

  async function pickDevice() {
    // A permission granted earlier in this origin survives until the cable is
    // replugged, so retry without the chooser when possible.
    try {
      const granted = await root.navigator.usb.getDevices();
      const known = granted.find((device) => device.vendorId === 0x04e8);
      if (known) return known;
    } catch {
      // getDevices is optional in older builds.
    }
    return root.navigator.usb.requestDevice({ filters: FILTERS });
  }

  async function connect() {
    if (active && !active.dead) return active;
    if (!root.navigator || !root.navigator.usb) throw new AdbError('WebUSB is not available in this browser.');
    if (!root.isSecureContext) throw new AdbError('WebUSB needs a secure context (localhost or HTTPS).');
    const device = await pickDevice();
    const link = new AdbUsb(device);
    try {
      await link.open();
    } catch (error) {
      try {
        await device.close();
      } catch {
        // Nothing to close.
      }
      throw error;
    }
    active = link;
    return link;
  }

  async function core(method, payload) {
    if (!active) throw new AdbError('Connect over USB first.');
    const output = await active.shell(
      `content call --uri content://org.lambdaproject.lambda.core --method ${method} --arg ${shellQuote(
        JSON.stringify(payload || {}),
      )}`,
    );
    return parseCoreResult(output);
  }

  function selfTest() {
    // Header encoding.
    const header = new Uint8Array(24);
    const view = new DataView(header.buffer);
    view.setUint32(0, CMD.CNXN, true);
    view.setUint32(20, (CMD.CNXN ^ 0xffffffff) >>> 0, true);
    if (String.fromCharCode(...header.slice(0, 4)) !== 'CNXN') throw new Error('CNXN byte order');
    // A deliberately odd 256-byte modulus exercises the ADB key struct.
    const modulus = new Uint8Array(256).fill(0x5a);
    modulus[0] = 0xc1;
    modulus[255] = 0xa7;
    const encoded = buildAdbPublicKey(modulus, 65537);
    const binary = atob(encoded);
    if (binary.length !== 524) throw new Error(`key blob length ${binary.length}`);
    const blob = Uint8Array.from(binary, (char) => char.charCodeAt(0));
    const blobView = new DataView(blob.buffer);
    if (blobView.getUint32(0, true) !== 64) throw new Error('key words');
    if (blobView.getUint32(8 + 8 * 64, true) !== 65537) throw new Error('key exponent');
    const n0 = blobView.getUint32(8, true);
    const n0inv = blobView.getUint32(4, true);
    if (((BigInt(n0) * BigInt(n0inv)) & 0xffffffffn) !== 0xffffffffn) throw new Error('n0inv');
    // R^2 mod n must fit and be non-zero for this odd modulus.
    const rrFirst = blobView.getUint32(8 + 4 * 64, true);
    if (rrFirst === 0 && blobView.getUint32(8 + 4 * 64 + 4, true) === 0) throw new Error('rr');
    if (parseCoreResult('Result: Bundle[{json={"ok":true,"state":"pending"}}]').state !== 'pending')
      throw new Error('core parse');
    if (shellQuote("a'b") !== "'a'\\''b'") throw new Error('quote');
    return true;
  }

  const api = {
    supported: () => Boolean(root.navigator && root.navigator.usb && root.isSecureContext),
    connect,
    shell: (command, timeout) => {
      if (!active) throw new AdbError('Connect over USB first.');
      return active.shell(command, timeout);
    },
    shellBytes: (command, timeout) => {
      if (!active) throw new AdbError('Connect over USB first.');
      return active.shellBytes(command, timeout);
    },
    core,
    close: async () => {
      if (active) await active.close();
      active = null;
    },
    deviceName: () => (active ? active.device.productName : null),
    selfTest,
    _test: { parseRsaSpki, buildAdbPublicKey, parseCoreResult, shellQuote, modInverse32, adbInterfaces, claimAdb, AdbError },
  };
  root.LexAdb = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
