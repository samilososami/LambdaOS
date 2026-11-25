#!/usr/bin/env node
// Send the pinned identity over an authenticated SSH channel into a private
// tmpfs directory. Plaintext keys are never written to the local filesystem.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync,execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const self=fileURLToPath(import.meta.url),alpha=path.resolve(path.dirname(self),'..');
if(process.getuid?.()===0){const r=spawnSync('runuser',['-u','kali','--',process.execPath,self,...process.argv.slice(2)],{stdio:'inherit'});process.exit(r.status??1);}
const socket=process.env.LAMBDA_SSH_SOCKET;
if(!socket)throw new Error('An authenticated LAMBDA_SSH_SOCKET is required.');
execFileSync(process.execPath,[path.join(alpha,'scripts/init_release_keys.mjs'),'--verify'],{stdio:'pipe'});
const plan=JSON.parse(fs.readFileSync(path.join(alpha,'config/signing/key-plan.json')));
const publicDir=path.join(alpha,'config/signing',plan.identity);
const secretDir=path.join(alpha,'private/signing',plan.identity);
const remote='/dev/shm/lambda-release-'+crypto.randomUUID();
const ssh=['-o','BatchMode=yes','-S',socket,'Usuario@192.168.1.147'];
execFileSync('ssh',[...ssh,'wsl.exe -d Ubuntu -u kali -- mkdir -m 700 '+remote]);
const chunks=[];
function entry(name,data){
  const h=Buffer.alloc(512);h.write(name,0,100,'utf8');h.write('0000600\0',100,8);h.write('0000000\0',108,8);h.write('0000000\0',116,8);
  h.write(data.length.toString(8).padStart(11,'0')+'\0',124,12);h.write(Math.floor(Date.now()/1000).toString(8).padStart(11,'0')+'\0',136,12);
  h.fill(32,148,156);h.write('0',156);h.write('ustar\0',257,6);h.write('00',263,2);
  const checksum=h.reduce((a,b)=>a+b,0);h.write(checksum.toString(8).padStart(6,'0')+'\0 ',148,8);
  chunks.push(h,data,Buffer.alloc((512-data.length%512)%512));
}
try{
  for(const name of plan.keys){
    const key=crypto.createPrivateKey({key:fs.readFileSync(path.join(secretDir,name+'.pk8')),format:'der',type:'pkcs8',passphrase:fs.readFileSync(path.join(secretDir,name+'.pass'),'utf8')});
    entry(name+'.pk8',key.export({format:'der',type:'pkcs8'}));
    entry(name+'.pem',Buffer.from(key.export({format:'pem',type:'pkcs8'})));
    entry(name+'.x509.pem',fs.readFileSync(path.join(publicDir,name+'.x509.pem')));
  }
  chunks.push(Buffer.alloc(1024));
  const archive=Buffer.concat(chunks);
  const r=spawnSync('ssh',[...ssh,'wsl.exe -d Ubuntu -u kali -- tar -xf - -C '+remote],{input:archive,encoding:'utf8',maxBuffer:1024*1024});
  archive.fill(0);for(const chunk of chunks)chunk.fill(0);
  if(r.status!==0)throw new Error('Key transfer failed; remote cleanup required at '+remote);
  console.log(remote);
}catch(e){
  // Only this newly-created exact directory can be removed by the failure cleanup.
  spawnSync('ssh',[...ssh,'wsl.exe -d Ubuntu -u kali -- rm -r -- '+remote],{stdio:'pipe'});
  throw e;
}
