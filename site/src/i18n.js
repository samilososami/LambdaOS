import { es } from './es.js';

const storageKey = 'lambdaos-language';
let language = 'en';
try { if (localStorage.getItem(storageKey) === 'es') language = 'es'; } catch { /* Storage is optional. */ }

export const getLanguage = () => language;

// Static, developer-authored strings only: innerHTML never receives user input.
const textNodes = [...document.querySelectorAll('[data-i18n]')];
const originals = new Map();
textNodes.forEach(node => {
  const key = node.dataset.i18n;
  if (!originals.has(key)) originals.set(key, node.innerHTML);
});

const ariaNodes = [...document.querySelectorAll('[data-i18n-aria]')];
const ariaOriginals = new Map();
ariaNodes.forEach(node => {
  const key = node.dataset.i18nAria;
  if (!ariaOriginals.has(key)) ariaOriginals.set(key, node.getAttribute('aria-label') ?? '');
});

const altNodes = [...document.querySelectorAll('[data-i18n-alt]')];
const altOriginals = new Map();
altNodes.forEach(node => {
  const key = node.dataset.i18nAlt;
  if (!altOriginals.has(key)) altOriginals.set(key, node.getAttribute('alt') ?? '');
});

const meta = {
  title: document.title,
  description: document.querySelector('meta[name="description"]')?.content ?? '',
  ogTitle: document.querySelector('meta[property="og:title"]')?.content ?? '',
  ogDescription: document.querySelector('meta[property="og:description"]')?.content ?? '',
  twitterTitle: document.querySelector('meta[name="twitter:title"]')?.content ?? '',
  twitterDescription: document.querySelector('meta[name="twitter:description"]')?.content ?? ''
};

function setMeta(name, value) {
  const el = document.querySelector(`meta[name="${name}"]`) ?? document.querySelector(`meta[property="${name}"]`);
  if (el) el.content = value;
}

function apply() {
  document.documentElement.lang = language;
  textNodes.forEach(node => {
    const key = node.dataset.i18n;
    node.innerHTML = language === 'es' ? (es[key] ?? originals.get(key)) : originals.get(key);
  });
  ariaNodes.forEach(node => {
    const key = node.dataset.i18nAria;
    node.setAttribute('aria-label', language === 'es' ? (es[key] ?? ariaOriginals.get(key)) : ariaOriginals.get(key));
  });
  altNodes.forEach(node => {
    const key = node.dataset.i18nAlt;
    node.setAttribute('alt', language === 'es' ? (es[key] ?? altOriginals.get(key)) : altOriginals.get(key));
  });

  document.title = language === 'es' ? es['meta.title'] : meta.title;
  setMeta('description', language === 'es' ? es['meta.desc'] : meta.description);
  const ogTitle = document.querySelector('meta[property="og:title"]');
  const ogDescription = document.querySelector('meta[property="og:description"]');
  const twitterTitle = document.querySelector('meta[name="twitter:title"]');
  const twitterDescription = document.querySelector('meta[name="twitter:description"]');
  const ogLocale = document.querySelector('meta[property="og:locale"]');
  if (ogTitle) ogTitle.content = language === 'es' ? es['meta.ogTitle'] : meta.ogTitle;
  if (ogDescription) ogDescription.content = language === 'es' ? es['meta.ogDesc'] : meta.ogDescription;
  if (twitterTitle) twitterTitle.content = language === 'es' ? es['meta.ogTitle'] : meta.twitterTitle;
  if (twitterDescription) twitterDescription.content = language === 'es' ? es['meta.ogDesc'] : meta.twitterDescription;
  if (ogLocale) ogLocale.content = language === 'es' ? 'es_ES' : 'en_US';

  document.querySelectorAll('[data-language]').forEach(button => {
    button.setAttribute('aria-pressed', String(button.dataset.language === language));
  });
}

export function setupLanguage(onChange = () => {}) {
  document.querySelectorAll('[data-language]').forEach(button => {
    button.addEventListener('click', () => {
      language = button.dataset.language === 'es' ? 'es' : 'en';
      try { localStorage.setItem(storageKey, language); } catch { /* Still switch in memory. */ }
      apply();
      onChange();
    });
  });
  apply();
  onChange();
}
