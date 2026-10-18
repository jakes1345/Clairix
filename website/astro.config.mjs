// @ts-check
import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';
import { satteri } from '@astrojs/markdown-satteri';

export default defineConfig({
  site: 'https://clairix.app',
  output: 'static',
  // /features.html is served by Cloudflare Pages at /features, matching the
  // paths in the copy and the canonical URLs.
  trailingSlash: 'never',
  build: {
    format: 'file',
    // External CSS only: the Content-Security-Policy allows 'self' styles,
    // never inline ones.
    inlineStylesheets: 'never',
  },
  vite: {
    build: {
      // Keep every script and asset as its own file (CSP: script-src 'self').
      assetsInlineLimit: 0,
    },
  },
  markdown: {
    // Shiki writes inline style attributes, which the CSP would block.
    syntaxHighlight: false,
    processor: satteri({ features: { headingAttributes: true } }),
  },
  integrations: [
    sitemap({
      filter: (page) => !page.endsWith('/404'),
    }),
  ],
  devToolbar: { enabled: false },
});
