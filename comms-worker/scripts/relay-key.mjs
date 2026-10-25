// Prints a fresh RELAY_KEY (base64 of a 32-byte Ed25519 seed) and the public
// key other relays will verify this relay's forwarded requests with.
//
//   npm run relay-key
//   npx wrangler secret put RELAY_KEY      # paste the first line
//
// The seed is the relay's identity towards other relays: keep it only in the
// Worker's secrets. Replacing it is safe; other relays fetch the new public
// key from GET / when the old one stops verifying.
import { webcrypto } from "node:crypto";

const seed = webcrypto.getRandomValues(new Uint8Array(32));
const prefix = Uint8Array.from([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20]);
const der = new Uint8Array(48);
der.set(prefix, 0);
der.set(seed, 16);
const key = await webcrypto.subtle.importKey("pkcs8", der, { name: "Ed25519" }, true, ["sign"]);
const jwk = await webcrypto.subtle.exportKey("jwk", key);
console.log(`RELAY_KEY=${Buffer.from(seed).toString("base64")}`);
console.log(`public key (published at GET /): ${Buffer.from(jwk.x, "base64url").toString("base64")}`);
