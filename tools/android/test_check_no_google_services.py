"""check-no-google-services.sh against made-up inputs: a clean dependency report and APK
pass, one with a Play services artifact, class or packaged file fails, and inputs that
show nothing (an empty configuration, an APK without dex) fail too. Needs unzip."""
import pathlib
import shutil
import subprocess
import zipfile

import pytest

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "check-no-google-services.sh"

pytestmark = pytest.mark.skipif(not shutil.which("unzip"), reason="needs unzip")

CLEAN_DEPS = """
fossSuiteReleaseRuntimeClasspath - Runtime classpath of '/fossSuiteRelease'.
+--- androidx.core:core-ktx:1.13.1
|    \\--- androidx.core:core:1.13.1
+--- com.google.zxing:core:3.5.4
\\--- com.squareup.okhttp3:okhttp:4.12.0
"""

GMS_DEPS = CLEAN_DEPS + """+--- com.google.android.gms:play-services-location:21.3.0
|    +--- com.google.android.gms:play-services-base:18.5.0
"""

FIREBASE_DEPS = CLEAN_DEPS + "+--- com.google.firebase:firebase-messaging:24.0.0\n"

EMPTY_DEPS = """
fossSuiteReleaseRuntimeClasspath - Runtime classpath of '/fossSuiteRelease'.
No dependencies
"""


def run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["bash", str(SCRIPT), *args], capture_output=True, text=True)


def write(tmp_path: pathlib.Path, name: str, text: str) -> str:
    path = tmp_path / name
    path.write_text(text)
    return str(path)


def apk(tmp_path: pathlib.Path, name: str, entries: dict[str, bytes]) -> str:
    path = tmp_path / name
    with zipfile.ZipFile(path, "w") as z:
        for arcname, data in entries.items():
            z.writestr(arcname, data)
    return str(path)


DEX_CLEAN = b"dex\n035\x00Landroidx/core/app/NotificationCompat;\x00Lapp/clairix/MainActivity;\x00"
DEX_GMS = DEX_CLEAN + b"Lcom/google/android/gms/location/LocationServices;\x00"


def test_clean_dependencies_and_apk_pass(tmp_path):
    result = run("--deps", write(tmp_path, "deps.txt", CLEAN_DEPS),
                 "--apk", apk(tmp_path, "a.apk", {"classes.dex": DEX_CLEAN, "classes2.dex": DEX_CLEAN}))
    assert result.returncode == 0, result.stdout + result.stderr


@pytest.mark.parametrize("deps", [GMS_DEPS, FIREBASE_DEPS])
def test_a_google_artifact_fails(tmp_path, deps):
    result = run("--deps", write(tmp_path, "deps.txt", deps))
    assert result.returncode == 1
    assert "com.google." in result.stdout


def test_an_empty_configuration_fails(tmp_path):
    result = run("--deps", write(tmp_path, "deps.txt", EMPTY_DEPS))
    assert result.returncode == 1
    assert "lists no dependencies" in result.stdout


def test_a_play_services_class_in_any_dex_fails(tmp_path):
    result = run("--apk", apk(tmp_path, "a.apk", {"classes.dex": DEX_CLEAN, "classes3.dex": DEX_GMS}))
    assert result.returncode == 1
    assert "Lcom/google/android/gms/location/LocationServices;" in result.stdout


def test_a_packaged_play_services_file_fails(tmp_path):
    result = run("--apk", apk(tmp_path, "a.apk", {"classes.dex": DEX_CLEAN,
                                                   "play-services-location.properties": b"version=21.3.0\n"}))
    assert result.returncode == 1
    assert "play-services-location.properties" in result.stdout


def test_an_apk_without_dex_fails(tmp_path):
    result = run("--apk", apk(tmp_path, "a.apk", {"AndroidManifest.xml": b"x"}))
    assert result.returncode == 1


def test_no_input_is_bad_usage():
    assert run().returncode == 2
    assert run("stray.txt").returncode == 2
