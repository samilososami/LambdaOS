import './style.css';
import { setupLanguage, getLanguage } from './i18n.js';

const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
const tr = (en, es) => getLanguage() === 'es' ? es : en;
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

/* ---------- header ---------- */
const header = $('#header');
const menu = $('#mobile-menu');
const menuToggle = $('.menu-toggle');
const setScrolled = () => header.classList.toggle('is-scrolled', scrollY > 24);
addEventListener('scroll', setScrolled, { passive: true });
setScrolled();

if (menuToggle) {
  menuToggle.addEventListener('click', () => {
    const open = menuToggle.getAttribute('aria-expanded') !== 'true';
    menuToggle.setAttribute('aria-expanded', String(open));
    menuToggle.setAttribute('aria-label', open ? tr('Close menu', 'Cerrar menú') : tr('Open menu', 'Abrir menú'));
    menu.hidden = !open;
    document.body.style.overflow = open ? 'hidden' : '';
  });
  $$('a', menu).forEach(a => a.addEventListener('click', () => {
    menuToggle.setAttribute('aria-expanded', 'false');
    menu.hidden = true;
    document.body.style.overflow = '';
  }));
}

// The header follows the section under it.
const themed = $$('[data-theme]').filter(el => el !== header);
const themeObserver = new IntersectionObserver(entries => {
  for (const e of entries) {
    if (e.isIntersecting) header.dataset.theme = e.target.dataset.theme;
  }
}, { rootMargin: '-45% 0px -50% 0px', threshold: 0 });
themed.forEach(el => themeObserver.observe(el));

/* ---------- reveal ---------- */
const revealTargets = $$('.section-label, .split-head, .film-head, .film-grid, .screen-tabs, .screen-stage, .gallery-caption, .wallpaper, .operator-head, .operator-grid, .capability-list, .lamb-copy, .lamb-card, .lex-stage, .lex-facts, .development-head, .roadmap, .specifications, .origin-layout, .origin-art, .faq-grid');
revealTargets.forEach(el => el.classList.add('reveal'));
const revealObserver = new IntersectionObserver(entries => {
  for (const e of entries) {
    if (e.isIntersecting) {
      e.target.classList.add('is-in');
      revealObserver.unobserve(e.target);
    }
  }
}, { rootMargin: '0px 0px -8% 0px', threshold: 0.05 });
revealTargets.forEach(el => revealObserver.observe(el));
requestAnimationFrame(() => {
  $('.hero-content')?.classList.add('stagger', 'is-in');
  $('.hero-device')?.classList.add('reveal', 'is-in');
});

/* ---------- hero parallax ---------- */
const heroArt = $('.hero-art');
if (heroArt && !reduced) {
  let ticking = false;
  addEventListener('scroll', () => {
    if (ticking || innerWidth < 900) return;
    ticking = true;
    requestAnimationFrame(() => {
      const y = Math.min(scrollY, innerHeight);
      heroArt.style.transform = `translate3d(0,${y * 0.1}px,0) scale(1.02)`;
      ticking = false;
    });
  }, { passive: true });
}

/* ---------- system gallery ---------- */
const screens = [
  { src: '/assets/setup-welcome.webp', title: tr('Welcome screen', 'Pantalla de inicio'), alt: tr('LambdaOS setup wizard on the Samsung Galaxy S10', 'Asistente de bienvenida de LambdaOS en el Samsung S10'), desc: tr('A custom setup wizard: language, connectivity, security and preferences.', 'Un asistente propio: idioma, conectividad, seguridad y preferencias.') },
  { src: '/assets/lockscreen.webp', title: tr('Lock screen', 'Pantalla de bloqueo'), alt: tr('Actual lock screen with clock, dark wallpaper and fingerprint sensor', 'Pantalla de bloqueo real con reloj, wallpaper oscuro y huella'), desc: tr('Clock, authentication and a wallpaper designed to complement the interface.', 'Reloj, autenticación y un fondo diseñado para acompañar la interfaz.') },
  { src: '/assets/quick-settings.webp', title: tr('Quick settings', 'Controles rápidos'), alt: tr('Native Android quick settings with the LambdaOS monochrome palette', 'Controles rápidos nativos de Android con la paleta monocroma LambdaOS'), desc: tr('Native Android controls, with the LambdaOS palette.', 'Controles nativos de Android, con la paleta de LambdaOS.') },
  { src: '/assets/settings.webp', title: tr('Settings', 'Ajustes'), alt: tr('LambdaOS settings on the Samsung Galaxy S10', 'Ajustes de LambdaOS en el Samsung Galaxy S10'), desc: tr('The same palette extends to settings and system surfaces.', 'La misma paleta se extiende a los ajustes y a las superficies del sistema.') }
];
let current = 0;
const tabButtons = $$('.screen-tabs [role="tab"]');
const screenCurrent = $('#screen-current');
const screenPrev = $('#screen-prev');
const screenNext = $('#screen-next');
const screenDesc = $('#screen-description');
const counter = $('.counter');

