#!/usr/bin/env python3
"""Makes the asset signing key: see tools/signing/README.md.

Writes android/asset-signing.pub (raw public key, base64 without padding, as
the app is built with) and android/asset-signing.pem (the same key as PEM for
openssl), and prints the secret seed once for the ASSET_SIGNING_KEY secret.
Refuses to overwrite an existing public key unless --force is given.
"""
import argparse
import base64
import os
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PUB_PATH = os.path.join(ROOT, "android", "asset-signing.pub")
PEM_PATH = os.path.join(ROOT, "android", "asset-signing.pem")


def b64_nopad(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii").rstrip("=")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Generate the Ed25519 asset signing key.")
    ap.add_argument("--force", action="store_true", help="replace an existing public key (a rotation)")
    args = ap.parse_args(argv)
    if os.path.exists(PUB_PATH) and not args.force:
        print(f"{PUB_PATH} exists; pass --force to rotate the key", file=sys.stderr)
        return 1
    key = Ed25519PrivateKey.generate()
    seed = key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
    public = key.public_key()
    raw = public.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    pem = public.public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    with open(PUB_PATH, "w", encoding="ascii") as f:
        f.write(b64_nopad(raw) + "\n")
    with open(PEM_PATH, "wb") as f:
        f.write(pem)
    print(f"Public key written to {PUB_PATH} and {PEM_PATH}; commit both.")
    print()
    print("Repository secret ASSET_SIGNING_KEY (shown once; store it safely):")
    print(base64.b64encode(seed).decode("ascii"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
