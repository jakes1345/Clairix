#!/usr/bin/env python3
"""Every host the app or the relay can contact is accounted for, and the website says so.

Reads the shipped Kotlin/Java sources of the Android app (android/app/src/<set>/, every
source set but the test ones) and the relay's TypeScript (comms-worker/src/), and pulls
out every host they name: the host of each http(s):// URL in a string literal, and every
domain-shaped literal assigned to a constant whose name contains HOST (DEFAULT_PUSH_HOSTS,
INVITE_HOST). Comments are skipped. Each host must be listed in docs/egress.json, in
exactly one of:

  destinations  a row of the egress table on the security page, with the hosts it covers
  links         shown to the user, or opened in their browser or another app when they
                tap it; Clairix itself never fetches it
  not_fetched   a name that is never a network destination (an XML namespace)

and every host listed there must still occur in the sources, so the list stays exact. The
first cell of every row of the security page's egress table must be one destination's
"row", and every destination must have its row in the table. Exits 1 with the reasons
when any of that fails. Reads files only; makes no network request.

Usage: egress_check.py [--root REPO]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from dataclasses import dataclass, field

TEST_SOURCE_SETS = {"test", "androidTest", "testShared", "sharedTest"}
APP_SUFFIXES = {".kt", ".java"}
RELAY_SUFFIXES = {".ts"}

URL_RE = re.compile(r"https?://([^/\s\"'`?#:\\]+)")
DOMAIN_RE = re.compile(r"^(?=.{4,253}$)(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}$")
HOST_CONST_RE = re.compile(r"\b(?:const\s+val|val|var|const|let)\s+([A-Za-z_][A-Za-z0-9_]*)")
IPV4_RE = re.compile(r"^\d{1,3}(?:\.\d{1,3}){3}$")
LOCAL_HOSTS = {"localhost"}


@dataclass
class Literal:
    text: str
    offset: int


@dataclass
class Scan:
    """Every host found, with where: {host: ["path:line", ...]}."""

    hosts: dict[str, list[str]] = field(default_factory=dict)

    def add(self, host: str, where: str) -> None:
        self.hosts.setdefault(host, []).append(where)


def strip_comments(src: str, kotlin: bool) -> tuple[str, list[Literal]]:
    """Blanks out comments (keeping newlines, so offsets and lines stay put) and returns the string literals.

    Handles // and /* */ comments, "..." and '...' literals with backslash escapes, Kotlin's
    \"\"\"raw strings\"\"\" and TypeScript's `template literals` (whose ${...} parts are kept
    in the literal's text, which is enough to tell a placeholder host from a real one).
    """
    out = list(src)
    literals: list[Literal] = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif two == "/*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif kotlin and src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j
            literals.append(Literal(src[i + 3:j], i + 3))
            i = min(n, j + 3)
        elif c in "\"'" or (c == "`" and not kotlin):
            j = i + 1
            while j < n and src[j] != c:
                if src[j] == "\\":
                    j += 1
                elif src[j] == "\n" and c != "`":
                    break  # an unterminated quote (a stray apostrophe): give up on it at the line's end
                j += 1
            literals.append(Literal(src[i + 1:j], i + 1))
            i = j + 1
        else:
            i += 1
    return "".join(out), literals


def _real_host(host: str) -> str | None:
    host = host.strip().lower().rstrip(".")
    if not host or any(ch in host for ch in "${}<>*()[] "):
        return None
    if host in LOCAL_HOSTS or IPV4_RE.match(host):
        return None
    if not DOMAIN_RE.match(host):
        return None
    return host


def _host_constant_rhs(code: str, start: int) -> str:
    """The right-hand side of a declaration from its '=', to the ';' or line end at bracket depth 0."""
    eq = code.find("=", start)
    if eq < 0:
        return ""
    depth, j = 0, eq + 1
    while j < len(code):
        ch = code[j]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth < 0:
                break
        elif ch == ";" and depth == 0:
            break
        elif ch == "\n" and depth == 0:
            # A declaration continues on the next line only after a trailing '=' or inside brackets.
            if code[eq + 1:j].strip():
                break
        j += 1
    return code[eq + 1:j]


def hosts_in(src: str, kotlin: bool) -> list[tuple[str, int]]:
    """(host, offset) for every host [src] names outside comments."""
    code, literals = strip_comments(src, kotlin)
    found: list[tuple[str, int]] = []
    for lit in literals:
        for m in URL_RE.finditer(lit.text):
            host = _real_host(m.group(1))
            if host:
                found.append((host, lit.offset + m.start()))
    by_offset = {lit.offset: lit for lit in literals}
    for m in HOST_CONST_RE.finditer(code):
        if "HOST" not in m.group(1).upper():
            continue
        rhs_start = m.end()
        rhs = _host_constant_rhs(code, rhs_start)
        eq = code.find("=", rhs_start)
        lo, hi = eq + 1, eq + 1 + len(rhs)
        for off, lit in by_offset.items():
            if lo <= off < hi:
                host = _real_host(lit.text)
                if host and lit.text.strip().lower() == host:
                    found.append((host, off))
    return found


def _line(src: str, offset: int) -> int:
    return src.count("\n", 0, offset) + 1


def source_files(root: pathlib.Path) -> list[tuple[pathlib.Path, bool]]:
    """(path, is_kotlin_or_java) for every shipped app source and every relay source."""
    files: list[tuple[pathlib.Path, bool]] = []
    app_src = root / "android" / "app" / "src"
    if app_src.is_dir():
        for source_set in sorted(p for p in app_src.iterdir() if p.is_dir()):
            if source_set.name in TEST_SOURCE_SETS or source_set.name.lower().startswith("test") or "AndroidTest" in source_set.name:
                continue
            for p in sorted(source_set.rglob("*")):
                if p.is_file() and p.suffix in APP_SUFFIXES:
                    files.append((p, True))
    relay_src = root / "comms-worker" / "src"
    if relay_src.is_dir():
        for p in sorted(relay_src.rglob("*")):
            if p.is_file() and p.suffix in RELAY_SUFFIXES and not p.name.endswith(".d.ts"):
                files.append((p, False))
    return files


def scan(root: pathlib.Path) -> Scan:
    result = Scan()
    for path, kotlin in source_files(root):
        src = path.read_text(encoding="utf-8")
        for host, off in hosts_in(src, kotlin):
            result.add(host, f"{path.relative_to(root).as_posix()}:{_line(src, off)}")
    return result


def table_rows(markdown: str, anchor: str = "{#egress}") -> list[str]:
    """The first cell, bold markers removed, of every body row of the first table after [anchor]."""
    at = markdown.find(anchor)
    if at < 0:
        raise ValueError(f"no heading with {anchor} on the security page")
    rows: list[str] = []
    in_table = False
    for line in markdown[at:].splitlines()[1:]:
        stripped = line.strip()
        if stripped.startswith("|"):
            in_table = True
            cells = [c.strip() for c in stripped.strip("|").split("|")]
            if all(re.fullmatch(r":?-{3,}:?", c) for c in cells if c):
                continue
            rows.append(cells[0].replace("**", "").strip())
        elif in_table:
            break
    if len(rows) < 2:
        raise ValueError("the egress table after the {#egress} heading has no rows")
    return rows[1:]  # the first row is the header


def check(root: pathlib.Path, egress_path: pathlib.Path, security_path: pathlib.Path) -> list[str]:
    errors: list[str] = []
    listed = json.loads(egress_path.read_text(encoding="utf-8"))
    category: dict[str, str] = {}

    def claim(host: str, where: str) -> None:
        if host != host.lower() or not DOMAIN_RE.match(host):
            errors.append(f"{egress_path.name}: '{host}' ({where}) is not a lower-case host name")
        if host in category:
            errors.append(f"{egress_path.name}: '{host}' is listed under both {category[host]} and {where}")
        category[host] = where

    rows: list[str] = []
    for d in listed.get("destinations", []):
        row = d.get("row", "")
        if not row:
            errors.append(f"{egress_path.name}: a destination has no row")
            continue
        if row in rows:
            errors.append(f"{egress_path.name}: the row '{row}' is listed twice")
        rows.append(row)
        if d.get("from") not in ("app", "relay", "both"):
            errors.append(f"{egress_path.name}: '{row}' must say whether the app, the relay or both contact it (\"from\")")
        for host in d.get("hosts", []):
            claim(host, f"destination '{row}'")
    for section in ("links", "not_fetched"):
        for host, why in listed.get(section, {}).items():
            if not str(why).strip():
                errors.append(f"{egress_path.name}: {section} '{host}' needs a reason")
            claim(host, section)

    found = scan(root)
    for host in sorted(found.hosts):
        if host not in category:
            where = ", ".join(found.hosts[host][:5])
            errors.append(
                f"{host} is named in the code ({where}) but not in {egress_path.name}: add it to the destination row of "
                f"the security page's egress table that covers it, or to links/not_fetched if Clairix never contacts it"
            )
    for host in sorted(category):
        if host not in found.hosts:
            errors.append(f"{egress_path.name} lists {host} ({category[host]}), which no app or relay source names any more")

    table = table_rows(security_path.read_text(encoding="utf-8"))
    for row in table:
        if row not in rows:
            errors.append(f"the security page's egress table has the row '{row}', which {egress_path.name} does not list")
    seen: set[str] = set()
    for row in table:
        if row in seen:
            errors.append(f"the security page's egress table has the row '{row}' twice")
        seen.add(row)
    for row in rows:
        if row not in table:
            errors.append(f"{egress_path.name} lists the destination '{row}', which the security page's egress table does not have")
    return errors


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2])
    args = ap.parse_args(argv)
    root = args.root.resolve()
    errors = check(root, root / "docs" / "egress.json", root / "website" / "src" / "content" / "docs" / "security.md")
    if errors:
        for e in errors:
            print(f"egress: {e}", file=sys.stderr)
        return 1
    print(f"egress: {len(scan(root).hosts)} hosts in the app and relay sources, each accounted for in docs/egress.json; "
          "the security page's table matches it")
    return 0


if __name__ == "__main__":
    sys.exit(main())
