import Lenis from 'lenis';
import 'lenis/dist/lenis.css';
import './style.css';
import { t, setupLanguage } from './i18n.js';
import { startsLite, softwareRendering, frameWatcher } from './perf.js';

const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
const easeOutExpo = x => (x === 1 ? 1 : 1 - 2 ** (-10 * x));

const root = document.documentElement;
const idle = window.requestIdleCallback ? fn => requestIdleCallback(fn, { timeout: 2000 }) : fn => setTimeout(fn, 200);

/* Slow devices get the lite profile from the start; others can still fall back to it later. */
let lite = reduceMotion || startsLite();
if (lite) root.classList.add('lite', 'lite-boot');

/* Smooth wheel scrolling, only where there is a wheel: touch screens keep their native scroll. */
const finePointer = matchMedia('(hover: hover) and (pointer: fine)').matches;
let lenis = lite || !finePointer ? null : new Lenis({ autoRaf: true, lerp: 0.085 });
function goLite() {
  if (lite) return;
  lite = true;
  root.classList.add('lite');
  lenis?.destroy();
  lenis = null;
}
const menuButton = document.querySelector('.menu-toggle');
const mobileMenu = document.querySelector('#mobile-menu');

/* The looping carousel needs its clones in the DOM before the language switch indexes the text. */
const track = document.querySelector('.carousel-track');
const originals = track ? [...track.children] : [];
if (track) {
  const clone = slide => {
    const copy = slide.cloneNode(true);
    copy.setAttribute('aria-hidden', 'true');
    copy.classList.add('is-clone');
    return copy;
  };
  track.prepend(...originals.map(clone));
  track.append(...originals.map(clone));
}

let closeTimer;
function setMenu(open) {
  clearTimeout(closeTimer);
  // Lock the page on <html>: overflow on <body> would make it a scroll container and unstick the header.
  document.documentElement.style.overflow = open ? 'hidden' : '';
  if (lenis) (open ? lenis.stop() : lenis.start());
  menuButton.setAttribute('aria-expanded', String(open));
  menuButton.setAttribute('aria-label', t(open ? 'Cerrar menú' : 'Abrir menú'));
  if (open) {
    mobileMenu.classList.remove('is-closing');
    mobileMenu.hidden = false;
    return;
  }
  if (mobileMenu.hidden) return;
  if (reduceMotion) { mobileMenu.hidden = true; return; }
  mobileMenu.classList.add('is-closing');
  closeTimer = setTimeout(() => {
    mobileMenu.hidden = true;
    mobileMenu.classList.remove('is-closing');
  }, 760);
}
menuButton.addEventListener('click', () => setMenu(menuButton.getAttribute('aria-expanded') !== 'true'));
document.addEventListener('click', event => {
  const link = event.target.closest('a[href^="#"]');
  if (!link || !lenis) return;
  const id = link.getAttribute('href');
  const target = id === '#top' ? 0 : document.querySelector(id);
  if (target === null) return;
  event.preventDefault();
  setMenu(false);
  // Lenis already honours the page's scroll-padding-top, which clears the sticky header.
  lenis.scrollTo(target, { duration: 1.5, easing: easeOutExpo });
  history.replaceState(null, '', id);
});
mobileMenu.querySelectorAll('a').forEach(link => link.addEventListener('click', () => setMenu(false)));
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && !mobileMenu.hidden) { setMenu(false); menuButton.focus(); }
});
matchMedia('(min-width: 801px)').addEventListener('change', event => { if (event.matches) setMenu(false); });

/* Support: copy an address or username. */
document.querySelectorAll('.copy-button').forEach(button => button.addEventListener('click', async () => {
  const value = button.closest('li').querySelector('code').textContent.trim();
  try {
    await navigator.clipboard.writeText(value);
  } catch {
    const area = Object.assign(document.createElement('textarea'), { value });
    document.body.append(area);
    area.select();
    document.execCommand('copy');
    area.remove();
  }
  button.textContent = t('Copiado');
  setTimeout(() => { button.textContent = t('Copiar'); }, 1600);
}));

setupLanguage(() => setMenu(menuButton.getAttribute('aria-expanded') === 'true'));

