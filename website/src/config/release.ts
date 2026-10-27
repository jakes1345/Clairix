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
 * - While `sumsUrl` is empty, /download leaves out the checksum-list check; while
 *   `sigUrl` is empty, it says the list is not signed yet rather than how to
 *   check a signature.
 * - While `openCollectiveUrl` is empty, the "Support Clairix" link is hidden in
 *   the footer and on /support.
 */
export const release = {
  /** Clairix Full version shown next to the download, e.g. "2.7.0". */
  version: '3.0.0 Beta 8',
  /** Absolute HTTPS URL of the signed Clairix Full APK. */
  apkUrl: 'https://github.com/jakes1345/Clairix/releases/download/v3.0.0-beta.8/clairix-v3.0.0-beta.8.apk',
  /** Lower-case hex SHA-256 of that exact APK (64 characters). */
  sha256: 'd5c460e4a728cdf882d1ef455bb2664eca2a0d2d397506db28e86e4771ab8a56',
  /** Optional: absolute URL of the release notes for this version. */
  releaseNotesUrl: 'https://github.com/jakes1345/Clairix/releases/tag/v3.0.0-beta.8',
  /** Absolute URL of the release's `SHA256SUMS.txt`, the checksums of every file in it; empty when it has none. */
  sumsUrl: 'https://github.com/jakes1345/Clairix/releases/download/v3.0.0-beta.8/SHA256SUMS.txt',
  /**
   * Absolute URL of `SHA256SUMS.txt.sig`, the signature over that list from the
   * release key (tools/signing). Empty until the first release signed with it.
   */
  sigUrl: 'https://github.com/jakes1345/Clairix/releases/download/v3.0.0-beta.8/SHA256SUMS.txt.sig',
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

export const sumsUrl: string | null = isHttps(release.sumsUrl) ? release.sumsUrl : null;

export const sigUrl: string | null = isHttps(release.sigUrl) ? release.sigUrl : null;

/** True when the release's checksum list is published together with its signature. */
export const releaseSigned: boolean = sumsUrl !== null && sigUrl !== null;
