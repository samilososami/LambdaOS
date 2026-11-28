import RFB from './novnc/core/rfb.js';

// channel is an already-open adb shell stream to `nc 127.0.0.1 5901`.
export function startDesktop(container, channel, options = {}) {
  const rfb = new RFB(container, channel, {
    credentials: { password: options.password || '' },
    shared: true,
  });
  rfb.scaleViewport = true;
  rfb.resizeSession = false;
  // 2026-11-28, 2280x1080 over adb: q6/c2 measured best (~60 KB frames, ~15 ms updates).
  rfb.qualityLevel = options.quality ?? 6;
  rfb.compressionLevel = options.compression ?? 2;
  rfb.showDotCursor = true;
  return rfb;
}

window.LexDesktop = { startDesktop };
