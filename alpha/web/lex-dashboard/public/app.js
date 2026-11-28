'use strict';
/* LEX over WebUSB. The page drives the whole handshake:
   pick the S10 → wait for ADB authorization → ask the phone for screen consent
   → start the desktop → attach a VNC client to it over the adb pipe. */

const $ = (id) => document.getElementById(id);
const app = $('app');
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

const state = {
  live: false,
  busy: false,
  consentToken: '',
  answered: false,
};

let desktop = null;
let desktopStream = null;

function show(name) {
  app.dataset.state = name;
}

function error(title, text, command) {
  $('error-title').textContent = title;
  $('error-text').textContent = text || '';
  $('error-cmd').hidden = !command;
  $('error-cmd-text').textContent = command || '';
  show('error');
}

/** The host's adb server holds the interface Chrome needs; only the person can free it. */
function looksLikeClaimFailure(message) {
  return /claim|another program|adb kill-server/i.test(message);
}

const adapter = () => window.LexAdb;

/* Chrome remembers a granted device for the origin. Picking it up at load means
   a second visit connects with one click and no chooser. */
let knownDevice = null;
async function pickExisting() {
  try {
    const granted = await navigator.usb.getDevices();
    return granted.find((device) => device.vendorId === 0x04e8) || null;
  } catch {
    return null;
  }
}
pickExisting().then((device) => { knownDevice = device; });

/* ---------- connection ---------- */

/** Runs inside the click: requestDevice has to be the first thing we await. */
async function beginConnect() {
  if (state.busy) return;
  if (!adapter() || !adapter().supported()) {
    error(
      'Este navegador no puede hablar con el USB.',
      'WebUSB solo está disponible en navegadores basados en Chromium (Chrome, Edge) y en un contexto seguro. Abre esta página en http://localhost.',
    );
    return;
  }
  state.busy = true;
  state.answered = false;
  if (knownDevice) {
    // No chooser needed: go straight at the remembered device, but never hang
    // on it. If it does not open, the chooser is one click away.
    show('opening');
    openRemembered(knownDevice);
    return;
  }
  show('picking');
  // A chooser that never appears should not look like normal waiting.
  const pickHint = setTimeout(() => { $('pick-hint').hidden = false; }, 12000);
  let picking;
  try {
    picking = adapter().request();
  } catch (failure) {
    // A request that lost the user gesture: maybe the browser already knows the
    // device and no chooser is needed at all.
    const remembered = await pickExisting();
    if (remembered) {
      clearTimeout(pickHint);
      finishConnect(Promise.resolve(remembered));
      return;
    }
    state.busy = false;
    error('No se pudo abrir el selector de dispositivos.', describe(failure));
    return;
  }
  finishConnect(picking, pickHint);
}

async function openRemembered(device) {
  // Accepting the phone's authorization dialog drops the USB link, so the
  // attach is retried a few times: after acceptance the same device object
  // reconnects and the handshake completes without asking again.
  const attempts = 4;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    // A USB re-enumeration (adbd restart, replug) invalidates the object we hold;
    // the granted list has the fresh one.
    const fresh = await pickExisting();
    if (fresh) {
      device = fresh;
      knownDevice = fresh;
    }
    try {
      // The phone may show its authorization dialog, which waits for a human up to 3 minutes.
      await withTimeout(adapter().attach(device), 170000);
      break;
    } catch (failure) {
      await adapter().close().catch(() => {});
      if (attempt < attempts) {
        await sleep(1500);
        continue;
      }
      knownDevice = null;
      state.busy = false;
      error(
        'No se pudo abrir el dispositivo recordado.',
        `${describe(failure)} Pulsa Reintentar para elegirlo otra vez.`,
      );
      return;
    }
  }
  state.busy = false;
  const settle = setTimeout(() => { if (!state.answered) show('authorize'); }, 1500);
  try {
    await identify();
  } catch (failure) {
    clearTimeout(settle);
    error('No se pudo terminar la conexión.', describe(failure));
    return;
  }
  clearTimeout(settle);
}

function withTimeout(promise, ms) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error('La conexión USB no respondió a tiempo.')), ms)),
  ]);
}

function describe(failure) {
  if (!failure) return 'Error desconocido.';
  const name = failure.name ? `${failure.name}: ` : '';
  return `${name}${failure.message || failure}`;
}

