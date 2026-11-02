"""check-16k-alignment.sh against real ELF files: a shared object linked for 16 KB pages
passes, one linked for 4 KB pages fails, an archive without a 64-bit library fails, and a
failing zipalign fails the APK. Needs gcc (or cc), readelf and zip; skipped without them."""
import os
import pathlib
import shutil
import subprocess
import sys
import zipfile

import pytest

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "check-16k-alignment.sh"
CC = shutil.which("cc") or shutil.which("gcc")

pytestmark = pytest.mark.skipif(
    not (CC and shutil.which("readelf")), reason="needs a C compiler and readelf")


def build_so(tmp_path: pathlib.Path, name: str, page_size: int) -> pathlib.Path:
    src = tmp_path / f"{name}.c"
    src.write_text("int clairix_answer(void) { return 42; }\n")
    out = tmp_path / name
    subprocess.run([CC, "-shared", "-fPIC", f"-Wl,-z,max-page-size={page_size}", "-o", str(out), str(src)],
                   check=True)
    return out


def archive(path: pathlib.Path, entries: dict[str, pathlib.Path]) -> pathlib.Path:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        for arcname, src in entries.items():
            z.write(src, arcname)
        z.writestr("classes.dex", b"dex\n")
    return path


def fake_zipalign(tmp_path: pathlib.Path, exit_code: int) -> pathlib.Path:
    tool = tmp_path / "zipalign"
    verdict = ("echo 'Verification FAILED'; echo '3 lib/arm64-v8a/libx.so (BAD - 2)'" if exit_code
               else "echo 'Verification succesful'")
    # Records its arguments, so a test can check the exact flags the script passes.
    tool.write_text(f"#!/bin/sh\necho \"$*\" >> '{tmp_path / 'zipalign.calls'}'\n[ $# -ge 1 ] || exit 2\n"
                    f"{verdict}\nexit {exit_code}\n")
    tool.chmod(0o755)
    return tool


def run(*args, env=None):
    e = dict(os.environ, **(env or {}))
    e.pop("ANDROID_HOME", None)
    return subprocess.run(["bash", str(SCRIPT), *map(str, args)], capture_output=True, text=True, env=e)


def test_aligned_library_passes_and_a_4k_library_fails(tmp_path):
    good = build_so(tmp_path, "libgood.so", 16384)
    bad = build_so(tmp_path, "libbad.so", 4096)
    ok_tool = fake_zipalign(tmp_path, 0)
    apk = archive(tmp_path / "good.apk", {"lib/arm64-v8a/libgood.so": good, "lib/x86_64/libgood.so": good,
                                          "lib/armeabi-v7a/libbad.so": bad})  # 32-bit is not checked
    r = run("--zipalign", ok_tool, apk)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "2 64-bit libraries read" in r.stdout and "built for 16 KB pages" in r.stdout
    assert (tmp_path / "zipalign.calls").read_text() == f"-c -P 16 -v 4 {apk}\n"

    apk2 = archive(tmp_path / "bad.apk", {"lib/arm64-v8a/libgood.so": good, "lib/x86_64/libbad.so": bad})
    r = run("--zipalign", ok_tool, apk2)
    assert r.returncode == 1
    assert "lib/x86_64/libbad.so has a LOAD segment aligned to 0x1000" in r.stdout
    assert "built for 16 KB pages" not in r.stdout


def test_bundle_reads_base_lib_and_skips_zipalign(tmp_path):
    good = build_so(tmp_path, "libgood.so", 16384)
    bad = build_so(tmp_path, "libbad.so", 4096)
    aab = archive(tmp_path / "app.aab", {"base/lib/arm64-v8a/libgood.so": good})
    r = run(aab)  # no zipalign anywhere: a bundle does not need it
    assert r.returncode == 0, r.stdout + r.stderr
    aab2 = archive(tmp_path / "bad.aab", {"base/lib/arm64-v8a/libbad.so": bad})
    assert run(aab2).returncode == 1


def test_no_64_bit_library_is_a_failure_not_a_pass(tmp_path):
    bad = build_so(tmp_path, "libbad.so", 4096)
    apk = archive(tmp_path / "arm32.apk", {"lib/armeabi-v7a/libbad.so": bad})
    r = run("--zipalign", fake_zipalign(tmp_path, 0), apk)
    assert r.returncode == 1 and "the check proved nothing" in r.stdout


def test_zipalign_failure_fails_the_apk_and_a_missing_zipalign_is_usage(tmp_path):
    good = build_so(tmp_path, "libgood.so", 16384)
    apk = archive(tmp_path / "good.apk", {"lib/arm64-v8a/libgood.so": good})
    r = run("--zipalign", fake_zipalign(tmp_path, 1), apk)
    assert r.returncode == 1 and "zipalign -c -P 16 -v 4 failed" in r.stdout and "BAD" in r.stdout
    r = run(apk)
    assert r.returncode == 2 and "zipalign not found" in r.stderr
    assert run().returncode == 2
    assert run("--zipalign", fake_zipalign(tmp_path, 0), tmp_path / "missing.apk").returncode == 2


def test_runs_with_the_interpreter_named_in_the_shebang():
    assert SCRIPT.read_text().startswith("#!/usr/bin/env bash\n")
    assert sys.platform != "win32"
