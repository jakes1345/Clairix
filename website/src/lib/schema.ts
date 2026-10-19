import { SITE_URL, PLAY_LIVE, PLAY_URL, SUPPORT_EMAIL } from '../config/site';
import { release, fullReleaseReady } from '../config/release';

// Before the Play listing: the APK itself to download, the download page to install from.
const downloadUrl = PLAY_LIVE ? PLAY_URL : fullReleaseReady ? release.apkUrl : `${SITE_URL}/download`;
const installUrl = PLAY_LIVE ? PLAY_URL : `${SITE_URL}/download`;

/** Organization. No ratings, counts, people or addresses: none exist to cite. */
export const organization = {
  '@context': 'https://schema.org',
  '@type': 'Organization',
  '@id': `${SITE_URL}/#organization`,
  name: 'Clairix',
  url: SITE_URL,
  logo: `${SITE_URL}/icon-192.png`,
  email: SUPPORT_EMAIL,
  contactPoint: {
    '@type': 'ContactPoint',
    contactType: 'customer support',
    email: SUPPORT_EMAIL,
    availableLanguage: 'English',
  },
};

/** SoftwareApplication with a single free offer. No aggregateRating until real Play ratings exist. */
export const softwareApplication = {
  '@context': 'https://schema.org',
  '@type': 'SoftwareApplication',
  '@id': `${SITE_URL}/#app`,
  name: 'Clairix',
  description:
    'Find hidden cameras, Bluetooth trackers and spyware with the phone in your pocket. Private encrypted calls. No ads, no trackers, data stays on your phone.',
  operatingSystem: 'Android 12+',
  applicationCategory: 'SecurityApplication',
  url: SITE_URL,
  downloadUrl,
  installUrl,
  image: `${SITE_URL}/og/home.png`,
  inLanguage: 'en',
  offers: {
    '@type': 'Offer',
    price: '0',
    priceCurrency: 'USD',
  },
  publisher: { '@id': `${SITE_URL}/#organization` },
};

export function faqPage(items: { q: string; a: string }[]) {
  return {
    '@context': 'https://schema.org',
    '@type': 'FAQPage',
    mainEntity: items.map((i) => ({
      '@type': 'Question',
      name: i.q,
      acceptedAnswer: { '@type': 'Answer', text: i.a },
    })),
  };
}

/** Strip the small amount of Markdown/HTML used in answers to plain text. */
export function toPlainText(s: string): string {
  return s
    .replace(/<[^>]+>/g, '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();
}
