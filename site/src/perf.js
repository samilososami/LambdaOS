/* How much motion can this device carry?
   "lite" keeps every section and interaction but drops smooth wheel scrolling, the floating
   phone, blur and the language reveal. ?lite and ?full in the URL force either mode. */
const query = new URLSearchParams(location.search);

/* Cheap signals, read before anything animates. */
export function startsLite() {
  if (query.has('full')) return false;
  if (query.has('lite')) return true;
  const { connection, hardwareConcurrency: cores, deviceMemory: memory } = navigator;
  return Boolean(connection?.saveData) || (cores > 0 && cores <= 2) || (memory > 0 && memory <= 2);
}

/* Software rendering (no usable GPU) is the clearest sign that full motion will stutter.
   A page without WebGL at all is not penalised: that is often a privacy setting, not a slow machine. */
export function softwareRendering() {
  if (query.has('full')) return false;
  try {
    const canvas = document.createElement('canvas');
    const any = canvas.getContext('webgl');
    if (!any) return false;
    const info = any.getExtension('WEBGL_debug_renderer_info');
    const renderer = info ? String(any.getParameter(info.UNMASKED_RENDERER_WEBGL)) : '';
    any.getExtension('WEBGL_lose_context')?.loseContext();
    if (/swiftshader|llvmpipe|softpipe|software|basic render/i.test(renderer)) return true;
    const strict = document.createElement('canvas').getContext('webgl', { failIfMajorPerformanceCaveat: true });
    strict?.getExtension('WEBGL_lose_context')?.loseContext();
    return !strict;
  } catch {
    return false;
  }
}

/* Watches real frames while the page scrolls: if most of them miss 45 fps, report it once. */
export function frameWatcher(onSlow) {
  const deltas = [];
  let last = 0, done = false;
  return now => {
    if (done || document.visibilityState !== 'visible') return;
    const delta = now - last;
    last = now;
    if (delta <= 0 || delta > 200) return; // a pause between scrolls, not a slow frame
    deltas.push(delta);
    if (deltas.length < 90) return;
    done = true;
    const sorted = deltas.sort((a, b) => a - b);
    if (sorted[Math.floor(sorted.length / 2)] > 22) onSlow();
  };
}
