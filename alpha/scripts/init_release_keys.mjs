#!/usr/bin/env node
// One-time identity provisioning. Never silently rotates existing release keys.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const here = path.dirname(fileURLToPath(import.meta.url));
if (process.getuid?.() === 0) {
  execFileSync('runuser', ['-u', 'kali', '--', process.execPath, fileURLToPath(import.meta.url), ...process.argv.slice(2)], {stdio:'inherit'});
  process.exit(0);
}
process.umask(0o077);
const alpha = path.resolve(here, '..');
const inventoryPath = path.join(alpha, 'config/signing/key-plan.json');
const plan = JSON.parse(fs.readFileSync(inventoryPath, 'utf8'));
const secretDir = path.join(alpha, 'private/signing', plan.identity);
const publicDir = path.join(alpha, 'config/signing', plan.identity);
const verifyOnly = process.argv.includes('--verify');
const init = process.argv.includes('--initialize');
const resume = process.argv.includes('--resume');
const extend = process.argv.includes('--extend');
const creating = init || resume || extend;
if (!creating && !verifyOnly) throw new Error('Use --initialize once, --resume after interruption, or --verify.');
if ([init,resume,extend,verifyOnly].filter(Boolean).length !== 1) throw new Error('Choose exactly one operation.');
if (init && fs.existsSync(secretDir)) throw new Error('Identity already exists. Refusing to rotate or overwrite it. Use --verify.');
if (resume && (!fs.existsSync(secretDir) || fs.existsSync(path.join(publicDir,'manifest.json')))) throw new Error('Resume is only valid for an incomplete identity.');
if (init) {
  fs.mkdirSync(secretDir, {recursive:true, mode:0o700});
  fs.mkdirSync(publicDir, {recursive:true, mode:0o755});
  fs.chmodSync(publicDir, 0o755);
}
const openssl = (args,input) => execFileSync('openssl', args, {stdio:['pipe','pipe','pipe'], input, maxBuffer:1024*1024});
const entries = [];
const pinned = (verifyOnly||extend) ? JSON.parse(fs.readFileSync(path.join(publicDir,'manifest.json'),'utf8')) : null;
if (pinned && (pinned.identity !== plan.identity || (!extend&&pinned.keys.length !== plan.keys.length))) throw new Error('Pinned identity does not match the key plan.');
if(extend&&pinned.keys.some(k=>!plan.keys.includes(k.name)))throw new Error('Extension cannot remove or rename a pinned identity.');
if ((fs.statSync(secretDir).mode & 0o077) !== 0) throw new Error('Private directory permissions too broad.');
for (const name of plan.keys) {
  if (!/^[a-zA-Z0-9_.-]+$/.test(name)) throw new Error('Invalid key identifier');
  const base = path.join(secretDir, name);
  const passwordFile = base + '.pass';
  const certFile = path.join(publicDir, name + '.x509.pem');
  if (creating && !fs.existsSync(base+'.encrypted.pem')) {
    const pass = crypto.randomBytes(48).toString('base64url');
    fs.writeFileSync(passwordFile, pass, {mode:0o600, flag:'wx'});
    const pair = crypto.generateKeyPairSync('rsa', {
      modulusLength:4096, publicExponent:0x10001,
      publicKeyEncoding:{type:'spki',format:'pem'},
      privateKeyEncoding:{type:'pkcs8',format:'pem',cipher:'aes-256-cbc',passphrase:pass}
    });
    // Master stays encrypted. Canonical Android pk8 also stays encrypted at rest.
    fs.writeFileSync(base + '.encrypted.pem', pair.privateKey, {mode:0o600,flag:'wx'});
  }
  if (creating && (!fs.existsSync(base+'.pk8') || fs.statSync(base+'.pk8').size===0)) {
    openssl(['pkcs8','-topk8','-in',base+'.encrypted.pem','-passin','file:'+passwordFile,
      '-outform','DER','-out',base+'.pk8','-v1','PBE-SHA1-3DES','-passout','stdin'], fs.readFileSync(passwordFile,'utf8')+'\n');
    fs.chmodSync(base+'.pk8',0o600);
  }
  if (creating && !fs.existsSync(certFile)) {
    openssl(['req','-new','-x509','-sha256','-days','10000','-key',base+'.encrypted.pem',
      '-passin','file:'+passwordFile,'-out',certFile,
      '-subj','/O=Lambda Project/OU=LambdaOS/CN=LambdaOS '+name]);
    fs.chmodSync(certFile,0o644);
  }
  const pass = fs.readFileSync(passwordFile,'utf8');
  const key = crypto.createPrivateKey({key:fs.readFileSync(base+'.pk8'),format:'der',type:'pkcs8',passphrase:pass});
  const cert = new crypto.X509Certificate(fs.readFileSync(certFile));
  const master = crypto.createPrivateKey({key:fs.readFileSync(base+'.encrypted.pem'),format:'pem',passphrase:pass});
  if (!crypto.createPublicKey(master).export({type:'spki',format:'der'}).equals(cert.publicKey.export({type:'spki',format:'der'}))) throw new Error('Encrypted master/certificate mismatch: '+name);
  const existing=pinned?.keys.find(k=>k.name===name);
  if ((verifyOnly||existing) && existing?.sha256 !== cert.fingerprint256) throw new Error('Certificate differs from pinned manifest: '+name);
  const challenge = Buffer.from('LambdaOS release identity verification v1\n'+name);
  const signature = crypto.sign('sha256',challenge,key);
  if (!crypto.verify('sha256',challenge,cert.publicKey,signature)) throw new Error('Key/cert mismatch: '+name);
  if (key.asymmetricKeyDetails.modulusLength !== 4096) throw new Error('Unexpected key size');
  for (const f of [passwordFile,base+'.encrypted.pem',base+'.pk8']) {
    if ((fs.statSync(f).mode & 0o077) !== 0) throw new Error('Private permissions too broad: '+path.basename(f));
  }
  entries.push({name,algorithm:'RSA-4096',certificate:path.basename(certFile),sha256:cert.fingerprint256,
    validFrom:cert.validFrom,validTo:cert.validTo,signVerify:'passed'});
  console.log((creating?'Created and verified: ':'Verified: ')+name);
}
if (new Set(entries.map(e=>e.sha256)).size !== entries.length) throw new Error('Duplicate signing identity');
const manifest = {identity:plan.identity,
  status:'keys-prepared-not-yet-used-for-an-OTA',privateDirectory:'alpha/private/signing/'+plan.identity,
  secretsExportedToBuilder:false,offlineBackupVerified:false,
  encryptedAtRest:true,passwordsOnSameHost:true,
  ...(extend?pinned:{}),createdOrVerifiedAt:new Date().toISOString(),keys:entries};
if (creating) {
  if(extend){
    fs.copyFileSync(path.join(publicDir,'manifest.json'),path.join(publicDir,'manifest-before-extension-'+Date.now()+'.json'),fs.constants.COPYFILE_EXCL);
    manifest.status=pinned.latestVerifiedRelease
      ? 'extended-preserving-all-previous-release-identities'
      : 'extended-preserving-all-previous-identities-not-yet-used-for-an-OTA';
  }
  fs.writeFileSync(path.join(publicDir,'manifest.json'),JSON.stringify(manifest,null,2)+'\n',{flag:extend?'w':'wx',mode:0o644});
}
console.log(`${entries.length} key/certificate pairs verified. No ROM, APK or device changed.`);