async function finishConnect(picking, pickHint) {
  if (pickHint) clearTimeout(pickHint);
  let device;
  try {
    device = await picking;
  } catch (failure) {
    state.busy = false;
    // Closing the chooser is a decision, not an error.
    if (/No device selected|chooser/i.test(describe(failure))) {
      show('connect');
      return;
    }
    error('No se pudo abrir el selector de dispositivos.', describe(failure));
    return;
  }
  try {
    try {
      await adapter().attach(device);
    } catch (first) {
      // Accepting the debugging prompt can reset the pipe; the device object
      // stays valid, so one clean retry usually lands.
      await sleep(900);
      await adapter().attach(device);
    }
  } catch (failure) {
    state.busy = false;
    const detail = describe(failure);
    if (looksLikeClaimFailure(detail)) {
      error(
        'El USB lo tiene ocupado adb en este ordenador.',
        'Chrome no puede coger la interfaz del teléfono mientras el servidor de adb la retiene. '
          + 'Ejecuta este comando en una terminal de este ordenador y vuelve a pulsar Conectar: '
          + 'Chrome ya guarda el permiso, así que no volverá a pedirte el dispositivo.',
        'adb kill-server',
      );
      return;
    }
    error('No se pudo abrir el dispositivo.', detail);
    return;
  }
  state.busy = false;
  // The USB link is open; adb only answers once the phone authorizes this
  // browser, so say that straight away instead of leaving the picker up.
  const settle = setTimeout(() => { if (!state.answered) show('authorize'); }, 1500);
  try {
    await identify();
  } catch (failure) {
    clearTimeout(settle);
    error('No se pudo terminar la conexión.', describe(failure));
    return;
  }
  clearTimeout(settle);
}

/** Wait until adb answers; an unauthorized phone keeps the socket silent. */
async function waitForShell(seconds = 75) {
  const deadline = Date.now() + seconds * 1000;
  let lastProblem = '';
  let attempts = 0;
  while (Date.now() < deadline) {
    try {
      const echo = (await adapter().shell('echo ok', 6000)).trim();
      if (echo.includes('ok')) {
        $('authorize-detail').hidden = true;
        return true;
      }
      lastProblem = 'El teléfono respondió, pero no al comando de prueba.';
    } catch (failure) {
      // Saying "waiting for you" when the shell is failing is misleading, so
      // show what actually came back once we have tried a couple of times.
      lastProblem = describe(failure);
    }
    attempts += 1;
    if (attempts >= 2) {
      const detail = $('authorize-detail');
      detail.hidden = false;
      detail.textContent = `Detalle: ${lastProblem}`;
      const key = $('authorize-key');
      const print = adapter().fingerprint();
      if (print) {
        key.hidden = false;
        key.textContent = `Clave de este navegador: ${print}. Si cambia entre recargas, el aviso se repetirá.`;
      }
      const trace = adapter().trace();
      if (trace.length) {
        const box = $('authorize-trace');
        box.hidden = false;
        box.textContent = trace.join('\n');
      }
    }
    await sleep(2500);
  }
  return false;
}

async function identify() {
  const answered = await waitForShell();
  state.answered = answered;
  if (!answered) {
    error(
      'El S10 no responde a adb.',
      'No se ha autorizado la depuración USB. Acepta el aviso en el teléfono y vuelve a intentarlo.',
    );
    return;
  }
  // The first command after the handshake can land before the pipe settles, so
  // ask a few times and keep the reason if it never answers. One line is enough:
  // the build flavor names both the system and the device.
  let identity = '';
  let lastError = '';
  for (let attempt = 0; attempt < 3 && !identity; attempt += 1) {
    try {
      const out = await adapter().shell('getprop ro.build.flavor', 8000);
      if (out.trim()) identity = out.trim();
    } catch (failure) {
      lastError = describe(failure);
    }
    if (!identity) await sleep(700);
  }
  if (!identity) {
    error(
      'No se pudo leer la identidad del dispositivo.',
      lastError || 'El teléfono no respondió a la consulta de propiedades.',
    );
    return;
  }
  // lineage_lambda_beyond1lte-userdebug: both words have to be there.
  const ours = /lambda/i.test(identity) && /beyond1lte/i.test(identity);
  if (!ours) {
    $('wrong-title').textContent = 'Ese dispositivo no es un S10 con LambdaOS.';
    $('wrong-text').textContent = `El dispositivo se identifica como «${identity}», y este escritorio solo se abre desde un Galaxy S10 con LambdaOS.`;
    show('wrong');
    return;
  }
  $('stage-device').textContent = 'Galaxy S10 · LambdaOS';
  await requestConsent();
}

/* ---------- screen consent, handled by the phone ---------- */

async function requestConsent() {
  const token =
    typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  state.consentToken = token;
  show('consent');
  try {
    await adapter().shell(
      `am start -n org.lambdaproject.lambda/.ConsentActivity --es token ${token}`,
      10000,
    );
  } catch (failure) {
    error('No se pudo pedir autorización al teléfono.', String(failure.message || failure));
    return;
  }
  const path = `/sdcard/Android/data/org.lambdaproject.lambda/files/consent-${token}`;
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline && state.consentToken === token) {
    await sleep(900);
    let answer = '';
    try {
      answer = (await adapter().shell(`cat ${path} 2>/dev/null`, 6000)).trim();
    } catch {
      continue;
    }
    if (answer.startsWith('accepted')) {
      state.consentToken = '';
      await startDesktop();
      return;
    }
    if (answer.startsWith('rejected')) {
      state.consentToken = '';
      show('rejected');
      return;
    }
  }
  if (state.consentToken === token) {
    state.consentToken = '';
    error('No ha llegado ninguna respuesta.', 'La solicitud ha caducado. Vuelve a intentarlo.');
  }
}

