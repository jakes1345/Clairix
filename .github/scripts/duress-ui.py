#!/usr/bin/env python3
"""Drives Clairix's lock screen from outside the app, for the emulator's duress test.

The duress passcode wipes the app and ends the process it ran in, which is also
where an instrumentation test runs, so the part of the test that types the code
cannot be an instrumentation: this script types it the way a person does, through
`adb shell input`, reading the screen with `uiautomator dump`, and checks what the
person holding the phone would see. See DuressFlowDeviceTest for the parts that run
inside the app, and the "Run the duress passcode test" step in emulator.yml.

  duress-ui.py type-duress CODE   open Clairix, type CODE on its lock screen, and check
                                  that ten seconds later the lock screen still says
                                  "Wrong passcode", in a new process, with no first-run page
  duress-ui.py relaunch CODE      open Clairix afresh: the lock screen, never a first-run
                                  page, and CODE opens the app

Exit status 0 when every check holds; otherwise 1, with a GitHub "::error::" line.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb")
PKG = "app.clairix"
LOCK = "Enter your passcode to continue"
WRONG = "Wrong passcode"
UNLOCK = "Unlock"
# What the first run shows (OnboardingScreen, Tour): the decoy must never show it.
FIRST_RUN = ("Nothing hides.", "Not now")
FIRST_RUN_PREFIXES = ("Tour · ",)
# The app past its lock: the Comms tab (AppShell HomeTab.COMMS).
APP_SHOWN = "Comms"
DUMP = "/sdcard/clairix-duress-ui.xml"
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def fail(message):
    print(f"::error::{message}")
    sys.exit(1)


def adb(*args, check=True):
    done = subprocess.run([ADB, *args], capture_output=True, text=True)
    if check and done.returncode != 0:
        fail(f"adb {' '.join(args)} failed: {done.stderr.strip() or done.stdout.strip()}")
    return done.stdout


def screen():
    """The window hierarchy now, or None when a dump could not be taken (mid-transition)."""
    done = subprocess.run([ADB, "shell", "uiautomator", "dump", DUMP], capture_output=True, text=True)
    if done.returncode != 0:
        return None
    xml = adb("exec-out", "cat", DUMP, check=False)
    start, end = xml.find("<hierarchy"), xml.rfind("</hierarchy>")
    if start == -1 or end == -1:
        return None
    try:
        return ET.fromstring(xml[start:end + len("</hierarchy>")])
    except ET.ParseError:
        return None


def texts(root):
    """Every text and content description Clairix shows, from a dump."""
    out = []
    for node in root.iter("node"):
        if node.get("package") != PKG:
            continue
        for key in ("text", "content-desc"):
            value = node.get(key) or ""
            if value:
                out.append(value)
    return out


def shows(root, text):
    return root is not None and text in texts(root)


def first_run(root):
    if root is None:
        return False
    shown = texts(root)
    return any(t in shown for t in FIRST_RUN) or any(t.startswith(p) for t in shown for p in FIRST_RUN_PREFIXES)


def wait_for(text, timeout_s):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        root = screen()
        if shows(root, text):
            return root
        time.sleep(0.5)
    return None


def tap(text, timeout_s=20):
    """Taps the centre of the Clairix node showing exactly [text].

    Every look at the screen is a full `uiautomator dump`, which takes seconds on a
    loaded emulator and fails while the window changes; five seconds left too few
    looks (smoke (33) on 635ed4e: "no Unlock on the screen to tap", after the digits
    had been typed). On failure the last screen seen is printed, so a button that
    was really missing can be told from a screen that could not be read.
    """
    deadline = time.monotonic() + timeout_s
    last = None
    dumps = 0
    while time.monotonic() < deadline:
        root = screen()
        dumps += 1
        if root is not None:
            last = texts(root)
            for node in root.iter("node"):
                if node.get("package") == PKG and node.get("text") == text:
                    m = BOUNDS.fullmatch(node.get("bounds") or "")
                    if m:
                        x1, y1, x2, y2 = map(int, m.groups())
                        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                        return
        time.sleep(0.5)
    seen = "no readable screen" if last is None else f"Clairix showed {last}"
    fail(f'no "{text}" on the screen to tap after {dumps} looks in {timeout_s}s; {seen}')


def pid():
    return adb("shell", "pidof", PKG, check=False).strip()


def launch():
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")


def type_code(code):
    for digit in code:
        tap(digit)
    tap(UNLOCK)


def type_duress(code):
    launch()
    if wait_for(LOCK, 30) is None:
        fail("the lock screen did not come up")
    before = pid()
    if not before:
        fail("Clairix is not running")
    type_code(code)
    # The wipe behind the duress code and the new process take their time on a
    # slow emulator (the AOSP image took more than five seconds on 023a525);
    # the words stay on the lock screen once shown, as checked below.
    if wait_for(WRONG, 30) is None:
        fail("the duress code did not read as a wrong passcode within 30 seconds")
    # Ten seconds on, the holder still sees the lock screen and the words it ended on,
    # never a first run, and the process that showed the old data is gone.
    time.sleep(10)
    root = screen()
    if not shows(root, LOCK):
        fail("the lock screen was gone ten seconds after the duress code")
    if not shows(root, WRONG):
        fail('the lock screen no longer said "Wrong passcode"')
    if first_run(root):
        fail("a first-run page showed after the duress code")
    after = pid()
    if not after:
        fail("Clairix was not running ten seconds after the duress code")
    if after == before:
        fail("the process that showed the old data is still the one on screen")
    print(f"duress: lock screen kept, \"{WRONG}\" kept, no first run, process {before} -> {after}")


def relaunch(code):
    adb("shell", "am", "force-stop", PKG)
    launch()
    root = wait_for(LOCK, 30)
    if root is None:
        fail("the decoy relaunched without its lock screen")
    if first_run(root):
        fail("a first-run page showed on relaunch")
    type_code(code)
    root = wait_for(APP_SHOWN, 15)
    if root is None:
        fail("the duress code did not open the decoy on relaunch")
    if first_run(root):
        fail("a first-run page showed behind the lock on relaunch")
    print("relaunch: lock screen, then the decoy, no first run")


def main(argv):
    if len(argv) != 3 or argv[1] not in ("type-duress", "relaunch") or not argv[2].isdigit():
        print(__doc__)
        return 2
    if argv[1] == "type-duress":
        type_duress(argv[2])
    else:
        relaunch(argv[2])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
