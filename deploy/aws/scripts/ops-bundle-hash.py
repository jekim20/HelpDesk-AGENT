#!/usr/bin/env python3
"""Hash the exact runtime files that must be installed on the HelpDesk EC2 host."""

import argparse
import hashlib
from pathlib import Path


FILES = [
    "compose.aws.yaml",
    "deploy/aws/config.example.env",
    "deploy/aws/systemd/docker.service.d/20-helpdesk-ebs.conf",
    "deploy/aws/scripts/ops-bundle-hash.py",
] + [f"deploy/aws/scripts/{path.name}" for path in sorted((Path(__file__).resolve().parent).glob("*.sh"))]


def digest(root: Path) -> str:
    value = hashlib.sha256()
    for name in sorted(FILES):
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise SystemExit(f"required runtime file is missing or unsafe: {name}")
        raw_name = name.encode()
        raw_contents = path.read_bytes()
        value.update(len(raw_name).to_bytes(4, "big"))
        value.update(raw_name)
        value.update(len(raw_contents).to_bytes(8, "big"))
        value.update(raw_contents)
    return value.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args()
    print(digest(args.root.resolve()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