/* ---------- the desktop itself ---------- */

async function startDesktop() {
  show('starting');
  try {
    const status = await adapter().core('desktop_status', {});
    if (status && status.running) {
      goLive();
      return;
    }
    const requestId =
      typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : `${Date.now()}`;
    const submitted = await adapter().core('submit', {
      tool: 'system.desktop',
      args: { action: 'start' },
      request_id: requestId,
    });
    const id = (submitted.job && submitted.job.id) || requestId;
    let job = submitted.job;
    const deadline = Date.now() + 60000;
    while (Date.now() < deadline) {
      if (job && !['queued', 'running'].includes(job.state)) break;
      await sleep(800);
      job = (await adapter().core('get', { id })).job;
    }
    if (!job || job.state !== 'completed') {
      error('El escritorio no ha arrancado.', (job && (job.error || job.state)) || 'Sin respuesta de Core.');
      return;
    }
    goLive();
  } catch (failure) {
    error('El escritorio no ha arrancado.', String(failure.message || failure));
  }
}

async function goLive() {
  show('live');
  $('screen-wait').hidden = false;
  try {
    // Xvnc on the phone speaks RFB; carry it through an adb shell pipe. The
    // password is the one LambdaOS wrote when it started the desktop.
    const password = (await adapter().shell('cat /data/local/lex/vncpass', 8000)).trim();
    if (!password) throw new Error('El teléfono no ha publicado la contraseña del escritorio. Activa adb root en el teléfono (modo desarrollador de Lambda Operator) y vuelve a intentarlo.');
    const channel = await adapter().openStream('nc 127.0.0.1 5901', 15000);
    desktopStream = channel;
    if (!window.LexDesktop) throw new Error('Falta el cliente de escritorio (lex-desktop.js).');
    desktop = window.LexDesktop.startDesktop($('screen'), channel, { password });
    state.live = true;
    desktop.addEventListener('connect', () => { $('screen-wait').hidden = true; });
    desktop.addEventListener('desktopname', (event) => {
      const name = event.detail && event.detail.name;
      if (name) $('stage-device').textContent = `Galaxy S10 · ${name}`;
    });
    desktop.addEventListener('securityfailure', (event) => {
      error('El teléfono ha rechazado la contraseña del escritorio.', String((event.detail && event.detail.reason) || ''));
    });
    desktop.addEventListener('disconnect', () => {
      if (!state.live) return;
      error('Se ha perdido la conexión con el escritorio.', 'Comprueba el teléfono y vuelve a conectar.');
    });
  } catch (failure) {
    error('No se pudo abrir el escritorio.', String(failure.message || failure));
  }
}

function stopLive() {
  state.live = false;
  try { if (desktop) desktop.disconnect(); } catch { /* already gone */ }
  desktop = null;
  try { if (desktopStream) desktopStream.close(); } catch { /* already gone */ }
  desktopStream = null;
  // noVNC leaves its screen div behind; drop it so the next session starts clean.
  for (const child of [...$('screen').children]) {
    if (child.id !== 'screen-wait') child.remove();
  }
  $('screen-wait').hidden = false;
}

/* ---------- controls ---------- */

$('btn-connect').addEventListener('click', beginConnect);
$('btn-retry-wrong').addEventListener('click', () => reset(true));
$('btn-retry-rejected').addEventListener('click', beginConnect);
$('btn-retry-error').addEventListener('click', () => reset(true));
$('btn-cancel-pick').addEventListener('click', () => reset(false));
$('btn-cancel-consent').addEventListener('click', () => {
  state.consentToken = '';
  reset(false);
});

$('btn-fullscreen').addEventListener('click', async () => {
  const stage = document.querySelector('.stage');
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await stage.requestFullscreen();
  } catch {
    /* Some browsers refuse without a direct gesture; the button is the gesture. */
  }
});

$('btn-disconnect').addEventListener('click', () => reset(true));

$('btn-copy-cmd').addEventListener('click', async () => {
  const button = $('btn-copy-cmd');
  try {
    await navigator.clipboard.writeText($('error-cmd-text').textContent);
    button.textContent = 'Copiado';
  } catch {
    button.textContent = 'No se pudo copiar';
  }
  setTimeout(() => { button.textContent = 'Copiar'; }, 1600);
});

async function reset(hard) {
  stopLive();
  state.answered = false;
  state.consentToken = '';
  if (hard && adapter()) {
    try {
      await adapter().close();
    } catch {
      /* Already gone. */
    }
  }
  show('connect');
}

/* Leaving the page hangs up the USB session cleanly. */
addEventListener('pagehide', () => {
  if (adapter()) adapter().close().catch(() => {});
});
