#!/usr/bin/env python3
"""Build auditable generation jobs without calling an external model."""

from __future__ import annotations

import argparse
from pathlib import Path

from pipeline_common import (
    PLAN_PATH,
    REPORT_DIR,
    SEED_PATH,
    build_g15_scenario_specs,
    build_prompt,
    duplicate_ids,
    load_plan,
    read_jsonl,
    select_group_sources,
    select_multi_turn_subtype_sources,
    validate_augmentation_sources,
    validate_plan,
    validate_record,
    write_json,
    write_jsonl,
)


DRY_RUN_COUNTS = {"G01": 5, "G06": 10, "G10": 5, "G12": 5, "G15": 10}


def build_jobs(plan_path: Path, seed_path: Path, dry_run: bool) -> tuple[list[dict], list[str]]:
    plan = load_plan(plan_path)
    errors = validate_plan(plan)
    seeds, seed_errors = read_jsonl(seed_path)
    errors.extend(seed_errors)
    for seed in seeds:
        errors.extend(validate_record(seed))
    errors.extend(f"duplicate seed id {seed_id}" for seed_id in duplicate_ids(seeds))
    seeds_by_id = {seed["id"]: seed for seed in seeds if seed.get("id")}
    jobs: list[dict] = []
    for group in plan["groups"]:
        if dry_run and group["id"] not in DRY_RUN_COUNTS:
            continue
        count = DRY_RUN_COUNTS[group["id"]] if dry_run else group["target_candidates"]
        sources = select_group_sources(group, seeds)
        source_ids = [source["id"] for source in sources]
        if not sources:
            errors.append(f"{group['id']}: no eligible seed_train_candidate sources")
            continue
        errors.extend(validate_augmentation_sources(source_ids, seeds_by_id))
        batch = "DRY_001" if dry_run else "PLAN_001"
        generation_group_id = f"{group['id']}_BATCH_001"
        job_specs: list[tuple[dict | None, int]] = [(None, count)]
        if group["id"] == "G15":
            subgroups = group["subgroups"]
            if dry_run:
                per_subtype, remainder = divmod(count, len(subgroups))
                job_specs = [
                    (subgroup, per_subtype + (index < remainder))
                    for index, subgroup in enumerate(subgroups)
                ]
            else:
                job_specs = [
                    (subgroup, int(subgroup["target_candidates"]))
                    for subgroup in subgroups
                ]
        for subtype, job_count in job_specs:
            subtype_name = subtype["name"] if subtype else None
            job_sources = (
                select_multi_turn_subtype_sources(subtype, sources)
                if subtype
                else sources
            )
            job_source_ids = [source["id"] for source in job_sources]
            if not job_sources:
                errors.append(f"{group['id']}/{subtype_name}: no eligible target sources")
                continue
            scenario_specs = (
                build_g15_scenario_specs(subtype, job_sources, job_count)
                if subtype
                else None
            )
            job = {
                "job_id": (
                    f"{group['id']}_{subtype_name}_{batch}"
                    if subtype_name
                    else f"{group['id']}_{batch}"
                ),
                "generation_group_id": generation_group_id,
                "generation_category": group["name"],
                "source_seed_ids": job_source_ids,
                "target_count": job_count,
                "prompt": build_prompt(
                    group,
                    job_count,
                    job_sources,
                    multi_turn_subtype=subtype,
                    scenario_specs=scenario_specs,
                ),
            }
            if subtype_name:
                job["multi_turn_subtype"] = subtype_name
                job["scenario_specs"] = scenario_specs
            jobs.append(job)
    return jobs, errors


def markdown(jobs: list[dict], errors: list[str], dry_run: bool, plan_summary: dict) -> str:
    lines = [
        "# Generation Job Report",
        "",
        f"- Mode: {'DRY_RUN' if dry_run else 'FULL_PLAN_ONLY'}",
        f"- Jobs: {len(jobs)}",
        f"- Planned candidates: {sum(job['target_count'] for job in jobs)}",
        f"- Plan groups: {plan_summary['groupCount']}",
        f"- Full plan target: {plan_summary['candidateTargetTotal']}",
        f"- Full group target sum: {plan_summary['groupTargetTotal']}",
        f"- G15 subgroup sum: {plan_summary['g15SubgroupTotal']}",
        f"- Errors: {len(errors)}",
        "",
        "| Group | Category | Multi-turn subtype | Count | Source seeds |",
        "| --- | --- | --- | ---: | --- |",
    ]
    for job in jobs:
        lines.append(
            f"| {job['generation_group_id']} | {job['generation_category']} | "
            f"{job.get('multi_turn_subtype', '-')} | "
            f"{job['target_count']} | {', '.join(job['source_seed_ids'])} |"
        )
    lines.extend(
        [
            "",
            "## Final prompts",
            "",
            "Each prompt contains `SYSTEM`, `USER`, and group-specific source instructions.",
        ]
    )
    for job in jobs:
        lines.extend(
            [
                "",
                f"<details><summary>{job['job_id']}</summary>",
                "",
                "```text",
                job["prompt"],
                "```",
                "",
                "</details>",
            ]
        )
    lines.extend(["", "## Errors", ""])
    lines.extend(f"- {error}" for error in errors or ["None"])
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--plan", type=Path, default=PLAN_PATH)
    parser.add_argument("--seed", type=Path, default=SEED_PATH)
    args = parser.parse_args()
    jobs, errors = build_jobs(args.plan, args.seed, args.dry_run)
    plan = load_plan(args.plan)
    g15 = next(group for group in plan["groups"] if group["id"] == "G15")
    plan_summary = {
        "groupCount": len(plan["groups"]),
        "candidateTargetTotal": plan["candidate_target_total"],
        "groupTargetTotal": sum(group["target_candidates"] for group in plan["groups"]),
        "g15SubgroupTotal": sum(
            subgroup["target_candidates"] for subgroup in g15["subgroups"]
        ),
    }
    output_name = "generation_jobs_dry_run.jsonl" if args.dry_run else "generation_jobs_full_plan.jsonl"
    write_jsonl(REPORT_DIR / output_name, jobs)
    report = {
        "status": "PASS" if not errors else "FAIL",
        "dryRun": args.dry_run,
        "jobCount": len(jobs),
        "plannedCandidates": sum(job["target_count"] for job in jobs),
        "planValidation": plan_summary,
        "errors": errors,
        "jobs": [{key: value for key, value in job.items() if key != "prompt"} for job in jobs],
    }
    write_json(REPORT_DIR / "generation_jobs_report.json", report)
    (REPORT_DIR / "generation_jobs_report.md").write_text(
        markdown(jobs, errors, args.dry_run, plan_summary), encoding="utf-8"
    )
    print(f"Generation jobs: {report['status']}")
    print(f"Jobs: {len(jobs)}")
    print(f"Planned candidates: {report['plannedCandidates']}")
    print(
        "Full plan: "
        f"{plan_summary['groupCount']} groups, "
        f"{plan_summary['groupTargetTotal']}/{plan_summary['candidateTargetTotal']} candidates, "
        f"G15 subgroups={plan_summary['g15SubgroupTotal']}"
    )
    for job in jobs:
        print(
            f"{job['generation_group_id']}: {job['target_count']} from "
            f"{','.join(job['source_seed_ids'])}"
        )
    for error in errors:
        print(f"ERROR: {error}")
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
