import base64
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SIGN = os.path.join(HERE, "sign.py")


def _keypair():
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

    key = Ed25519PrivateKey.generate()
    seed = key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
    raw = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return base64.b64encode(seed).decode(), base64.b64encode(raw).decode().rstrip("=")


def test_sign_then_verify_and_tamper(tmp_path):
    seed, pub = _keypair()
    pubfile = tmp_path / "asset-signing.pub"
    pubfile.write_text(pub + "\n")
    target = tmp_path / "SHA256SUMS.txt"
    target.write_bytes(b"abc  clairix-v1.apk\n")
    env = dict(os.environ, ASSET_SIGNING_KEY=seed)
    subprocess.run([sys.executable, SIGN, str(target)], check=True, env=env)
    sig = (tmp_path / "SHA256SUMS.txt.sig").read_text().strip()
    assert "=" not in sig and len(base64.b64decode(sig + "=" * (-len(sig) % 4))) == 64
    assert subprocess.run([sys.executable, SIGN, "--verify", str(pubfile), str(target)]).returncode == 0
    target.write_bytes(b"abd  clairix-v1.apk\n")
    assert subprocess.run([sys.executable, SIGN, "--verify", str(pubfile), str(target)]).returncode == 1


def test_signing_without_a_key_refuses(tmp_path):
    target = tmp_path / "f"
    target.write_bytes(b"x")
    env = {k: v for k, v in os.environ.items() if k != "ASSET_SIGNING_KEY"}
    assert subprocess.run([sys.executable, SIGN, str(target)], env=env).returncode != 0
    assert not (tmp_path / "f.sig").exists()
