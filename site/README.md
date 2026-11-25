# LambdaOS website

Public site: https://lambdaos.samilososami.com

Bilingual (EN/ES) product site for LambdaOS. Vanilla HTML, CSS and JavaScript
built with Vite, plus [Lenis](https://github.com/darkroomengineering/lenis) for
smooth wheel scrolling. Vercel builds it from this `site/` folder.

## Run locally

Requires Node.js 20.19+ or 22.12+ and npm.

```sh
cd site
npm ci
npm run dev -- --port 8882 --strictPort
```

Open <http://127.0.0.1:8882>. The default dev port in `package.json` is 4318.

```sh
npm run check   # syntax check for the entry scripts
npm run build   # production build in dist/
npm run preview
```

Run it as `kali`; ordinary development should not create root-owned files.

## How the page works

- **One phone carries the story.** `.story` is a tall scroll track with a sticky
  `.stage`. Each `<article class="step">` is one stop: its `data-screen` picks
  the layer shown on the phone, `data-chapter` picks the paper art and rail
  entry, `data-mode="land"` rotates the phone for LEX and `data-tone="dark"`
  turns the page black for Operator. `src/main.js` maps the scroll position to
  a step; everything else is CSS transitions.
- **Films play inside the phone.** Film steps load a muted 8 s loop
  (`media/*-loop.mp4`) into the phone screen; "Play with sound" opens the full
  720p film in a dialog. The intro plays the boot animation once.
- **Paper art sits on the page.** Sculptures are exported on pure white and
  blended with `mix-blend-mode: multiply`, so only the paper and its shadows
  show on the `#F3F4F2` background.
- **Keyboard and reduced motion.** Focusing a step's button scrolls the story to
  it. `prefers-reduced-motion` turns off smooth scrolling and movement and keeps
  plain cross-fades. Without JavaScript the story renders as a stacked article.

## Files

- `index.html`: content, metadata and JSON-LD (WebSite, Organization,
  SoftwareApplication, four VideoObjects, FAQPage).
- `src/style.css`, `src/main.js`, `src/i18n.js`, `src/es.js`: styles,
  behaviour, language switch and the Spanish dictionary keyed by `data-i18n`.
- `public/assets/screens/`: real S10 screenshots. The Operator captures come
  from the developer-build QA runs on the author's lab network; the router's
  BSSID in `op-confirm.webp` is masked.
- `public/assets/art/`: paper sculptures generated with gpt-image-2 through the
  Codex CLI (prompts in `design/ART-PROMPTS.md`), normalised to a white ground.
  `flight.webp` comes from the previous site's LEX art.
- `public/assets/lex-*.webp`: LEX desktop captures from the S10.
- `public/media/`: the explainer, Operator, LEX and Lamb films, their loops
  and posters, the boot animation and the Lamb states loop, transcoded from the
  `claude-creations` LambdaOS project (branch `ccr-345ecc84-h76vum`).
- `public/fonts/`: Nimbus Sans, the typeface the OS itself uses. See
  `docs/FONT-LICENSE.txt`.
- `public/social-preview.png`: 1200 × 630 share card.

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
