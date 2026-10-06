#!/usr/bin/env python3
"""Generate small candidate batches while keeping labels owned by the pipeline."""

from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.request
from copy import deepcopy
from pathlib import Path
from typing import Any

from pipeline_common import (
    GENERATED_DIR,
    PLAN_PATH,
    SEED_PATH,
    build_g15_scenario_specs,
    build_prompt,
    canonical_conversation_errors,
    load_plan,
    read_jsonl,
    select_group_sources,
    select_multi_turn_subtype_sources,
    validate_augmentation_sources,
    validate_record,
    write_jsonl,
)


def generation_instruction(job: dict[str, Any]) -> str:
    if job.get("scenario_specs"):
        return (
            job["prompt"]
            + "\n\nG15 OUTPUT CONTRACT\n"
            + "Return JSONL with exactly one object per structured scenario spec, in the same "
            + "order. Each object must contain only `conversation`. `conversation` must be a "
            + "non-empty JSON array of objects with `role` (`user` or `assistant`) and non-empty "
            + "string `content`; the final role must be `user`. Example shape: "
            + '{"conversation":[{"role":"user","content":"..."},'
            + '{"role":"assistant","content":"..."},{"role":"user","content":"..."}]}. '
            + "Never return a plain-text dialogue string. Do not emit difficulty, IDs, labels, "
            + "targets, provenance, subtype, or scenario metadata; the pipeline attaches them."
        )
    return (
        job["prompt"]
        + "\n\nJSONL objects must contain only source_seed_id, difficulty, and conversation. "
        + "Do not choose or emit target labels; the pipeline attaches source-controlled targets."
    )


class GeneratorClient:
    def generate(
        self, job: dict[str, Any], sources: list[dict[str, Any]], count: int
    ) -> tuple[list[dict[str, Any]], str]:
        raise NotImplementedError


class DryRunGeneratorClient(GeneratorClient):
    def generate(
        self, job: dict[str, Any], sources: list[dict[str, Any]], count: int
    ) -> tuple[list[dict[str, Any]], str]:
        records = []
        group_id = job["group"]["id"]
        for index in range(count):
            scenario_specs = job.get("scenario_specs")
            scenario_spec = scenario_specs[index] if scenario_specs else None
            source_id = (
                scenario_spec["source_seed_id"]
                if scenario_spec
                else sources[index % len(sources)]["id"]
            )
            source = next(source for source in sources if source["id"] == source_id)
            record = {
                "conversation": (
                    dry_run_g15_conversation(scenario_spec)
                    if scenario_spec
                    else dry_run_conversation(group_id, source, index)
                )
            }
            if not scenario_spec:
                record.update(
                    {
                        "source_seed_id": source["id"],
                        "difficulty": list(
                            job["group"].get("difficulty", source["difficulty"])
                        ),
                    }
                )
            records.append(record)
        raw = "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records)
        return records, raw


class OpenAICompatibleGeneratorClient(GeneratorClient):
    """Small HTTP boundary; no provider SDK or retry framework is required."""

    def __init__(self) -> None:
        self.api_key = os.environ.get("OPENAI_API_KEY")
        if not self.api_key:
            raise RuntimeError("OPENAI_API_KEY is required for provider=openai-compatible")
        self.base_url = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
        self.model = os.environ.get("DEV_DESK_AUGMENTATION_MODEL", "gpt-4o-mini")

    def generate(
        self, job: dict[str, Any], sources: list[dict[str, Any]], count: int
    ) -> tuple[list[dict[str, Any]], str]:
        instruction = generation_instruction(job)
        payload = json.dumps(
            {
                "model": self.model,
                "temperature": 0.7,
                "messages": [{"role": "user", "content": instruction}],
            }
        ).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}/chat/completions",
            data=payload,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                response_body = response.read().decode("utf-8")
        except urllib.error.URLError as error:
            raise RuntimeError(f"generation request failed: {error.reason}") from error
        envelope = json.loads(response_body)
        raw = envelope["choices"][0]["message"]["content"].strip()
        if raw.startswith("```"):
            raw = raw.split("\n", 1)[1].rsplit("```", 1)[0].strip()
        records = [json.loads(line) for line in raw.splitlines() if line.strip()]
        if len(records) != count:
            raise RuntimeError(f"provider returned {len(records)} records, expected {count}")
        return records, raw + "\n"


