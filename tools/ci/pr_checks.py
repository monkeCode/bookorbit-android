#!/usr/bin/env python3
"""Fail-closed PR routing and local documentation-link checks. No network or secrets."""
import argparse
from pathlib import Path, PurePosixPath
import re
import subprocess
from urllib.parse import unquote, urlsplit


ROOT_DOCS = frozenset({"README.md", "ROADMAP.md", "LICENSE", "tools/README.md"})
DOC_SUFFIXES = frozenset({".md", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg"})
IMAGE_SIGNATURES = {
    ".png": lambda b: b.startswith(b"\x89PNG\r\n\x1a\n"),
    ".jpg": lambda b: b.startswith(b"\xff\xd8\xff"),
    ".jpeg": lambda b: b.startswith(b"\xff\xd8\xff"),
    ".gif": lambda b: b.startswith((b"GIF87a", b"GIF89a")),
    ".webp": lambda b: b.startswith(b"RIFF") and b[8:12] == b"WEBP",
}


def documentation_only(paths):
    """Unknown files, build inputs and all workflow changes require Android checks."""
    if not paths:
        return False
    return all(path in ROOT_DOCS or
               (path.startswith("docs/") and PurePosixPath(path).suffix.lower() in DOC_SUFFIXES)
               for path in paths)


def changed_paths(base, head):
    for sha in (base, head):
        if not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError("Expected exact base/head commit SHAs")
    # Disable rename detection so moving code to docs cannot hide the deleted code path.
    raw = subprocess.check_output(["git", "diff", "--no-renames", "--name-only", "-z", base, head])
    return [path.decode("utf-8") for path in raw.split(b"\0") if path]


def check_markdown(path, root):
    text = path.read_text(encoding="utf-8")
    # Examples in fenced code blocks are not rendered links.
    text = re.sub(r"(?ms)^```[^\n]*\n.*?^```[^\n]*$", "", text)
    text = re.sub(r"(?ms)^~~~[^\n]*\n.*?^~~~[^\n]*$", "", text)
    targets = re.findall(r"!?\[[^\]\n]*\]\(([^\s)]+)(?:\s+[^)]*)?\)", text)
    targets += re.findall(r'''(?:src|href)=["']([^"']+)["']''', text)
    targets += re.findall(r"(?m)^\s*\[[^\]]+\]:\s*(\S+)", text)
    errors = []
    for target in targets:
        target = target.strip("<>")
        parts = urlsplit(target)
        if parts.scheme or parts.netloc or not parts.path:
            continue  # External URLs/anchors are not fetched.
        resolved = ((root if parts.path.startswith("/") else path.parent) / unquote(parts.path).lstrip("/")).resolve()
        if not resolved.is_relative_to(root.resolve()):
            errors.append(f"{path.relative_to(root)}: link escapes the repository: {target}")
        elif not resolved.exists():
            errors.append(f"{path.relative_to(root)}: missing local link: {target}")
    return errors


def check_docs(paths, root):
    errors = []
    for name in paths:
        path = root / name
        if not path.is_file():
            continue  # Deletions are valid; links to deleted files are checked below.
        if path.suffix.lower() in IMAGE_SIGNATURES:
            with path.open("rb") as stream:
                valid = IMAGE_SIGNATURES[path.suffix.lower()](stream.read(16))
            if not valid:
                errors.append(f"{name}: invalid image signature")
    # Check maintained docs even when only an image changed/deleted. Historical
    # snapshots intentionally retain their original relative links and are excluded.
    markdown = [root / p for p in ROOT_DOCS if p.endswith(".md")]
    markdown += list((root / "docs").rglob("*.md"))
    markdown += [root / p for p in paths if p.endswith(".md")]
    for path in sorted(set(markdown)):
        if not path.is_file() or path.is_relative_to(root / "docs/history"):
            continue
        errors.extend(check_markdown(path, root))
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("classify", "docs"))
    parser.add_argument("base")
    parser.add_argument("head")
    args = parser.parse_args()
    paths = changed_paths(args.base, args.head)
    if args.mode == "classify":
        print(f"android={str(not documentation_only(paths)).lower()}")
    else:
        errors = check_docs(paths, Path.cwd())
        if errors:
            raise SystemExit("\n".join(errors))
        print(f"Documentation links and changed images checked ({len(paths)} changed paths); no network requests.")
