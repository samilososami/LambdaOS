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
  const apply = () => {
    document.documentElement.lang = language;
    for (const { node, value, key } of entries) {
      node.textContent = language === 'en' ? value : value.replace(key, spanish.get(key));
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
    onChange();
  };
  document.querySelectorAll('[data-language]').forEach(button => {
    button.addEventListener('click', () => {
      language = button.dataset.language === 'es' ? 'es' : 'en';
      try { localStorage.setItem(storageKey, language); } catch { /* Still switch in memory. */ }
      apply();
    });
  });
  apply();
}
