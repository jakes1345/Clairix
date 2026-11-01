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


# ── keygen.py, against a stand-in for the GitHub CLI ────────────────────────


class _GhCalls:
    """Records each `gh` command and the standard input it was given."""

    def __init__(self, fail_on=None):
        self.calls = []
        self.fail_on = fail_on

    def __call__(self, cmd, input=None, text=None):
        self.calls.append((cmd, input))
        return subprocess.CompletedProcess(cmd, 1 if len(self.calls) == self.fail_on else 0)


def _keygen(tmp_path, monkeypatch, gh):
    import importlib.util

    spec = importlib.util.spec_from_file_location("keygen", os.path.join(HERE, "keygen.py"))
    keygen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(keygen)
    monkeypatch.setattr(keygen, "PUB_PATH", str(tmp_path / "asset-signing.pub"))
    monkeypatch.setattr(keygen, "PEM_PATH", str(tmp_path / "asset-signing.pem"))
    monkeypatch.setattr(keygen.shutil, "which", lambda name: "/usr/bin/gh")
    monkeypatch.setattr(keygen.subprocess, "run", gh)
    return keygen


def test_keygen_puts_the_seed_in_each_environment_named_and_never_prints_it(tmp_path, monkeypatch, capsys):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

    gh = _GhCalls()
    keygen = _keygen(tmp_path, monkeypatch, gh)
    assert keygen.main(["--gh-secret", "o/r", "--gh-env", "release", "--gh-env", "assets"]) == 0
    base = ["gh", "secret", "set", "ASSET_SIGNING_KEY", "--repo", "o/r"]
    assert [cmd for cmd, _ in gh.calls] == [base + ["--env", "release"], base + ["--env", "assets"]]
    seed = gh.calls[0][1].strip()
    assert gh.calls[1][1].strip() == seed
    out = capsys.readouterr()
    assert seed not in out.out and seed not in out.err
    # The public key written is the seed's.
    key = Ed25519PrivateKey.from_private_bytes(base64.b64decode(seed))
    raw = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    assert (tmp_path / "asset-signing.pub").read_text().strip() == base64.b64encode(raw).decode().rstrip("=")


def test_keygen_without_an_environment_sets_the_repository_secret(tmp_path, monkeypatch):
    gh = _GhCalls()
    keygen = _keygen(tmp_path, monkeypatch, gh)
    assert keygen.main(["--gh-secret", "o/r"]) == 0
    assert [cmd for cmd, _ in gh.calls] == [["gh", "secret", "set", "ASSET_SIGNING_KEY", "--repo", "o/r"]]


def test_keygen_refuses_an_environment_without_a_repository(tmp_path, monkeypatch):
    gh = _GhCalls()
    keygen = _keygen(tmp_path, monkeypatch, gh)
    assert keygen.main(["--gh-env", "assets"]) == 1
    assert gh.calls == []
    assert not (tmp_path / "asset-signing.pub").exists()


def test_keygen_writes_no_public_key_when_a_secret_cannot_be_set(tmp_path, monkeypatch):
    gh = _GhCalls(fail_on=2)
    keygen = _keygen(tmp_path, monkeypatch, gh)
    assert keygen.main(["--gh-secret", "o/r", "--gh-env", "release", "--gh-env", "assets"]) == 1
    assert len(gh.calls) == 2
    assert not (tmp_path / "asset-signing.pub").exists()
    assert not (tmp_path / "asset-signing.pem").exists()
