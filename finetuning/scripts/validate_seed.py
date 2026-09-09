#!/usr/bin/env python3
"""Validate the DevDesk seed JSONL and its augmentation eligibility."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

from pipeline_common import (
    REPORT_DIR,
    SEED_PATH,
    duplicate_ids,
    read_jsonl,
    validate_augmentation_sources,
    validate_record,
    write_json,
)


def validate_seed_file(path: Path, source_ids: list[str] | None = None) -> dict:
    records, parse_errors = read_jsonl(path)
    errors = list(parse_errors)
    for record in records:
        errors.extend(validate_record(record))
    duplicate = duplicate_ids(records)
    errors.extend(f"duplicate id {record_id}" for record_id in duplicate)
    seeds_by_id = {record.get("id"): record for record in records if record.get("id")}
    selected_sources = source_ids
    if selected_sources is None:
        selected_sources = [
            record["id"]
            for record in records
            if record.get("split_hint") == "seed_train_candidate"
        ]
    errors.extend(validate_augmentation_sources(selected_sources, seeds_by_id))
    split_counts = Counter(record.get("split_hint") for record in records)
    return {
        "status": "PASS" if not errors else "FAIL",
        "total": len(records),
        "errors": errors,
        "duplicateIds": duplicate,
        "splitCounts": dict(sorted(split_counts.items(), key=lambda item: str(item[0]))),
        "augmentationSourceCount": len(selected_sources),
        "excludedFromAugmentation": {
            "heldout_candidate": split_counts.get("heldout_candidate", 0),
            "historical_regression_candidate": split_counts.get(
                "historical_regression_candidate", 0
            ),
        },
    }


def markdown(report: dict) -> str:
    lines = [
        "# Seed Validation",
        "",
        f"- Status: {report['status']}",
        f"- Total: {report['total']}",
        f"- Augmentation sources: {report['augmentationSourceCount']}",
        "",
        "## Split counts",
        "",
    ]
    lines.extend(f"- {name}: {count}" for name, count in report["splitCounts"].items())
    lines.extend(["", "## Errors", ""])
    lines.extend(f"- {error}" for error in report["errors"] or ["None"])
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=Path, default=SEED_PATH)
    parser.add_argument("--source-id", action="append", dest="source_ids")
    args = parser.parse_args()
    report = validate_seed_file(args.seed, args.source_ids)
    write_json(REPORT_DIR / "seed_validation.json", report)
    (REPORT_DIR / "seed_validation.md").write_text(markdown(report), encoding="utf-8")
    print(f"Seed validation: {report['status']}")
    print(f"Total: {report['total']}")
    for name, count in report["splitCounts"].items():
        print(f"{name}: {count}")
    for error in report["errors"]:
        print(f"ERROR: {error}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
