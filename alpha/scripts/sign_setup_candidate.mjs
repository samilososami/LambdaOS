#!/usr/bin/env node
// Sign an allowlisted, separately built Lambda system APK with its platform identity.
// Does not install, flash, provision, change credentials, or sign a ROM.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync,execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const self=fileURLToPath(import.meta.url);
if(process.getuid?.()===0){const r=spawnSync('runuser',['-u','kali','--',process.execPath,self,...process.argv.slice(2)],{stdio:'inherit'});process.exit(r.status??1);}
const alpha=path.resolve(path.dirname(self),'..');
if(process.argv.length<4||process.argv.length>5)throw new Error('Usage: node sign_setup_candidate.mjs input.apk output.apk [expected.package]');
const expectedPackage=process.argv[4]||'org.lineageos.setupwizard';
const overlayTargets={
  'org.lambdaproject.overlay.accessibility.framework':'android',
  'org.lambdaproject.overlay.accessibility.systemui':'com.android.systemui'
};
if(!['org.lineageos.setupwizard','com.android.settings','com.android.phone','com.stevesoltys.seedvault','com.android.systemui',...Object.keys(overlayTargets)].includes(expectedPackage))throw new Error('Package not allowlisted for this reviewed native UI update.');
const [input,output]=process.argv.slice(2,4).map(p=>path.resolve(p));
if(!fs.statSync(input).isFile()||fs.existsSync(output))throw new Error('Input must exist and output must be new.');
// xmltree reads the manifest without resolving private framework icon resources
// (badging fails for legitimate platform APKs that reference those resources).
const manifestTree=execFileSync('/tools/codex/android-sdk/build-tools/35.0.0/aapt',['dump','xmltree',input,'AndroidManifest.xml'],{encoding:'utf8',maxBuffer:4*1024*1024});
const pkg=manifestTree.match(/^ +A: package="([^"]+)"/m)?.[1];
if(pkg!==expectedPackage)throw new Error('Input package differs from the explicitly requested native component.');
if(overlayTargets[pkg]){
  const target=manifestTree.match(/A: android:targetPackage\(0x01010021\)="([^"]+)"/)?.[1];
  if(target!==overlayTargets[pkg]||!manifestTree.includes('A: android:hasCode(0x0101000c)=(type 0x12)0x0')||/E: (uses-permission|activity|service|receiver|provider)\b/.test(manifestTree))throw Error('Overlay must be code-free, permission-free and target only its approved package.');
  const entries=execFileSync('/usr/bin/unzip',['-Z','-1',input],{encoding:'utf8'}).trim().split('\n');
  if(entries.some(name=>! /^(AndroidManifest\.xml|resources\.arsc|res\/[^\n]+|META-INF\/[^\n]+)$/.test(name)))throw Error('Unexpected executable/data entry in resource-only overlay.');
}
const publicDir=path.join(alpha,'config/signing/lambdaos-release-v1');
const privateDir=path.join(alpha,'private/signing/lambdaos-release-v1');
const manifest=JSON.parse(fs.readFileSync(path.join(publicDir,'manifest.json'),'utf8'));
const expected=manifest.keys.find(k=>k.name==='platform').sha256.replaceAll(':','').toLowerCase();
const certificate=path.join(publicDir,'platform.x509.pem');
const actual=new crypto.X509Certificate(fs.readFileSync(certificate)).fingerprint256.replaceAll(':','').toLowerCase();
if(actual!==expected)throw new Error('Platform certificate differs from the pinned identity.');
const apksigner='/tools/codex/android-sdk/build-tools/35.0.0/apksigner';
const work=fs.mkdtempSync('/dev/shm/lambda-setup-sign-');fs.chmodSync(work,0o700);
const keyPath=path.join(work,'platform.pk8');
let bytes;
try{
  const key=crypto.createPrivateKey({key:fs.readFileSync(path.join(privateDir,'platform.pk8')),format:'der',type:'pkcs8',passphrase:fs.readFileSync(path.join(privateDir,'platform.pass'),'utf8')});
  bytes=key.export({format:'der',type:'pkcs8'});
  fs.writeFileSync(keyPath,bytes,{mode:0o600,flag:'wx'});bytes.fill(0);
  execFileSync(apksigner,['sign','--key',keyPath,'--cert',certificate,'--out',output,input],{stdio:'pipe'});
  const verification=execFileSync(apksigner,['verify','--print-certs',output],{encoding:'utf8',stdio:['ignore','pipe','pipe']});
  const fingerprint=verification.match(/Signer #1 certificate SHA-256 digest: ([a-f0-9]+)/)?.[1];
  if(fingerprint!==expected)throw new Error('Candidate signature does not match the installed platform identity.');
  console.log(pkg);console.log('Platform SHA-256: '+expected);
  console.log('APK SHA-256: '+crypto.createHash('sha256').update(fs.readFileSync(output)).digest('hex'));
  console.log('Signed candidate only; not installed: '+output);
} finally {
  bytes?.fill(0);
  // Only this exact, newly created tmpfs key and its empty directory are removed.
  if(fs.existsSync(keyPath))fs.unlinkSync(keyPath);
  fs.rmdirSync(work);
}
