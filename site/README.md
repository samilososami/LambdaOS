# LambdaOS website

Public site: https://lambdaos.samilososami.com

Editorial, bilingual (EN/ES) product website for LambdaOS. Vanilla HTML, CSS
and JavaScript, built with Vite. Black-and-white visual system, real device
captures, folded-paper art and the project films.

## Run locally

Requires Node.js 20.19+ or 22.12+ and npm.

```sh
cd site
npm ci
npm run dev -- --port 8882 --strictPort
```

Open <http://127.0.0.1:8882>. The default dev port in `package.json` is 4318;
pass `--port 8882` for the review build.

```sh
npm run check   # syntax check for the three entry scripts
npm run build   # production build in dist/
npm run preview
```

The project runs as `kali` or root; ordinary development should stay under
`kali` to preserve file ownership.

## Structure

- `index.html`: semantic content, metadata, JSON-LD (WebSite, Organization,
  SoftwareApplication, VideoObject, FAQPage).
- `src/style.css`: monochrome design system, responsive layout, motion.
- `src/main.js`: header theme switching, reveals, gallery tabs, wallpaper
  compare, video and image dialogs, marquee.
- `src/i18n.js` + `src/es.js`: English in the HTML, Spanish dictionary keyed by
  `data-i18n`.
- `public/assets/`: real captures (`setup-welcome`, `lockscreen`,
  `quick-settings`, `settings`, `operator`, `lamb`, wallpapers), the LEX
  desktop captures and the transparent wordmark.
- `public/assets/gen/`: folded-paper art generated for the site (hero,
  operator, lex, development). The origin band reuses `fold-art.webp`.
- `public/media/`: web-optimised trailer and promo (720×1280 H.264) and their
  posters, derived from the `claude-creations` archive.
- `public/social-preview.png`: 1200×630 share card.
- `public/sitemap.xml`, `public/robots.txt`: crawl metadata.

## Notes

- The site is English-first; Spanish is client-side (`EN / ES`), stored in
  `localStorage`, and also updates the title, description and Open Graph tags.
- Videos require a click to play; nothing autoplays with sound.
- Images are lazy-loaded except the hero, which is preloaded.
- `prefers-reduced-motion` disables reveals, the marquee and the parallax.
- `design/DESIGN.md` describes the previous visual direction; the current
  contract lives in `index.html` and `src/style.css`.

## Support

LambdaOS is developed by one person, alongside my studies. Supporting the
project is completely optional: nothing on this site is paid, and nothing
changes if you don't. Every contribution goes towards hardware, time and
motivation to keep going.

- **Bitcoin (BTC):** `bc1qa8r8ll0m0e58f3ngrauh08nnzdn0alm825nc3r`
- **Litecoin (LTC):** `ltc1qtpyddqcq6vscm0gyajkgp3y2r7msdnfythzgua`
- **PayPal:** [@samilososami](https://paypal.me/samilososami)
- **Revolut:** [@samilososami](https://revolut.me/samilososami)

Only send what you can spare. Crypto transfers are final; double-check the
address before sending.
