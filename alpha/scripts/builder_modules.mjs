#!/usr/bin/env node
// Reproducible incremental build on the already authorized Windows/WSL host.
// Activates the existing build swap file; never creates disks or restarts WSL.
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const self=fileURLToPath(import.meta.url);
if(process.getuid?.()===0){const r=spawnSync('runuser',['-u','kali','--',process.execPath,self,...process.argv.slice(2)],{stdio:'inherit'});process.exit(r.status??1);}
const args=process.argv.slice(2);
if(args.length>1 || (args.length===1 && args[0]!=='--target-files'))throw new Error('Usage: builder_modules.mjs [--target-files]');
const targetFiles=args[0]==='--target-files';
const socket=process.env.LAMBDA_SSH_SOCKET;
if(!socket)throw new Error('An authenticated LAMBDA_SSH_SOCKET is required.');
const jobs=Number(process.env.LAMBDA_BUILD_JOBS||8);
if(!Number.isInteger(jobs)||jobs<1||jobs>12)throw new Error('Expected 1–12 build jobs.');
// WSL stops between foreground sessions. The fstab entry has not reliably
// activated this file on every start; verify it rather than assuming it is active.
const swap=`set -eu
test -f /srv/lambdaos/.lambdaos-build.swap
test "$(stat -c '%s:%a:%U' /srv/lambdaos/.lambdaos-build.swap)" = '34359738368:600:root'
if ! /usr/sbin/swapon --show=NAME --noheadings --raw | grep -Fxq '/srv/lambdaos/.lambdaos-build.swap'; then
    /usr/sbin/swapon -p 10 /srv/lambdaos/.lambdaos-build.swap
fi`;
const encoded=Buffer.from(swap).toString('base64');
const ps=`& wsl.exe -d Ubuntu -u root -- bash -c "echo ${encoded} | base64 -d | bash"; exit $LASTEXITCODE`;
const activated=spawnSync('ssh',['-o','BatchMode=yes','-S',socket,'Usuario@192.168.1.147','powershell.exe -NoProfile -NonInteractive -EncodedCommand '+Buffer.from(ps,'utf16le').toString('base64')],{stdio:'inherit'});
if(activated.status!==0)throw new Error('Existing build swap could not be enabled. No build started.');
const result=spawnSync(process.execPath,[path.join(path.dirname(self),'builder_exec.mjs'),`LAMBDA_MODULES_ONLY=${targetFiles?0:1} LAMBDA_BUILD_JOBS=${jobs} bash /srv/lambdaos/alpha/scripts/build_target_files.sh /srv/lambdaos/src/lineage-22.2`],{stdio:'inherit'});
process.exit(result.status??1);
