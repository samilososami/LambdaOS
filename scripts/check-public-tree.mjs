#!/usr/bin/env node
// Publication guard: report paths/rules only, never print matching secret values.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

if (process.getuid?.() === 0) {
  const result = spawnSync('runuser', ['-u', 'kali', '--', process.execPath,
    fileURLToPath(import.meta.url), ...process.argv.slice(2)], {stdio: 'inherit'});
  process.exit(result.status ?? 1);
}
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const list = spawnSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'],
  {cwd: root, encoding: 'utf8'});
if (list.status !== 0) throw new Error('Cannot enumerate public Git tree');
const files = [...new Set(list.stdout.split('\0').filter(Boolean))];
const errors = [];
const forbidden = /(^|\/)(private|backups|checkpoints|node_modules|\.git|out|__pycache__)(\/|$)|\.(pk8|p12|pfx|jks|keystore|key|pass|pem|apk|img|bin|zip|gguf|litertlm|safetensors|vhdx)$/i;
const secretRules = [
  ['private-key', /-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----/],
  ['github-token', /\bgh[pousr]_[A-Za-z0-9]{30,}\b/],
  ['github-fine-token', /\bgithub_pat_[A-Za-z0-9_]{40,}\b/],
  ['hf-token', /\bhf_[A-Za-z0-9]{25,}\b/],
  ['aws-key', /\bAKIA[A-Z0-9]{16}\b/],
];
for (const name of files) {
  const full = path.join(root, name);
  const stat = fs.lstatSync(full);
  if (!stat.isFile() || stat.isSymbolicLink()) errors.push([name, 'not-regular-file']);
  if (forbidden.test(name)) errors.push([name, 'private-or-binary-release-path']);
  if (stat.size > 100 * 1024 * 1024) errors.push([name, 'github-file-limit']);
  if (!/\.(png|jpe?g|webp|gif|otf|ttf)$/i.test(name)) {
    const text = fs.readFileSync(full, 'utf8');
    for (const [rule, expression] of secretRules) if (expression.test(text)) errors.push([name, rule]);
  }
}
// Check only authored publication docs; upstream docs retain their own context.
const docs = files.filter(p => /^(README\.md|ROADMAP\.md|NOTICE\.md|docs\/.*\.md|releases\/.*\.md|assets\/(README|INDEX)\.md)$/.test(p));
for (const name of docs) {
  const text = fs.readFileSync(path.join(root, name), 'utf8');
  const refs = [...text.matchAll(/\]\(([^)]+)\)/g)].map(m => m[1])
    .concat([...text.matchAll(/(?:src|href)="([^"]+)"/g)].map(m => m[1]));
  for (const raw of refs) {
    if (/^(https?:|mailto:|#)/.test(raw)) continue;
    const target = path.resolve(root, path.dirname(name), decodeURIComponent(raw.split('#')[0]));
    if (!target.startsWith(root + path.sep) || !fs.existsSync(target)) errors.push([name, `broken-local-link:${raw}`]);
  }
}
for (const [file, field] of [['assets/manifest.json', 'entries'], ['docs/source-inventory.json', 'files']]) {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, file), 'utf8'));
  for (const entry of manifest[field]) {
    const full = path.resolve(root, entry.path);
    if (!full.startsWith(root + path.sep)) throw new Error('Invalid manifest path');
    const data = fs.readFileSync(full);
    const actual = crypto.createHash('sha256').update(data).digest('hex');
    if (actual !== entry.sha256 || data.length !== entry.bytes) errors.push([entry.path, 'manifest-mismatch']);
  }
}
if (errors.length) {
  console.error(JSON.stringify({status: 'failed', errors}, null, 2));
  process.exit(1);
}
console.log(JSON.stringify({status: 'passed', files: files.length, authored_docs: docs.length,
  checked: ['publication-paths', 'known-secret-patterns', 'file-sizes', 'local-links', 'asset-and-source-hashes']}));
