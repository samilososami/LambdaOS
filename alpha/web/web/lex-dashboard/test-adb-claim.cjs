'use strict';
// Self-check for the WebUSB claim path: interface selection order and retry.
// Run with: node alpha/web/lex-dashboard/test-adb-claim.cjs
const assert = require('node:assert/strict');
const { _test } = require('./public/adb-webusb.js');

function iface(number, cls, sub, proto, endpoints = []) {
  return {
    interfaceNumber: number,
    alternate: {
      interfaceClass: cls,
      interfaceSubclass: sub,
      interfaceProtocol: proto,
      endpoints,
    },
  };
}

function device(entries, failures) {
  return {
    configuration: { interfaces: entries },
    claims: [],
    async claimInterface(number) {
      if (failures > 0) {
        failures -= 1;
        throw new Error('Unable to claim interface.');
      }
      this.claims.push(number);
    },
  };
}

(async () => {
  const mixed = {
    configuration: {
      interfaces: [iface(0, 0xff, 0xff, 0x00), iface(1, 0xff, 0x42, 0x01)],
    },
  };
  assert.equal(_test.adbInterfaces(mixed)[0].interfaceNumber, 1, 'ADB interface must come first');

  const retry = device([iface(0, 0xff, 0x42, 0x01)], 2);
  const claimed = await _test.claimAdb(retry);
  assert.equal(claimed.interfaceNumber, 0);
  assert.deepEqual(retry.claims, [0], 'claim must succeed after adb releases the interface');

  const blocked = device([iface(0, 0xff, 0x42, 0x01)], 99);
  await assert.rejects(() => _test.claimAdb(blocked), (error) => {
    assert.match(error.message, /adb kill-server/);
    return true;
  });

  const notAdb = { configuration: { interfaces: [iface(0, 8, 6, 0x50)] } };
  await assert.rejects(() => _test.claimAdb(notAdb), /does not expose the ADB USB interface/);

  console.log('adb claim self-check ok');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
