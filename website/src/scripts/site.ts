/**
 * The only client script on the site. No framework, no third parties, no
 * storage beyond one localStorage key for the chosen colour theme.
 */

const THEMES = ['acid', 'infrared', 'hazard', 'volt'] as const;
type Theme = (typeof THEMES)[number];
const root = document.documentElement;

/* ---------- Mobile menu ---------- */
const toggle = document.querySelector<HTMLButtonElement>('[data-menu-toggle]');
const menu = document.getElementById('mobile-nav');
if (toggle && menu) {
  const setOpen = (open: boolean) => {
    toggle.setAttribute('aria-expanded', String(open));
    toggle.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
    menu.hidden = !open;
  };
  toggle.addEventListener('click', () => setOpen(toggle.getAttribute('aria-expanded') !== 'true'));
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !menu.hidden) {
      setOpen(false);
      toggle.focus();
    }
  });
  menu.addEventListener('click', (e) => {
    if ((e.target as HTMLElement).closest('a')) setOpen(false);
  });
  window.matchMedia('(min-width: 901px)').addEventListener('change', (m) => m.matches && setOpen(false));
}

/* ---------- Reveal on scroll ---------- */
const revealables = document.querySelectorAll<HTMLElement>('[data-reveal]');
if ('IntersectionObserver' in window && revealables.length) {
  const io = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-in');
          io.unobserve(entry.target);
        }
      }
    },
    { rootMargin: '0px 0px -8% 0px', threshold: 0.08 },
  );
  revealables.forEach((el) => io.observe(el));
} else {
  revealables.forEach((el) => el.classList.add('is-in'));
}

/* ---------- Theme switcher (easter egg) ---------- */
const live = document.getElementById('theme-status');
const current = (): Theme => {
  const t = root.getAttribute('data-theme');
  return (THEMES as readonly string[]).includes(t ?? '') ? (t as Theme) : 'acid';
};
const label = (t: Theme) => t.charAt(0).toUpperCase() + t.slice(1);

function syncButtons(theme: Theme) {
  document.querySelectorAll<HTMLButtonElement>('[data-set-theme]').forEach((b) => {
    b.setAttribute('aria-pressed', String(b.dataset.setTheme === theme));
  });
}

function setTheme(theme: Theme, announce = true) {
  root.setAttribute('data-theme', theme);
  try {
    if (theme === 'acid') window.localStorage.removeItem('clx-theme');
    else window.localStorage.setItem('clx-theme', theme);
  } catch {
    /* Storage unavailable: the theme still applies for this page view. */
  }
  syncButtons(theme);
  if (announce && live) live.textContent = `${label(theme)} theme on.`;
}

syncButtons(current());
document.querySelectorAll<HTMLButtonElement>('[data-set-theme]').forEach((b) => {
  b.addEventListener('click', () => setTheme(b.dataset.setTheme as Theme));
});
document.querySelectorAll<HTMLElement>('[data-cycle-theme]').forEach((el) => {
  el.addEventListener('click', () => {
    const next = THEMES[(THEMES.indexOf(current()) + 1) % THEMES.length];
    setTheme(next);
  });
});

/* ---------- Contents highlighting on long pages ---------- */
const tocLinks = Array.from(document.querySelectorAll<HTMLAnchorElement>('[data-toc] a[href^="#"]'));
if (tocLinks.length && 'IntersectionObserver' in window) {
  const targets = tocLinks
    .map((a) => document.getElementById(decodeURIComponent(a.hash.slice(1))))
    .filter((el): el is HTMLElement => !!el);
  const visible = new Set<string>();
  const mark = () => {
    const firstVisible = targets.find((t) => visible.has(t.id));
    let active = firstVisible?.id;
    if (!active) {
      // Between headings: the last heading above the viewport is current.
      const above = targets.filter((t) => t.getBoundingClientRect().top < 120);
      active = above[above.length - 1]?.id;
    }
    tocLinks.forEach((a) => a.setAttribute('aria-current', String(a.hash.slice(1) === active)));
  };
  const io = new IntersectionObserver(
    (entries) => {
      entries.forEach((e) => (e.isIntersecting ? visible.add(e.target.id) : visible.delete(e.target.id)));
      mark();
    },
    { rootMargin: '-80px 0px -55% 0px' },
  );
  targets.forEach((t) => io.observe(t));
}
