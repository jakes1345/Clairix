#!/usr/bin/env python3
"""Makes the asset signing key: see tools/signing/README.md.

Writes android/asset-signing.pub (raw public key, base64 without padding, as
the app is built with) and android/asset-signing.pem (the same key as PEM for
openssl), and hands over the secret seed for the ASSET_SIGNING_KEY secret:
with --gh-secret OWNER/REPO it goes straight into that repository's secret
through the GitHub CLI and is never shown; without it, it is printed once.
Refuses to overwrite an existing public key unless --force is given.

Run this yourself, in your own terminal. The seed must not pass through a
browser, a chat, an AI session or anything that keeps a transcript: whatever
sees it can sign releases as you.
"""
import argparse
import base64
import os
import shutil
import subprocess
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
    ap.add_argument(
        "--gh-secret", metavar="OWNER/REPO",
        help="store the seed as that repository's ASSET_SIGNING_KEY secret with `gh secret set`, never printing it",
    )
    args = ap.parse_args(argv)
    if os.path.exists(PUB_PATH) and not args.force:
        print(f"{PUB_PATH} exists; pass --force to rotate the key", file=sys.stderr)
        return 1
    if args.gh_secret and shutil.which("gh") is None:
        print("--gh-secret needs the GitHub CLI (gh) on PATH, signed in to an account that administers the repository", file=sys.stderr)
        return 1
    key = Ed25519PrivateKey.generate()
    seed = key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
    seed_b64 = base64.b64encode(seed).decode("ascii")
    public = key.public_key()
    raw = public.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    pem = public.public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    if args.gh_secret:
        # The seed goes to gh on its standard input: not on the command line,
        # where every process on the machine could read it, and not on screen.
        done = subprocess.run(
            ["gh", "secret", "set", "ASSET_SIGNING_KEY", "--repo", args.gh_secret],
            input=seed_b64 + "\n", text=True,
        )
        if done.returncode != 0:
            print("gh secret set failed; nothing was written, run again", file=sys.stderr)
            return 1
    with open(PUB_PATH, "w", encoding="ascii") as f:
        f.write(b64_nopad(raw) + "\n")
    with open(PEM_PATH, "wb") as f:
        f.write(pem)
    print(f"Public key written to {PUB_PATH} and {PEM_PATH}; commit both.")
    if args.gh_secret:
        print(f"Secret ASSET_SIGNING_KEY set on {args.gh_secret}. The seed exists nowhere else: a lost secret means a new key (--force) and a new release.")
    else:
        print()
        print("Repository secret ASSET_SIGNING_KEY (shown once; store it safely):")
        print(seed_b64)
    return 0


if __name__ == "__main__":
    sys.exit(main())
