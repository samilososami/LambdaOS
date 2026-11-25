'use strict';

const POLL_MS = 5000;

const state = {
  device: null,
  desktop: null,
  jobs: [],
  busy: false,
  polling: false,
  pollTimer: null,
  shotBusy: false,
  shotUrl: null,
  shotLoaded: false,
  connection: { connected: false, host: '' },
};

const $ = (id) => document.getElementById(id);
const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

function formatBytes(bytes) {
  if (!Number.isFinite(bytes)) return '—';
  const gib = bytes / 1024 ** 3;
  if (gib >= 100) return `${Math.round(gib)} GB`;
  if (gib >= 10) return `${gib.toFixed(1)} GB`;
  return `${gib.toFixed(2)} GB`;
}

function relativeTime(ms) {
  if (!Number.isFinite(ms)) return '—';
  const diff = Date.now() - ms;
  if (diff < 45000) return 'just now';
  const minute = 60000;
  const hour = 3600000;
  const day = 86400000;
  if (diff < hour) return rtf.format(-Math.round(diff / minute), 'minute');
  if (diff < day) return rtf.format(-Math.round(diff / hour), 'hour');
  return rtf.format(-Math.round(diff / day), 'day');
}

function formatDuration(ms) {
  if (!Number.isFinite(ms)) return '—';
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`;
  const minutes = Math.floor(ms / 60000);
  const seconds = Math.round((ms % 60000) / 1000);
  return `${minutes}m ${seconds}s`;
}

function setAction(text, tone = '') {
  const element = $('action-status');
  element.textContent = text || '';
  element.dataset.tone = tone;
}

function connected() {
  return state.connection.connected;
}

function setConnectStatus(text, tone = '') {
  const element = $('connect-status');
  element.textContent = text;
  element.dataset.tone = tone;
}

function adoptConnection(host) {
  state.connection.connected = true;
  state.connection.host = host;
  $('connection').classList.add('is-connected');
  $('btn-connect-usb').disabled = true;
  $('connect-host').readOnly = true;
  setConnectStatus(`Connected · ${host} approved on the phone.`, 'ok');
  syncButtons();
  startPolling();
  void refreshShot();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const usbAdapter = () => window.LexAdb;

async function usbCore(method, payload) {
  const data = await usbAdapter().core(method, payload || {});
  if (data && data.ok === false) throw new Error(data.detail || data.error || 'Core rejected the request.');
  return data;
}

// Accepting Android's USB-debugging prompt resets the USB function, so the
// first shell after AUTH can die with a cancelled transfer. Reopen once: adbd
// keeps the accepted key in memory for this session and does not prompt again.
async function usbCoreRetry(method, payload) {
  try {
    return await usbCore(method, payload);
  } catch (error) {
    if (!/USB read failed|transfer was cancelled|USB disconnected|not connected/i.test(error.message)) throw error;
    await usbAdapter().close().catch(() => {});
    await sleep(900);
    await usbAdapter().connect();
    return usbCore(method, payload);
  }
}

function renderDevice(data, error) {
  const pill = $('pill-text');
  const dot = $('pill-dot');

  if (!data || data.ok !== true) {
    const message = error?.message || 'No data from adb.';
    if (!connected()) {
      pill.textContent = 'Not connected · approve on the S10';
      dot.dataset.state = 'idle';
    } else {
      pill.textContent = /unauthorized/i.test(message)
        ? 'ADB unauthorized · accept the prompt on the S10'
        : 'USB link unavailable · check the phone';
      dot.dataset.state = 'bad';
    }
    $('device-pill').title = message;
    for (const [valueId, detailId] of [
      ['m-battery', 'm-battery-detail'],
      ['m-wifi', 'm-wifi-detail'],
      ['m-storage', 'm-storage-detail'],
      ['m-core', 'm-core-detail'],
      ['m-worker', 'm-worker-detail'],
    ]) {
      $(valueId).textContent = '—';
      $(detailId).textContent = 'waiting for USB';
    }
    $('m-battery-bar').style.width = '0%';
    $('m-storage-bar').style.width = '0%';
    $('shot-activity').textContent = 'Foreground —';
    return;
  }

  const device = data.device || {};
  const worker = data.worker || {};
  const model = device.model || 'S10';
  const android = device.android ? `Android ${device.android}` : 'Android —';
  pill.textContent = `${model} · ${android} · worker ${worker.available ? 'online' : 'offline'}`;
  $('device-pill').removeAttribute('title');
  dot.dataset.state = worker.available ? 'ok' : 'warn';

  const battery = data.battery;
  $('m-battery').textContent = Number.isFinite(battery?.level) ? `${battery.level}%` : '—';
  $('m-battery-detail').textContent = battery?.status
    ? `${battery.status}${battery.temperature_c != null ? ` · ${battery.temperature_c} °C` : ''}`
    : 'battery unavailable';
  const level = Number.isFinite(battery?.level) ? battery.level : 0;
  const batteryBar = $('m-battery-bar');
  batteryBar.style.width = `${Math.max(0, Math.min(100, level))}%`;
  batteryBar.dataset.tone = level <= 10 ? 'critical' : level <= 25 ? 'warn' : 'ok';

  const wifi = data.wifi;
  $('m-wifi').textContent = wifi?.connected ? wifi.ssid || 'Connected' : 'Offline';
  $('m-wifi').title = wifi?.ssid || '';
  $('m-wifi-detail').textContent = wifi?.connected ? wifi.ip || 'no IPv4 address' : 'no wireless link';

  const storage = data.storage;
  $('m-storage').textContent = storage ? `${formatBytes(storage.free_bytes)} free` : '—';
  $('m-storage-detail').textContent = storage
    ? `${storage.used_percent ?? '—'}% used of ${formatBytes(storage.total_bytes)}`
    : 'storage unavailable';
  const used = Number.isFinite(storage?.used_percent) ? storage.used_percent : 0;
  const storageBar = $('m-storage-bar');
  storageBar.style.width = `${Math.max(0, Math.min(100, used))}%`;
  storageBar.dataset.tone = used >= 90 ? 'critical' : used >= 75 ? 'warn' : 'ok';

  const core = data.core || {};
  $('m-core').textContent = core.version || 'Unreachable';
  $('m-core-detail').textContent = core.version ? 'ContentProvider RPC' : core.error || 'Core not responding';
  $('m-core-detail').title = core.error || '';

  $('m-worker').textContent = worker.available ? 'Online' : 'Offline';
  const adapters = worker.adapters || 0;
  $('m-worker-detail').textContent = worker.available
    ? `${adapters} adapter${adapters === 1 ? '' : 's'} · maintenance`
    : 'radio worker unavailable';
  $('m-worker-detail').title = worker.error || '';

  const activity = data.top_activity || '—';
  $('shot-activity').textContent = `Foreground · ${activity}`;
  $('shot-activity').title = activity;
}

function renderJobs(jobs) {
  const body = $('jobs-body');
  body.replaceChildren();
  $('jobs-empty').hidden = jobs.length > 0;
  $('jobs-meta').textContent = jobs.length ? `${jobs.length} shown · Core` : 'Core · empty';
  $('jobs-meta').removeAttribute('title');

  const fragment = document.createDocumentFragment();
  for (const job of jobs) {
    const row = document.createElement('tr');

    const tool = document.createElement('td');
    tool.className = 'tool';
    tool.textContent = job.action ? `${job.tool} · ${job.action}` : job.tool || '—';
    row.append(tool);

    const stateCell = document.createElement('td');
    stateCell.dataset.label = 'State';
    const chip = document.createElement('span');
    chip.className = 'chip';
    chip.dataset.state = job.state || 'unknown';
    chip.textContent = job.state || 'unknown';
    stateCell.append(chip);
    row.append(stateCell);

    const created = document.createElement('td');
    created.className = 'time';
    created.dataset.label = 'Created';
    created.textContent = relativeTime(job.created_ms);
    if (Number.isFinite(job.created_ms)) created.title = new Date(job.created_ms).toLocaleString();
    row.append(created);

    const duration = document.createElement('td');
    duration.className = 'duration col-duration';
    duration.dataset.label = 'Duration';
    duration.textContent = formatDuration(job.duration_ms);
    row.append(duration);

    const detail = document.createElement('td');
    detail.className = 'detail';
    detail.dataset.label = 'Detail';
    detail.textContent = job.error || '—';
    if (job.error) {
      detail.classList.add('has-error');
      detail.title = job.error;
    } else {
      detail.classList.add('is-empty');
    }
    row.append(detail);

    fragment.append(row);
  }
  body.append(fragment);
}

function syncButtons() {
  const data = state.desktop;
  const running = data?.running === true;
  const busy = state.busy;
  const ready = connected();

  const start = $('btn-start');
  const stop = $('btn-stop');
  const cta = $('btn-start-cta');

  start.hidden = running;
  start.disabled = busy || !ready;
  stop.hidden = !running;
  stop.disabled = busy || !ready;
  cta.disabled = busy || !ready;
  cta.hidden = running;
  $('btn-refresh').disabled = busy || !ready;
  $('btn-shot').disabled = !ready || state.shotBusy;
  $('desk-frame').setAttribute('aria-busy', String(busy));

  if (busy) {
    $('desk-dot').dataset.state = 'busy';
  } else if (running) {
    $('desk-dot').dataset.state = 'on';
  } else {
    $('desk-dot').dataset.state = 'off';
  }
}

function renderDesktop(data) {
  state.desktop = data || null;

  const running = data?.running === true;
  const desktop = data?.desktop || null;

  $('desk-frame').dataset.state = running ? 'running' : 'stopped';
  $('desk-empty').hidden = false;

  const title = $('empty-title');
  const copy = $('empty-copy');

  if (running) {
    $('desk-state').textContent = 'Desktop live on the phone';
    $('desk-meta').textContent = `${desktop?.geometry || '2280 × 1080'} · XFCE`;
    title.textContent = 'Desktop is live';
    copy.textContent =
      'The XFCE session is running on the S10. To watch it on a computer, run “adb forward tcp:6080 tcp:6080” there and open http://127.0.0.1:6080/vnc.html — WebUSB cannot open host ports.';
  } else {
    $('desk-state').textContent = 'Desktop stopped';
    $('desk-meta').textContent = 'system.desktop · WebUSB';
    title.textContent = 'Desktop is off';
    copy.textContent = 'Start the Kali XFCE session over the USB link. Core runs the typed system.desktop job on the phone.';
  }

  syncButtons();
}

function parseUsbWifi(text) {
  const match = /Wifi is connected to "([^"]*)"/.exec(text || '');
  if (!match) return { connected: false, ssid: null, ip: null };
  const ip = /IP:\s*\/?(\d{1,3}(?:\.\d{1,3}){3})/.exec(text || '');
  return { connected: true, ssid: match[1], ip: ip ? ip[1] : null };
}

function parseUsbStorage(text) {
  const match = /^\s*\S+\s+(\d+)\s+(\d+)\s+(\d+)\s+\d+%\s+\/data\s*$/m.exec(text || '');
  if (!match) return null;
  const total = Number(match[1]) * 1024;
  const used = Number(match[2]) * 1024;
  const free = Number(match[3]) * 1024;
  return {
    total_bytes: total,
    used_bytes: used,
    free_bytes: free,
    used_percent: total ? Math.round((used / total) * 100) : null,
  };
}

async function usbShellSafe(command) {
  try {
    return await usbAdapter().shell(command);
  } catch {
    return null;
  }
}

async function usbDevice() {
  const props = await usbShellSafe(
    'getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk',
  );
  const batteryText = await usbShellSafe('dumpsys battery');
  const wifiText = await usbShellSafe('cmd wifi status');
  const storageText = await usbShellSafe('df -k /data');
  if (props === null && batteryText === null && wifiText === null && storageText === null) {
    throw new Error('USB link lost. Reconnect over USB.');
  }
  const coreStatus = await usbCore('status').catch((error) => ({ ok: false, error: error.message }));
  const radio = await usbCore('radio_status').catch((error) => ({ ok: false, error: error.message }));
  const propLines = String(props || '').split(/\r?\n/);
  const level = /level:\s*(\d+)/.exec(batteryText || '');
  const temperature = /temperature:\s*(\d+)/.exec(batteryText || '');
  const rawStatus = /status:\s*(\d+)/.exec(batteryText || '');
  const labels = { 1: 'unknown', 2: 'charging', 3: 'discharging', 4: 'not charging', 5: 'full' };
  const status = rawStatus ? labels[Number(rawStatus[1])] || 'unknown' : '';
  return {
    ok: true,
    device: {
      model: propLines[0] || null,
      product: propLines[0] || null,
      android: propLines[1] || null,
      sdk: propLines[2] || null,
    },
    battery: batteryText
      ? {
          level: level ? Number(level[1]) : null,
          status,
          charging: rawStatus ? [2, 5].includes(Number(rawStatus[1])) : null,
          temperature_c: temperature ? Number((Number(temperature[1]) / 10).toFixed(1)) : null,
        }
      : null,
    wifi: wifiText ? parseUsbWifi(wifiText) : null,
    storage: parseUsbStorage(storageText),
    top_activity: null,
    core: {
      reachable: coreStatus.ok !== false,
      version: coreStatus.version || null,
      error: coreStatus.ok === false ? coreStatus.error : null,
    },
    worker: { available: radio.available === true, adapters: 0, error: radio.ok === false ? radio.error : null },
  };
}

async function usbDesktopState() {
  const status = await usbCore('desktop_status');
  return { ok: true, running: status.running === true, desktop: status.desktop || null };
}

async function usbJobs(limit = 10) {
  const data = await usbCore('list', { include_hidden: true });
  const jobs = (Array.isArray(data.jobs) ? data.jobs : [])
    .slice()
    .sort((a, b) => (b.created_ms || 0) - (a.created_ms || 0))
    .slice(0, limit)
    .map((job) => ({
      id: job.id,
      tool: job.tool,
      action: job.args && typeof job.args === 'object' ? job.args.action ?? null : null,
      state: job.state,
      created_ms: job.created_ms ?? null,
      duration_ms: job.duration_ms ?? null,
      error: job.error ?? null,
    }));
  return { ok: true, jobs };
}

async function usbSubmitDesktop(action) {
  const requestId =
    typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const submitted = await usbCore('submit', { tool: 'system.desktop', args: { action }, request_id: requestId });
  const id = submitted.job?.id || requestId;
  const deadline = Date.now() + 45000;
  let job = submitted.job;
  while (Date.now() < deadline) {
    if (job && !['queued', 'running'].includes(job.state)) break;
    await sleep(700);
    job = (await usbCore('get', { id })).job;
  }
  if (!job) throw new Error('Core returned no job.');
  if (['queued', 'running'].includes(job.state)) throw new Error(`system.desktop ${action} is still ${job.state}.`);
  if (job.state !== 'completed') throw new Error(job.error || `system.desktop ${action} ended ${job.state}.`);
  return usbDesktopState();
}

async function loadDevice() {
  try {
    const data = await usbDevice();
    state.device = data;
    renderDevice(data);
  } catch (error) {
    state.device = null;
    renderDevice(null, error);
  }
}

async function loadDesktop() {
  if (state.busy) return;
  try {
    renderDesktop(await usbDesktopState());
  } catch (error) {
    $('desk-state').textContent = 'Desktop state unavailable';
    $('desk-meta').textContent = 'Core / USB error';
    $('desk-dot').dataset.state = 'bad';
  }
}

async function loadJobs() {
  try {
    const data = await usbJobs(10);
    state.jobs = data.jobs || [];
    renderJobs(state.jobs);
  } catch (error) {
    $('jobs-meta').textContent = 'Core unavailable';
    $('jobs-meta').title = error.message;
  }
}

async function pollOnce() {
  if (state.polling || document.hidden || !connected()) return;
  state.polling = true;
  try {
    // One ADB stream at a time on the WebUSB link.
    await loadDevice();
    await loadDesktop();
    await loadJobs();
  } finally {
    state.polling = false;
  }
}

function stopPolling() {
  if (state.pollTimer) clearInterval(state.pollTimer);
  state.pollTimer = null;
}

function startPolling() {
  stopPolling();
  void pollOnce();
  state.pollTimer = setInterval(() => void pollOnce(), POLL_MS);
}

function restoreDesktop() {
  renderDesktop(state.desktop || { ok: true, running: false, desktop: null });
}

async function startDesktop() {
  if (state.busy || !connected()) return;
  state.busy = true;
  syncButtons();
  setAction('Submitting the typed system.desktop start job…');
  $('empty-title').textContent = 'Starting the desktop…';
  $('empty-copy').textContent = 'Core is running system.desktop start. The XFCE session usually appears in 3–6 seconds.';
  $('btn-start-cta').textContent = 'Starting…';
  $('desk-state').textContent = 'Starting desktop…';

  try {
    const data = await usbSubmitDesktop('start');
    renderDesktop(data);
    setAction('Desktop started. The live view needs an ADB forward on a computer.', 'ok');
  } catch (error) {
    setAction(error.message, 'error');
    restoreDesktop();
  } finally {
    state.busy = false;
    syncButtons();
    $('btn-start-cta').textContent = 'Start desktop';
    void pollOnce();
  }
}

async function stopDesktop() {
  if (state.busy || !connected()) return;
  state.busy = true;
  syncButtons();
  setAction('Submitting the typed system.desktop stop job…');
  $('desk-state').textContent = 'Stopping desktop…';

  try {
    const data = await usbSubmitDesktop('stop');
    renderDesktop(data);
    setAction('Desktop stopped.', 'ok');
  } catch (error) {
    setAction(error.message, 'error');
    restoreDesktop();
  } finally {
    state.busy = false;
    syncButtons();
    void pollOnce();
  }
}

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

async function refreshShot() {
  const image = $('shot');
  const empty = $('shot-empty');
  if (!connected()) {
    image.hidden = true;
    empty.hidden = false;
    empty.textContent = 'Connect first';
    return;
  }
  if (state.shotBusy) return;
  state.shotBusy = true;
  $('btn-shot').disabled = true;
  empty.hidden = false;
  empty.textContent = 'Capturing…';
  image.classList.add('is-loading');
  try {
    const raw = await usbAdapter().shellBytes('screencap -p', 25000);
    // adb's v1 shell merges stderr into stdout; skip any warning before the PNG.
    const start = raw.findIndex(
      (value, index) => value === 0x89 && PNG_SIGNATURE.every((byte, offset) => raw[index + offset] === byte),
    );
    if (start < 0) throw new Error('The phone did not return a PNG frame.');
    const bytes = raw.subarray(start);
    if (state.shotUrl) URL.revokeObjectURL(state.shotUrl);
    state.shotUrl = URL.createObjectURL(new Blob([bytes], { type: 'image/png' }));
    image.onload = () => {
      state.shotLoaded = true;
      image.classList.remove('is-loading');
      image.hidden = false;
      empty.hidden = true;
      const width = image.naturalWidth || 0;
      const height = image.naturalHeight || 0;
      if (width && height) {
        $('shot-frame').style.aspectRatio = `${width} / ${height}`;
        $('shot-frame').style.minHeight = '0';
        $('shot-size').textContent = `${width} × ${height}`;
      }
    };
    image.onerror = () => {
      image.classList.remove('is-loading');
      if (!state.shotLoaded) image.hidden = true;
      empty.hidden = false;
      empty.textContent = 'Screenshot unavailable';
    };
    image.src = state.shotUrl;
  } catch (error) {
    image.classList.remove('is-loading');
    if (!state.shotLoaded) image.hidden = true;
    empty.hidden = false;
    empty.textContent = /Timed out/i.test(error.message) ? 'Screenshot timed out' : 'Screenshot unavailable';
  } finally {
    state.shotBusy = false;
    $('btn-shot').disabled = !connected();
  }
}

// ---- Connection gate -------------------------------------------------------

async function connectViaUsb() {
  const adapter = usbAdapter();
  if (!adapter || !adapter.supported()) {
    setConnectStatus(
      window.isSecureContext === false
        ? 'WebUSB needs a secure context: open this page from http://127.0.0.1:… or over HTTPS.'
        : 'WebUSB is not available in this browser. Use Chrome or Edge with the S10 on a USB cable.',
      'error',
    );
    return;
  }
  const host = $('connect-host').value.trim();
  if (!host) {
    setConnectStatus('Give this device a name first.', 'error');
    return;
  }
  $('btn-connect-usb').disabled = true;
  $('connect-host').readOnly = true;
  setConnectStatus('Pick the S10 in Chrome’s USB chooser…', 'wait');
  try {
    await adapter.connect();
    setConnectStatus('USB link up. If Android asks “Allow USB debugging?”, accept it.', 'wait');
    const first = await usbCoreRetry('connect', { host });
    if (first.state === 'trusted' || first.state === 'allowed') {
      adoptConnection(host);
      return;
    }
    if (first.state === 'denied') throw new Error('The phone denied this host.');
    setConnectStatus(`Waiting for approval on the S10: “${host} quiere conectarse a este teléfono. ¿Permitir?”`, 'wait');
    const deadline = Date.now() + 180000;
    for (;;) {
      if (Date.now() > deadline) throw new Error('No answer from the phone. Check the notification shade and retry.');
      await sleep(1500);
      const status = await usbCoreRetry('connect_status', { host });
      if (status.state === 'trusted' || status.state === 'allowed') {
        adoptConnection(host);
        return;
      }
      if (status.state === 'denied') throw new Error('The phone denied this host.');
    }
  } catch (error) {
    setConnectStatus(error.message, 'error');
    $('btn-connect-usb').disabled = false;
    $('connect-host').readOnly = false;
    await adapter.close().catch(() => {});
  }
}

function boot() {
  const platform = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || 'Chrome';
  $('connect-host').value = String(platform).slice(0, 64);
  const usb = Boolean(window.LexAdb && window.LexAdb.supported());
  $('btn-connect-usb').disabled = !usb;
  if (!usb) {
    setConnectStatus(
      window.isSecureContext === false
        ? 'WebUSB needs a secure context: open this page from http://127.0.0.1:… or over HTTPS.'
        : 'WebUSB is not available in this browser. Use Chrome or Edge with the S10 on a USB cable.',
      'error',
    );
  } else {
    setConnectStatus('Plug the S10 in by USB, stop any host adb server, then press Connect.');
  }
  renderDevice(null, { message: 'Connect to the S10 to load telemetry.' });
  renderDesktop({ ok: true, running: false, desktop: null });
  syncButtons();
}

$('btn-start').addEventListener('click', startDesktop);
$('btn-start-cta').addEventListener('click', startDesktop);
$('btn-stop').addEventListener('click', stopDesktop);
$('btn-refresh').addEventListener('click', async () => {
  if (state.busy || !connected()) return;
  setAction('Refreshing…');
  await pollOnce();
  setAction('');
});
$('btn-shot').addEventListener('click', refreshShot);
$('btn-connect-usb').addEventListener('click', connectViaUsb);

document.addEventListener('visibilitychange', () => {
  if (document.hidden) stopPolling();
  else if (connected()) startPolling();
});

window.addEventListener('pagehide', () => {
  if (connected() && window.LexAdb) void window.LexAdb.close();
});

boot();
