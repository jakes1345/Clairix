"""check-bundle-signer.sh against real signatures: a bundle jar-signed by keytool/jarsigner
with a throwaway key, and APKs whose signer apksigner reports. Passes when the bundle and
every APK share one certificate, fails when one differs, when the bundle is unsigned, when
apksigner cannot verify an APK or an APK has more than one signer. A fake apksigner stands
in for the SDK's in most cases; one test uses the real apksigner and aapt2 when an Android
SDK is found. Needs a JDK's keytool and jarsigner; skipped without them."""
import hashlib
import os
import pathlib
import shutil
import subprocess
import zipfile

import pytest

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "check-bundle-signer.sh"
PASS = "throwaway"

pytestmark = pytest.mark.skipif(
    not (shutil.which("keytool") and shutil.which("jarsigner")), reason="needs keytool and jarsigner")


def keystore(tmp_path: pathlib.Path, name: str) -> tuple[pathlib.Path, str]:
    """A throwaway keystore with one self-signed key, and that certificate's SHA-256 (lowercase hex)."""
    ks = tmp_path / f"{name}.jks"
    subprocess.run(["keytool", "-genkeypair", "-keystore", str(ks), "-storepass", PASS, "-keypass", PASS,
                    "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
                    "-dname", f"CN={name}", "-noprompt"], check=True, capture_output=True)
    der = subprocess.run(["keytool", "-exportcert", "-keystore", str(ks), "-storepass", PASS, "-alias", name],
                         check=True, capture_output=True).stdout
    return ks, hashlib.sha256(der).hexdigest()


def bundle(tmp_path: pathlib.Path, name: str, signer: pathlib.Path | None) -> pathlib.Path:
    aab = tmp_path / name
    with zipfile.ZipFile(aab, "w") as z:
        z.writestr("base/manifest/AndroidManifest.xml", b"manifest\n")
        z.writestr("base/dex/classes.dex", b"dex\n")
    if signer is not None:
        subprocess.run(["jarsigner", "-keystore", str(signer), "-storepass", PASS, str(aab), signer.stem],
                       check=True, capture_output=True)
    return aab


def fake_apksigner(tmp_path: pathlib.Path, report: dict[str, list[str]], fail: set[str] = frozenset()) -> pathlib.Path:
    """Prints, for each APK by file name, one 'Signer #n certificate SHA-256 digest' line per digest given."""
    tool = tmp_path / "apksigner"
    lines = ["#!/bin/sh", '[ "$1" = verify ] && [ "$2" = --print-certs ] || exit 2', 'case "$(basename "$3")" in']
    for apk, digests in report.items():
        if apk in fail:
            lines.append(f"  {apk}) echo 'DOES NOT VERIFY' >&2; exit 1 ;;")
            continue
        body = "; ".join(f"echo 'Signer #{i} certificate DN: CN=x'; echo 'Signer #{i} certificate SHA-256 digest: {d}'"
                         for i, d in enumerate(digests, 1))
        lines.append(f"  {apk}) {body} ;;")
    lines += ["  *) exit 1 ;;", "esac"]
    tool.write_text("\n".join(lines) + "\n")
    tool.chmod(0o755)
    return tool


def touch(tmp_path: pathlib.Path, name: str) -> pathlib.Path:
    p = tmp_path / name
    p.write_bytes(b"apk\n")
    return p


def run(*args):
    env = dict(os.environ)
    env.pop("ANDROID_HOME", None)
    return subprocess.run(["bash", str(SCRIPT), *map(str, args)], capture_output=True, text=True, env=env)


def test_same_certificate_everywhere_passes_and_prints_the_digest(tmp_path):
    ks, digest = keystore(tmp_path, "release")
    aab = bundle(tmp_path, "clairix-play.aab", ks)
    apks = [touch(tmp_path, "clairix.apk"), touch(tmp_path, "clairix-comms.apk")]
    tool = fake_apksigner(tmp_path, {"clairix.apk": [digest], "clairix-comms.apk": [digest.upper()]})
    r = run("--apksigner", tool, aab, *apks)
    assert r.returncode == 0, r.stdout + r.stderr
    assert f"Release certificate SHA-256: {digest} (the bundle and 2 APK(s) agree)" in r.stdout


def test_an_apk_signed_by_another_key_fails(tmp_path):
    ks, digest = keystore(tmp_path, "release")
    _, other = keystore(tmp_path, "upload")
    aab = bundle(tmp_path, "clairix-play.aab", ks)
    apks = [touch(tmp_path, "clairix.apk"), touch(tmp_path, "clairix-comms.apk")]
    tool = fake_apksigner(tmp_path, {"clairix.apk": [digest], "clairix-comms.apk": [other]})
    r = run("--apksigner", tool, aab, *apks)
    assert r.returncode == 1
    assert f"signed by {other}, but the bundle by {digest}" in r.stdout
    assert "Release certificate" not in r.stdout


def test_a_bundle_signed_by_another_key_fails(tmp_path):
    _, digest = keystore(tmp_path, "release")
    upload, _ = keystore(tmp_path, "upload")
    aab = bundle(tmp_path, "clairix-play.aab", upload)
    tool = fake_apksigner(tmp_path, {"clairix.apk": [digest]})
    r = run("--apksigner", tool, aab, touch(tmp_path, "clairix.apk"))
    assert r.returncode == 1
    assert "but the bundle by" in r.stdout