function renderScreens() {
  const prev = (current + screens.length - 1) % screens.length;
  const next = (current + 1) % screens.length;
  screenCurrent.src = screens[current].src;
  screenCurrent.alt = screens[current].alt;
  screenPrev.src = screens[prev].src;
  screenNext.src = screens[next].src;
  screenDesc.textContent = screens[current].desc;
  counter.textContent = `${String(current + 1).padStart(2, '0')} / ${String(screens.length).padStart(2, '0')}`;
  tabButtons.forEach((b, i) => {
    b.setAttribute('aria-selected', String(i === current));
    b.tabIndex = i === current ? 0 : -1;
  });
  $('#screen-panel').setAttribute('aria-labelledby', tabButtons[current].id);
}
tabButtons.forEach((b, i) => {
  b.addEventListener('click', () => { current = i; renderScreens(); });
  b.addEventListener('keydown', e => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
    e.preventDefault();
    const dir = e.key === 'ArrowRight' ? 1 : -1;
    current = (current + dir + screens.length) % screens.length;
    renderScreens();
    tabButtons[current].focus();
  });
});
$$('[data-direction]').forEach(b => b.addEventListener('click', () => {
  current = (current + Number(b.dataset.direction) + screens.length) % screens.length;
  renderScreens();
}));
renderScreens();

/* ---------- wallpaper compare ---------- */
const slider = $('#wallpaper-slider');
const lightLayer = $('.wallpaper-light-layer');
const compareLine = $('.compare-line');
function paintWallpaper() {
  const v = Number(slider.value);
  lightLayer.style.clipPath = `inset(0 ${100 - v}% 0 0)`;
  compareLine.style.left = `${v}%`;
}
slider?.addEventListener('input', paintWallpaper);
slider?.addEventListener('change', paintWallpaper);
paintWallpaper();

/* ---------- image dialog ---------- */
const imageDialog = $('#image-viewer');
const viewerImage = $('#viewer-image');
const viewerTitle = $('#viewer-title');
const viewerCaption = $('#viewer-caption');
$('#enlarge-screen')?.addEventListener('click', () => {
  const s = screens[current];
  viewerImage.src = s.src;
  viewerImage.alt = s.alt;
  viewerTitle.textContent = s.title;
  viewerCaption.textContent = tr('Actual Samsung Galaxy S10 screenshot · development build', 'Captura real del Samsung Galaxy S10 · compilación de desarrollo');
  imageDialog.showModal();
});
$('#close-viewer')?.addEventListener('click', () => imageDialog.close());
imageDialog?.addEventListener('close', () => { viewerImage.src = '/assets/setup-welcome.webp'; });
$('#open-operator')?.addEventListener('click', () => {
  viewerImage.src = '/assets/operator.webp';
  viewerImage.alt = tr('Lambda Operator — current app', 'Lambda Operator — aplicación actual');
  viewerTitle.textContent = tr('Lambda Operator — current app', 'Lambda Operator — aplicación actual');
  viewerCaption.textContent = tr('Actual Lambda app screenshot on the S10', 'Captura real de la aplicación Lambda en el S10');
  imageDialog.showModal();
});

/* ---------- video dialog ---------- */
const videoDialog = $('#video-viewer');
const videoPlayer = $('#video-player');
const videoTitle = $('#video-title');
const videos = {
  trailer: { src: '/media/trailer.mp4', title: tr('LambdaOS — Trailer', 'LambdaOS — Trailer') },
  promo: { src: '/media/promo.mp4', title: tr('LambdaOS — Promo', 'LambdaOS — Promo') }
};
$$('[data-open-video]').forEach(btn => btn.addEventListener('click', () => {
  const v = videos[btn.dataset.openVideo];
  videoTitle.textContent = v.title;
  videoPlayer.src = v.src;
  videoDialog.showModal();
  videoPlayer.play().catch(() => {});
}));
$('#close-video')?.addEventListener('click', () => videoDialog.close());
videoDialog?.addEventListener('close', () => {
  videoPlayer.pause();
  videoPlayer.removeAttribute('src');
  videoPlayer.load();
});

/* ---------- marquee ---------- */
const track = $('[data-marquee]');
if (track) {
  const html = track.innerHTML;
  track.innerHTML = html + html + html;
  if (reduced) track.style.animation = 'none';
}

/* ---------- support ---------- */
$$('.support-copy').forEach(btn => {
  btn.addEventListener('click', async () => {
    const value = btn.closest('.support-card').querySelector('.support-value').textContent.trim();
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
    btn.classList.add('is-copied');
    setTimeout(() => {
      btn.textContent = tr('Copy', 'Copiar');
      btn.classList.remove('is-copied');
    }, 1600);
  });
});

/* ---------- language ---------- */
setupLanguage(() => {
  menuToggle?.setAttribute('aria-label', tr('Open menu', 'Abrir menú'));
  renderScreens();
  videos.trailer.title = tr('LambdaOS — Trailer', 'LambdaOS — Trailer');
  videos.promo.title = tr('LambdaOS — Promo', 'LambdaOS — Promo');
  paintWallpaper();
});
