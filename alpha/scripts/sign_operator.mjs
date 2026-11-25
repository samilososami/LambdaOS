// Preserve Lambda's existing releasekey identity; no platform/root privilege escalation.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync,spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const self=fileURLToPath(import.meta.url);
if(process.getuid?.()===0){const r=spawnSync('runuser',['-u','kali','--',process.execPath,self,...process.argv.slice(2)],{stdio:'inherit'});process.exit(r.status??1);}
const alpha=path.resolve(path.dirname(self),'..');
const [input,output]=process.argv.slice(2).map(p=>path.resolve(p));
if(!input||!output||input===output)throw Error('Usage: sign_operator.mjs input.apk output.apk');
const sdk='/tools/codex/android-sdk/build-tools/35.0.0';
const tree=execFileSync(`${sdk}/aapt`,['dump','xmltree',input,'AndroidManifest.xml'],{encoding:'utf8'});
if(!tree.includes('A: package="org.lambdaproject.lambda"')&&!tree.includes('A: package="org.lambdaproject.lambda.wpsuitest"'))throw Error('Not Operator or its isolated WPS instrumentation package');
const pub=path.join(alpha,'config/signing/lambdaos-release-v1');
const priv=path.join(alpha,'private/signing/lambdaos-release-v1');
const cert=path.join(pub,'releasekey.x509.pem');
const expected=JSON.parse(fs.readFileSync(path.join(pub,'manifest.json'))).keys.find(k=>k.name==='releasekey').sha256.replaceAll(':','').toLowerCase();
if(new crypto.X509Certificate(fs.readFileSync(cert)).fingerprint256.replaceAll(':','').toLowerCase()!==expected)throw Error('Certificate mismatch');
const dir=fs.mkdtempSync('/dev/shm/lambda-operator-sign-');fs.chmodSync(dir,0o700);const keyPath=path.join(dir,'releasekey.pk8');let bytes;
try{
 const key=crypto.createPrivateKey({key:fs.readFileSync(path.join(priv,'releasekey.pk8')),format:'der',type:'pkcs8',passphrase:fs.readFileSync(path.join(priv,'releasekey.pass'),'utf8')});
 bytes=key.export({format:'der',type:'pkcs8'});fs.writeFileSync(keyPath,bytes,{mode:0o600,flag:'wx'});bytes.fill(0);
 fs.mkdirSync(path.dirname(output),{recursive:true});
 execFileSync(`${sdk}/apksigner`,['sign','--key',keyPath,'--cert',cert,'--out',output,input],{stdio:'pipe'});
 const verified=execFileSync(`${sdk}/apksigner`,['verify','--print-certs',output],{encoding:'utf8'});
 if(!verified.includes(expected))throw Error('Signature mismatch');
 console.log(JSON.stringify({apk:output,certificate_sha256:expected,sha256:crypto.createHash('sha256').update(fs.readFileSync(output)).digest('hex')}));
}finally{bytes?.fill(0);if(fs.existsSync(keyPath))fs.unlinkSync(keyPath);fs.rmdirSync(dir);}
