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

/* System carousel: an endless loop (clones on both sides). Motion follows a critically damped
   spring, so rapid clicks queue up, a change of direction turns smoothly instead of restarting,
   and a released drag hands its speed to the glide. Mouse and touch drags move one screen. */
if (track) {
  const slides = [...track.children];
  const count = originals.length;
  const dots = [...document.querySelectorAll('.carousel-dots span')];
  const arrows = [...document.querySelectorAll('.carousel-arrow')];
  let first = 0, step = 1, half = 0, current = count;
  const slideDepth = [], dotWidth = [];
  // Slide centres do not move while scrolling, so measure them once instead of every frame.
  // Sub-pixel centres from the rects (scaling around the centre leaves them in place); offsetLeft
  // rounds each slide to whole pixels and the error added up to a visible snap at the end.
  const measure = () => {
    const origin = track.getBoundingClientRect().left - track.scrollLeft;
    const centre = slide => { const r = slide.getBoundingClientRect(); return r.left + r.width / 2 - origin; };
    first = centre(slides[0]);
    step = (centre(slides.at(-1)) - first) / (slides.length - 1);
    half = track.clientWidth / 2;
  };
  const leftFor = index => first + index * step - half;
  const indexAt = left => (left + half - first) / step;

  function paint(left) {
    const position = indexAt(left);
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

  // The spring: position and velocity in pixels, target in slides.
  let pos = 0, vel = 0, target = count, running = false, last = 0, frame = 0;
  const SMOOTH = 0.2; // seconds; the glide settles in about three times this
  function tick(now) {
    const dt = Math.min(0.05, Math.max(0.001, (now - last) / 1000));
    last = now;
    // Inside the clones? Shift position and target by one full set: the view is identical.
    const at = indexAt(pos);
    if (at < count - 0.5 || at > count * 2 - 0.5) {
      const shift = (at < count - 0.5 ? count : -count);
      pos += shift * step;
      target += shift;
    }
    const goal = leftFor(target);
    const omega = 2 / SMOOTH, x = omega * dt;
    const decay = 1 / (1 + x + 0.48 * x * x + 0.235 * x * x * x);
    const change = pos - goal, temp = (vel + omega * change) * dt;
    vel = (vel - omega * temp) * decay;
    pos = goal + (change + temp) * decay;
    if (Math.abs(goal - pos) < 0.35 && Math.abs(vel) < 6) {
      pos = goal; vel = 0; running = false;
      track.scrollLeft = pos;
      paint(pos);
      track.classList.remove('is-animating');
      return;
    }
    track.scrollLeft = pos;
    paint(pos);
    frame = requestAnimationFrame(tick);
  }
  function glide(index, startVelocity = vel) {
    // Never queue more than three screens ahead of what is on screen.
    const at = indexAt(running ? pos : track.scrollLeft);
    target = Math.max(Math.round(at) - 3, Math.min(Math.round(at) + 3, index));
    if (reduceMotion) {
      track.scrollLeft = pos = leftFor(target);
      paint(pos);
      settle();
      return;
    }
    vel = startVelocity;
    if (running) return;
    // Snap off before reading the position: a layout flush with snapping on would jump the track.
    track.classList.add('is-animating');
    pos = track.scrollLeft;
    running = true;
    last = performance.now();
    frame = requestAnimationFrame(tick);
  }
  const nudge = direction => glide((running ? target : current) + direction);
  function stop() {
    cancelAnimationFrame(frame);
    running = false;
    track.classList.remove('is-animating');
  }
  // Resting on a clone after a native scroll (trackpad): jump to the matching original.
  function settle() {
    if (dragging || running) return;
    if (current < count || current >= count * 2) {
      track.scrollLeft += (current < count ? count : -count) * step;
      paint(track.scrollLeft);
    }
  }

  let pending = false, settleTimer;
  track.addEventListener('scroll', () => {
    if (running) return; // the spring paints its own frames
    if (!pending) { pending = true; requestAnimationFrame(() => { pending = false; paint(track.scrollLeft); }); }
    if (!('onscrollend' in window)) { clearTimeout(settleTimer); settleTimer = setTimeout(settle, 140); }
  }, { passive: true });
  track.addEventListener('scrollend', settle);
  arrows.forEach(arrow => arrow.addEventListener('click', () => nudge(Number(arrow.dataset.step))));
  slides.forEach((slide, i) => slide.addEventListener('click', () => { if (!moved && i !== current) glide(i); }));
  track.addEventListener('keydown', event => {
    if (event.key === 'ArrowRight') { event.preventDefault(); nudge(1); }
    if (event.key === 'ArrowLeft') { event.preventDefault(); nudge(-1); }
  });

  let dragging = false, moved = false, startX = 0, startLeft = 0, startIndex = 0, lastX = 0, lastT = 0, velocity = 0;
  track.addEventListener('pointerdown', event => {
    if (event.button > 0) return;
    track.classList.add('is-dragging');
    stop();
    clearTimeout(settleTimer);
    paint(track.scrollLeft);
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
    // The finger's speed becomes the glide's starting speed (pointer px/ms to scroll px/s).
    glide(moved ? startIndex - direction : current, moved ? -velocity * 1000 : 0);
    track.classList.remove('is-dragging');
  };
  track.addEventListener('pointerup', endDrag);
  track.addEventListener('pointercancel', endDrag);
  addEventListener('resize', () => {
    stop();
    measure();
    track.scrollLeft = pos = leftFor(current);
    paint(pos);
  });
  measure();
  track.scrollLeft = pos = leftFor(count);
  paint(pos);
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
