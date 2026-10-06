#!/usr/bin/env python3
"""Summarize validated augmentation candidates for human review."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
from typing import Any

from pipeline_common import PROCESSED_DIR, REPORT_DIR, read_jsonl, write_json


def count_by(records: list[dict[str, Any]], getter) -> dict[str, int]:
    counts = Counter()
    for record in records:
        values = getter(record)
        if not isinstance(values, (list, tuple, set)):
            values = [values]
        for value in values:
            if value is not None:
                counts[str(value)] += 1
    return dict(sorted(counts.items()))


def analyze(records: list[dict[str, Any]], validation: dict[str, Any]) -> dict[str, Any]:
    return {
        "totalGenerated": validation.get("total", len(records)),
        "passed": validation.get("passed", 0),
        "warnings": validation.get("warnings", 0),
        "failed": validation.get("failed", 0),
        "machineValidated": validation.get("machineValidatedCandidateCount", len(records)),
        "machineRejected": validation.get("machineRejected", 0),
        "blockingFailures": validation.get("blockingFailures", validation.get("failed", 0)),
        "approvedTrainingExport": validation.get("approvedTrainingExportCount", 0),
        "pilotGenerated": validation.get("pilotGenerated", 0),
        "pilotApproved": validation.get("pilotApproved", 0),
        "productionGenerated": validation.get("productionGenerated", 0),
        "productionApproved": validation.get("productionApproved", 0),
        "productionTarget": validation.get("productionTarget", 0),
        "productionRemaining": validation.get("productionRemaining", 0),
        "byGenerationSubtype": validation.get("generationSubtypeCounts", {}),
        "byReviewStatus": count_by(records, lambda r: r.get("review_status", "UNREVIEWED")),
        "byGroup": count_by(records, lambda r: str(r.get("generation_group_id", "")).split("_", 1)[0]),
        "byIntent": count_by(records, lambda r: r.get("intent")),
        "byDifficulty": count_by(records, lambda r: r.get("difficulty", [])),
        "byResource": count_by(records, lambda r: r.get("target", {}).get("arguments", {}).get("resource")),
        "byTicketType": count_by(records, lambda r: r.get("target", {}).get("arguments", {}).get("type")),
        "bySourceSeed": count_by(records, lambda r: r.get("source_seed_id")),
        "exactDuplicates": validation.get("exactDuplicates", []),
        "normalizedDuplicates": validation.get("normalizedDuplicates", []),
        "nearDuplicates": validation.get("nearDuplicates", []),
        "schemaFailures": [
            result
            for result in validation.get("results", [])
            if result.get("status") in {"FAIL", "MACHINE_REJECTED"}
        ],
    }


def markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Dataset Analysis",
        "",
        f"- Total generated: {report['totalGenerated']}",
        f"- Passed: {report['passed']}",
        f"- Warnings: {report['warnings']}",
        f"- Failed: {report['failed']}",
        f"- Machine validated: {report['machineValidated']}",
        f"- Machine rejected: {report['machineRejected']}",
        f"- Blocking failures: {report['blockingFailures']}",
        f"- Approved training export: {report['approvedTrainingExport']}",
        f"- Pilot generated: {report['pilotGenerated']}",
        f"- Pilot approved: {report['pilotApproved']}",
        f"- Production generated: {report['productionGenerated']}",
        f"- Production approved: {report['productionApproved']}",
        f"- Production target: {report['productionTarget']}",
        f"- Production remaining: {report['productionRemaining']}",
        f"- Exact duplicates: {len(report['exactDuplicates'])}",
        f"- Normalized duplicates: {len(report['normalizedDuplicates'])}",
        f"- Near duplicates: {len(report['nearDuplicates'])}",
    ]
    for title, key in (
        ("By group", "byGroup"),
        ("By intent", "byIntent"),
        ("By difficulty", "byDifficulty"),
        ("By resource", "byResource"),
        ("By ticket type", "byTicketType"),
        ("By source seed", "bySourceSeed"),
        ("By review status", "byReviewStatus"),
        ("By generation subtype", "byGenerationSubtype"),
    ):
        lines.extend(["", f"## {title}", ""])
        lines.extend(f"- {name}: {count}" for name, count in report[key].items())
    lines.extend(["", "## Schema failures", ""])
    if report["schemaFailures"]:
        lines.extend(
            f"- {item['id']}: {'; '.join(item['failures'])}" for item in report["schemaFailures"]
        )
    else:
        lines.append("- None")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input", type=Path, default=PROCESSED_DIR / "machine_validated_candidates.jsonl"
    )
    parser.add_argument(
        "--validation", type=Path, default=REPORT_DIR / "candidate_validation.json"
    )
    args = parser.parse_args()
    records, errors = read_jsonl(args.input)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    import json

    validation = json.loads(args.validation.read_text(encoding="utf-8"))
    report = analyze(records, validation)
    write_json(REPORT_DIR / "dataset_analysis.json", report)
    (REPORT_DIR / "dataset_analysis.md").write_text(markdown(report), encoding="utf-8")
    print(f"Total generated: {report['totalGenerated']}")
    print(f"Passed: {report['passed']}")
    print(f"Warnings: {report['warnings']}")
    print(f"Failed: {report['failed']}")
    print(f"Pilot generated: {report['pilotGenerated']}")
    print(f"Pilot approved: {report['pilotApproved']}")
    print(f"Production generated: {report['productionGenerated']}")
    print(f"Production approved: {report['productionApproved']}")
    print(f"Production target: {report['productionTarget']}")
    print(f"Production remaining: {report['productionRemaining']}")
    print(f"Exact duplicates: {len(report['exactDuplicates'])}")
    print(f"Near duplicates: {len(report['nearDuplicates'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
