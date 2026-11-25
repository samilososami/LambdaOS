import sharp from 'sharp';
import { mkdir, copyFile } from 'node:fs/promises';
const root = '/tools/codex/workspace/LambdaOS-public/assets';
await mkdir('public/assets', {recursive:true});
const files = [
 ['setup-welcome', `${root}/screenshots/setup-welcome.png`, 900],
 ['lockscreen', `${root}/screenshots/lockscreen.png`, 900],
 ['quick-settings', `${root}/screenshots/quick-settings.png`, 900],
 ['settings', `${root}/screenshots/settings.png`, 900],
 ['wallpaper-dark', `${root}/wallpapers/lambda-dark-s10.png`, 864],
 ['wallpaper-light', `${root}/wallpapers/lambda-light-s10.png`, 865],
 ['wordmark', `${root}/brand/lambdaos-banner.png`, 1280],
 ['lamb', `${root}/design/round-06/lamb/13-selected-no-mark.png`, 420],
 ['operator', '/tools/codex/workspace/LambdaOS/alpha/out/lambda-alpha-live.png', 900]
];
for (const [name, source, width] of files) {
 await sharp(source).resize({width,withoutEnlargement:true}).webp({quality:88}).toFile(`public/assets/${name}.webp`);
}
await copyFile(`${root}/brand/lambdaos-social-preview.png`, 'public/social-preview.png');
console.log(`Prepared ${files.length} assets and social preview.`);
