#!/usr/bin/env node
// Local, code-free preview of the same resources built into the next ROM.
// The preview changes only isStatic so it can be reversibly enabled in /data.
// Build/sign/install/enable are separate operations. No device commands here.
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync,execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const self=fileURLToPath(import.meta.url);
if(process.getuid?.()===0){const r=spawnSync('runuser',['-u','kali','--',process.execPath,self,...process.argv.slice(2)],{stdio:'inherit'});process.exit(r.status??1);}
const alpha=path.resolve(path.dirname(self),'..');
if(process.argv.length!==3)throw Error('Usage: node build_accessibility_overlays.mjs <new-output-directory>');
const output=path.resolve(process.argv[2]);
if(fs.existsSync(output))throw Error('Output must be a new directory.');
fs.mkdirSync(output,{recursive:true});
const aapt2='/tools/codex/android-sdk/build-tools/35.0.0/aapt2';
const sdk='/tools/codex/android-sdk/platforms/android-35/android.jar';
for(const module of ['LambdaAccessibilityFramework','LambdaAccessibilitySystemUI']){
 const root=path.join(alpha,'vendor/lambda/overlays',module),build=path.join(output,module);
 fs.mkdirSync(build);
 const manifest=fs.readFileSync(path.join(root,'AndroidManifest.xml'),'utf8');
 if((manifest.match(/android:isStatic="true"/g)||[]).length!==1)throw Error('Expected exactly one static ROM overlay');
 if(!manifest.includes('android:hasCode="false"')||/<(?:uses-permission|activity|service|receiver|provider)\b/.test(manifest))throw Error('Expected a resource-only overlay');
 fs.writeFileSync(path.join(build,'AndroidManifest.xml'),manifest.replace('android:isStatic="true"','android:isStatic="false"'));
 const resources=path.join(build,'resources.zip'),apk=path.join(output,module+'-unsigned.apk');
 execFileSync(aapt2,['compile','--dir',path.join(root,'res'),'-o',resources],{stdio:'inherit'});
 // RRO qualifiers must survive even when their values equal the defaults:
 // otherwise the target's night resource wins over our unqualified value.
 execFileSync(aapt2,['link','--manifest',path.join(build,'AndroidManifest.xml'),'-I',sdk,'--min-sdk-version','29','--target-sdk-version','35','--no-resource-deduping','--no-resource-removal','-o',apk,resources],{stdio:'inherit'});
 console.log(apk);
}
