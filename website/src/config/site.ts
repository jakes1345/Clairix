/**
 * Site-wide facts, navigation and per-page metadata. Titles, descriptions and
 * Open Graph lines come from the approved copy (site-copy/meta.md).
 */

export const SITE_URL = 'https://clairix.app';
export const PLAY_URL = 'https://play.google.com/store/apps/details?id=app.clairix';
export const PACKAGE_NAME = 'app.clairix';
export const SUPPORT_EMAIL = 'support@clairix.app';
export const SECURITY_EMAIL = 'security@clairix.app';

/**
 * Press asset folder (logos, icon, screenshots). Empty until it is published;
 * the press page hides the line while it is unset.
 */
export const PRESS_ASSETS_URL = '';

export interface NavLink {
  label: string;
  href: string;
}

export const primaryNav: NavLink[] = [
  { label: 'Features', href: '/features' },
  { label: 'Security', href: '/security' },
  { label: 'Support', href: '/support' },
  { label: 'Download', href: '/download' },
];

export const footerColumns: { title: string; links: NavLink[] }[] = [
  {
    title: 'Product',
    links: [
      { label: 'Features', href: '/features' },
      { label: 'Download', href: '/download' },
      { label: 'Clairix Full', href: '/download#clairix-full' },
    ],
  },
  {
    title: 'Trust',
    links: [
      { label: 'Security', href: '/security' },
      { label: 'What leaves your phone', href: '/security#egress' },
      { label: 'Privacy Policy', href: '/privacy' },
      { label: 'Terms of Service', href: '/terms' },
      { label: 'Delete your account', href: '/delete-account' },
    ],
  },
  {
    title: 'Help',
    links: [
      { label: 'Support', href: '/support' },
      { label: 'Report a vulnerability', href: `mailto:${SECURITY_EMAIL}` },
      { label: 'Contact', href: `mailto:${SUPPORT_EMAIL}` },
    ],
  },
  {
    title: 'Company',
    links: [{ label: 'Press', href: '/press' }],
  },
];

export const footerLine =
  'Clairix. No ads, no analytics, no trackers. Map data from OpenStreetMap contributors, ODbL.';
export const legalLine =
  '© 2026 Clairix. Android and Google Play are trademarks of Google LLC. AirTag is a trademark of Apple Inc. Other names are the property of their owners.';

export interface PageMeta {
  title: string;
  description: string;
  ogTitle: string;
  /** Path of the 1200x630 Open Graph image under /public. */
  ogImage: string;
  ogImageAlt: string;
}

export const pages = {
  home: {
    title: 'Clairix – Personal security for Android',
    description:
      'Find hidden cameras, Bluetooth trackers and spyware with the phone in your pocket. Private encrypted calls. No ads, no trackers, data stays on your phone.',
    ogTitle: "Clairix. Know what's watching.",
    ogImage: '/og/home.png',
    ogImageAlt: 'Clairix. Nothing hides.',
  },
  features: {
    title: 'Features – Clairix',
    description:
      'What each Clairix feature does, how it works and what it cannot do: camera sweeps, tracker alerts, spyware checks, fake networks, encrypted comms.',
    ogTitle: 'Clairix features, including the limits',
    ogImage: '/og/features.png',
    ogImageAlt: "What it finds. What it can't.",
  },
  security: {
    title: 'Security and privacy – Clairix',
    description:
      'Exactly what leaves your phone and what never does. How Clairix encrypts messages, what the relay stores and for how long, and how to report a bug.',
    ogTitle: 'How Clairix handles your data',
    ogImage: '/og/security.png',
    ogImageAlt: 'Your data stays on your phone.',
  },
  download: {
    title: 'Download – Clairix',
    description:
      'Get Clairix free on Google Play, or download Clairix Full as an APK and verify it with SHA-256. Requires Android 12 or newer.',
    ogTitle: 'Download Clairix',
    ogImage: '/og/download.png',
    ogImageAlt: 'Get Clairix. Free.',
  },
  privacy: {
    title: 'Privacy Policy – Clairix',
    description:
      'What stays on your phone, what leaves it and where it goes, what the relay keeps and for how long, and how to delete your data.',
    ogTitle: 'Clairix Privacy Policy',
    ogImage: '/og/privacy.png',
    ogImageAlt: 'Clairix Privacy Policy',
  },
  terms: {
    title: 'Terms of Service – Clairix',
    description:
      'The terms for using the Clairix app and relay, including acceptable use and the limits of detection.',
    ogTitle: 'Clairix Terms of Service',
    ogImage: '/og/terms.png',
    ogImageAlt: 'Clairix Terms of Service',
  },
  deleteAccount: {
    title: 'Delete your account – Clairix',
    description:
      'How to delete your Clairix relay account in the app or by email, what is deleted, and how long anything is retained.',
    ogTitle: 'Delete your Clairix account',
    ogImage: '/og/delete-account.png',
    ogImageAlt: 'Delete your account.',
  },
  support: {
    title: 'Support – Clairix',
    description:
      'Help with Clairix: permissions, battery settings, Samsung phones, calls that do not ring, scans that stop, and how to report a bug.',
    ogTitle: 'Clairix support',
    ogImage: '/og/support.png',
    ogImageAlt: 'We answer our own email.',
  },
  press: {
    title: 'Press – Clairix',
    description:
      'Press resources for Clairix, the Android personal-security app: boilerplate descriptions, fact sheet and media contact.',
    ogTitle: 'Clairix press kit',
    ogImage: '/og/press.png',
    ogImageAlt: 'Clairix press kit',
  },
  notFound: {
    title: 'Not found – Clairix',
    description: 'This page does not exist. Head back to Clairix, personal security for Android.',
    ogTitle: 'Clairix',
    ogImage: '/og/home.png',
    ogImageAlt: 'Clairix. Nothing hides.',
  },
} satisfies Record<string, PageMeta>;

/** OG image lines for the per-page images rendered by scripts/render-og.mjs. */
export const ogImageText: Record<string, { big: string; small: string }> = {
  features: { big: 'What it finds.\nWhat it can’t.', small: 'Every feature, with its limits.' },
  security: { big: 'Your data stays\non your phone.', small: 'The full list of exceptions, in plain words.' },
  download: { big: 'Get Clairix.\nFree.', small: 'Google Play, or verify and sideload Clairix Full.' },
  privacy: { big: 'Privacy\nPolicy', small: 'What leaves your phone, and what never does.' },
  terms: { big: 'Terms of\nService', small: 'Lawful use only. Detection is best-effort.' },
  'delete-account': { big: 'Delete your\naccount.', small: 'In the app in two taps, or by email.' },
  support: { big: 'We answer our\nown email.', small: 'support@clairix.app' },
  press: { big: 'Press', small: 'Boilerplate, fact sheet, contact.' },
};