def dry_run_conversation(group_id: str, source: dict[str, Any], index: int) -> list[dict[str, str]]:
    target = source["target"]
    arguments = target.get("arguments", {})
    resource = arguments.get("resource", "")
    alias = {"DEV_DB": "개발 DB", "PROD_DB": "운영 DB", "VPN": "VPN"}.get(resource, resource)
    templates = {
        "G01": [
            "내 {resource} 권한이 현재 활성 상태인지 확인해줘.",
            "지금 {alias}에 접근 가능한지 봐줄래?",
            "{resource} 사용 권한을 내가 갖고 있는지 알려줘.",
            "작업 전에 {alias} 권한 상태부터 체크 부탁해.",
            "현재 내 계정으로 {resource}를 쓸 수 있는 상태야?",
        ],
        "G06": [
            "장애 대응 로그를 확인해야 해서 {resource} 권한 신청해줘.",
            "{alias} 접근 요청을 넣어줘. 오류 분석 업무에 필요해.",
            "접속 실패 원인을 조사하려고 해. {resource} 권한을 신청해줘.",
            "복구 작업 때문에 {alias} 접근 권한이 필요해. 요청 등록해줘.",
            "{resource} 권한 신청 건 올려줘. 문제 재현을 해야 해.",
            "로그 분석 업무를 맡았으니 {alias} 접근 요청을 접수해줘.",
            "서비스 오류를 살펴볼 수 있도록 {resource} 권한 신청해줘.",
            "{alias}에서 장애 원인을 확인해야 해. 접근 권한 요청 넣어줘.",
            "재현 테스트 때문에 {resource}를 봐야 하니 권한 신청해줘.",
            "{alias} 권한 요청을 등록해줘. 복구 이후 검증 업무 때문이야.",
        ],
        "G10": [
            "내 계정이 잠겼어. 계정 지원 티켓 만들어줘.",
            "회사 계정 로그인이 안 돼. 계정 지원 요청 접수해줘.",
            "사내 계정에 문제가 생겼어. 지원 티켓 하나 올려줘.",
            "계정 지원 절차 설명 말고 실제 지원 티켓을 등록해줘.",
            "로그인 문제를 확인받고 싶어. 계정 지원 건 만들어줘.",
        ],
        "G12": [
            "VPN 권한 신청 절차는 어떻게 진행돼?",
            "운영 DB 접근 승인을 받으려면 어떤 조건이 필요해?",
            "계정이 잠겼을 때 지원 요청 절차를 알려줘.",
            "DEV_DB 권한 신청은 보통 얼마나 걸려?",
            "장애 티켓을 접수할 때 필요한 정보가 뭐야?",
        ],
    }
    if group_id in templates:
        text = templates[group_id][index % len(templates[group_id])].format(
            resource=resource, alias=alias
        )
        return [{"role": "user", "content": text}]
    if group_id == "G15":
        if target.get("decision") == "NO_TOOL":
            openings = [
                "DEV_DB 권한 상태를 먼저 확인해줘.",
                "운영 DB 접근 상태부터 보고 싶어.",
                "VPN 권한이 있는지 우선 봐줘.",
                "개발 DB 사용 가능 여부를 확인해줘.",
            ]
            followups = [
                "그 권한 신청 절차만 설명해줄래?",
                "해당 권한 요청에는 어떤 조건이 필요해?",
                "그 리소스 신청 방법을 알려줘.",
                "방금 확인한 권한의 승인 절차가 궁금해.",
            ]
            return [
                {"role": "user", "content": openings[index % len(openings)]},
                {"role": "assistant", "content": "요청한 권한 상태를 확인했습니다."},
                {"role": "user", "content": followups[index % len(followups)]},
            ]
        if target.get("tool_name") == "getAccessStatus":
            return [
                {"role": "user", "content": f"{alias} 정책을 먼저 알려줘."},
                {"role": "assistant", "content": f"{alias} 정책을 안내했습니다."},
                {"role": "user", "content": "그 리소스에 내 권한이 있는지 확인해줘."},
            ]
        ticket_type = arguments.get("type")
        access_actions = [
            "그 권한을 실제로 신청해줘.",
            "방금 확인한 리소스의 접근 요청을 넣어줘.",
            "그쪽 사용 권한을 받을 수 있게 티켓 올려줘.",
            "확인한 대상에 대한 권한 신청을 접수해줘.",
            "마지막으로 본 리소스 접근 권한을 요청해줘.",
            "그 권한 건을 PENDING 요청으로 등록해줘.",
            "앞에서 말한 대상의 사용 권한을 신청해줘.",
            "설명은 됐고 해당 리소스 접근 티켓을 만들어줘.",
            "그 리소스를 쓸 수 있도록 권한 요청을 올려줘.",
            "조금 전에 확인한 권한을 실제로 신청해줘.",
        ]
        action = {
            "ACCESS_REQUEST": access_actions[index % len(access_actions)],
            "INCIDENT": "그 리소스에서 발생한 장애를 티켓으로 접수해줘.",
            "ACCOUNT_SUPPORT": "리소스 얘기는 됐고 계정 지원 티켓을 만들어줘.",
        }.get(ticket_type, "그 요청을 접수해줘.")
        openers = [
            f"{alias} 접근 정책을 먼저 확인하고 싶어.",
            f"오늘 사용할 {alias}가 어떤 대상인지 알려줘.",
            f"{alias} 관련 권한 상태를 우선 봐줘.",
            f"작업 전에 {alias} 접근 여부부터 확인해줘.",
            f"{alias} 사용 조건을 간단히 안내해줘.",
            f"내 {alias} 권한 상태가 어떤지 먼저 알려줘.",
            f"{alias} 접근이 가능한 상태인지 확인 부탁해.",
            f"지금 {alias}를 쓸 수 있는지부터 보고 싶어.",
            f"{alias} 권한에 관해 먼저 확인할 게 있어.",
            f"업무 대상은 {alias}야. 현재 상태부터 봐줘.",
        ]
        return [
            {"role": "user", "content": openers[index % len(openers)]},
            {"role": "assistant", "content": f"{alias} 관련 내용을 확인했습니다."},
            {"role": "user", "content": action},
        ]
    conversation = deepcopy(source["conversation"])
    conversation[-1]["content"] = conversation[-1]["content"] + f" 요청 번호 {index + 1}."
    return conversation


