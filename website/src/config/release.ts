/**
 * Release configuration for Clairix Full and the support link.
 *
 * This is the single source for the values that change with each Clairix Full
 * release. Leave a value as an empty string until it exists: the site hides or
 * replaces anything that depends on it, so a half-filled release never shows a
 * broken link.
 *
 * - While `apkUrl` or `sha256` is empty, /download shows "Coming with the first
 *   beta" instead of a download button and checksum.
 * - While `openCollectiveUrl` is empty, the "Support Clairix" link is hidden in
 *   the footer and on /support.
 */
export const release = {
  /** Clairix Full version shown next to the download, e.g. "2.7.0". */
  version: '3.0.0 Beta 2',
  /** Absolute HTTPS URL of the signed Clairix Full APK. */
  apkUrl: 'https://github.com/jakes1345/Clairix/releases/download/v3.0.0-beta.2/clairix-v3.0.0-beta.2.apk',
  /** Lower-case hex SHA-256 of that exact APK (64 characters). */
  sha256: '3db841d39aeddfe14fb07caa661a7f518ada0f4d84b234931433c21a7d82b5d3',
  /** Optional: absolute URL of the release notes for this version. */
  releaseNotesUrl: 'https://github.com/jakes1345/Clairix/releases/tag/v3.0.0-beta.2',
} as const;

export const funding = {
  /** Open Collective page, e.g. "https://opencollective.com/clairix". */
  openCollectiveUrl: '',
} as const;

const isHttps = (u: string) => /^https:\/\/\S+$/.test(u);

/** True only when the APK link and a well-formed checksum are both set. */
export const fullReleaseReady: boolean =
  isHttps(release.apkUrl) && /^[a-f0-9]{64}$/i.test(release.sha256);

export const openCollectiveUrl: string | null = isHttps(funding.openCollectiveUrl)
  ? funding.openCollectiveUrl
  : null;

export const releaseNotesUrl: string | null = isHttps(release.releaseNotesUrl)
  ? release.releaseNotesUrl
  : null;
