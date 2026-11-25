import './style.css';
import { t, setupLanguage } from './i18n.js';

const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
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
  }, 300);
}
menuButton.addEventListener('click', () => setMenu(menuButton.getAttribute('aria-expanded') !== 'true'));
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
let ticking = false;
function onScroll() {
  ticking = false;
  header.classList.toggle('is-scrolled', scrollY > 24);
  if (!reduceMotion && innerWidth > 800 && scrollY < innerHeight * 1.2) {
    heroRibbon.style.transform = `translateY(${(scrollY * 0.22).toFixed(1)}px)`;
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

/* System carousel: an endless loop (clones on both sides), scroll-snap, arrows, keys, mouse drag
   and dots that follow the scroll continuously. */
if (track) {
  const slides = [...track.children];
  const count = originals.length;
  const dots = [...document.querySelectorAll('.carousel-dots span')];
  const arrows = [...document.querySelectorAll('.carousel-arrow')];
  let current = count;
  let dragging = false, moved = false, startX = 0, startLeft = 0, settleTimer;
  const centerOf = slide => slide.offsetLeft + slide.offsetWidth / 2;
  const spacing = () => centerOf(slides[1]) - centerOf(slides[0]);
  const leftFor = index => centerOf(slides[index]) - track.clientWidth / 2;
  const goTo = (index, instant = false) => {
    track.scrollTo({ left: leftFor(index), behavior: instant || reduceMotion ? 'auto' : 'smooth' });
  };
  function paint() {
    const position = (track.scrollLeft + track.clientWidth / 2 - centerOf(slides[0])) / spacing();
    current = Math.round(position);
    slides.forEach((slide, i) => slide.style.setProperty('--d', Math.min(Math.abs(position - i), 1.5).toFixed(3)));
    const logical = ((position - count) % count + count) % count;
    dots.forEach((dot, i) => {
      const raw = Math.abs(logical - i);
      const distance = Math.min(raw, count - raw);
      dot.style.setProperty('--w', Math.max(0, 1 - distance).toFixed(3));
    });
  }
  // Once the scroll settles on a clone, jump to the matching original without animation.
  function settle() {
    if (dragging) return;
    if (current < count || current >= count * 2) {
      track.scrollLeft += (current < count ? count : -count) * spacing();
      paint();
    }
  }
  let pending = false;
  track.addEventListener('scroll', () => {
    if (!pending) { pending = true; requestAnimationFrame(() => { pending = false; paint(); }); }
    clearTimeout(settleTimer);
    settleTimer = setTimeout(settle, 140);
  }, { passive: true });
  arrows.forEach(arrow => arrow.addEventListener('click', () => goTo(current + Number(arrow.dataset.step))));
  slides.forEach((slide, i) => slide.addEventListener('click', () => { if (!moved && i !== current) goTo(i); }));
  track.addEventListener('keydown', event => {
    if (event.key === 'ArrowRight') { event.preventDefault(); goTo(current + 1); }
    if (event.key === 'ArrowLeft') { event.preventDefault(); goTo(current - 1); }
  });
  // Mouse drag (touch already scrolls natively).
  track.addEventListener('pointerdown', event => {
    if (event.pointerType !== 'mouse') return;
    dragging = true; moved = false; startX = event.clientX; startLeft = track.scrollLeft;
    track.setPointerCapture(event.pointerId);
  });
  track.addEventListener('pointermove', event => {
    if (!dragging) return;
    const delta = event.clientX - startX;
    if (Math.abs(delta) > 4) { moved = true; track.classList.add('is-dragging'); }
    track.scrollLeft = startLeft - delta;
  });
  const endDrag = () => {
    if (!dragging) return;
    dragging = false;
    track.classList.remove('is-dragging');
    if (moved) goTo(current);
  };
  track.addEventListener('pointerup', endDrag);
  track.addEventListener('pointercancel', endDrag);
  addEventListener('resize', () => { goTo(current, true); paint(); });
  goTo(count, true);
  paint();
}

/* Hero phone leans slightly toward the pointer. */
const phone3d = document.querySelector('.phone3d');
const hero = document.querySelector('.hero');
if (phone3d && !reduceMotion && matchMedia('(pointer: fine)').matches) {
  hero.addEventListener('pointermove', event => {
    const box = hero.getBoundingClientRect();
    const x = (event.clientX - box.left) / box.width - 0.5;
    const y = (event.clientY - box.top) / box.height - 0.5;
    phone3d.style.setProperty('--ty', `${(x * 14).toFixed(2)}deg`);
    phone3d.style.setProperty('--tx', `${(-y * 10).toFixed(2)}deg`);
  });
  hero.addEventListener('pointerleave', () => {
    phone3d.style.setProperty('--ty', '0deg');
    phone3d.style.setProperty('--tx', '0deg');
  });
}
