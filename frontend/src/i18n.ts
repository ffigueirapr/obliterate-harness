// UI language. English by default; Portuguese as an option (saved in this browser).
// Same pattern as the backend: t(en, pt) picks the text for the current language.
export type Lang = 'en' | 'pt';

const KEY = 'hm.lang';

function initial(): Lang {
  try {
    const v = localStorage.getItem(KEY);
    if (v === 'pt' || v === 'en') return v;
  } catch {
    /* storage unavailable */
  }
  return 'en';
}

let current: Lang = initial();

export const lang = (): Lang => current;

export function setLang(l: Lang) {
  current = l;
  try {
    localStorage.setItem(KEY, l);
  } catch {
    /* storage unavailable: only this tab keeps the choice */
  }
  document.documentElement.lang = l === 'pt' ? 'pt-BR' : 'en';
}

export const t = (en: string, pt: string) => (current === 'pt' ? pt : en);

/** "1 unit" / "2 units" in the current language. */
export const plural = (n: number, en: [string, string], pt: [string, string]) => {
  const [one, many] = current === 'pt' ? pt : en;
  return `${n} ${n === 1 ? one : many}`;
};

export const fmtDate = (iso?: string) => (iso ? iso.slice(0, 16).replace('T', ' ') : '');
