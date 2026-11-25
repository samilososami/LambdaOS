import './style.css';
import { t, setupLanguage } from './i18n.js';

const menuButton = document.querySelector('.menu-toggle');
const mobileMenu = document.querySelector('#mobile-menu');

function setMenu(open) {
  mobileMenu.hidden = !open;
  document.body.style.overflow = open ? 'hidden' : '';
  menuButton.setAttribute('aria-expanded', String(open));
  menuButton.setAttribute('aria-label', t(open ? 'Cerrar menú' : 'Abrir menú'));
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
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
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

/* System carousel: native scroll-snap, arrows, dots, keyboard and mouse drag. */
const track = document.querySelector('.carousel-track');
if (track) {
  const slides = [...track.children];
  const dots = [...document.querySelectorAll('.carousel-dots span')];
  const arrows = [...document.querySelectorAll('.carousel-arrow')];
  let current = 0;
  const centerOf = slide => slide.offsetLeft + slide.offsetWidth / 2;
  const goTo = index => {
    const slide = slides[Math.max(0, Math.min(slides.length - 1, index))];
    track.scrollTo({ left: centerOf(slide) - track.clientWidth / 2, behavior: reduceMotion ? 'auto' : 'smooth' });
  };
  function paint() {
    const middle = track.scrollLeft + track.clientWidth / 2;
    const spacing = slides.length > 1 ? centerOf(slides[1]) - centerOf(slides[0]) : 1;
    let nearest = 0;
    slides.forEach((slide, i) => {
      const distance = Math.abs(centerOf(slide) - middle) / spacing;
      slide.style.setProperty('--d', Math.min(distance, 1.5).toFixed(3));
      if (distance < Math.abs(centerOf(slides[nearest]) - middle) / spacing) nearest = i;
    });
    current = nearest;
    dots.forEach((dot, i) => dot.classList.toggle('is-on', i === current));
    arrows[0].disabled = current === 0;
    arrows[1].disabled = current === slides.length - 1;
  }
  let pending = false;
  track.addEventListener('scroll', () => { if (!pending) { pending = true; requestAnimationFrame(() => { pending = false; paint(); }); } }, { passive: true });
  arrows.forEach(arrow => arrow.addEventListener('click', () => goTo(current + Number(arrow.dataset.step))));
  slides.forEach((slide, i) => slide.addEventListener('click', () => { if (!moved && i !== current) goTo(i); }));
  track.addEventListener('keydown', event => {
    if (event.key === 'ArrowRight') { event.preventDefault(); goTo(current + 1); }
    if (event.key === 'ArrowLeft') { event.preventDefault(); goTo(current - 1); }
  });
  // Mouse drag (touch already scrolls natively).
  let startX = 0, startLeft = 0, dragging = false, moved = false;
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
  addEventListener('resize', paint);
  paint();
  requestAnimationFrame(() => goTo(0));
}
