#!/usr/bin/env python3
"""HISTORICAL tool, refused for the active MangaDenizi MEX profile.

Install the old native single-source integration into an existing Kitsugi-Beta checkout.

Default is read-only --check. Never clones, changes branches, commits, pushes, or deletes
installed extensions/user data. A conflicting destination file stops the whole preflight.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
INTEGRATION = ROOT / "integration" / "kitsugi"
PATCH = INTEGRATION / "single-source.patch"
ASSETS = ROOT / "keiyoushi-extensions-tr/src/tr/golgebahcesi/assets"


def read_profile(path: Path = ROOT / "sources.properties") -> dict[str, str]:
    profile: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, separator, value = line.partition("=")
        if not separator or name.strip() in profile:
            raise ValueError("Invalid or duplicate source profile key")
        profile[name.strip()] = value.strip()
    required = {
        "activeSource": "golgebahcesi",
        "keiyoushi.enabled": "tr:golgebahcesi",
        "kotatsu.enabled": "",
        "kitsugi.adapter": "native",
    }
    if any(profile.get(key) != value for key, value in required.items()):
        raise ValueError("Retired Golge integration: the active MangaDenizi profile uses a native MEX. Do not apply the old patch: it disables MEX loading. See the repository README.")
    return profile


def copies() -> list[tuple[Path, Path]]:
    pairs: list[tuple[Path, Path]] = []
    for source_dir, target_dir in (
        ("src/main/kotlin", "app/src/main/java"),
        ("src/main/res", "app/src/main/res"),
        ("src/test/kotlin", "app/src/test/java"),
        ("src/test/resources", "app/src/test/resources"),
    ):
        for file in sorted((INTEGRATION / source_dir).rglob("*")):
            if file.is_file():
                pairs.append((file, Path(target_dir) / file.relative_to(INTEGRATION / source_dir)))
    for name in ("reader_capture.js", "worker_hook.js"):
        pairs.append((ASSETS / name, Path("app/src/main/assets/golgebahcesi") / name))
    return pairs


def git_apply(target: Path, *flags: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "apply", *flags, str(PATCH)], cwd=target,
        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )


def preflight(target: Path) -> tuple[bool, list[tuple[Path, Path]]]:
    read_profile()
    if not (target / "app/build.gradle.kts").is_file():
        raise ValueError("Target must be the Kitsugi-Beta root (app/build.gradle.kts is missing)")
    for name in re.findall(r"^\+\+\+ b/(.+)$", PATCH.read_text(encoding="utf-8"), re.MULTILINE):
        relative = Path(name)
        if relative.is_absolute() or ".." in relative.parts or not name.startswith("app/"):
            raise ValueError(f"Unsafe patch path: {name}")
        if not (target / relative).resolve().is_relative_to(target):
            raise ValueError(f"Patch path escapes target: {name}")
    plan = copies()
    for source, relative in plan:
        destination = target / relative
        if not destination.resolve().is_relative_to(target):
            raise ValueError(f"Destination escapes target: {relative}")
        if destination.exists() and (not destination.is_file() or destination.read_bytes() != source.read_bytes()):
            raise ValueError(f"Refusing to overwrite existing different file: {relative}")
    check = git_apply(target, "--check")
    if check.returncode == 0:
        return False, plan
    if git_apply(target, "--reverse", "--check").returncode == 0:
        return True, plan
    raise ValueError("App patch does not apply; no files changed.\n" + check.stderr.strip())


def install(target: Path, apply: bool = False) -> tuple[bool, int]:
    target = target.resolve()
    already_applied, plan = preflight(target)
    if apply:
        if not already_applied:
            result = git_apply(target)
            if result.returncode:
                raise ValueError("App patch failed: " + result.stderr.strip())
        for source, relative in plan:
            destination = target / relative
            if destination.exists():  # Identical, checked before any writes.
                continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            temporary: Path | None = None
            try:
                with tempfile.NamedTemporaryFile(dir=destination.parent, suffix=".tmp", delete=False) as stream:
                    temporary = Path(stream.name)
                    stream.write(source.read_bytes())
                temporary.replace(destination)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
    return already_applied, len(plan)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path, help="Path to an existing Kitsugi-Beta checkout")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="Read-only preflight (default)")
    mode.add_argument("--apply", action="store_true", help="Apply app patch and copy the native adapter/assets/tests")
    args = parser.parse_args()
    try:
        already, count = install(args.app, apply=args.apply)
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    status = "already applied" if already else "ready"
    print(f"Single-source integration {status}; {count} native source/resource/test files checked.")
    if args.apply:
        print("Installed. Build and device validation are still required; this command does not compile an APK.")
    else:
        print("Read-only check passed. Run again with --apply to install. No files changed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
