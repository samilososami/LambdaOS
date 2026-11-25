#!/usr/bin/env node
// Mechanical propagation of the approved shared design into native packages.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const self=fileURLToPath(import.meta.url),alpha=path.resolve(path.dirname(self),'..');
if(process.getuid?.()===0){const r=spawnSync('runuser',['-u','kali','--',process.execPath,self],{stdio:'inherit'});process.exit(r.status??1);}
const base=fs.readFileSync(path.join(alpha,'apps/SetupWizard/src/org/lineageos/setupwizard/LambdaBackground.java'),'utf8');
const helper=fs.readFileSync(path.join(alpha,'platform/common/LambdaNativeUi.java.in'),'utf8');
for(const [tree,pkg,src,res] of [
 ['Settings','com.android.settings','src','res'],
 ['Telephony','com.android.phone','src','res'],
 ['Seedvault','com.stevesoltys.seedvault','app/src/main/java','app/src/main/res']
]){
 const root=path.join(alpha,'platform',tree),java=path.join(root,src,pkg.replaceAll('.','/')),resources=path.join(root,res);
 fs.mkdirSync(java,{recursive:true});fs.mkdirSync(path.join(resources,'font'),{recursive:true});fs.mkdirSync(path.join(resources,'values'),{recursive:true});
 fs.writeFileSync(path.join(java,'LambdaNativeUi.java'),helper.replaceAll('@PACKAGE@',pkg));
 fs.writeFileSync(path.join(java,'LambdaSetupBackground.java'),base.replace('package org.lineageos.setupwizard;',`package ${pkg};`).replaceAll('LambdaBackground','LambdaSetupBackground').replace('public final class LambdaSetupBackground',`${tree==='Settings'?'@androidx.annotation.Keep\n':''}public final class LambdaSetupBackground`));
 for(const font of ['lambda_regular.otf','lambda_bold.otf'])fs.copyFileSync(path.join(alpha,'apps/SetupWizard/res/font',font),path.join(resources,'font',font));
 fs.copyFileSync(path.join(alpha,'platform/common/lambda_native.xml'),path.join(resources,'values/lambda_native.xml'));
 fs.mkdirSync(path.join(resources,'raw'),{recursive:true});
 fs.copyFileSync(path.join(alpha,'apps/SetupWizard/res/raw/lambda_font_license.txt'),path.join(resources,'raw/lambda_font_license.txt'));
}
console.log('Shared native visuals staged; no device changes.');
