#!/usr/bin/env node
// Deterministic conversion of the approved vector/font/mascot assets, not new artwork.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
const here=path.dirname(fileURLToPath(import.meta.url));
if(process.getuid?.()===0){execFileSync('runuser',['-u','kali','--',process.execPath,fileURLToPath(import.meta.url)],{stdio:'inherit'});process.exit(0);}
const alpha=path.resolve(here,'..'),project=path.resolve(alpha,'..');
const design=path.join(project,'design/round-07-setup');
const res=path.join(alpha,'apps/SetupWizard/res');
for(const d of ['font','drawable','drawable-nodpi','raw'])fs.mkdirSync(path.join(res,d),{recursive:true});
for(const [src,dest] of [
 ['assets/NimbusSans-Regular.otf','font/lambda_regular.otf'],
 ['assets/NimbusSans-Bold.otf','font/lambda_bold.otf'],
 ['assets/face-success-v2.png','drawable-nodpi/lamb_finished.png'],
 ['assets/NimbusSans-LICENSE.txt','raw/lambda_font_license.txt'],
 ['assets/lucide/LICENSE','raw/lambda_icons_license.txt']
])fs.copyFileSync(path.join(design,src),path.join(res,dest));
fs.copyFileSync(path.join(project,'design/round-06/lamb/13-selected-no-mark.png'),path.join(res,'drawable-nodpi/lamb_welcome.png'));
// Reuse the approved wordmark verbatim, cropped at draw time using its pinned
// component coordinates. Do not redraw the lambda or substitute a typeface.
fs.copyFileSync(path.join(project,'design/round-04/bootscreen/bootscreen.png'),path.join(res,'drawable-nodpi/lambda_brand_source.png'));
// The app and its setup previews must use the very same category glyphs.
const glyph=fs.readFileSync(path.join(alpha,'apps/Lambda/src/org/lambdaproject/lambda/Glyph.java'),'utf8');
fs.writeFileSync(path.join(alpha,'apps/SetupWizard/src/org/lineageos/setupwizard/LambdaAppGlyph.java'),
 glyph.replace('package org.lambdaproject.lambda;','package org.lineageos.setupwizard;')
      .replaceAll('class Glyph','class LambdaAppGlyph').replaceAll('Glyph(Context','LambdaAppGlyph(Context'));
fs.copyFileSync(path.join(design,'screens/80-wallpaper.png'),path.join(res,'drawable-nodpi/lambda_wallpaper.png'));
const attr=s=>Object.fromEntries([...s.matchAll(/([\w-]+)="([^"]*)"/g)].map(m=>[m[1],m[2]]));
for(const f of fs.readdirSync(path.join(design,'assets/lucide')).filter(f=>f.endsWith('.svg'))){
 const svg=fs.readFileSync(path.join(design,'assets/lucide',f),'utf8');
 const paths=[];
 for(const m of svg.matchAll(/<(path|circle|rect|line|polyline|polygon)\s+([^>]+)\/?\s*>/g)){
  const a=attr(m[2]);let d;
  switch(m[1]){
   case 'path': d=a.d;break;
   case 'circle':{const x=+a.cx,y=+a.cy,r=+a.r;d=`M${x-r},${y}a${r},${r} 0 1,0 ${r*2},0a${r},${r} 0 1,0 ${-r*2},0`;break;}
   case 'line':d=`M${a.x1},${a.y1}L${a.x2},${a.y2}`;break;
   case 'polyline':case 'polygon':d='M'+a.points.trim().replace(/\s+/g,' L')+(m[1]==='polygon'?'Z':'');break;
   case 'rect':{const x=+a.x,y=+a.y,w=+a.width,h=+a.height,r=+(a.rx||0);d=r?`M${x+r},${y}H${x+w-r}Q${x+w},${y} ${x+w},${y+r}V${y+h-r}Q${x+w},${y+h} ${x+w-r},${y+h}H${x+r}Q${x},${y+h} ${x},${y+h-r}V${y+r}Q${x},${y} ${x+r},${y}Z`:`M${x},${y}h${w}v${h}h${-w}Z`;break;}
  }
  if(!d || /["<>]/.test(d))throw new Error('Invalid SVG '+f);
  paths.push(`<path android:pathData="${d}" android:fillColor="@android:color/transparent" android:strokeColor="#f4f4f4" android:strokeWidth="1.5" android:strokeLineCap="round" android:strokeLineJoin="round"/>`);
 }
 if(!paths.length)throw new Error('No vector paths '+f);
 fs.writeFileSync(path.join(res,'drawable','lambda_'+f.replace('.svg','.xml').replaceAll('-','_')),`<!-- Lucide, ISC; see res/raw/lambda_icons_license.txt. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n${paths.join('\n')}\n</vector>\n`);
}
console.log('Approved Lambda setup assets staged; no phone changes.');
// Share the exact native background and font with Android's secure setup dialogs.
const settings=path.join(alpha,'platform/Settings');
fs.mkdirSync(path.join(settings,'res/font'),{recursive:true});
fs.mkdirSync(path.join(settings,'src/com/android/settings'),{recursive:true});
fs.copyFileSync(path.join(res,'font/lambda_regular.otf'),path.join(settings,'res/font/lambda_regular.otf'));
const background=fs.readFileSync(path.join(alpha,'apps/SetupWizard/src/org/lineageos/setupwizard/LambdaBackground.java'),'utf8');
fs.writeFileSync(path.join(settings,'src/com/android/settings/LambdaSetupBackground.java'),background.replace('package org.lineageos.setupwizard;','package com.android.settings;').replace('public final class LambdaBackground','@androidx.annotation.Keep\npublic final class LambdaSetupBackground'));
execFileSync(process.execPath,[path.join(here,'prepare_native_assets.mjs')],{stdio:'inherit'});
