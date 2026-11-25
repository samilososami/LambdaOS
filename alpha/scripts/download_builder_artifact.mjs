#!/usr/bin/env node
// Download one verified builder artifact over the authenticated SSH master.
// The transfer is written to an exact .part file and atomically renamed only
// after the remote reader and local stream both finish successfully.
import fs from 'node:fs';
import path from 'node:path';
import {spawn, spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const self = fileURLToPath(import.meta.url);
if (process.getuid?.() === 0) {
  const result = spawnSync('runuser', ['-u', 'kali', '--', process.execPath, self, ...process.argv.slice(2)], {stdio: 'inherit'});
  process.exit(result.status ?? 1);
}

const [remotePath, localPath] = process.argv.slice(2);
const socket = process.env.LAMBDA_SSH_SOCKET;
const outputRoot = path.resolve(path.dirname(self), '..', 'out') + path.sep;
if (!socket) throw new Error('An authenticated LAMBDA_SSH_SOCKET is required.');
if (!remotePath?.startsWith('/srv/lambdaos/releases/') || !/^[a-zA-Z0-9_./-]+$/.test(remotePath)) {
  throw new Error('Expected an explicit artifact below /srv/lambdaos/releases/.');
}
const destination = path.resolve(localPath ?? '');
if (!destination.startsWith(outputRoot) || destination.includes('\0')) {
  throw new Error('Destination must be below the LambdaOS alpha/out directory.');
}
fs.mkdirSync(path.dirname(destination), {recursive: true, mode: 0o755});
const partial = destination + '.part';
if (fs.existsSync(destination) || fs.existsSync(partial)) {
  throw new Error('Destination already exists: ' + destination);
}

const remote = spawn('ssh', [
  '-o', 'BatchMode=yes', '-S', socket, 'Usuario@192.168.1.147',
  `wsl.exe -d Ubuntu -u kali -- dd if=${remotePath} bs=4M status=none`,
], {stdio: ['ignore', 'pipe', 'inherit']});
const output = fs.createWriteStream(partial, {flags: 'wx', mode: 0o644});
remote.stdout.pipe(output);

const remoteStatus = new Promise((resolve, reject) => {
  remote.once('error', reject);
  remote.once('close', code => code === 0 ? resolve() : reject(new Error(`Remote reader exited with status ${code}`)));
});
const outputStatus = new Promise((resolve, reject) => {
  output.once('error', reject);
  output.once('close', resolve);
});

try {
  await Promise.all([remoteStatus, outputStatus]);
  fs.renameSync(partial, destination);
  console.log(`${fs.statSync(destination).size} ${destination}`);
} catch (error) {
  remote.kill('SIGTERM');
  if (fs.existsSync(partial)) fs.rmSync(partial);
  throw error;
}