/* Motion: reveals on scroll, a header that tightens, a slow hero parallax. */
if (!reduceMotion && 'IntersectionObserver' in window) {
  document.documentElement.classList.add('js-anim');
  document.querySelectorAll('[data-stagger]').forEach(group => {
    [...group.children].forEach((child, i) => child.style.setProperty('--i', i));
  });
  const reveal = new IntersectionObserver(entries => entries.forEach(entry => {
    if (!entry.isIntersecting) return;
    entry.target.classList.add('in-view');
    reveal.unobserve(entry.target);
  }), { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
  document.querySelectorAll('[data-reveal],[data-stagger]').forEach(el => reveal.observe(el));
}

const header = document.querySelector('.site-header');
const heroRibbon = document.querySelector('.hero-ribbon');
const watchFrames = frameWatcher(goLite);
let ticking = false, ribbonY = -1;
function onScroll(now) {
  ticking = false;
  if (!lite && now) watchFrames(now);
  // Two thresholds, so the header never flickers between sizes around one scroll position.
  header.classList.toggle('is-scrolled', header.classList.contains('is-scrolled') ? scrollY > 8 : scrollY > 40);
  if (!lite && innerWidth > 800 && scrollY < innerHeight * 1.2) {
    const y = Math.round(scrollY * 0.22);
    if (y !== ribbonY) { ribbonY = y; heroRibbon.style.transform = `translateY(${y}px)`; }
  }
}
addEventListener('scroll', () => { if (!ticking) { ticking = true; requestAnimationFrame(onScroll); } }, { passive: true });
onScroll();

/* The nav marks the section currently in the middle of the screen. */
const navLinks = [...document.querySelectorAll('.desktop-nav a')];
const spy = new IntersectionObserver(entries => entries.forEach(entry => {
  if (!entry.isIntersecting) return;
  navLinks.forEach(link => link.classList.toggle('is-active', link.getAttribute('href') === `#${entry.target.id}`));
}), { rootMargin: '-45% 0px -50% 0px' });
navLinks.forEach(link => {
  const target = document.querySelector(link.getAttribute('href'));
  if (target) spy.observe(target);
});
const heroSpy = new IntersectionObserver(([entry]) => {
  if (entry.isIntersecting) navLinks.forEach(link => link.classList.remove('is-active'));
}, { rootMargin: '-45% 0px -50% 0px' });
heroSpy.observe(document.querySelector('.hero'));

/* System carousel: an endless loop (clones on both sides). Mouse and touch drags are handled here,
   so a swipe always moves exactly one screen and lands on the same eased curve as the arrows. */
if (track) {
  const slides = [...track.children];
  const count = originals.length;
  const dots = [...document.querySelectorAll('.carousel-dots span')];
  const arrows = [...document.querySelectorAll('.carousel-arrow')];
  let first = 0, step = 1, half = 0, current = count, tween = 0, settleTimer;
  const slideDepth = [], dotWidth = [];
  // Slide centres do not move while scrolling, so measure them once instead of every frame.
  const measure = () => {
    first = slides[0].offsetLeft + slides[0].offsetWidth / 2;
    step = slides[1].offsetLeft + slides[1].offsetWidth / 2 - first;
    half = track.clientWidth / 2;
  };
  const leftFor = index => first + index * step - half;
  let dragging = false, moved = false, startX = 0, startLeft = 0, startIndex = 0, lastX = 0, lastT = 0, velocity = 0;

  function paint() {
    const position = (track.scrollLeft + half - first) / step;
    current = Math.round(position);
    slides.forEach((slide, i) => {
      const d = Math.min(Math.abs(position - i), 1.5).toFixed(3);
      if (d !== slideDepth[i]) { slideDepth[i] = d; slide.style.setProperty('--d', d); }
    });
    const logical = ((position - count) % count + count) % count;
    dots.forEach((dot, i) => {
      const raw = Math.abs(logical - i);
      const w = Math.max(0, 1 - Math.min(raw, count - raw)).toFixed(3);
      if (w !== dotWidth[i]) { dotWidth[i] = w; dot.style.setProperty('--w', w); }
    });
  }
  // Resting on a clone: jump to the matching original, which looks identical.
  function settle() {
    if (dragging || track.classList.contains('is-animating')) return;
    if (current < count || current >= count * 2) {
      track.scrollLeft += (current < count ? count : -count) * step;
      paint();
    }
  }
  function goTo(index, instant = false) {
    cancelAnimationFrame(tween);
    const to = leftFor(index);
    if (instant || reduceMotion) {
      track.scrollLeft = to;
      track.classList.remove('is-animating');
      paint();
      settle();
      return;
    }
    // Snap off before reading the position: a layout flush with snapping on would jump the track.
    track.classList.add('is-animating');
    const from = track.scrollLeft, start = performance.now(), duration = 850;
    const frame = now => {
      const p = Math.min(1, (now - start) / duration);
      track.scrollLeft = from + (to - from) * easeOutExpo(p);
      if (p < 1) { tween = requestAnimationFrame(frame); return; }
      track.classList.remove('is-animating');
      paint();
      settle();
    };
    tween = requestAnimationFrame(frame);
  }

  let pending = false;
  track.addEventListener('scroll', () => {
    if (!pending) { pending = true; requestAnimationFrame(() => { pending = false; paint(); }); }
    if (!('onscrollend' in window)) { clearTimeout(settleTimer); settleTimer = setTimeout(settle, 140); }
  }, { passive: true });
  track.addEventListener('scrollend', settle);
  arrows.forEach(arrow => arrow.addEventListener('click', () => goTo(current + Number(arrow.dataset.step))));
  slides.forEach((slide, i) => slide.addEventListener('click', () => { if (!moved && i !== current) goTo(i); }));
  track.addEventListener('keydown', event => {
    if (event.key === 'ArrowRight') { event.preventDefault(); goTo(current + 1); }
    if (event.key === 'ArrowLeft') { event.preventDefault(); goTo(current - 1); }
  });

  track.addEventListener('pointerdown', event => {
    if (event.button > 0) return;
    cancelAnimationFrame(tween);
    track.classList.add('is-dragging');
    track.classList.remove('is-animating');
    clearTimeout(settleTimer);
    paint();
    settle();
    dragging = true; moved = false; velocity = 0;
    startX = lastX = event.clientX; lastT = event.timeStamp;
    startLeft = track.scrollLeft; startIndex = current;
    track.setPointerCapture(event.pointerId);
  });
  track.addEventListener('pointermove', event => {
    if (!dragging) return;
    const delta = event.clientX - startX;
    if (!moved && Math.abs(delta) > 6) moved = true;
    if (!moved) return;
    // Past the neighbour the screen resists, so one swipe never covers two screens.
    const over = Math.abs(delta) - step;
    track.scrollLeft = startLeft - (over > 0 ? Math.sign(delta) * (step + over * 0.2) : delta);
    const dt = event.timeStamp - lastT;
    if (dt > 0) velocity = (event.clientX - lastX) / dt;
    lastX = event.clientX; lastT = event.timeStamp;
  });
  const endDrag = event => {
    if (!dragging) return;
    dragging = false;
    let direction = 0;
    if (moved) {
      if (event.timeStamp - lastT > 90) velocity = 0;
      const delta = lastX - startX;
      if (Math.abs(delta) > Math.min(60, step * 0.16)) direction = Math.sign(delta);
      else if (Math.abs(velocity) > 0.3) direction = Math.sign(velocity);
    }
    // A tap that interrupted a glide finishes it on the nearest screen.
    goTo(moved ? startIndex - direction : current);
    track.classList.remove('is-dragging');
  };
  track.addEventListener('pointerup', endDrag);
  track.addEventListener('pointercancel', endDrag);
  addEventListener('resize', () => { measure(); goTo(current, true); });
  measure();
  goTo(count, true);
}

/* Hero phone leans toward the pointer, eased every frame rather than jumping. */
const phone3d = document.querySelector('.phone3d');
const hero = document.querySelector('.hero');
if (phone3d && !reduceMotion && matchMedia('(pointer: fine)').matches) {
  let targetX = 0, targetY = 0, x = 0, y = 0, running = false;
  const loop = () => {
    x += (targetX - x) * 0.07;
    y += (targetY - y) * 0.07;
    phone3d.style.setProperty('--ty', `${(x * 14).toFixed(3)}deg`);
    phone3d.style.setProperty('--tx', `${(-y * 10).toFixed(3)}deg`);
    running = Math.abs(targetX - x) + Math.abs(targetY - y) > 0.0005;
    if (running) requestAnimationFrame(loop);
  };
  const kick = () => { if (!running) { running = true; requestAnimationFrame(loop); } };
  // Measure once per visit instead of forcing a layout read on every pointer move.
  let box = null;
  hero.addEventListener('pointerenter', () => { box = hero.getBoundingClientRect(); });
  addEventListener('scroll', () => { box = null; }, { passive: true });
  hero.addEventListener('pointermove', event => {
    if (lite) return;
    box ??= hero.getBoundingClientRect();
    targetX = (event.clientX - box.left) / box.width - 0.5;
    targetY = (event.clientY - box.top) / box.height - 0.5;
    kick();
  });
  hero.addEventListener('pointerleave', () => { targetX = 0; targetY = 0; kick(); });
}

/* Infinite animations rest while they are off screen. */
const offscreen = new IntersectionObserver(entries => entries.forEach(entry => entry.target.classList.toggle('is-off', !entry.isIntersecting)));
document.querySelectorAll('.hero, .stack').forEach(el => offscreen.observe(el));

addEventListener('load', () => {
  // No usable GPU: drop to lite before the first scroll.
  if (!lite) idle(() => { if (softwareRendering()) goLite(); });
  // Fetch and decode every lazy image in the background, one per idle slot, so reaching
  // the carousel or LEX never waits on a decode. Skipped when the visitor asked to save data.
  if (navigator.connection?.saveData) return;
  const queue = [...document.images].filter(img => img.loading === 'lazy');
  const next = () => {
    const img = queue.shift();
    if (!img) return;
    img.loading = 'eager';
    (img.decode ? img.decode() : Promise.resolve()).catch(() => {}).finally(() => idle(next));
  };
  idle(next);
}, { once: true });
