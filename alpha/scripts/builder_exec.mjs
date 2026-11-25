#!/usr/bin/env node
// Run an explicit Linux command through the existing authenticated SSH master.
// No credentials are stored or printed. Works for kali and root.
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
if (process.getuid?.() === 0) {
  const r=spawnSync('runuser',['-u','kali','--',process.execPath,fileURLToPath(import.meta.url),...process.argv.slice(2)],{stdio:'inherit'});
  process.exit(r.status ?? 1);
}
const command=process.argv[2];
if(!command) throw new Error('Usage: builder_exec.mjs <bash command>');
const socket=process.env.LAMBDA_SSH_SOCKET;
if(!socket) throw new Error('Set LAMBDA_SSH_SOCKET to an authenticated SSH control socket.');
const encoded=Buffer.from(command,'utf8').toString('base64');
const ps=`& wsl.exe -d Ubuntu -u kali -- bash -c "echo ${encoded} | base64 -d | bash"; exit $LASTEXITCODE`;
const r=spawnSync('ssh',['-o','BatchMode=yes','-S',socket,'Usuario@192.168.1.147',
  'powershell.exe -NoProfile -NonInteractive -EncodedCommand '+Buffer.from(ps,'utf16le').toString('base64')],{stdio:'inherit'});
process.exit(r.status ?? 1);
