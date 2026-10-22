#!/usr/bin/env python3
"""Signs files with the asset signing key, or verifies signatures.

    sign.py FILE...                       writes FILE.sig for each file, signing with the
                                          key in $ASSET_SIGNING_KEY (base64 seed)
    sign.py --verify PUBKEY FILE...       checks FILE.sig for each file against a public
                                          key file (raw base64, as android/asset-signing.pub)

A signature is the 64-byte Ed25519 signature over the file's exact bytes,
base64 without padding, one line: the form the app's crypto crate reads.
"""
import argparse
import base64
import os
import sys

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey


def b64_nopad(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii").rstrip("=")


def b64_decode(text: str) -> bytes:
    text = text.strip()
    return base64.b64decode(text + "=" * (-len(text) % 4))


def load_private(seed_b64: str) -> Ed25519PrivateKey:
    seed = b64_decode(seed_b64)
    if len(seed) != 32:
        raise SystemExit("ASSET_SIGNING_KEY must be the base64 of a 32-byte Ed25519 seed")
    return Ed25519PrivateKey.from_private_bytes(seed)


def load_public(path: str) -> Ed25519PublicKey:
    with open(path, "r", encoding="ascii") as f:
        raw = b64_decode(f.read())
    if len(raw) != 32:
        raise SystemExit(f"{path} must hold a 32-byte Ed25519 public key, base64")
    return Ed25519PublicKey.from_public_bytes(raw)


def sign_file(key: Ed25519PrivateKey, path: str) -> str:
    with open(path, "rb") as f:
        data = f.read()
    sig = b64_nopad(key.sign(data))
    with open(path + ".sig", "w", encoding="ascii") as f:
        f.write(sig + "\n")
    return sig


def verify_file(public: Ed25519PublicKey, path: str) -> bool:
    with open(path, "rb") as f:
        data = f.read()
    with open(path + ".sig", "r", encoding="ascii") as f:
        sig = b64_decode(f.read())
    try:
        public.verify(sig, data)
        return True
    except InvalidSignature:
        return False


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Sign or verify release assets.")
    ap.add_argument("--verify", metavar="PUBKEY", help="verify FILE.sig for each file against this public key file")
    ap.add_argument("--key-env", default="ASSET_SIGNING_KEY", help="environment variable holding the base64 seed")
    ap.add_argument("files", nargs="+")
    args = ap.parse_args(argv)
    if args.verify:
        public = load_public(args.verify)
        bad = [p for p in args.files if not verify_file(public, p)]
        for p in bad:
            print(f"BAD signature: {p}", file=sys.stderr)
        for p in args.files:
            if p not in bad:
                print(f"ok: {p}")
        return 1 if bad else 0
    seed = os.environ.get(args.key_env, "")
    if not seed.strip():
        raise SystemExit(f"{args.key_env} is not set: the signing key is required to publish (see tools/signing/README.md)")
    key = load_private(seed)
    for p in args.files:
        sign_file(key, p)
        print(f"signed: {p} -> {p}.sig")
    return 0


if __name__ == "__main__":
    sys.exit(main())
