# LambdaOS website

Public site: https://lambdaos.samilososami.com

Bilingual (EN/ES) product website for LambdaOS, built by Vercel from this `site/` folder. Vanilla HTML/CSS/JavaScript, built with Vite. Real system captures and original branding are reused from the LambdaOS repository; the hero and folded-paper visual are generated editorial artwork.

## Run locally

Requires Node.js 20.19+ or 22.12+ and npm.

```sh
cd site
npm ci
npm run dev
```

Development URL: http://127.0.0.1:4318

```sh
npm run check
npm run build
npm run preview
```

The project has no dependency on a user home directory. It runs as `kali` or root; ordinary development should remain under `kali` to preserve file ownership. Root can run the existing build directly with:

```sh
sudo node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port 4319 --strictPort
```

## Content and assets

- `index.html`: content, semantic structure and metadata.
- `src/style.css`: responsive monochrome design.
- `src/main.js`: mobile menu, language switch and support copy buttons.
- `public/assets/`: optimized delivery images.
- `scripts/prepare-assets.mjs`: regenerate assets using the original local LambdaOS files.
- `design/DESIGN.md`: visual direction and intentional differences from the generated concepts.
- `docs/FONT-LICENSE.txt`: bundled Inter font license.

Current development capabilities and future plans are distinguished explicitly. The site does not claim a public ROM download is available or that every peripheral has been validated.

## Deployment

Vercel project `lambdaos`, scope `samilososamis-projects`. The public site is static: no backend, analytics, external font requests or cookies.

```sh
vercel deploy --prod --yes --scope samilososamis-projects
```

Cloudflare DNS: `lambdaos` is a DNS-only CNAME to `61461b3d64789e75.vercel-dns-017.com`. Vercel manages HTTPS. Other domain records were not changed.

Do not commit or share `.env*`, `.vercel/`, credentials, signing keys, device backups or private build data. The website deploy excludes local design concepts, QA artifacts and source asset-preparation scripts.

## Support

LambdaOS is developed by one person, alongside my studies. Supporting the
project is completely optional: nothing on this site is paid, and nothing
changes if you don't.

- **Bitcoin (BTC):** `bc1qa8r8ll0m0e58f3ngrauh08nnzdn0alm825nc3r`
- **Litecoin (LTC):** `ltc1qtpyddqcq6vscm0gyajkgp3y2r7msdnfythzgua`
- **PayPal:** [@samilososami](https://paypal.me/samilososami)
- **Revolut:** [@samilososami](https://revolut.me/samilososami)
