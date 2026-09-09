#!/usr/bin/env python3

from __future__ import annotations

import json
import io
import sys
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

from pipeline_common import (  # noqa: E402
    PLAN_PATH,
    SEED_PATH,
    build_g15_scenario_specs,
    build_prompt,
    load_plan,
    read_jsonl,
    select_group_sources,
    select_multi_turn_subtype_sources,
    validate_augmentation_sources,
    validate_record,
)
from build_generation_jobs import build_jobs  # noqa: E402
import generate_candidates as generation_module  # noqa: E402
from validate_candidates import (  # noqa: E402
    approved_stage_export_records,
    approved_training_export_records,
    format_training_model_inputs,
    generation_stage_summary,
    machine_validated_records,
    validate_candidates,
)
from validate_seed import validate_seed_file  # noqa: E402


def seed(
    seed_id: str = "S001",
    split: str = "seed_train_candidate",
    target: dict | None = None,
) -> dict:
    return {
        "id": seed_id,
        "intent": "ACCESS_STATUS",
        "difficulty": ["DIRECT"],
        "conversation": [{"role": "user", "content": "DEV_DB 권한 상태 알려줘."}],
        "target": target
        or {
            "decision": "TOOL",
            "tool_name": "getAccessStatus",
            "arguments": {"resource": "DEV_DB"},
        },
        "split_hint": split,
    }


def candidate(source: dict, candidate_id: str = "AUG_G01_0001") -> dict:
    return {
        "id": candidate_id,
        "source_seed_id": source["id"],
        "generation_group_id": "G01_BATCH_001",
        "generation_category": "access_status_general",
        "generation_provider": "dry-run",
        "generation_model": None,
        "generation_mode": "dry-run",
        "generation_stage": "pilot",
        "generation_run_id": "dry_run",
        "review_status": "UNREVIEWED",
        "intent": source["intent"],
        "difficulty": ["DIRECT"],
        "conversation": [{"role": "user", "content": "내 DEV_DB 권한을 확인해줘."}],
        "target": deepcopy(source["target"]),
    }


def plan() -> dict:
    return {
        "candidate_target_total": 1,
        "groups": [
            {"id": "G01", "name": "access_status_general", "target_candidates": 1}
        ],
    }


def access_request_seed(resource: str = "PROD_DB") -> dict:
    record = seed(
        "S016",
        target={
            "decision": "TOOL",
            "tool_name": "createTicket",
            "arguments": {"type": "ACCESS_REQUEST", "resource": resource},
        },
    )
    record["intent"] = "ACCESS_REQUEST"
    return record


def group_plan(group_id: str, name: str) -> dict:
    return {
        "candidate_target_total": 1,
        "groups": [{"id": group_id, "name": name, "target_candidates": 1}],
    }


def no_tool_seed() -> dict:
    record = seed(
        "S046",
        target={"decision": "NO_TOOL", "tool_name": None, "arguments": {}},
    )
    record["intent"] = "NO_TOOL"
    return record


def g15_plan() -> dict:
    group = deepcopy(next(group for group in load_plan()["groups"] if group["id"] == "G15"))
    return {"candidate_target_total": 100, "groups": [group]}


def g15_candidate(source: dict, subtype: str, conversation: list[dict]) -> dict:
    record = candidate(source, "AUG_G15_TEST_0001")
    record.update(
        {
            "generation_group_id": "G15_BATCH_TEST",
            "generation_category": "multi_turn",
            "generation_subtype": subtype,
            "conversation": conversation,
        }
    )
    return record


def g15_generation_job(source: dict, subtype_name: str) -> dict:
    group = next(group for group in load_plan()["groups"] if group["id"] == "G15")
    subtype = next(item for item in group["subgroups"] if item["name"] == subtype_name)
    specs = build_g15_scenario_specs(subtype, [source], 1)
    return {
        "group": group,
        "generation_group_id": "G15_BATCH_TEST",
        "generation_provider": "openai-compatible",
        "generation_model": "test-model",
        "generation_mode": "external",
        "generation_stage": "pilot",
        "generation_run_id": "test_run",
        "multi_turn_subtype": subtype_name,
        "scenario_specs": specs,
        "prompt": build_prompt(
            group,
            1,
            [source],
            multi_turn_subtype=subtype,
            scenario_specs=specs,
        ),
    }


