import { copy } from './copy.js';

const english = new Map(copy);
const spanish = new Map(copy.map(([es, en]) => [en, es]));
const storageKey = 'lambdaos-language';
let language = 'en';
try { if (localStorage.getItem(storageKey) === 'es') language = 'es'; } catch { /* Storage is optional. */ }

export const getLanguage = () => language;
export function t(spanishText) {
  return language === 'es' ? spanishText : english.get(spanishText) ?? spanishText;
}

/* A drop spreads from the click; each piece of visible text changes as the edge reaches it. */
const RIPPLE_MS = 1100;
function ripple({ x, y }, waves) {
  const radius = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
  const ring = Object.assign(document.createElement('span'), { className: 'lang-ripple' });
  ring.style.left = `${x}px`;
  ring.style.top = `${y}px`;
  document.body.append(ring);
  const size = `${radius * 2}px`;
  ring.animate(
    [{ width: '0px', height: '0px', opacity: 1 }, { opacity: 0.7, offset: 0.55 }, { width: size, height: size, opacity: 0 }],
    { duration: RIPPLE_MS, easing: 'cubic-bezier(.33,1,.68,1)' }
  ).finished.then(() => ring.remove(), () => ring.remove());
  for (const [element, sets] of waves) {
    const box = element.getBoundingClientRect();
    const distance = Math.hypot(Math.max(box.left - x, 0, x - box.right), Math.max(box.top - y, 0, y - box.bottom));
    // The ring follows an ease-out-cubic radius: invert it to know when the edge arrives.
    const delay = (1 - Math.cbrt(1 - Math.min(distance / radius, 1))) * RIPPLE_MS;
    setTimeout(() => {
      sets.forEach(set => set());
      element.animate([{ opacity: 0, filter: 'blur(6px)' }, { opacity: 1, filter: 'blur(0)' }], { duration: 560, easing: 'cubic-bezier(.16,1,.3,1)' });
    }, delay);
  }
}

export function setupLanguage(onChange) {
  // Keep original node references: never replace markup or reset controls.
  const entries = [];
  const walker = document.createTreeWalker(document.documentElement, NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) {
    const node = walker.currentNode;
    if (node.parentElement?.closest('script,style,svg')) continue;
    const value = node.textContent;
    const key = value.trim();
    if (spanish.has(key)) entries.push({ node, value, key });
  }
  const attributes = [];
  document.querySelectorAll('[alt],[aria-label],meta[content]').forEach(node => {
    for (const name of ['alt', 'aria-label', 'content']) {
      const value = node.getAttribute(name);
      if (spanish.has(value)) attributes.push({ node, name, value });
    }
  });
  const textFor = ({ value, key }) => (language === 'en' ? value : value.replace(key, spanish.get(key)));
  const apply = origin => {
    document.documentElement.lang = language;
    // With an origin, visible text waits for the ripple; everything off-screen changes at once.
    const waves = new Map();
    for (const entry of entries) {
      const set = () => { entry.node.textContent = textFor(entry); };
      const element = entry.node.parentElement;
      // document.title replaces its own text node, so an entry can be detached.
      const box = origin && element?.getBoundingClientRect();
      if (!box || !box.width || box.bottom < 0 || box.top > innerHeight || box.right < 0 || box.left > innerWidth) { set(); continue; }
      if (!waves.has(element)) waves.set(element, []);
      waves.get(element).push(set);
    }
    for (const { node, name, value } of attributes) {
      node.setAttribute(name, language === 'en' ? value : spanish.get(value));
    }
    // The Spanish metadata must use the new headline too.
    const title = language === 'en'
      ? 'LambdaOS — The most complete operating system for pentesting on Android'
      : 'LambdaOS — El sistema operativo para pentesting en Android más completo';
    document.title = title;
    document.querySelector('meta[property="og:title"]').content = title;
    document.querySelector('meta[property="og:locale"]').content = language === 'en' ? 'en_US' : 'es_ES';
    document.querySelectorAll('[data-language]').forEach(button => {
      button.setAttribute('aria-pressed', String(button.dataset.language === language));
    });
    if (origin) ripple(origin, waves);
    onChange();
  };
  document.querySelectorAll('[data-language]').forEach(button => {
    button.addEventListener('click', event => {
      const next = button.dataset.language === 'es' ? 'es' : 'en';
      if (next === language) return;
      language = next;
      try { localStorage.setItem(storageKey, language); } catch { /* Still switch in memory. */ }
      if (matchMedia('(prefers-reduced-motion: reduce)').matches) { apply(); return; }
      // A keyboard press has no pointer position: start from the button itself.
      const box = button.getBoundingClientRect();
      apply(event.detail ? { x: event.clientX, y: event.clientY } : { x: box.left + box.width / 2, y: box.top + box.height / 2 });
    });
  });
  apply();
}