def dry_run_g15_conversation(spec: dict[str, Any]) -> list[dict[str, str]]:
    resources = spec["scenario_resources"]
    subtype = spec["generation_subtype"]
    if subtype == "single_resource_write":
        return [
            {"role": "user", "content": f"{resources[0]} 권한 상태를 확인해줘."},
            {"role": "assistant", "content": f"{resources[0]} 접근에 관한 문의군요."},
            {"role": "user", "content": "그 권한을 신청해줘."},
        ]
    if subtype == "multi_resource_write":
        return [
            {"role": "user", "content": f"{resources[0]} 권한부터 확인해줘."},
            {"role": "assistant", "content": f"{resources[0]} 접근에 관한 문의군요."},
            {"role": "user", "content": f"이번에는 {resources[1]} 권한도 확인해줘."},
            {"role": "assistant", "content": f"{resources[1]} 접근도 필요한 상황이군요."},
            {"role": "user", "content": "그 권한을 신청해줘."},
        ]
    if subtype == "single_resource_howto":
        return [
            {"role": "user", "content": f"{resources[0]} 권한 기준을 알려줘."},
            {"role": "assistant", "content": f"{resources[0]} 접근 기준에 관한 문의군요."},
            {"role": "user", "content": "그 권한을 신청하는 절차는 어떻게 돼?"},
        ]
    return [
        {"role": "user", "content": f"{resources[0]} 권한 기준을 알려줘."},
        {"role": "assistant", "content": f"{resources[0]} 접근 기준에 관한 문의군요."},
        {"role": "user", "content": f"이번에는 {resources[1]} 권한도 궁금해."},
        {"role": "assistant", "content": f"{resources[1]} 접근 기준도 궁금한 상황이군요."},
        {"role": "user", "content": "그 권한을 신청하는 절차는 어떻게 돼?"},
    ]


