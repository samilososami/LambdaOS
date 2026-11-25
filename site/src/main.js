import Lenis from 'lenis';
import './style.css';
import { setupLanguage, getLanguage } from './i18n.js';

const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
const finePointer = matchMedia('(pointer: fine)').matches;
const tr = (en, es) => (getLanguage() === 'es' ? es : en);
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const root = document.documentElement;

/* ---------- smooth scroll (desktop wheel; touch stays native) ---------- */
const lenis = reduced ? null : new Lenis({ lerp: 0.11 });
if (lenis) {
  const raf = t => { lenis.raf(t); requestAnimationFrame(raf); };
  requestAnimationFrame(raf);
}
const scrollToY = y => (lenis ? lenis.scrollTo(y, { duration: 1.2 }) : scrollTo({ top: y, behavior: reduced ? 'auto' : 'smooth' }));

/* ---------- header menu ---------- */
const hdr = $('#hdr');
const sheet = $('#sheet');
const menuBtn = $('.menu-btn');
const menuLabel = open => (open ? tr('Close', 'Cerrar') : tr('Menu', 'Menú'));
function setMenu(open) {
  menuBtn.setAttribute('aria-expanded', String(open));
  menuBtn.textContent = menuLabel(open);
  sheet.hidden = !open;
  document.body.style.overflow = open ? 'hidden' : '';
  if (lenis) open ? lenis.stop() : lenis.start();
}
menuBtn.addEventListener('click', () => setMenu(menuBtn.getAttribute('aria-expanded') !== 'true'));
addEventListener('keydown', e => { if (e.key === 'Escape' && !sheet.hidden) setMenu(false); });

/* in-page links: close the menu first, then glide */
document.addEventListener('click', e => {
  const a = e.target.closest('a[href^="#"]');
  if (!a) return;
  const id = a.getAttribute('href').slice(1);
  const el = id && document.getElementById(id);
  if (!el) return;
  e.preventDefault();
  if (!sheet.hidden) setMenu(false);
  const inStory = el.classList.contains('anchor') || id === 'top';
  const y = el.getBoundingClientRect().top + scrollY - (inStory ? 0 : hdr.offsetHeight);
  scrollToY(Math.max(0, y));
  history.replaceState(null, '', `#${id}`);
});

/* ---------- story: one phone, many screens ---------- */
const story = $('.story');
const stage = $('.stage');
const device = $('.device');
const steps = $$('.step', stage);
const layers = new Map($$('.layer', stage).map(el => [el.dataset.key, el]));
const boot = layers.get('boot');
const railLinks = $$('.rail a');
const railBar = $('.rail-bar span');
const N = steps.length;
story.style.setProperty('--n', N);

const chapters = {};
steps.forEach((s, i) => {
  const c = s.dataset.chapter;
  chapters[c] ??= { start: i, end: i };
  chapters[c].end = i;
});

let current = -1;
let storyTop = 0;
let stepH = 1;
let bootDone = reduced || !boot;
let stageVisible = true;

function measure() {
  storyTop = story.getBoundingClientRect().top + scrollY;
  stepH = Math.max(1, (story.offsetHeight - stage.offsetHeight) / (N - 1));
  const wide = innerWidth >= 960;
  const room = wide ? Math.min(innerWidth * 0.5, (innerWidth - parseFloat(getComputedStyle(device).left)) * 2 - 60) : innerWidth - 40;
  stage.style.setProperty('--ls', clamp((room * 0.94) / device.offsetHeight, 0.4, 1).toFixed(3));
}

function videoOf(el) {
  return el.tagName === 'VIDEO' ? el : el.querySelector('video');
}

function showScreen(key) {
  if (key === 'welcome' && current === 0 && !bootDone) key = 'boot';
  if (key !== 'boot' && boot && !boot.paused) { boot.pause(); bootDone = true; }
  layers.forEach((el, k) => {
    const on = k === key;
    el.classList.toggle('is-on', on);
    const v = videoOf(el);
    if (!v || el === boot) return;
    if (on && stageVisible) {
      if (!v.getAttribute('src')) v.src = v.dataset.src;
      v.play().catch(() => {});
    } else if (!v.paused) v.pause();
  });
}

function setStep(i) {
  current = i;
  steps.forEach((s, k) => {
    s.classList.toggle('is-active', k === i);
    s.classList.toggle('is-past', k < i);
  });
  const s = steps[i];
  stage.dataset.mode = s.dataset.mode || 'story';
  stage.dataset.chapter = s.dataset.chapter;
  document.body.classList.toggle('is-dark', s.dataset.tone === 'dark');
  railLinks.forEach(a => a.classList.toggle('is-on', a.dataset.chapter === s.dataset.chapter));
  showScreen(s.dataset.screen);
  // warm the next film so it starts without a gap
  const next = steps[i + 1] && layers.get(steps[i + 1].dataset.screen);
  const nv = next && videoOf(next);
  if (nv && next !== boot && !nv.getAttribute('src')) { nv.preload = 'auto'; nv.src = nv.dataset.src; }
}

/* ---------- status ribbon drift ---------- */
const status = $('#status');
const ribbon = $('.ribbon');