class PipelineValidationTest(unittest.TestCase):
    def write_jsonl(self, path: Path, records: list[dict]) -> None:
        path.write_text(
            "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
            encoding="utf-8",
        )

    def test_invalid_json_is_detected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "seed.jsonl"
            path.write_text("{invalid\n", encoding="utf-8")
            report = validate_seed_file(path)
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("invalid JSON" in error for error in report["errors"]))

    def test_duplicate_id_is_detected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "seed.jsonl"
            self.write_jsonl(path, [seed(), seed()])
            report = validate_seed_file(path)
        self.assertEqual(["S001"], report["duplicateIds"])

    def test_unsupported_tool_name_is_detected(self):
        record = seed(target={"decision": "TOOL", "tool_name": "deleteUser", "arguments": {}})
        self.assertTrue(any("unsupported tool_name" in error for error in validate_record(record)))

    def test_get_access_status_invalid_resource_is_detected(self):
        record = seed(target={"decision": "TOOL", "tool_name": "getAccessStatus", "arguments": {"resource": "STAGING_DB"}})
        self.assertTrue(any("invalid getAccessStatus resource" in error for error in validate_record(record)))

    def test_create_ticket_invalid_type_is_detected(self):
        record = seed(target={"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "CHANGE", "resource": "DEV_DB"}})
        self.assertTrue(any("invalid createTicket type" in error for error in validate_record(record)))

    def test_account_support_resource_mismatch_is_detected(self):
        record = seed(target={"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCOUNT_SUPPORT", "resource": "ACCOUNT"}})
        self.assertTrue(any("ACCOUNT_SUPPORT resource" in error for error in validate_record(record)))

    def test_no_tool_arguments_are_detected(self):
        record = seed(target={"decision": "NO_TOOL", "tool_name": None, "arguments": {"resource": "VPN"}})
        self.assertTrue(any("NO_TOOL arguments" in error for error in validate_record(record)))

    def test_heldout_source_is_rejected(self):
        heldout = seed("S029", "heldout_candidate")
        errors = validate_augmentation_sources(["S029"], {"S029": heldout})
        self.assertTrue(any("cannot be an augmentation source" in error for error in errors))

    def test_historical_regression_source_is_rejected(self):
        historical = seed("S044", "historical_regression_candidate")
        errors = validate_augmentation_sources(["S044"], {"S044": historical})
        self.assertTrue(any("cannot be an augmentation source" in error for error in errors))

    def test_generation_prompts_exclude_heldout_and_historical_content(self):
        seeds, seed_errors = read_jsonl(SEED_PATH)
        self.assertEqual([], seed_errors)
        blocked = [
            item
            for item in seeds
            if item["split_hint"]
            in {"heldout_candidate", "historical_regression_candidate"}
        ]
        blocked_ids = {item["id"] for item in blocked}
        jobs, errors = build_jobs(PLAN_PATH, SEED_PATH, dry_run=False)
        self.assertEqual([], errors)
        plan_text = PLAN_PATH.read_text(encoding="utf-8")
        prompt_text = (PLAN_PATH.parent.parent / "prompts/augmentation_generation_prompts.md").read_text(
            encoding="utf-8"
        )
        all_final_prompts = "\n".join(job["prompt"] for job in jobs)

        for blocked_id in blocked_ids:
            self.assertNotIn(blocked_id, plan_text)
            self.assertNotIn(blocked_id, prompt_text)
            self.assertNotIn(blocked_id, all_final_prompts)
        for job in jobs:
            self.assertTrue(blocked_ids.isdisjoint(job["source_seed_ids"]))
        for item in blocked:
            serialized_conversation = json.dumps(
                item["conversation"], ensure_ascii=False
            )
            literal_conversation = "\n".join(
                message["content"] for message in item["conversation"]
            )
            self.assertNotIn(serialized_conversation, all_final_prompts)
            self.assertNotIn(literal_conversation, prompt_text)
            self.assertNotIn(literal_conversation, all_final_prompts)
        self.assertNotIn(
            "로그인 실패해. 계정 지원 티켓 만들어줘.", prompt_text
        )

    def test_g11_sources_and_pipeline_owned_target_preserve_account_support(self):
        seeds, seed_errors = read_jsonl(SEED_PATH)
        self.assertEqual([], seed_errors)
        full_plan = load_plan()
        g11 = next(group for group in full_plan["groups"] if group["id"] == "G11")
        sources = select_group_sources(g11, seeds)
        self.assertTrue(sources)
        expected_target = {
            "decision": "TOOL",
            "tool_name": "createTicket",
            "arguments": {
                "type": "ACCOUNT_SUPPORT",
                "resource": "ACCOUNT_SUPPORT",
            },
        }
        self.assertTrue(
            all(
                source["split_hint"] == "seed_train_candidate"
                and source["intent"] == "ACCOUNT_SUPPORT"
                and source["target"] == expected_target
                for source in sources
            )
        )
        self.assertTrue(
            {"S029", "S044", "S047", "S050", "S062", "S063", "S064", "S065"}.isdisjoint(
                source["id"] for source in sources
            )
        )

        source = sources[0]
        job = {
            "group": g11,
            "generation_group_id": "G11_BATCH_TEST",
            "generation_provider": "openai-compatible",
            "generation_model": "test-model",
            "generation_mode": "external",
            "generation_stage": "pilot",
            "generation_run_id": "test_run",
            "prompt": build_prompt(g11, 1, [source]),
        }
        raw = [
            {
                "source_seed_id": source["id"],
                "difficulty": ["FAILURE_KEYWORD_CONFLICT"],
                "conversation": [
                    {
                        "role": "user",
                        "content": "인증 오류가 반복돼. 계정 관련 지원 요청을 접수해줘.",
                    }
                ],
                "target": {
                    "decision": "TOOL",
                    "tool_name": "createTicket",
                    "arguments": {"type": "INCIDENT", "resource": "VPN"},
                },
            }
        ]
        generated = generation_module.assemble_candidates(
            raw, job, {source["id"]: source}
        )[0]
        self.assertEqual(expected_target, generated["target"])
        self.assertIn(
            "Do not choose or emit target labels",
            generation_module.generation_instruction(job),
        )

        g07 = next(group for group in full_plan["groups"] if group["id"] == "G07")
        incident_sources = select_group_sources(g07, seeds)
        self.assertTrue(incident_sources)
        self.assertTrue(
            all(
                source["target"]["arguments"]["type"] == "INCIDENT"
                for source in incident_sources
            )
        )

    def test_exact_duplicate_is_failed(self):
        source = seed()
        first = candidate(source, "AUG_G01_0001")
        second = candidate(source, "AUG_G01_0002")
        report = validate_candidates([first, second], [source], plan(), 0.9)
        self.assertEqual(2, report["failed"])
        self.assertEqual(1, len(report["exactDuplicates"]))

    def test_near_duplicate_emits_warning(self):
        source = seed()
        first = candidate(source, "AUG_G01_0001")
        second = candidate(source, "AUG_G01_0002")
        second["conversation"][0]["content"] = "내 DEV_DB 권한을 지금 확인해줘."
        report = validate_candidates([first, second], [source], plan(), 0.7)
        self.assertEqual(0, report["failed"])
        self.assertGreater(report["warnings"], 0)

    def test_missing_source_seed_id_is_detected(self):
        source = seed()
        record = candidate(source)
        record.pop("source_seed_id")
        report = validate_candidates([record], [source], plan(), 0.9)
        self.assertEqual(1, report["failed"])
        self.assertTrue(any("source_seed_id" in item for item in report["results"][0]["failures"]))

    def test_missing_generation_group_id_is_detected(self):
        source = seed()
        record = candidate(source)
        record.pop("generation_group_id")
        report = validate_candidates([record], [source], plan(), 0.9)
        self.assertEqual(1, report["failed"])
        self.assertTrue(any("generation_group_id" in item for item in report["results"][0]["failures"]))

    def test_missing_provenance_is_detected(self):
        source = seed()
        for field in (
            "generation_provider",
            "generation_model",
            "generation_mode",
            "generation_stage",
            "generation_run_id",
        ):
            with self.subTest(field=field):
                record = candidate(source)
                record.pop(field)
                report = validate_candidates([record], [source], plan(), 0.9)
                self.assertEqual(1, report["failed"])
                self.assertTrue(
                    any(field in item for item in report["results"][0]["failures"])
                )

    def test_dry_run_candidate_is_excluded_from_approved_training_export(self):
        source = seed()
        dry_run = candidate(source, "AUG_G01_001_0001")
        dry_run["review_status"] = "KEEP"
        external = candidate(source, "AUG_G01_002_0001")
        external.update(
            {
                "generation_provider": "openai-compatible",
                "generation_model": "gpt-4o-mini",
                "generation_mode": "external",
                "generation_stage": "production",
                "generation_run_id": "prod_test",
                "review_status": "KEEP",
            }
        )
        results = [
            {"id": dry_run["id"], "status": "PASS"},
            {"id": external["id"], "status": "PASS"},
        ]
        exported = approved_training_export_records([dry_run, external], results)
        self.assertEqual([external["id"]], [record["id"] for record in exported])

    def test_external_candidate_defaults_to_unreviewed_and_is_not_approved(self):
        source = seed()
        external = candidate(source)
        external.update(
            {
                "generation_provider": "openai-compatible",
                "generation_model": "gpt-4o-mini",
                "generation_mode": "external",
            }
        )
        external.pop("review_status")
        results = [{"id": external["id"], "status": "PASS"}]
        machine_records = machine_validated_records([external], results)
        approved = approved_training_export_records([external], results)
        self.assertEqual("UNREVIEWED", machine_records[0]["review_status"])
        self.assertEqual([], approved)

    def test_only_keep_review_status_is_approved(self):
        source = seed()
        records = []
        for index, status in enumerate(("KEEP", "REWRITE", "DROP", "UNREVIEWED"), 1):
            record = candidate(source, f"AUG_G01_002_{index:04d}")
            record.update(
                {
                    "generation_provider": "openai-compatible",
                    "generation_model": "gpt-4o-mini",
                    "generation_mode": "external",
                    "generation_stage": "production",
                    "generation_run_id": "prod_test",
                    "review_status": status,
                }
            )
            records.append(record)
        results = [{"id": record["id"], "status": "PASS"} for record in records]
        approved = approved_training_export_records(records, results)
        self.assertEqual([records[0]["id"]], [record["id"] for record in approved])

    def test_pilot_and_production_approved_exports_are_separated(self):
        source = seed()
        pilot = candidate(source, "AUG_G01_PILOT_0001")
        pilot.update(
            {
                "generation_provider": "openai-compatible",
                "generation_model": "gpt-4o-mini",
                "generation_mode": "external",
                "generation_stage": "pilot",
                "generation_run_id": "pilot_test",
                "review_status": "KEEP",
            }
        )
        production = deepcopy(pilot)
        production.update(
            {
                "id": "AUG_G01_PROD_0001",
                "generation_stage": "production",
                "generation_run_id": "prod_test",
            }
        )
        records = [pilot, production]
        results = [{"id": record["id"], "status": "PASS"} for record in records]
        self.assertEqual(
            [pilot["id"]],
            [
                record["id"]
                for record in approved_stage_export_records(records, results, "pilot")
            ],
        )
        self.assertEqual(
            [production["id"]],
            [
                record["id"]
                for record in approved_stage_export_records(
                    records, results, "production"
                )
            ],
        )
        self.assertEqual(
            [production["id"]],
            [record["id"] for record in approved_training_export_records(records, results)],
        )

    def test_pilot_candidates_do_not_reduce_production_quota(self):
        source = seed()
        pilot_records = [candidate(source, f"AUG_G01_PILOT_{index:04d}") for index in range(7)]
        summary = generation_stage_summary(pilot_records, [], [], load_plan())
        self.assertEqual(7, summary["pilotGenerated"])
        self.assertEqual(0, summary["productionGenerated"])
        self.assertEqual(1000, summary["productionTarget"])
        self.assertEqual(1000, summary["productionRemaining"])
        g15 = next(group for group in load_plan()["groups"] if group["id"] == "G15")
        self.assertEqual(100, g15["target_candidates"])

    def test_invalid_review_status_is_detected(self):
        source = seed()
        record = candidate(source)
        record["review_status"] = "APPROVED"
        report = validate_candidates([record], [source], plan(), 0.9)
        self.assertEqual(1, report["failed"])
        self.assertTrue(
            any("invalid review_status" in item for item in report["results"][0]["failures"])
        )

    def test_g06_prompt_keeps_explicit_access_request_quality_rules(self):
        g06 = next(group for group in load_plan()["groups"] if group["id"] == "G06")
        prompt = build_prompt(g06, 1, [access_request_seed()])
        self.assertIn("접근 권한 요청", prompt)
        self.assertIn("generic cue", prompt)
        self.assertIn("권한 만들어줘", prompt)
        self.assertIn("near-paraphrase", prompt)
        self.assertIn("한국어로 부자연스러운 표현을 피한다", prompt)
        self.assertIn("접근 권한 요청할게", prompt)
        self.assertIn("요청할게", prompt)
        self.assertIn("신청할게", prompt)
        self.assertIn("신청하고 싶어", prompt)
        self.assertIn("신청할 필요가 있어", prompt)
        self.assertIn("Agent에게 권한 신청 실행을 명시적으로 요구", prompt)
        self.assertIn("required_decision = TOOL", prompt)
        self.assertIn("required_tool_name = createTicket", prompt)
        self.assertIn("required_type = ACCESS_REQUEST", prompt)
        self.assertIn("required_resource = PROD_DB", prompt)

    def test_g06_mismatched_unreviewed_resource_is_blocking_failure(self):
        source = access_request_seed("PROD_DB")
        record = candidate(source, "AUG_G06_005_0001")
        record.update(
            {
                "generation_group_id": "G06_BATCH_005",
                "generation_category": "access_request_intent_reason_conflict",
                "conversation": [{"role": "user", "content": "DEV_DB 권한 신청해줘"}],
            }
        )
        report = validate_candidates(
            [record],
            [source],
            group_plan("G06", "access_request_intent_reason_conflict"),
            0.9,
            resource_consistency_groups={"G06"},
        )
        self.assertEqual(1, report["failed"])
        self.assertEqual("FAIL", report["status"])
        self.assertEqual(1, report["blockingFailures"])
        self.assertEqual(0, report["machineRejected"])
        self.assertTrue(
            any("does not match target resource PROD_DB" in error for error in report["results"][0]["failures"])
        )

    def test_g06_mismatched_drop_resource_is_non_blocking_machine_rejected(self):
        source = access_request_seed("PROD_DB")
        record = candidate(source, "AUG_G06_005_0004")
        record.update(
            {
                "generation_group_id": "G06_BATCH_005",
                "generation_category": "access_request_intent_reason_conflict",
                "review_status": "DROP",
                "conversation": [{"role": "user", "content": "DEV_DB 권한 신청해줘"}],
            }
        )
        report = validate_candidates(
            [record],
            [source],
            group_plan("G06", "access_request_intent_reason_conflict"),
            0.9,
            resource_consistency_groups={"G06"},
        )
        self.assertEqual("PASS", report["status"])
        self.assertEqual(1, report["machineRejected"])
        self.assertEqual(0, report["blockingFailures"])
        self.assertEqual("MACHINE_REJECTED", report["results"][0]["status"])
        self.assertTrue(report["results"][0]["failures"])

    def test_drop_record_is_never_in_approved_export(self):
        source = access_request_seed("PROD_DB")
        record = candidate(source, "AUG_G06_005_0005")
        record.update(
            {
                "generation_provider": "openai-compatible",
                "generation_model": "gpt-4o-mini",
                "generation_mode": "external",
                "review_status": "DROP",
            }
        )
        results = [{"id": record["id"], "status": "PASS"}]
        self.assertEqual([], approved_training_export_records([record], results))

    def test_g06_matching_explicit_resource_passes(self):
        source = access_request_seed("PROD_DB")
        record = candidate(source, "AUG_G06_005_0002")
        record.update(
            {
                "generation_group_id": "G06_BATCH_005",
                "generation_category": "access_request_intent_reason_conflict",
                "conversation": [{"role": "user", "content": "PROD_DB 권한 신청해줘"}],
            }
        )
        report = validate_candidates(
            [record],
            [source],
            group_plan("G06", "access_request_intent_reason_conflict"),
            0.9,
            resource_consistency_groups={"G06"},
        )
        self.assertEqual(0, report["failed"])

    def test_g06_multiple_explicit_resources_are_failed(self):
        source = access_request_seed("PROD_DB")
        record = candidate(source, "AUG_G06_005_0003")
        record.update(
            {
                "generation_group_id": "G06_BATCH_005",
                "generation_category": "access_request_intent_reason_conflict",
                "conversation": [
                    {"role": "user", "content": "DEV_DB 말고 PROD_DB 권한 신청해줘"}
                ],
            }
        )
        report = validate_candidates(
            [record],
            [source],
            group_plan("G06", "access_request_intent_reason_conflict"),
            0.9,
            resource_consistency_groups={"G06"},
        )
        self.assertEqual(1, report["failed"])
        self.assertTrue(
            any("multiple supported resources" in error for error in report["results"][0]["failures"])
        )

    def test_g15_multiple_resources_remain_allowed(self):
        source = access_request_seed("PROD_DB")
        record = candidate(source, "AUG_G15_005_0001")
        record.update(
            {
                "generation_group_id": "G15_BATCH_005",
                "generation_category": "multi_turn",
                "conversation": [
                    {"role": "user", "content": "DEV_DB 상태를 먼저 확인해줘."},
                    {"role": "assistant", "content": "DEV_DB 상태를 확인했습니다."},
                    {"role": "user", "content": "그거 말고 PROD_DB 권한 신청해줘."},
                ],
            }
        )
        report = validate_candidates(
            [record],
            [source],
            group_plan("G15", "multi_turn"),
            0.9,
            resource_consistency_groups={"G06"},
        )
        self.assertEqual(0, report["failed"])

    def test_g15_generation_plan_keeps_four_explicit_subtype_jobs(self):
        expected = {
            "single_resource_write": 25,
            "multi_resource_write": 25,
            "single_resource_howto": 25,
            "multi_resource_howto": 25,
        }
        expected_source_intents = {
            "single_resource_write": "ACCESS_REQUEST",
            "multi_resource_write": "ACCESS_REQUEST",
            "single_resource_howto": "NO_TOOL",
            "multi_resource_howto": "NO_TOOL",
        }
        plan = load_plan()
        g15 = next(group for group in plan["groups"] if group["id"] == "G15")
        self.assertEqual(
            expected,
            {
                subgroup["name"]: subgroup["target_candidates"]
                for subgroup in g15["subgroups"]
            },
        )
        self.assertEqual(
            expected_source_intents,
            {
                subgroup["name"]: subgroup["source_intent"]
                for subgroup in g15["subgroups"]
            },
        )
        self.assertTrue(
            all(
                subgroup["source_split"] == "seed_train_candidate"
                for subgroup in g15["subgroups"]
            )
        )

        jobs, errors = build_jobs(PLAN_PATH, SEED_PATH, dry_run=False)
        seeds, seed_errors = read_jsonl(SEED_PATH)
        self.assertEqual([], seed_errors)
        seeds_by_id = {item["id"]: item for item in seeds}
        self.assertEqual([], errors)
        self.assertEqual(1000, sum(job["target_count"] for job in jobs))
        g15_jobs = [
            job for job in jobs if job["generation_group_id"].startswith("G15_")
        ]
        self.assertEqual(
            expected,
            {job["multi_turn_subtype"]: job["target_count"] for job in g15_jobs},
        )
        self.assertEqual(100, sum(job["target_count"] for job in g15_jobs))
        for job in g15_jobs:
            subtype = next(
                item
                for item in g15["subgroups"]
                if item["name"] == job["multi_turn_subtype"]
            )
            self.assertIn(
                f"multi_turn_subtype = {job['multi_turn_subtype']}",
                job["prompt"],
            )
            self.assertIn(subtype["resource_count_requirement"], job["prompt"])
            self.assertIn(subtype["final_turn_requirement"], job["prompt"])
            self.assertIn(subtype["target_requirement"], job["prompt"])
            self.assertTrue(job["scenario_specs"])
            self.assertTrue(
                all(
                    seeds_by_id[source_id]["split_hint"] == "seed_train_candidate"
                    and seeds_by_id[source_id]["intent"]
                    == expected_source_intents[job["multi_turn_subtype"]]
                    for source_id in job["source_seed_ids"]
                )
            )

    def test_g15_structured_scenarios_are_pipeline_owned(self):
        g15 = next(group for group in load_plan()["groups"] if group["id"] == "G15")
        subtypes = {item["name"]: item for item in g15["subgroups"]}
        write_source = access_request_seed("PROD_DB")
        no_tool = no_tool_seed()

        single_write = build_g15_scenario_specs(
            subtypes["single_resource_write"], [write_source], 1
        )[0]
        self.assertEqual(["PROD_DB"], single_write["scenario_resources"])
        self.assertEqual("PROD_DB", single_write["required_resource"])

        multi_write = build_g15_scenario_specs(
            subtypes["multi_resource_write"], [write_source], 1
        )[0]
        self.assertNotEqual(
            multi_write["scenario_resources"][0], multi_write["required_resource"]
        )
        self.assertEqual(
            multi_write["required_resource"], multi_write["scenario_resources"][-1]
        )
        multi_write_conversation = generation_module.dry_run_g15_conversation(multi_write)
        self.assertIn(multi_write["scenario_resources"][0], multi_write_conversation[0]["content"])
        self.assertIn(multi_write["required_resource"], multi_write_conversation[2]["content"])
        self.assertFalse(
            any(resource in multi_write_conversation[-1]["content"] for resource in ("DEV_DB", "PROD_DB", "VPN"))
        )

        multi_howto = build_g15_scenario_specs(
            subtypes["multi_resource_howto"], [no_tool], 1
        )[0]
        self.assertEqual(2, len(set(multi_howto["scenario_resources"])))
        multi_howto_conversation = generation_module.dry_run_g15_conversation(multi_howto)
        self.assertGreaterEqual(len(multi_howto_conversation), 5)
        self.assertEqual("user", multi_howto_conversation[0]["role"])
        self.assertEqual("user", multi_howto_conversation[2]["role"])
        self.assertIn(multi_howto["scenario_resources"][0], multi_howto_conversation[0]["content"])
        self.assertIn(multi_howto["scenario_resources"][1], multi_howto_conversation[2]["content"])

    def test_g15_prompt_does_not_include_source_conversation_verbatim(self):
        source = access_request_seed("PROD_DB")
        source["conversation"][0]["content"] = "UNIQUE_SOURCE_CONVERSATION_MARKER"
        g15 = next(group for group in load_plan()["groups"] if group["id"] == "G15")
        subtype = next(
            item for item in g15["subgroups"] if item["name"] == "single_resource_write"
        )
        prompt = build_prompt(g15, 1, [source], multi_turn_subtype=subtype)
        self.assertNotIn("UNIQUE_SOURCE_CONVERSATION_MARKER", prompt)
        self.assertIn("STRUCTURED SCENARIO SPECS", prompt)
        self.assertIn('"scenario_resources": ["PROD_DB"]', prompt)
        self.assertNotIn("각 record에는 다음 필드를 포함한다", prompt)
        self.assertNotIn("동일한 source_seed_id를 기록하라", prompt)
        self.assertIn("canonical conversation array만 포함한다", prompt)

    def test_g15_source_selection_blocks_heldout_and_historical(self):
        training = access_request_seed("PROD_DB")
        training["id"] = "S_TRAIN"
        heldout = access_request_seed("PROD_DB")
        heldout.update({"id": "S_HELDOUT", "split_hint": "heldout_candidate"})
        historical = access_request_seed("PROD_DB")
        historical.update(
            {"id": "S_HIST", "split_hint": "historical_regression_candidate"}
        )
        subtype = {
            "source_split": "seed_train_candidate",
            "source_intent": "ACCESS_REQUEST",
        }
        selected = select_multi_turn_subtype_sources(
            subtype, [training, heldout, historical]
        )
        self.assertEqual(["S_TRAIN"], [item["id"] for item in selected])

    def test_g15_runtime_count_is_the_final_prompt_source_of_truth(self):
        def generated_from_specs(job, _sources, count):
            records = [
                {
                    "source_seed_id": spec["source_seed_id"],
                    "difficulty": "medium",
                    "conversation": generation_module.dry_run_g15_conversation(spec),
                }
                for spec in job["scenario_specs"]
            ]
            self.assertEqual(count, len(records))
            raw = "".join(
                json.dumps(record, ensure_ascii=False) + "\n" for record in records
            )
            return records, raw
        argv = [
            "generate_candidates.py",
            "--group",
            "G15",
            "--count",
            "2",
            "--batch",
            "TEST",
            "--provider",
            "dry-run",
            "--multi-turn-subtype",
            "single_resource_write",
        ]
        with tempfile.TemporaryDirectory() as directory:
            with (
                patch.object(sys, "argv", argv),
                patch.object(generation_module, "GENERATED_DIR", Path(directory)),
                patch.object(
                    generation_module.DryRunGeneratorClient,
                    "generate",
                    side_effect=generated_from_specs,
                ) as generate,
            ):
                self.assertEqual(0, generation_module.main())
                generated, errors = generation_module.read_jsonl(
                    Path(directory) / "G15_BATCH_TEST.jsonl"
                )
                self.assertEqual([], errors)
                self.assertEqual(
                    ["single_resource_write", "single_resource_write"],
                    [record["generation_subtype"] for record in generated],
                )
                self.assertTrue(all(record["scenario_resources"] for record in generated))
                self.assertTrue(all(record["required_resource"] for record in generated))
                self.assertEqual(
                    [["MULTI_TURN"], ["MULTI_TURN"]],
                    [record["difficulty"] for record in generated],
                )
                self.assertTrue(
                    all(record["generation_stage"] == "pilot" for record in generated)
                )
                self.assertTrue(
                    all(record["generation_run_id"] == "dry_run" for record in generated)
                )
        job, _, requested_count = generate.call_args.args
        self.assertEqual(2, requested_count)
        final_prompt = generation_module.generation_instruction(job)
        self.assertEqual(1, final_prompt.count("생성 개수: 2"))
        self.assertNotIn("생성 개수: 10", final_prompt)
        self.assertNotIn("10개 생성", final_prompt)
        self.assertNotIn("25개씩 생성", final_prompt)
        self.assertIn("must contain only `conversation`", final_prompt)
        self.assertIn("Never return a plain-text dialogue string", final_prompt)

    def test_g15_generation_rejects_plain_text_conversation(self):
        source = access_request_seed("PROD_DB")
        job = g15_generation_job(source, "single_resource_write")
        raw = [{"conversation": "사용자: PROD_DB 상태를 알려줘.\n사용자: 신청해줘."}]
        with self.assertRaisesRegex(
            RuntimeError, "provider record 1: conversation must be a non-empty list"
        ):
            generation_module.assemble_candidates(raw, job, {source["id"]: source})

    def test_external_generation_requires_stage_and_run_id_before_provider(self):
        base = [
            "generate_candidates.py",
            "--group",
            "G01",
            "--count",
            "1",
            "--provider",
            "openai-compatible",
        ]
        for extra in ([], ["--stage", "pilot"], ["--run-id", "pilot_test"]):
            with (
                self.subTest(extra=extra),
                patch.object(sys, "argv", base + extra),
                patch.object(sys, "stderr", io.StringIO()),
            ):
                with self.assertRaises(SystemExit) as raised:
                    generation_module.main()
                self.assertEqual(2, raised.exception.code)

    def test_g15_generation_accepts_canonical_array_and_owns_difficulty(self):
        source = no_tool_seed()
        job = g15_generation_job(source, "single_resource_howto")
        raw = [
            {
                "source_seed_id": "PROVIDER_MUST_NOT_OWN_THIS",
                "difficulty": "medium",
                "conversation": [
                    {"role": "user", "content": "DEV_DB 권한 기준을 알려줘."},
                    {"role": "assistant", "content": "권한 기준을 안내했습니다."},
                    {"role": "user", "content": "그 권한 신청 절차는 어떻게 돼?"},
                ],
            }
        ]
        generated = generation_module.assemble_candidates(
            raw, job, {source["id"]: source}
        )[0]
        self.assertEqual(source["id"], generated["source_seed_id"])
        self.assertEqual(["MULTI_TURN", "NO_TOOL_LOOKALIKE"], generated["difficulty"])
        self.assertIsInstance(generated["conversation"], list)

    def test_non_g15_generation_keeps_existing_provider_fields(self):
        source = seed()
        job = {
            "group": {"id": "G01", "name": "access_status_general"},
            "generation_group_id": "G01_BATCH_TEST",
            "generation_provider": "dry-run",
            "generation_model": None,
            "generation_mode": "dry-run",
            "generation_stage": "pilot",
            "generation_run_id": "dry_run",
            "prompt": "test",
        }
        raw = [
            {
                "source_seed_id": source["id"],
                "difficulty": ["DIRECT"],
                "conversation": deepcopy(source["conversation"]),
            }
        ]
        generated = generation_module.assemble_candidates(
            raw, job, {source["id"]: source}
        )[0]
        self.assertEqual(raw[0]["difficulty"], generated["difficulty"])
        self.assertEqual(raw[0]["conversation"], generated["conversation"])
        self.assertIn("source_seed_id, difficulty, and conversation", generation_module.generation_instruction(job))

    def test_provider_count_mismatch_is_rejected_without_truncation(self):
        content = "\n".join(
            json.dumps(
                {
                    "source_seed_id": "S043",
                    "difficulty": ["MULTI_TURN"],
                    "conversation": [{"role": "user", "content": f"요청 {index}"}],
                },
                ensure_ascii=False,
            )
            for index in range(3)
        )
        response_body = json.dumps(
            {"choices": [{"message": {"content": content}}]}
        ).encode("utf-8")

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return None

            def read(self):
                return response_body

        client = object.__new__(generation_module.OpenAICompatibleGeneratorClient)
        client.api_key = "test-key"
        client.base_url = "https://example.invalid/v1"
        client.model = "test-model"
        with patch.object(
            generation_module.urllib.request,
            "urlopen",
            return_value=FakeResponse(),
        ):
            with self.assertRaisesRegex(
                RuntimeError,
                "provider returned 3 records, expected 2",
            ):
                client.generate({"prompt": "생성 개수: 2"}, [], 2)

    def test_single_resource_write_rejects_two_resources(self):
        source = access_request_seed("PROD_DB")
        record = g15_candidate(
            source,
            "single_resource_write",
            [
                {"role": "user", "content": "DEV_DB와 PROD_DB 권한을 확인해줘."},
                {"role": "assistant", "content": "권한 상태를 확인했습니다."},
                {"role": "user", "content": "그 권한을 신청해줘."},
            ],
        )
        report = validate_candidates([record], [source], g15_plan(), 0.9)
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(
            any("requires exactly 1" in error for error in report["results"][0]["failures"])
        )

    def test_multi_resource_write_rejects_latest_resource_target_mismatch(self):
        source = access_request_seed("PROD_DB")
        record = g15_candidate(
            source,
            "multi_resource_write",
            [
                {"role": "user", "content": "PROD_DB 상태를 확인해줘."},
                {"role": "assistant", "content": "PROD_DB 상태를 확인했습니다."},
                {"role": "user", "content": "DEV_DB도 확인해줘."},
                {"role": "assistant", "content": "DEV_DB 상태를 확인했습니다."},
                {"role": "user", "content": "그 권한을 신청해줘."},
            ],
        )
        report = validate_candidates([record], [source], g15_plan(), 0.9)
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(
            any("latest explicit resource DEV_DB" in error for error in report["results"][0]["failures"])
        )

    def test_multi_resource_write_accepts_latest_resource_reference(self):
        source = access_request_seed("PROD_DB")
        record = g15_candidate(
            source,
            "multi_resource_write",
            [
                {"role": "user", "content": "DEV_DB 상태를 확인해줘."},
                {"role": "assistant", "content": "DEV_DB 상태를 확인했습니다."},
                {"role": "user", "content": "PROD_DB도 확인해줘."},
                {"role": "assistant", "content": "PROD_DB 상태를 확인했습니다."},
                {"role": "user", "content": "그 권한 신청해줘."},
            ],
        )
        record["scenario_resources"] = ["DEV_DB", "PROD_DB"]
        record["required_resource"] = "PROD_DB"
        report = validate_candidates([record], [source], g15_plan(), 0.9)
        self.assertEqual("PASS", report["status"])

    def test_single_resource_howto_accepts_one_resource_no_tool(self):
        source = no_tool_seed()
        record = g15_candidate(
            source,
            "single_resource_howto",
            [
                {"role": "user", "content": "DEV_DB 권한 상태를 확인해줘."},
                {"role": "assistant", "content": "DEV_DB 권한 상태를 확인했습니다."},
                {"role": "user", "content": "그 권한 신청 절차가 궁금해."},
            ],
        )
        record["scenario_resources"] = ["DEV_DB"]
        report = validate_candidates([record], [source], g15_plan(), 0.9)
        self.assertEqual("PASS", report["status"])

    def test_multi_resource_howto_rejects_resources_listed_in_one_turn(self):
        source = no_tool_seed()
        record = g15_candidate(
            source,
            "multi_resource_howto",
            [
                {"role": "user", "content": "DEV_DB와 PROD_DB 권한 상태를 확인해줘."},
                {"role": "assistant", "content": "권한 상태를 확인했습니다."},
                {"role": "user", "content": "그 권한 신청 방법을 알려줘."},
            ],
        )
        report = validate_candidates([record], [source], g15_plan(), 0.9)
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(
            any("first user turn" in error for error in report["results"][0]["failures"])
        )

    def test_multi_resource_howto_accepts_sequential_resource_turns(self):
        source = no_tool_seed()
        record = g15_candidate(
            source,
            "multi_resource_howto",
            [
                {"role": "user", "content": "DEV_DB 상태를 확인해줘."},
                {"role": "assistant", "content": "DEV_DB 상태를 확인했습니다."},
                {"role": "user", "content": "PROD_DB도 확인해줘."},
                {"role": "assistant", "content": "PROD_DB 상태를 확인했습니다."},
                {"role": "user", "content": "그 권한 신청 절차가 궁금해."},
            ],
        )
        record["scenario_resources"] = ["DEV_DB", "PROD_DB"]
        report = validate_candidates([record], [source], g15_plan(), 0.9)
        self.assertEqual("PASS", report["status"])

    def test_generation_subtype_is_excluded_from_training_model_input(self):
        source = access_request_seed("PROD_DB")
        record = g15_candidate(
            source,
            "single_resource_write",
            [
                {"role": "user", "content": "PROD_DB 상태를 확인해줘."},
                {"role": "assistant", "content": "PROD_DB 상태를 확인했습니다."},
                {"role": "user", "content": "그 권한을 신청해줘."},
            ],
        )
        record["scenario_resources"] = ["PROD_DB"]
        record["required_resource"] = "PROD_DB"
        formatted = format_training_model_inputs([record])
        self.assertEqual({"conversation", "target"}, set(formatted[0]))
        self.assertNotIn("generation_subtype", formatted[0])
        self.assertNotIn("scenario_resources", formatted[0])
        self.assertNotIn("required_resource", formatted[0])
        self.assertNotIn("generation_stage", formatted[0])
        self.assertNotIn("generation_run_id", formatted[0])

    def test_model_input_formatter_rejects_noncanonical_conversation(self):
        source = access_request_seed("PROD_DB")
        record = g15_candidate(source, "single_resource_write", [])
        record["conversation"] = "사용자: PROD_DB 권한 신청해줘."
        with self.assertRaisesRegex(ValueError, "conversation must be a non-empty list"):
            format_training_model_inputs([record])

    def test_invalid_conversations_are_excluded_from_duplicate_comparison(self):
        source = seed()
        first = candidate(source, "AUG_G01_INVALID_0001")
        second = candidate(source, "AUG_G01_INVALID_0002")
        first["conversation"] = "plain text"
        second["conversation"] = "another plain text"
        report = validate_candidates([first, second], [source], plan(), 0.7)
        self.assertEqual(2, report["failed"])
        self.assertEqual([], report["exactDuplicates"])
        self.assertEqual([], report["normalizedDuplicates"])
        self.assertEqual([], report["nearDuplicates"])
        for result in report["results"]:
            self.assertEqual(1, len(result["failures"]))
            self.assertIn("conversation must be a non-empty list", result["failures"][0])

    def test_structured_multi_resource_write_metadata_must_match_scenario_order(self):
        source = access_request_seed("PROD_DB")
        record = g15_candidate(
            source,
            "multi_resource_write",
            [
                {"role": "user", "content": "DEV_DB 상태를 확인해줘."},
                {"role": "assistant", "content": "첫 권한 상태를 확인했습니다."},
                {"role": "user", "content": "PROD_DB도 확인해줘."},
                {"role": "assistant", "content": "두 번째 권한 상태를 확인했습니다."},
                {"role": "user", "content": "그 권한을 신청해줘."},
            ],
        )
        record["scenario_resources"] = ["PROD_DB", "DEV_DB"]
        record["required_resource"] = "PROD_DB"
        report = validate_candidates([record], [source], g15_plan(), 0.9)
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(
            any(
                "scenario must end with required_resource" in error
                or "resource order" in error
                for error in report["results"][0]["failures"]
            )
        )


if __name__ == "__main__":
    unittest.main()
