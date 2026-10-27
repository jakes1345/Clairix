/**
 * The invite page (/i). The invite itself is in the URL fragment, which the
 * browser never sends to this site, so nothing about it reaches the server.
 * The page reads it, shows who sent it and on which relay, and offers to open
 * it in Clairix through an Android intent that names Clairix's package: no
 * other app can take it, as any app could take a link scheme of its own.
 */

const PACKAGE = 'app.clairix';
const REQUIRED = ['r', 'n', 'k', 'c', 's', 'g', 'i'];

interface Invite {
  /** The whole invite as the app reads it: clairix:v1?… */
  code: string;
  relayHost: string;
  number: string;
  name: string;
}

function parse(fragment: string): Invite | null {
  let code: string;
  try {
    code = decodeURIComponent(fragment.replace(/^#/, ''));
  } catch {
    return null;
  }
  if (!code.startsWith('clairix:v1?')) return null;
  const params = new URLSearchParams(code.slice('clairix:v1?'.length));
  if (REQUIRED.some((k) => !params.get(k))) return null;
  const number = params.get('n')!.replace(/\D/g, '');
  if (!/^[1-9]\d{8}$/.test(number)) return null;
  let relayHost: string;
  try {
    const relay = new URL(params.get('r')!);
    if (relay.protocol !== 'https:' && relay.protocol !== 'http:') return null;
    relayHost = relay.host;
  } catch {
    return null;
  }
  return { code, relayHost, number, name: (params.get('d') ?? '').slice(0, 40) };
}

function formatNumber(n: string): string {
  return `${n.slice(0, 3)} ${n.slice(3, 6)} ${n.slice(6)}`;
}

function show(id: string, on: boolean): void {
  const el = document.getElementById(id);
  if (el) el.hidden = !on;
}

function setText(id: string, text: string): void {
  const el = document.getElementById(id);
  if (el) el.textContent = text;
}

function render(): void {
  const invite = parse(location.hash);
  show('invite-bad', invite === null);
  show('invite-good', invite !== null);
  if (!invite) return;
  const who = invite.name || formatNumber(invite.number);
  setText('invite-from', who);
  setText('invite-number', formatNumber(invite.number));
  setText('invite-relay', invite.relayHost);
  setText('invite-who', invite.name || 'them');
  const open = document.getElementById('invite-open') as HTMLAnchorElement | null;
  if (open) {
    open.href = `intent://clairix.app/i#Intent;scheme=https;package=${PACKAGE};S.c=${encodeURIComponent(invite.code)};end`;
  }
  const text = document.getElementById('invite-text') as HTMLTextAreaElement | null;
  if (text) text.value = invite.code;
  const copy = document.getElementById('invite-copy');
  copy?.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(invite.code);
      setText('invite-copy', 'Copied');
    } catch {
      text?.select();
    }
  });
}

render();
window.addEventListener('hashchange', render);