let ticking = false;
function update() {
  ticking = false;
  const pos = scrollY - storyTop;
  const raw = pos / stepH;
  const i = clamp(Math.round(raw), 0, N - 1);
  if (i !== current) setStep(i);
  railBar.style.transform = `scaleX(${clamp(pos / (stepH * (N - 1)), 0, 1).toFixed(4)})`;
  const ch = chapters[steps[i].dataset.chapter];
  const span = Math.max(1, ch.end - ch.start + 1);
  stage.style.setProperty('--drift', clamp((raw - ch.start + 0.5) / span - 0.5, -0.8, 0.8).toFixed(3));
  if (ribbon && !reduced) {
    const r = status.getBoundingClientRect();
    if (r.top < innerHeight && r.bottom > 0) {
      const p = (innerHeight - r.top) / (innerHeight + r.height);
      ribbon.style.setProperty('--rx', `${((0.5 - p) * 22).toFixed(2)}vw`);
    }
  }
}
const onScroll = () => { if (!ticking) { ticking = true; requestAnimationFrame(update); } };
addEventListener('scroll', onScroll, { passive: true });

let resizeTimer;
addEventListener('resize', () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => { measure(); update(); }, 120);
});

/* keyboard: focusing a step's button brings that step into view */
steps.forEach((s, k) => s.addEventListener('focusin', () => {
  if (k !== current) scrollToY(storyTop + k * stepH);
}));

/* pause every film when the stage is off screen */
new IntersectionObserver(([entry]) => {
  stageVisible = entry.isIntersecting;
  if (current >= 0) showScreen(steps[current].dataset.screen);
}).observe(stage);

/* pointer: the phone leans toward the cursor, the paper drifts away */
if (finePointer && !reduced) {
  let tx = 0, ty = 0, cx = 0, cy = 0;
  addEventListener('pointermove', e => {
    tx = (e.clientX / innerWidth) * 2 - 1;
    ty = (e.clientY / innerHeight) * 2 - 1;
  }, { passive: true });
  const loop = () => {
    const dx = tx - cx, dy = ty - cy;
    if (Math.abs(dx) + Math.abs(dy) > 0.0015) {
      cx += dx * 0.06;
      cy += dy * 0.06;
      stage.style.setProperty('--mx', cx.toFixed(3));
      stage.style.setProperty('--my', cy.toFixed(3));
    }
    requestAnimationFrame(loop);
  };
  requestAnimationFrame(loop);
}

/* ---------- first paint: the phone boots, the headline rises ---------- */
function start() {
  if (root.classList.contains('is-loaded')) return;
  root.classList.add('is-loaded');
  if (!bootDone && current === 0) {
    boot.addEventListener('ended', () => {
      bootDone = true;
      if (current === 0) showScreen('welcome');
    }, { once: true });
    boot.play().catch(() => { bootDone = true; if (current === 0) showScreen('welcome'); });
  }
}

/* ---------- films ---------- */
const player = $('#player');
const playerVideo = $('#player-video');
const playerTitle = $('#player-title');
const films = {
  explainer: ['LambdaOS in a minute', 'LambdaOS en un minuto'],
  operator: ['Operator, explained', 'Operator, explicado'],
  lex: ['LEX, explained', 'LEX, explicado'],
  lamb: ['Lamb, explained', 'Lamb, explicado'],
};
let openFilm = null;
document.addEventListener('click', e => {
  const b = e.target.closest('[data-film]');
  if (!b || !films[b.dataset.film]) return;
  openFilm = b.dataset.film;
  playerTitle.textContent = tr(...films[openFilm]);
  playerVideo.poster = `/media/${openFilm}-poster.jpg`;
  playerVideo.src = `/media/${openFilm}.mp4`;
  player.showModal();
  lenis?.stop();
  playerVideo.play().catch(() => {});
});
$('.player-close').addEventListener('click', () => player.close());
player.addEventListener('click', e => { if (e.target === player) player.close(); });
player.addEventListener('close', () => {
  openFilm = null;
  playerVideo.pause();
  playerVideo.removeAttribute('src');
  playerVideo.load();
  lenis?.start();
});

/* ---------- support: copy addresses ---------- */
$$('.support-row .cp').forEach(btn => btn.addEventListener('click', async () => {
  const value = btn.closest('.support-row').querySelector('.pv').textContent.trim();
  try {
    await navigator.clipboard.writeText(value);
  } catch {
    const area = Object.assign(document.createElement('textarea'), { value });
    document.body.append(area);
    area.select();
    document.execCommand('copy');
    area.remove();
  }
  btn.textContent = tr('Copied', 'Copiado');
  btn.classList.add('is-done');
  setTimeout(() => { btn.textContent = tr('Copy', 'Copiar'); btn.classList.remove('is-done'); }, 1600);
}));

/* ---------- boot ---------- */
setupLanguage(() => {
  menuBtn.textContent = menuLabel(menuBtn.getAttribute('aria-expanded') === 'true');
  if (openFilm) playerTitle.textContent = tr(...films[openFilm]);
});
measure();
update();
// Start as soon as the type is ready, but never keep the headline waiting.
const ready = () => requestAnimationFrame(() => requestAnimationFrame(start));
(document.fonts?.ready ?? Promise.resolve()).then(ready);
setTimeout(start, 500);
addEventListener('load', () => { measure(); update(); });
