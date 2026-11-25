import './style.css';
import { t, setupLanguage } from './i18n.js';

const menuButton = document.querySelector('.menu-toggle');
const mobileMenu = document.querySelector('#mobile-menu');

function setMenu(open) {
  mobileMenu.hidden = !open;
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
