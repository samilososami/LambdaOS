#!/usr/bin/env node
/**
 * LambdaOS LEX dashboard: a static file server. Zero dependencies.
 *
 * The page talks to the phone itself over Chrome WebUSB, so this process
 * never runs adb and needs no device access. Serve `public/` anywhere that
 * provides localhost or HTTPS (WebUSB requires a secure context).
 */
import http from 'node:http';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = await fs.realpath(path.join(HERE, 'public'));
const DEFAULT_PORT = 7788;

const BASE_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Content-Security-Policy': [
    "default-src 'self'",
    "img-src 'self' data: blob:",
    "style-src 'self'",
    "script-src 'self'",
    "connect-src 'self'",
    "font-src 'self'",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
  ].join('; '),
};

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.json': 'application/json; charset=utf-8',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
};

function argValue(name) {
  const args = process.argv.slice(2);
  const flag = args.findIndex((arg) => arg === name);
  if (flag >= 0) {
    if (!args[flag + 1]) {
      console.error(`Missing value after ${name}.`);
      process.exit(2);
    }
    return args[flag + 1];
  }
  return null;
}

function readPort() {
  const raw = argValue('--port') ?? argValue('-p') ?? process.env.PORT ?? String(DEFAULT_PORT);
  const port = Number(raw);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    console.error(`Invalid port: ${raw}`);
    process.exit(2);
  }
  return port;
}

function sendText(res, status, text) {
  const body = Buffer.from(text);
  res.writeHead(status, {
    ...BASE_HEADERS,
    'Content-Type': 'text/plain; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

async function serve(req, res) {
  try {
    if (req.method !== 'GET' && req.method !== 'HEAD') return sendText(res, 405, 'Only GET and HEAD.\n');
    let relative;
    try {
      const url = new URL(req.url || '/', 'http://localhost');
      relative = decodeURIComponent(url.pathname === '/' ? 'index.html' : url.pathname.replace(/^\/+/, ''));
    } catch {
      return sendText(res, 400, 'Malformed URL.\n');
    }
    if (!relative || relative.includes('\0')) return sendText(res, 400, 'Malformed URL.\n');
    const target = path.resolve(PUBLIC_DIR, relative);
    if (target !== PUBLIC_DIR && !target.startsWith(PUBLIC_DIR + path.sep)) {
      return sendText(res, 403, 'Forbidden.\n');
    }
    let real;
    try {
      real = await fs.realpath(target);
    } catch {
      return sendText(res, 404, 'Not found.\n');
    }
    if (real !== PUBLIC_DIR && !real.startsWith(PUBLIC_DIR + path.sep)) {
      return sendText(res, 403, 'Forbidden.\n');
    }
    const stat = await fs.stat(real).catch(() => null);
    if (!stat || !stat.isFile()) return sendText(res, 404, 'Not found.\n');
    const data = await fs.readFile(real);
    res.writeHead(200, {
      ...BASE_HEADERS,
      'Content-Type': CONTENT_TYPES[path.extname(real).toLowerCase()] || 'application/octet-stream',
      'Content-Length': data.length,
      'Cache-Control': 'no-cache',
    });
    res.end(req.method === 'HEAD' ? undefined : data);
  } catch (error) {
    console.error('[lex]', error);
    if (!res.headersSent) sendText(res, 500, 'Server error.\n');
    else res.destroy();
  }
}

const PORT = readPort();
const HOST = argValue('--host') ?? process.env.HOST ?? '127.0.0.1';

const server = http.createServer((req, res) => {
  void serve(req, res);
});
server.on('error', (error) => {
  console.error(`[lex] server error: ${error.message}`);
  process.exit(1);
});
server.listen(PORT, HOST, () => {
  const shown = HOST === '0.0.0.0' || HOST === '::' ? 'localhost' : HOST;
  console.log(`LambdaOS LEX dashboard: http://${shown}:${PORT}`);
  console.log('Static page. The browser connects to the S10 over WebUSB; this server never runs adb.');
});