def assemble_candidates(
    raw_records: list[dict[str, Any]], job: dict[str, Any], sources_by_id: dict[str, dict[str, Any]]
) -> list[dict[str, Any]]:
    candidates = []
    batch_id = job["generation_group_id"].rsplit("_", 1)[-1]
    for index, raw in enumerate(raw_records, 1):
        scenario_specs = job.get("scenario_specs")
        scenario_spec = scenario_specs[index - 1] if scenario_specs else None
        source_id = (
            scenario_spec["source_seed_id"]
            if scenario_spec
            else raw.get("source_seed_id")
        )
        source = sources_by_id.get(source_id)
        conversation = raw.get("conversation")
        if scenario_spec:
            conversation_errors = canonical_conversation_errors(
                conversation, f"provider record {index}"
            )
            if conversation_errors:
                raise RuntimeError("; ".join(conversation_errors))
            difficulty = ["MULTI_TURN"]
            if job.get("multi_turn_subtype", "").endswith("_howto"):
                difficulty.append("NO_TOOL_LOOKALIKE")
        else:
            difficulty = raw.get("difficulty")
        candidate = {
            "id": f"AUG_{job['group']['id']}_{batch_id}_{index:04d}",
            "source_seed_id": source_id,
            "generation_group_id": job["generation_group_id"],
            "generation_category": job["group"]["name"],
            "generation_provider": job["generation_provider"],
            "generation_model": job["generation_model"],
            "generation_mode": job["generation_mode"],
            "generation_stage": job["generation_stage"],
            "generation_run_id": job["generation_run_id"],
            "review_status": "UNREVIEWED",
            "intent": source.get("intent") if source else None,
            "difficulty": difficulty,
            "conversation": conversation,
        }
        if job.get("multi_turn_subtype"):
            candidate["generation_subtype"] = job["multi_turn_subtype"]
        if scenario_spec:
            candidate["scenario_resources"] = list(scenario_spec["scenario_resources"])
            if "required_resource" in scenario_spec:
                candidate["required_resource"] = scenario_spec["required_resource"]
        if source:
            candidate["target"] = deepcopy(source["target"])
        candidates.append(candidate)
    return candidates


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--group", required=True)
    parser.add_argument("--count", required=True, type=int)
    parser.add_argument("--batch", default="001")
    parser.add_argument("--multi-turn-subtype")
    parser.add_argument(
        "--provider", choices=("dry-run", "openai-compatible"), default="dry-run"
    )
    parser.add_argument("--stage", choices=("pilot", "production"))
    parser.add_argument("--run-id")
    parser.add_argument("--plan", type=Path, default=PLAN_PATH)
    parser.add_argument("--seed", type=Path, default=SEED_PATH)
    args = parser.parse_args()
    if args.count < 1 or args.count > 50:
        parser.error("--count must be between 1 and 50")
    plan = load_plan(args.plan)
    group = next((item for item in plan["groups"] if item["id"] == args.group), None)
    if group is None:
        parser.error(f"unknown group {args.group}")
    multi_turn_subtype = None
    if group["id"] == "G15":
        multi_turn_subtype = next(
            (
                subtype
                for subtype in group["subgroups"]
                if subtype["name"] == args.multi_turn_subtype
            ),
            None,
        )
        if multi_turn_subtype is None:
            allowed = ", ".join(subtype["name"] for subtype in group["subgroups"])
            parser.error(f"G15 requires --multi-turn-subtype: {allowed}")
    elif args.multi_turn_subtype:
        parser.error("--multi-turn-subtype is only valid for G15")
    if args.provider == "openai-compatible" and (
        args.stage is None or not isinstance(args.run_id, str) or not args.run_id.strip()
    ):
        parser.error(
            "external generation requires --stage pilot|production and a non-empty --run-id"
        )
    seeds, errors = read_jsonl(args.seed)
    for seed_record in seeds:
        errors.extend(validate_record(seed_record))
    if errors:
        raise RuntimeError("; ".join(errors))
    sources = select_group_sources(group, seeds)
    if multi_turn_subtype:
        sources = select_multi_turn_subtype_sources(multi_turn_subtype, sources)
    sources_by_id = {source["id"]: source for source in sources}
    source_errors = validate_augmentation_sources(sources_by_id, {s["id"]: s for s in seeds})
    if source_errors or not sources:
        raise RuntimeError("; ".join(source_errors or ["no eligible sources"]))
    generation_group_id = f"{group['id']}_BATCH_{args.batch}"
    scenario_specs = (
        build_g15_scenario_specs(multi_turn_subtype, sources, args.count)
        if multi_turn_subtype
        else None
    )
    if args.provider == "dry-run":
        client: GeneratorClient = DryRunGeneratorClient()
        provenance = {
            "generation_provider": "dry-run",
            "generation_model": None,
            "generation_mode": "dry-run",
            "generation_stage": args.stage or "pilot",
            "generation_run_id": args.run_id.strip() if args.run_id and args.run_id.strip() else "dry_run",
        }
    else:
        external_client = OpenAICompatibleGeneratorClient()
        client = external_client
        provenance = {
            "generation_provider": "openai-compatible",
            "generation_model": external_client.model,
            "generation_mode": "external",
            "generation_stage": args.stage,
            "generation_run_id": args.run_id.strip(),
        }
    job = {
        "group": group,
        "generation_group_id": generation_group_id,
        "prompt": build_prompt(
            group,
            args.count,
            sources,
            multi_turn_subtype=multi_turn_subtype,
            scenario_specs=scenario_specs,
        ),
        **provenance,
    }
    if multi_turn_subtype:
        job["multi_turn_subtype"] = multi_turn_subtype["name"]
        job["scenario_specs"] = scenario_specs
    raw_records, raw_text = client.generate(job, sources, args.count)
    raw_path = GENERATED_DIR / "raw" / f"{generation_group_id}.jsonl"
    raw_path.parent.mkdir(parents=True, exist_ok=True)
    raw_path.write_text(raw_text, encoding="utf-8")
    candidates = assemble_candidates(raw_records, job, sources_by_id)
    output_path = GENERATED_DIR / f"{generation_group_id}.jsonl"
    write_jsonl(output_path, candidates)
    print(f"Generated: {len(candidates)}")
    print(f"Provider: {args.provider}")
    print(f"Raw: {raw_path}")
    print(f"Candidates: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
