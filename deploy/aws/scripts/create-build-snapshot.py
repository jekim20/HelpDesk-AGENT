#!/usr/bin/env python3
"""Create a deterministic, secret-filtered Docker build context archive.

The archive records the Git HEAD and hashes every included file. It does not
commit, push, contact AWS, or include evaluation/finetuning/user artifacts.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile


ROOT = Path(__file__).resolve().parents[3]
EXACT_FILES = (
    ".dockerignore",
    "Dockerfile",
    "Dockerfile.frontend",
    "gradlew",
    "settings.gradle",
    "build.gradle",
    "deploy/nginx.conf",
)
DIRECTORIES = ("gradle", "src/main", "frontend")
EXCLUDED_DIRS = {
    ".git", ".aws", ".gradle", "build", "dist", "node_modules", "__pycache__",
}


def excluded(path: Path) -> bool:
    if any(part in EXCLUDED_DIRS for part in path.parts):
        return True
    name = path.name
    return (
        name == ".DS_Store"
        or name == ".env"
        or name.startswith(".env.")
        or name.endswith((".pem", ".key", ".log"))
    )


def build_files() -> list[Path]:
    paths = {ROOT / name for name in EXACT_FILES}
    for directory in DIRECTORIES:
        base = ROOT / directory
        if not base.is_dir():
            raise SystemExit(f"required build input directory is missing: {directory}")
        paths.update(path for path in base.rglob("*") if path.is_file() and not excluded(path.relative_to(ROOT)))
    missing = [str(path.relative_to(ROOT)) for path in paths if not path.is_file()]
    if missing:
        raise SystemExit("required build inputs are missing: " + ", ".join(sorted(missing)))
    if any(path.is_symlink() for path in paths):
        raise SystemExit("symlink build inputs are not supported in a reproducible snapshot")
    return sorted(paths, key=lambda path: path.relative_to(ROOT).as_posix())


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git_value(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    output = args.output.expanduser().resolve()
    if output.exists():
        raise SystemExit(f"refusing to overwrite existing snapshot: {output}")

    files = build_files()
    manifest = {
        "format": 1,
        "gitCommit": git_value("rev-parse", "HEAD"),
        "snapshotterSha256": sha256(Path(__file__).resolve()),
        "worktreeStatus": git_value(
            "status", "--porcelain=v1", "--untracked-files=all", "--",
            *EXACT_FILES, *DIRECTORIES,
        ).splitlines(),
        "files": [
            {"path": path.relative_to(ROOT).as_posix(), "sha256": sha256(path)}
            for path in files
        ],
    }
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("xb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as archive:
                for path in files:
                    relative = path.relative_to(ROOT).as_posix()
                    info = archive.gettarinfo(str(path), arcname=relative)
                    info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    info.mtime = 0
                    info.pax_headers = {}
                    with path.open("rb") as stream:
                        archive.addfile(info, stream)
                info = tarfile.TarInfo("source-manifest.json")
                info.size = len(manifest_bytes)
                info.mode = 0o644
                info.uid = info.gid = 0
                info.uname = info.gname = ""
                info.mtime = 0
                archive.addfile(info, __import__("io").BytesIO(manifest_bytes))

    print(f"snapshot={output}")
    print(f"git_commit={manifest['gitCommit']}")
    print(f"included_files={len(files)}")
    print(f"snapshot_sha256={sha256(output)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
