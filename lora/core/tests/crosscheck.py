# Independent model of the Clairix LoRa constructions, written against pyca/cryptography rather
# than the C++ core, checked against tests/known_answers.h in CI: python3 tests/crosscheck.py tests/known_answers.h
import hashlib, hmac, struct, sys
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

def sha512(b): return hashlib.sha512(b).digest()
def hkdf(ikm, salt, info, n=32): return HKDF(hashes.SHA512(), n, salt, info).derive(ikm)
def pub(sk): return X25519PrivateKey.from_private_bytes(sk).public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
def dh(sk, pk): return X25519PrivateKey.from_private_bytes(sk).exchange(X25519PublicKey.from_public_bytes(pk))
def chacha(key, data): return Cipher(algorithms.ChaCha20(key, b"\0"*16), None).encryptor().update(data)
A = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
def b32(b):
    bits = "".join(f"{x:08b}" for x in b); bits += "0" * (-len(bits) % 5)
    return "".join(A[int(bits[i:i+5], 2)] for i in range(0, len(bits), 5))
def seq(n, s): return bytes((s + i) & 0xff for i in range(n))

out = {}
netkey = sha512(b"clairix-lora public network v1")[:32]
out["KA_PUBLIC_KEY"] = netkey.hex()
nid = hkdf(netkey, b"clairix-lora-v1", b"id", 8)
out["KA_PUBLIC_ID"] = nid.hex()
h = hmac.new(nid, b"hop" + struct.pack(">I", 0), hashlib.sha512).digest()
out["KA_PUBLIC_CHANNEL"] = str(((h[0] << 8) | h[1]) % 15)
out["KA_PUBLIC_HINT"] = str(hmac.new(nid, bytes([0, 0, 0x30, 0x39, 0xC1, 2, 3, 4]), hashlib.sha512).digest()[0])
sa, sb, se = seq(32, 1), seq(32, 101), seq(32, 201)
Apub, Bpub, E = pub(sa), pub(sb), pub(se)
inner = bytes([1]) + struct.pack(">II", 0x11223344, 29883000) + b"Clairix Lite" + b"\x80"
inner += b"\0" * (32 - len(inner))
dh1, dh2 = dh(se, Bpub), dh(sa, Bpub)
k1 = hkdf(dh1, E + Bpub, b"clairix-lora-lite-v1 sender")
k2 = hkdf(dh1 + dh2, E + Bpub + Apub, b"clairix-lora-lite-v1 message")
head = bytes([1]) + E + chacha(k1, Apub)
out["KA_LITE_BODY"] = (head + ChaCha20Poly1305(k2).encrypt(b"\0"*12, inner, head)).hex()
fp = b32(sha512(b"clairix-lora-id" + Apub)[:5]); out["KA_FINGERPRINT"] = fp[:4] + "-" + fp[4:]
nA, nB = seq(16, 50), seq(16, 70)
out["KA_PAIR_DIGITS"] = str(struct.unpack(">I", sha512(b"clairix-lora-pair-sas" + Apub + Bpub + nA + nB)[:4])[0] % 1000000)
out["KA_PAIR_COMMIT"] = sha512(b"clairix-lora-pair-commit" + Apub + nA)[:32].hex()
code = b32(netkey + sha512(b"clairix-lora-netcode" + netkey)[:2])
out["KA_PUBLIC_CODE"] = " ".join(code[i:i+5] for i in range(0, len(code), 5))

# Phone <-> radio link frames, big-endian, from lora/core/include/clx/link.h's layout.
def link_bundle(tag, hops, created_ms, env): return tag + bytes([hops]) + struct.pack(">QH", created_ms, len(env)) + env
fw = b"1.0.0"
out["KA_LINK_INFO"] = (bytes([0x40, 1, 2, len(fw)]) + fw + struct.pack(">BbBB", 2, 20, 4, 0xFF) + seq(8, 0xA0)
                       + bytes([2]) + struct.pack(">HHIIIH", 3987, 7, 123456, 42, 99, 13)).hex()
out["KA_LINK_CONFIG"] = (struct.pack(">BBbB", 0x41, 0, 14, 3) + seq(32, 0) + struct.pack(">Q", 1793577600123)).hex()
lb = link_bundle(seq(16, 0x10), 2, 1793574000000, bytes.fromhex("deadbeef01"))
out["KA_LINK_BUNDLE_OUT"] = (bytes([0x42, 1]) + lb).hex()
out["KA_LINK_BUNDLE_IN"] = (bytes([0x43]) + struct.pack(">Q", 0x0102030405060708) + lb).hex()
out["KA_LINK_GOT"] = (bytes([0x44]) + struct.pack(">Q", 0x0102030405060708)).hex()
out["KA_LINK_LITE_OUT"] = bytes([0x45, 5, 1, 2, 3]).hex()
out["KA_LINK_LITE_IN"] = (bytes([0x46]) + struct.pack(">hb", -117, round(-7.25 * 4)) + bytes([9, 8, 7])).hex()
out["KA_LINK_PAIR_OUT"] = bytes([0x47, 4, 5]).hex()
out["KA_LINK_PAIR_IN"] = (bytes([0x48]) + struct.pack(">hb", -40, round(9.5 * 4)) + bytes([6])).hex()
out["KA_LINK_HELLO"] = bytes([0x49, 1]).hex()

# Compare with the C++ known answers.
cpp = {}
for line in open(sys.argv[1]):
    if line.startswith("#define"):
        _, k, v = line.split(" ", 2); cpp[k] = v.strip().strip('"')
bad = [k for k in out if cpp.get(k) != out[k]]
for k in bad: print("MISMATCH", k, cpp.get(k), out[k])
print("all match" if not bad else f"{len(bad)} mismatches")
sys.exit(1 if bad else 0)