def test_an_unsigned_bundle_fails(tmp_path):
    _, digest = keystore(tmp_path, "release")
    aab = bundle(tmp_path, "clairix-play.aab", None)
    tool = fake_apksigner(tmp_path, {"clairix.apk": [digest]})
    r = run("--apksigner", tool, aab, touch(tmp_path, "clairix.apk"))
    assert r.returncode == 1
    assert "the bundle is not signed" in r.stdout


def test_an_apk_that_does_not_verify_or_has_two_signers_fails(tmp_path):
    ks, digest = keystore(tmp_path, "release")
    aab = bundle(tmp_path, "clairix-play.aab", ks)
    tool = fake_apksigner(tmp_path, {"bad.apk": [digest], "two.apk": [digest, "ab" * 32]}, fail={"bad.apk"})
    r = run("--apksigner", tool, aab, touch(tmp_path, "bad.apk"))
    assert r.returncode == 1 and "apksigner could not verify the APK" in r.stdout
    r = run("--apksigner", tool, aab, touch(tmp_path, "two.apk"))
    assert r.returncode == 1 and "expected exactly one signer, found 2" in r.stdout


def test_bad_usage_exits_2(tmp_path):
    tool = fake_apksigner(tmp_path, {})
    assert run("--apksigner", tool, touch(tmp_path, "only.aab")).returncode == 2
    assert run("--apksigner", tool, touch(tmp_path, "x.apk"), touch(tmp_path, "y.apk")).returncode == 2
    assert run(touch(tmp_path, "z.aab"), touch(tmp_path, "z.apk")).returncode == 2  # no apksigner anywhere


def sdk_tool(name: str) -> tuple[pathlib.Path | None, pathlib.Path | None]:
    """A build-tools binary and android.jar from an Android SDK, if one is installed."""
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), "/opt/android-sdk"):
        if not root or not pathlib.Path(root, "build-tools").is_dir():
            continue
        tools = sorted(pathlib.Path(root, "build-tools").iterdir())
        platforms = sorted(pathlib.Path(root, "platforms").glob("android-*/android.jar"))
        if tools and platforms and (tools[-1] / name).exists():
            return tools[-1] / name, platforms[-1]
    return None, None


@pytest.mark.skipif(sdk_tool("apksigner")[0] is None, reason="needs an Android SDK with build-tools and a platform")
def test_real_apksigner_on_a_real_apk(tmp_path):
    apksigner, android_jar = sdk_tool("apksigner")
    aapt2 = apksigner.parent / "aapt2"
    manifest = tmp_path / "AndroidManifest.xml"
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.example" '
                        'android:versionCode="1"><uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35"/>'
                        '<application/></manifest>\n')
    ks, digest = keystore(tmp_path, "release")
    other, _ = keystore(tmp_path, "upload")
    aab = bundle(tmp_path, "clairix-play.aab", ks)
    signed = {}
    for name, key in (("same.apk", ks), ("other.apk", other)):
        apk = tmp_path / name
        subprocess.run([str(aapt2), "link", "-o", str(apk), "-I", str(android_jar), "--manifest", str(manifest)],
                       check=True, capture_output=True)
        subprocess.run([str(apksigner), "sign", "--ks", str(key), "--ks-pass", f"pass:{PASS}", str(apk)],
                       check=True, capture_output=True)
        signed[name] = apk
    r = run("--apksigner", apksigner, aab, signed["same.apk"])
    assert r.returncode == 0, r.stdout + r.stderr
    assert f"Release certificate SHA-256: {digest}" in r.stdout
    assert run("--apksigner", apksigner, aab, signed["other.apk"]).returncode == 1


def test_signers_named_by_platform_range_are_read(tmp_path):
    """Newer build-tools name a v3 signer by the versions it covers, the same certificate on several lines."""
    ks, digest = keystore(tmp_path, "release")
    aab = bundle(tmp_path, "clairix-play.aab", ks)
    tool = tmp_path / "apksigner"
    tool.write_text("#!/bin/sh\n"
                    "echo 'Verifies'\n"
                    "echo 'Signer (minSdkVersion=31, maxSdkVersion=32) certificate DN: CN=x'\n"
                    f"echo 'Signer (minSdkVersion=31, maxSdkVersion=32) certificate SHA-256 digest: {digest.upper()}'\n"
                    f"echo 'Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {digest}'\n")
    tool.chmod(0o755)
    r = run("--apksigner", tool, aab, touch(tmp_path, "ranged.apk"))
    assert r.returncode == 0, r.stdout + r.stderr
    assert f"Release certificate SHA-256: {digest}" in r.stdout


def test_signers_named_by_scheme_are_read(tmp_path):
    """What CI's build-tools print: the scheme before "Signer", a colon after it, one line per scheme."""
    ks, digest = keystore(tmp_path, "release")
    aab = bundle(tmp_path, "clairix-play.aab", ks)
    tool = tmp_path / "apksigner"
    tool.write_text("#!/bin/sh\n"
                    "echo 'V3.0 Signer: certificate DN: CN=release'\n"
                    f"echo 'V3.0 Signer: certificate SHA-256 digest: {digest}'\n"
                    "echo 'V3.0 Signer: certificate SHA-1 digest: 67d414cc4b5cf776e5962110001d294d13dede10'\n"
                    f"echo 'V3.1 Signer: certificate SHA-256 digest: {digest}'\n")
    tool.chmod(0o755)
    r = run("--apksigner", tool, aab, touch(tmp_path, "scheme.apk"))
    assert r.returncode == 0, r.stdout + r.stderr
    assert f"Release certificate SHA-256: {digest}" in r.stdout
