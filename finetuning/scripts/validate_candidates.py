#!/usr/bin/env python3
"""Validate generated candidates, leakage, and lightweight duplication risks."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from copy import deepcopy
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any

from pipeline_common import (
    GENERATED_DIR,
    PLAN_PATH,
    PROCESSED_DIR,
    REPORT_DIR,
    ROOT,
    SEED_PATH,
    canonical_conversation_errors,
    conversation_text,
    duplicate_ids,
    load_plan,
    normalized_text,
    read_jsonl,
    validate_augmentation_sources,
    validate_record,
    write_json,
    write_jsonl,
)


DEFAULT_CONFIG = ROOT / "config/validation_config.json"
SUPPORTED_RESOURCE_TOKEN = re.compile(
    r"(?<![A-Z0-9_])(DEV_DB|PROD_DB|VPN)(?![A-Z0-9_])"
)


def resource_consistency_errors(
    record: dict[str, Any], group_id: str, allowlist: set[str]
) -> list[str]:
    if group_id not in allowlist:
        return []
    target_resource = record.get("target", {}).get("arguments", {}).get("resource")
    if target_resource not in {"DEV_DB", "PROD_DB", "VPN"}:
        return []
    resources = set(SUPPORTED_RESOURCE_TOKEN.findall(conversation_text(record).upper()))
    record_id = record.get("id", "<missing-id>")
    if len(resources) > 1:
        return [
            f"{record_id}: multiple supported resources in conversation: {sorted(resources)}"
        ]
    if resources and target_resource not in resources:
        return [
            f"{record_id}: conversation resource {next(iter(resources))} "
            f"does not match target resource {target_resource}"
        ]
    return []


def supported_resources_by_turn(record: dict[str, Any]) -> list[list[str]]:
    resources_by_turn = []
    for message in record.get("conversation", []):
        content = message.get("content", "") if isinstance(message, dict) else ""
        resources_by_turn.append(SUPPORTED_RESOURCE_TOKEN.findall(content.upper()))
    return resources_by_turn


def g15_subtype_contract_errors(
    record: dict[str, Any], subtype_name: str
) -> list[str]:
    record_id = record.get("id", "<missing-id>")
    conversation = record.get("conversation", [])
    resources_by_turn = supported_resources_by_turn(record)
    unique_resources = {resource for turn in resources_by_turn for resource in turn}
    user_turns = [
        (message, resources_by_turn[index])
        for index, message in enumerate(conversation)
        if isinstance(message, dict) and message.get("role") == "user"
    ]
    final_user_resources = set(user_turns[-1][1]) if user_turns else set()
    target = record.get("target", {})
    target_resource = target.get("arguments", {}).get("resource")
    errors: list[str] = []

    if subtype_name.endswith("_write"):
        if target.get("decision") != "TOOL":
            errors.append(f"{record_id}: {subtype_name} target decision must be TOOL")
        if target.get("tool_name") != "createTicket":
            errors.append(f"{record_id}: {subtype_name} tool_name must be createTicket")
        if target.get("arguments", {}).get("type") != "ACCESS_REQUEST":
            errors.append(f"{record_id}: {subtype_name} type must be ACCESS_REQUEST")
    elif target.get("decision") != "NO_TOOL":
        errors.append(f"{record_id}: {subtype_name} target decision must be NO_TOOL")

    if subtype_name == "single_resource_write":
        if len(unique_resources) != 1:
            errors.append(
                f"{record_id}: single_resource_write requires exactly 1 supported resource, "
                f"found {sorted(unique_resources)}"
            )
        elif target_resource not in unique_resources:
            errors.append(
                f"{record_id}: single_resource_write target resource {target_resource} "
                f"does not match conversation resource {next(iter(unique_resources))}"
            )
    elif subtype_name == "multi_resource_write":
        if len(unique_resources) < 2:
            errors.append(
                f"{record_id}: multi_resource_write requires at least 2 supported resources"
            )
        if final_user_resources:
            errors.append(
                f"{record_id}: multi_resource_write final user turn must not repeat a "
                "supported resource"
            )
        explicit_before_final = [
            resource
            for turn in resources_by_turn[: len(conversation) - 1]
            for resource in turn
        ]
        if explicit_before_final and explicit_before_final[-1] != target_resource:
            errors.append(
                f"{record_id}: multi_resource_write latest explicit resource "
                f"{explicit_before_final[-1]} does not match target resource {target_resource}"
            )
    elif subtype_name == "single_resource_howto":
        if len(unique_resources) != 1:
            errors.append(
                f"{record_id}: single_resource_howto requires exactly 1 supported resource, "
                f"found {sorted(unique_resources)}"
            )
    elif subtype_name == "multi_resource_howto":
        if len(conversation) < 5:
            errors.append(
                f"{record_id}: multi_resource_howto requires at least 5 conversation turns"
            )
        if len(unique_resources) < 2:
            errors.append(
                f"{record_id}: multi_resource_howto requires at least 2 supported resources"
            )
        if final_user_resources:
            errors.append(
                f"{record_id}: multi_resource_howto final user turn must not repeat a "
                "supported resource"
            )
        first_user_resources = set(user_turns[0][1]) if user_turns else set()
        if len(first_user_resources) > 1:
            errors.append(
                f"{record_id}: multi_resource_howto first user turn must not list "
                "multiple supported resources"
            )
        first_user_turn_by_resource: dict[str, int] = {}
        for user_index, (_, turn_resources) in enumerate(user_turns):
            for resource in turn_resources:
                first_user_turn_by_resource.setdefault(resource, user_index)
        if len(set(first_user_turn_by_resource.values())) < 2:
            errors.append(
                f"{record_id}: multi_resource_howto resources must appear sequentially "
                "in different user turns"
            )
    errors.extend(
        g15_scenario_metadata_errors(
            record,
            subtype_name,
            unique_resources,
            resources_by_turn,
            target_resource,
        )
    )
    return errors


def g15_scenario_metadata_errors(
    record: dict[str, Any],
    subtype_name: str,
    unique_resources: set[str],
    resources_by_turn: list[list[str]],
    target_resource: str | None,
) -> list[str]:
    """Validate pipeline-owned scenario metadata when a structured spec is present."""
    scenario_resources = record.get("scenario_resources")
    required_resource = record.get("required_resource")
    if scenario_resources is None and required_resource is None:
        return []  # Legacy G15 records predate structured scenario generation.

    record_id = record.get("id", "<missing-id>")
    errors: list[str] = []
    if not isinstance(scenario_resources, list) or not scenario_resources:
        return [f"{record_id}: scenario_resources must be a non-empty list"]
    if any(resource not in {"DEV_DB", "PROD_DB", "VPN"} for resource in scenario_resources):
        errors.append(f"{record_id}: scenario_resources contains unsupported resource")
        return errors
    if len(set(scenario_resources)) != len(scenario_resources):
        errors.append(f"{record_id}: scenario_resources must contain unique resources")
    if set(scenario_resources) != unique_resources:
        errors.append(
            f"{record_id}: conversation resources {sorted(unique_resources)} do not match "
            f"scenario_resources {scenario_resources}"
        )

    if subtype_name.startswith("single_") and len(scenario_resources) != 1:
        errors.append(f"{record_id}: single subtype scenario_resources must contain 1 item")
    if subtype_name.startswith("multi_") and len(scenario_resources) < 2:
        errors.append(f"{record_id}: multi subtype scenario_resources must contain at least 2 items")

    if subtype_name.endswith("_write"):
        if required_resource not in {"DEV_DB", "PROD_DB", "VPN"}:
            errors.append(f"{record_id}: structured write requires required_resource")
        else:
            if required_resource != target_resource:
                errors.append(
                    f"{record_id}: required_resource {required_resource} does not match "
                    f"target resource {target_resource}"
                )
            if subtype_name == "multi_resource_write" and scenario_resources[-1] != required_resource:
                errors.append(
                    f"{record_id}: multi_resource_write scenario must end with "
                    f"required_resource {required_resource}"
                )
    elif required_resource is not None:
        errors.append(f"{record_id}: how-to subtype must not contain required_resource")

    if subtype_name.startswith("multi_"):
        conversation = record.get("conversation", [])
        if len(conversation) < 5:
            errors.append(f"{record_id}: structured multi subtype requires at least 5 turns")
        first_user_turn: dict[str, int] = {}
        for turn_index, message in enumerate(conversation[:-1]):
            if not isinstance(message, dict) or message.get("role") != "user":
                continue
            for resource in resources_by_turn[turn_index]:
                first_user_turn.setdefault(resource, turn_index)
        if any(resource not in first_user_turn for resource in scenario_resources):
            errors.append(
                f"{record_id}: every scenario resource must appear in a user turn before "
                "the final reference"
            )
        else:
            actual_order = sorted(scenario_resources, key=first_user_turn.__getitem__)
            if actual_order != scenario_resources:
                errors.append(
                    f"{record_id}: user-turn resource order {actual_order} does not match "
                    f"scenario_resources {scenario_resources}"
                )
            if len({first_user_turn[resource] for resource in scenario_resources}) != len(
                scenario_resources
            ):
                errors.append(
                    f"{record_id}: multi subtype resources must first appear in different "
                    "user turns"
                )
    return errors


def machine_validated_records(
    records: list[dict[str, Any]], results: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    accepted_ids = {
        result["id"]
        for result in results
        if result.get("status") in {"PASS", "WARN_NEAR_DUPLICATE"}
    }
    machine_validated = []
    for record in records:
        if record.get("id") not in accepted_ids:
            continue
        normalized = deepcopy(record)
        normalized.setdefault("review_status", "UNREVIEWED")
        machine_validated.append(normalized)
    return machine_validated


def approved_stage_export_records(
    records: list[dict[str, Any]], results: list[dict[str, Any]], stage: str
) -> list[dict[str, Any]]:
    machine_validated = machine_validated_records(records, results)
    return [
        record
        for record in machine_validated
        if record.get("generation_mode") == "external"
        and record.get("generation_stage") == stage
        and record.get("review_status") == "KEEP"
    ]


def approved_training_export_records(
    records: list[dict[str, Any]], results: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    return approved_stage_export_records(records, results, "production")


def generation_stage_summary(
    records: list[dict[str, Any]],
    pilot_approved: list[dict[str, Any]],
    production_approved: list[dict[str, Any]],
    plan: dict[str, Any],
) -> dict[str, int]:
    production_target = int(plan["candidate_target_total"])
    production_generated = sum(
        record.get("generation_stage") == "production" for record in records
    )
    return {
        "pilotGenerated": sum(record.get("generation_stage") == "pilot" for record in records),
        "pilotApproved": len(pilot_approved),
        "productionGenerated": production_generated,
        "productionApproved": len(production_approved),
        "productionTarget": production_target,
        "productionRemaining": max(production_target - production_generated, 0),
    }


def format_training_model_inputs(
    records: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    formatted = []
    for record in records:
        errors = canonical_conversation_errors(
            record.get("conversation"), str(record.get("id", "<missing-id>"))
        )
        if errors:
            raise ValueError("; ".join(errors))
        formatted.append(
            {
                "conversation": deepcopy(record["conversation"]),
                "target": deepcopy(record["target"]),
            }
        )
    return formatted


def validate_candidates(
    records: list[dict[str, Any]],
    seeds: list[dict[str, Any]],
    plan: dict[str, Any],
    near_threshold: float,
    parse_errors: list[str] | None = None,
    resource_consistency_groups: set[str] | None = None,
) -> dict[str, Any]:
    seeds_by_id = {seed["id"]: seed for seed in seeds if seed.get("id")}
    groups_by_id = {group["id"]: group for group in plan["groups"]}
    failures: dict[str, list[str]] = defaultdict(list)
    warnings: dict[str, list[str]] = defaultdict(list)
    global_errors = list(parse_errors or [])
    resource_consistency_groups = resource_consistency_groups or set()
    schema_valid_record_indexes: set[int] = set()

    for record_index, record in enumerate(records):
        record_id = str(record.get("id", "<missing-id>"))
        schema_errors = validate_record(record, generated=True)
        failures[record_id].extend(schema_errors)
        if not schema_errors:
            schema_valid_record_indexes.add(record_index)
        conversation_is_canonical = not canonical_conversation_errors(
            record.get("conversation"), record_id
        )
        source_id = record.get("source_seed_id")
        source_errors = validate_augmentation_sources([source_id], seeds_by_id)
        failures[record_id].extend(source_errors)
        source = seeds_by_id.get(source_id)
        if source:
            if record.get("target") != source.get("target"):
                failures[record_id].append(f"{record_id}: target differs from source seed")
            if record.get("intent") != source.get("intent"):
                failures[record_id].append(f"{record_id}: intent differs from source seed")
        generation_group_id = record.get("generation_group_id")
        group_id = generation_group_id.split("_", 1)[0] if isinstance(generation_group_id, str) else ""
        group = groups_by_id.get(group_id)
        if group is None:
            failures[record_id].append(f"{record_id}: unknown generation group {group_id!r}")
        elif record.get("generation_category") != group.get("name"):
            failures[record_id].append(f"{record_id}: generation_category does not match plan")
        generation_subtype = record.get("generation_subtype")
        if group_id != "G15" and (
            record.get("scenario_resources") is not None
            or record.get("required_resource") is not None
        ):
            failures[record_id].append(
                f"{record_id}: structured scenario metadata is only valid for G15"
            )
        if group_id == "G15" and generation_subtype is None and (
            record.get("scenario_resources") is not None
            or record.get("required_resource") is not None
        ):
            failures[record_id].append(
                f"{record_id}: structured scenario metadata requires generation_subtype"
            )
        if generation_subtype is not None:
            if group_id != "G15" or group is None:
                failures[record_id].append(
                    f"{record_id}: generation_subtype is only valid for G15"
                )
            else:
                subtype_names = {item["name"] for item in group.get("subgroups", [])}
                if generation_subtype not in subtype_names:
                    failures[record_id].append(
                        f"{record_id}: unknown G15 generation_subtype {generation_subtype!r}"
                    )
                elif conversation_is_canonical:
                    failures[record_id].extend(
                        g15_subtype_contract_errors(record, generation_subtype)
                    )
        if conversation_is_canonical:
            failures[record_id].extend(
                resource_consistency_errors(record, group_id, resource_consistency_groups)
            )

    for duplicate_id in duplicate_ids(records):
        failures[duplicate_id].append(f"duplicate id {duplicate_id}")

    exact_pairs: list[dict[str, str]] = []
    normalized_pairs: list[dict[str, str]] = []
    near_pairs: list[dict[str, Any]] = []
    comparable_records = [
        record
        for index, record in enumerate(records)
        if index in schema_valid_record_indexes
    ]
    for left_index, left in enumerate(comparable_records):
        left_id = str(left.get("id", f"index-{left_index}"))
        left_text = conversation_text(left)
        left_normalized = normalized_text(left_text)
        for right in comparable_records[left_index + 1 :]:
            right_id = str(right.get("id", "<missing-id>"))
            right_text = conversation_text(right)
            right_normalized = normalized_text(right_text)
            pair = {"left": left_id, "right": right_id}
            if left_text == right_text:
                exact_pairs.append(pair)
                failures[left_id].append(f"exact duplicate text with {right_id}")
                failures[right_id].append(f"exact duplicate text with {left_id}")
                continue
            if left_normalized == right_normalized:
                normalized_pairs.append(pair)
                failures[left_id].append(f"normalized duplicate text with {right_id}")
                failures[right_id].append(f"normalized duplicate text with {left_id}")
                continue
            ratio = SequenceMatcher(None, left_normalized, right_normalized).ratio()
            if ratio >= near_threshold:
                detail = {**pair, "similarity": round(ratio, 4)}
                near_pairs.append(detail)
                warnings[left_id].append(f"near duplicate with {right_id}: {ratio:.4f}")
                warnings[right_id].append(f"near duplicate with {left_id}: {ratio:.4f}")
                if left.get("source_seed_id") == right.get("source_seed_id"):
                    warnings[left_id].append(f"high sibling similarity for {left.get('source_seed_id')}")
                    warnings[right_id].append(f"high sibling similarity for {right.get('source_seed_id')}")

    results = []
    for record in records:
        record_id = str(record.get("id", "<missing-id>"))
        record_failures = list(dict.fromkeys(failures[record_id]))
        record_warnings = list(dict.fromkeys(warnings[record_id]))
        if record_failures:
            status = (
                "MACHINE_REJECTED"
                if record.get("review_status", "UNREVIEWED") == "DROP"
                else "FAIL"
            )
        else:
            status = "WARN_NEAR_DUPLICATE" if record_warnings else "PASS"
        results.append(
            {
                "id": record_id,
                "status": status,
                "failures": record_failures,
                "warnings": record_warnings,
            }
        )
    status_counts = Counter(result["status"] for result in results)
    machine_validated = status_counts["PASS"] + status_counts["WARN_NEAR_DUPLICATE"]
    machine_rejected = status_counts["MACHINE_REJECTED"]
    blocking_failures = status_counts["FAIL"]
    return {
        "status": "FAIL" if global_errors or blocking_failures else "PASS",
        "total": len(records),
        "passed": status_counts["PASS"],
        "warnings": status_counts["WARN_NEAR_DUPLICATE"],
        "failed": blocking_failures,
        "machineValidated": machine_validated,
        "machineRejected": machine_rejected,
        "blockingFailures": blocking_failures,
        "generationSubtypeCounts": dict(
            sorted(
                Counter(
                    str(record["generation_subtype"])
                    for record in records
                    if record.get("generation_subtype") is not None
                ).items()
            )
        ),
        "nearDuplicateThreshold": near_threshold,
        "exactDuplicates": exact_pairs,
        "normalizedDuplicates": normalized_pairs,
        "nearDuplicates": near_pairs,
        "globalErrors": global_errors,
        "results": results,
    }


def markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Candidate Validation",
        "",
        f"- Status: {report['status']}",
        f"- Total: {report['total']}",
        f"- PASS: {report['passed']}",
        f"- WARN_NEAR_DUPLICATE: {report['warnings']}",
        f"- FAIL: {report['failed']}",
        f"- Machine validated: {report['machineValidatedCandidateCount']}",
        f"- Machine rejected: {report['machineRejected']}",
        f"- Blocking failures: {report['blockingFailures']}",
        f"- Pilot generated: {report['pilotGenerated']}",
        f"- Pilot approved: {report['pilotApproved']}",
        f"- Production generated: {report['productionGenerated']}",
        f"- Production approved: {report['productionApproved']}",
        f"- Production target: {report['productionTarget']}",
        f"- Production remaining: {report['productionRemaining']}",
        f"- Exact duplicates: {len(report['exactDuplicates'])}",
        f"- Normalized duplicates: {len(report['normalizedDuplicates'])}",
        f"- Near duplicates: {len(report['nearDuplicates'])}",
        f"- Near-duplicate threshold: {report['nearDuplicateThreshold']}",
        f"- Approved training export: {report['approvedTrainingExportCount']}",
        f"- Training model input: {report['trainingModelInputCount']}",
        f"- Dry-run excluded from approval: {report['dryRunExcludedFromApproval']}",
        f"- External UNREVIEWED: {report['externalUnreviewed']}",
        f"- Resource consistency groups: {', '.join(report['resourceConsistencyGroups'])}",
        f"- Generation subtype counts: {report['generationSubtypeCounts']}",
        "",
        "## Record results",
        "",
        "| ID | Status | Details |",
        "| --- | --- | --- |",
    ]
    for result in report["results"]:
        details = "; ".join(result["failures"] + result["warnings"]) or "-"
        lines.append(f"| {result['id']} | {result['status']} | {details} |")
    if report["globalErrors"]:
        lines.extend(["", "## Global errors", ""])
        lines.extend(f"- {error}" for error in report["globalErrors"])
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", action="append", type=Path)
    parser.add_argument("--seed", type=Path, default=SEED_PATH)
    parser.add_argument("--plan", type=Path, default=PLAN_PATH)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    args = parser.parse_args()
    paths = args.input or sorted(GENERATED_DIR.glob("*.jsonl"))
    records: list[dict[str, Any]] = []
    parse_errors: list[str] = []
    for path in paths:
        file_records, file_errors = read_jsonl(path)
        records.extend(file_records)
        parse_errors.extend(f"{path.name}: {error}" for error in file_errors)
    seeds, seed_errors = read_jsonl(args.seed)
    parse_errors.extend(seed_errors)
    config = json.loads(args.config.read_text(encoding="utf-8"))
    resource_consistency_groups = set(config.get("single_target_resource_groups", []))
    report = validate_candidates(
        records,
        seeds,
        load_plan(args.plan),
        float(config["near_duplicate_threshold"]),
        parse_errors,
        resource_consistency_groups,
    )
    machine_records = machine_validated_records(records, report["results"])
    pilot_approved_records = approved_stage_export_records(
        records, report["results"], "pilot"
    )
    production_approved_records = approved_stage_export_records(
        records, report["results"], "production"
    )
    approved_records = production_approved_records
    training_model_inputs = format_training_model_inputs(production_approved_records)
    review_counts = Counter(record.get("review_status", "UNREVIEWED") for record in records)
    report["machineValidatedCandidateCount"] = len(machine_records)
    report["approvedTrainingExportCount"] = len(approved_records)
    report["trainingModelInputCount"] = len(training_model_inputs)
    report.update(
        generation_stage_summary(
            records,
            pilot_approved_records,
            production_approved_records,
            load_plan(args.plan),
        )
    )
    report["dryRunExcludedFromApproval"] = sum(
        record.get("generation_mode") == "dry-run" for record in machine_records
    )
    report["externalUnreviewed"] = sum(
        record.get("generation_mode") == "external"
        and record.get("review_status") == "UNREVIEWED"
        for record in machine_records
    )
    report["reviewStatusCounts"] = dict(sorted(review_counts.items()))
    report["resourceConsistencyGroups"] = sorted(resource_consistency_groups)
    write_jsonl(
        PROCESSED_DIR / "machine_validated_candidates.jsonl",
        machine_records,
    )
    write_jsonl(
        PROCESSED_DIR / "approved_pilot_candidates.jsonl", pilot_approved_records
    )
    write_jsonl(
        PROCESSED_DIR / "approved_production_candidates.jsonl",
        production_approved_records,
    )
    write_jsonl(PROCESSED_DIR / "approved_training_candidates.jsonl", approved_records)
    write_jsonl(PROCESSED_DIR / "approved_training_model_input.jsonl", training_model_inputs)
    write_json(REPORT_DIR / "candidate_validation.json", report)
    (REPORT_DIR / "candidate_validation.md").write_text(markdown(report), encoding="utf-8")
    print(f"Candidate validation: {report['status']}")
    print(f"Total: {report['total']}")
    print(f"PASS: {report['passed']}")
    print(f"WARN_NEAR_DUPLICATE: {report['warnings']}")
    print(f"FAIL: {report['failed']}")
    print(f"Exact duplicates: {len(report['exactDuplicates'])}")
    print(f"Near duplicates: {len(report['nearDuplicates'])}")
    print(f"Machine validated: {report['machineValidatedCandidateCount']}")
    print(f"Machine rejected: {report['machineRejected']}")
    print(f"Blocking failures: {report['blockingFailures']}")
    print(f"Pilot generated: {report['pilotGenerated']}")
    print(f"Pilot approved: {report['pilotApproved']}")
    print(f"Production generated: {report['productionGenerated']}")
    print(f"Production approved: {report['productionApproved']}")
    print(f"Production target: {report['productionTarget']}")
    print(f"Production remaining: {report['productionRemaining']}")
    print(f"Approved training export: {report['approvedTrainingExportCount']}")
    print(f"Training model input: {report['trainingModelInputCount']}")
    print(f"Dry-run excluded from approval: {report['dryRunExcludedFromApproval']}")
    print(f"External UNREVIEWED: {report['externalUnreviewed']}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
