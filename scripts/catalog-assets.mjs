#!/usr/bin/env node
// Generate the browsable inventory from the actual published files.
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
const walk = dir => fs.readdirSync(dir, {withFileTypes: true})
  .sort((a, b) => a.name.localeCompare(b.name, 'en'))
  .flatMap(entry => {
    const name = path.join(dir, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Unexpected symlink: ${name}`);
    return entry.isDirectory() ? walk(name) : [name];
  });
const digest = data => crypto.createHash('sha256').update(data).digest('hex');
const relative = name => path.relative(root, name).split(path.sep).join('/');
const images = walk(path.join(root, 'assets')).filter(p => /\.(png|jpe?g|svg|gif|webp)$/i.test(p));
const inventory = images.map(name => {
  const data = fs.readFileSync(name);
  const entry = {path: relative(name), bytes: data.length, sha256: digest(data)};
  if (data.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))) {
    entry.width = data.readUInt32BE(16);
    entry.height = data.readUInt32BE(20);
  }
  return entry;
});
fs.writeFileSync(path.join(root, 'assets/manifest.json'), JSON.stringify({
  schema: 1, snapshot: '2026-10-29', images: inventory.length,
  unique_sha256: new Set(inventory.map(x => x.sha256)).size,
  bytes: inventory.reduce((total, x) => total + x.bytes, 0), entries: inventory,
}, null, 2) + '\n');
const groups = new Map();
for (const item of inventory) {
  const components = item.path.split('/');
  const group = components[1] === 'design' ? components.slice(1, 3).join('/') : components[1];
  if (!groups.has(group)) groups.set(group, []);
  groups.get(group).push(item);
}
const link = p => p.split('/').map(encodeURIComponent).join('/');
const lines = ['# Complete image index', '',
  'Generated from the files in this repository. See [the library guide](README.md) for status and attribution.', '',
  `${inventory.length} images; ${(inventory.reduce((n, x) => n+x.bytes, 0)/1048576).toFixed(1)} MiB. Original dimensions are retained.`, ''];
for (const [group, entries] of groups) {
  lines.push(`## ${group}`, '', '| Image | PNG dimensions | Size |', '|---|---:|---:|');
  for (const item of entries) {
    const short = item.path.replace(/^assets\//, '');
    lines.push(`| [${path.basename(short)}](${link(short)}) | ${item.width ? `${item.width} × ${item.height}` : 'See file'} | ${(item.bytes/1024).toFixed(1)} KiB |`);
  }
  lines.push('');
}
fs.writeFileSync(path.join(root, 'assets/INDEX.md'), lines.join('\n').trimEnd() + '\n');
// Git's own view, not a filesystem walk, so gitignored files (signing keys,
// build output, the stray alpha/config/config path) never enter the inventory.
// The publication check enumerates the same universe.
const list = spawnSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'],
  {cwd: root, encoding: 'utf8'});
if (list.status !== 0) throw new Error('Cannot enumerate public Git tree');
const tracked = [...new Set(list.stdout.split('\0').filter(Boolean))];
const keep = name => !/(^|\/)(private|out|build|__pycache__)(\/|$)/.test(name) && !name.endsWith('.pyc');
const sources = tracked.filter(name => name.startsWith('alpha/') && keep(name));
const kernelSources = tracked.filter(name => name.startsWith('kernel/') && keep(name));
const publishedSources = [...sources, ...kernelSources].sort((a, b) =>
  a.localeCompare(b, 'en')).map(name => path.join(root, name));
fs.writeFileSync(path.join(root, 'docs/source-inventory.json'), JSON.stringify({
  schema: 1, snapshot: '2026-11-28', files: publishedSources.map(name => {
    const data = fs.readFileSync(name);
    return {path: relative(name), bytes: data.length, sha256: digest(data)};
  }),
}, null, 2) + '\n');
console.log(JSON.stringify({images: inventory.length, source_files: publishedSources.length,
  kernel_source_files: kernelSources.length,
  image_bytes: inventory.reduce((total, x) => total + x.bytes, 0)}));
