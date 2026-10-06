#!/usr/bin/env python3
"""Shared, dependency-free helpers for the DevDesk augmentation pipeline."""

from __future__ import annotations

import json
import re
import unicodedata
from collections import Counter
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
SEED_PATH = ROOT / "data/seeds/devdesk_qlora_seed_dataset_v0.4.jsonl"
PLAN_PATH = ROOT / "config/augmentation_plan.yaml"
PROMPT_PATH = ROOT / "prompts/augmentation_generation_prompts.md"
REPORT_DIR = ROOT / "data/reports"
GENERATED_DIR = ROOT / "data/generated"
PROCESSED_DIR = ROOT / "data/processed"

ALLOWED_SPLITS = {
    "seed_train_candidate",
    "heldout_candidate",
    "historical_regression_candidate",
}
SUPPORTED_RESOURCES = {"VPN", "DEV_DB", "PROD_DB"}
SUPPORTED_RESOURCE_ORDER = ("DEV_DB", "PROD_DB", "VPN")
SUPPORTED_TICKET_TYPES = {"ACCESS_REQUEST", "INCIDENT", "ACCOUNT_SUPPORT"}
SUPPORTED_TOOLS = {"getAccessStatus", "createTicket"}
REVIEW_STATUSES = {"KEEP", "REWRITE", "DROP", "UNREVIEWED"}
GENERATION_STAGES = {"pilot", "production"}


class DataError(ValueError):
    pass


def read_jsonl(path: Path) -> tuple[list[dict[str, Any]], list[str]]:
    records: list[dict[str, Any]] = []
    errors: list[str] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        return [], [f"cannot read {path}: {error}"]
    for line_number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            errors.append(f"line {line_number}: invalid JSON: {error.msg}")
            continue
        if not isinstance(value, dict):
            errors.append(f"line {line_number}: record must be a JSON object")
            continue
        records.append(value)
    return records, errors


def write_jsonl(path: Path, records: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records)
    path.write_text(text, encoding="utf-8")


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def validate_record(record: dict[str, Any], *, generated: bool = False) -> list[str]:
    errors: list[str] = []
    record_id = record.get("id", "<missing-id>")
    if not isinstance(record.get("id"), str) or not record["id"].strip():
        errors.append("missing or empty id")
    if not isinstance(record.get("intent"), str) or not record["intent"].strip():
        errors.append(f"{record_id}: missing or empty intent")
    if not isinstance(record.get("difficulty"), list):
        errors.append(f"{record_id}: difficulty must be a list")

    errors.extend(canonical_conversation_errors(record.get("conversation"), record_id))

    target = record.get("target")
    if not isinstance(target, dict):
        errors.append(f"{record_id}: target must be an object")
    else:
        errors.extend(validate_target(target, record_id))

    if generated:
        for key in ("source_seed_id", "generation_group_id", "generation_category"):
            if not isinstance(record.get(key), str) or not record[key].strip():
                errors.append(f"{record_id}: missing or empty {key}")
        provider = record.get("generation_provider")
        mode = record.get("generation_mode")
        if not isinstance(provider, str) or not provider.strip():
            errors.append(f"{record_id}: missing or empty generation_provider")
        if "generation_model" not in record:
            errors.append(f"{record_id}: missing generation_model")
        if mode not in {"dry-run", "external"}:
            errors.append(f"{record_id}: generation_mode must be dry-run or external")
        elif mode == "dry-run":
            if provider != "dry-run":
                errors.append(f"{record_id}: dry-run mode requires dry-run provider")
            if record.get("generation_model") is not None:
                errors.append(f"{record_id}: dry-run generation_model must be null")
        else:
            if provider == "dry-run":
                errors.append(f"{record_id}: external mode cannot use dry-run provider")
            model = record.get("generation_model")
            if not isinstance(model, str) or not model.strip():
                errors.append(f"{record_id}: external generation_model must be non-empty")
        review_status = record.get("review_status", "UNREVIEWED")
        if review_status not in REVIEW_STATUSES:
            errors.append(f"{record_id}: invalid review_status {review_status!r}")
        generation_stage = record.get("generation_stage")
        if generation_stage not in GENERATION_STAGES:
            errors.append(
                f"{record_id}: generation_stage must be pilot or production"
            )
        generation_run_id = record.get("generation_run_id")
        if not isinstance(generation_run_id, str) or not generation_run_id.strip():
            errors.append(f"{record_id}: generation_run_id must be a non-empty string")
    elif record.get("split_hint") not in ALLOWED_SPLITS:
        errors.append(f"{record_id}: unsupported split_hint {record.get('split_hint')!r}")
    return errors


def canonical_conversation_errors(conversation: Any, record_id: str) -> list[str]:
    errors: list[str] = []
    if not isinstance(conversation, list) or not conversation:
        errors.append(f"{record_id}: conversation must be a non-empty list")
    else:
        for index, message in enumerate(conversation):
            if not isinstance(message, dict):
                errors.append(f"{record_id}: conversation[{index}] must be an object")
                continue
            if message.get("role") not in {"user", "assistant"}:
                errors.append(f"{record_id}: conversation[{index}] has invalid role")
            if not isinstance(message.get("content"), str) or not message["content"].strip():
                errors.append(f"{record_id}: conversation[{index}] has empty content")
        if isinstance(conversation[-1], dict) and conversation[-1].get("role") != "user":
            errors.append(f"{record_id}: last conversation role must be user")
    return errors


def validate_target(target: dict[str, Any], record_id: str) -> list[str]:
    errors: list[str] = []
    decision = target.get("decision")
    tool_name = target.get("tool_name")
    arguments = target.get("arguments")
    if decision == "NO_TOOL":
        if tool_name is not None:
            errors.append(f"{record_id}: NO_TOOL tool_name must be null")
        if arguments != {}:
            errors.append(f"{record_id}: NO_TOOL arguments must be empty")
        return errors
    if decision != "TOOL":
        return [f"{record_id}: decision must be TOOL or NO_TOOL"]
    if tool_name not in SUPPORTED_TOOLS:
        errors.append(f"{record_id}: unsupported tool_name {tool_name!r}")
        return errors
    if not isinstance(arguments, dict):
        return errors + [f"{record_id}: arguments must be an object"]

    resource = arguments.get("resource")
    if tool_name == "getAccessStatus":
        if resource not in SUPPORTED_RESOURCES:
            errors.append(f"{record_id}: invalid getAccessStatus resource {resource!r}")
        return errors

    ticket_type = arguments.get("type")
    if ticket_type not in SUPPORTED_TICKET_TYPES:
        errors.append(f"{record_id}: invalid createTicket type {ticket_type!r}")
    elif ticket_type == "ACCOUNT_SUPPORT":
        if resource != "ACCOUNT_SUPPORT":
            errors.append(f"{record_id}: ACCOUNT_SUPPORT resource must be ACCOUNT_SUPPORT")
    elif resource not in SUPPORTED_RESOURCES:
        errors.append(f"{record_id}: invalid createTicket resource {resource!r}")
    return errors


def duplicate_ids(records: Iterable[dict[str, Any]]) -> list[str]:
    counts = Counter(record.get("id") for record in records)
    return sorted(str(record_id) for record_id, count in counts.items() if count > 1)


def validate_augmentation_sources(
    source_ids: Iterable[str], seeds_by_id: dict[str, dict[str, Any]]
) -> list[str]:
    errors: list[str] = []
    for source_id in source_ids:
        seed = seeds_by_id.get(source_id)
        if seed is None:
            errors.append(f"unknown augmentation source {source_id}")
        elif seed.get("split_hint") != "seed_train_candidate":
            errors.append(
                f"{source_id}: split {seed.get('split_hint')} cannot be an augmentation source"
            )
    return errors


def load_plan(path: Path = PLAN_PATH) -> dict[str, Any]:
    """Parse the fields used by the pipeline from the repository's constrained YAML."""
    lines = path.read_text(encoding="utf-8").splitlines()
    total = _top_level_int(lines, "candidate_target_total")
    groups: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    active_list: str | None = None
    subgroup: dict[str, Any] | None = None
    in_groups = False

    for raw in lines:
        if raw == "groups:":
            in_groups = True
            continue
        if not in_groups:
            continue
        group_match = re.match(r"^- id:\s*(\S+)\s*$", raw)
        if group_match:
            current = {"id": group_match.group(1)}
            groups.append(current)
            active_list = None
            subgroup = None
            continue
        if current is None:
            continue
        property_match = re.match(r"^  ([a-z_]+):(?:\s*(.*))?$", raw)
        if property_match:
            key, raw_value = property_match.groups()
            if raw_value:
                current[key] = _yaml_scalar(raw_value)
                active_list = None
            else:
                current[key] = []
                active_list = key
            subgroup = None
            continue
        list_match = re.match(r"^  -\s*(.*)$", raw)
        if list_match and active_list:
            value = list_match.group(1)
            if active_list == "subgroups" and value.startswith("name:"):
                subgroup = {"name": _yaml_scalar(value.split(":", 1)[1].strip())}
                current[active_list].append(subgroup)
            else:
                current[active_list].append(_yaml_scalar(value))
            continue
        subgroup_property = re.match(r"^    ([a-z_]+):\s*(.*)$", raw)
        if subgroup_property and subgroup is not None:
            key, value = subgroup_property.groups()
            subgroup[key] = _yaml_scalar(value)

    if len(groups) != 15:
        raise DataError(f"expected 15 generation groups, found {len(groups)}")
    return {"candidate_target_total": total, "groups": groups}


def _top_level_int(lines: list[str], key: str) -> int:
    pattern = re.compile(rf"^{re.escape(key)}:\s*(\d+)\s*$")
    for line in lines:
        match = pattern.match(line)
        if match:
            return int(match.group(1))
    raise DataError(f"missing integer field {key}")


def _yaml_scalar(value: str) -> Any:
    value = value.strip()
    if value.isdigit():
        return int(value)
    if len(value) >= 2 and value[0] == "[" and value[-1] == "]":
        return [item.strip() for item in value[1:-1].split(",") if item.strip()]
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value


def validate_plan(plan: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    groups = plan["groups"]
    ids = [group.get("id") for group in groups]
    if ids != [f"G{index:02d}" for index in range(1, 16)]:
        errors.append("generation group ids must be exactly G01 through G15")
    group_total = sum(int(group.get("target_candidates", 0)) for group in groups)
    if group_total != plan["candidate_target_total"]:
        errors.append(
            f"group target sum {group_total} != candidate_target_total {plan['candidate_target_total']}"
        )
    g15 = next((group for group in groups if group.get("id") == "G15"), None)
    subgroup_total = sum(
        int(group.get("target_candidates", 0)) for group in (g15 or {}).get("subgroups", [])
    )
    if not g15 or subgroup_total != int(g15.get("target_candidates", 0)):
        errors.append("G15 subgroup target sum must equal G15 target")
    return errors


GROUP_DIFFICULTIES = {
    "G01": {"DIRECT", "PARAPHRASE"},
    "G02": {"DISTRACTOR", "CONTRASTIVE", "NO_TOOL_LOOKALIKE"},
    "G03": {"DIRECT", "PARAPHRASE"},
    "G04": {"INFO_TO_ACTION_OVERRIDE", "NO_TOOL_LOOKALIKE"},
    "G05": {"REASON_CONTEXT", "PARAPHRASE"},
    "G06": {"INTENT_REASON_CONFLICT"},
    "G07": {"DIRECT", "PARAPHRASE"},
    "G08": {"INFO_TO_ACTION_OVERRIDE", "NO_TOOL_LOOKALIKE"},
    "G09": {"CONTRASTIVE", "DISTRACTOR"},
    "G10": {"DIRECT", "PARAPHRASE", "INFO_TO_ACTION_OVERRIDE"},
    "G11": {"CONTRASTIVE", "FAILURE_KEYWORD_CONFLICT", "PARAPHRASE"},
    "G12": {"POLICY", "HOW_TO", "NO_TOOL_LOOKALIKE", "DIRECT"},
    "G13": {"UNSUPPORTED", "UNSUPPORTED_RESOURCE", "UNSUPPORTED_ACTION", "NEAR_NAME_RESOURCE"},
    "G14": {"AMBIGUOUS", "IMPLICIT_WRITE"},
    "G15": {"MULTI_TURN"},
}


def select_group_sources(
    group: dict[str, Any], seeds: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    train = [seed for seed in seeds if seed.get("split_hint") == "seed_train_candidate"]
    group_id = group["id"]
    if group_id == "G15":
        # G15 constructs a new scenario. Its source conversation is not an example,
        # so eligibility is defined by each subtype's label contract instead.
        selected = train
    else:
        selected = [seed for seed in train if seed.get("intent") == group.get("intent")]
        preferred = [
            seed
            for seed in selected
            if set(seed.get("difficulty", [])) & GROUP_DIFFICULTIES.get(group_id, set())
        ]
        if preferred:
            selected = preferred
    return selected


def select_multi_turn_subtype_sources(
    subtype: dict[str, Any], sources: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    source_split = subtype.get("source_split", "seed_train_candidate")
    source_intent = subtype.get("source_intent")
    configured_ids = subtype.get("source_seed_ids")
    return [
        source
        for source in sources
        if source.get("split_hint") == source_split
        and (source_intent is None or source.get("intent") == source_intent)
        and (configured_ids is None or source.get("id") in configured_ids)
    ]


def build_g15_scenario_specs(
    subtype: dict[str, Any], sources: list[dict[str, Any]], count: int
) -> list[dict[str, Any]]:
    """Choose G15 labels/resources before the model realizes the conversation."""
    if not sources:
        raise DataError("G15 structured generation requires at least one eligible source")
    subtype_name = subtype["name"]
    specs: list[dict[str, Any]] = []
    for index in range(count):
        source = sources[index % len(sources)]
        spec: dict[str, Any] = {
            "scenario_index": index + 1,
            "source_seed_id": source["id"],
            "generation_subtype": subtype_name,
            "target_contract": source["target"],
        }
        if subtype_name.endswith("_write"):
            required_resource = source["target"].get("arguments", {}).get("resource")
            if required_resource not in SUPPORTED_RESOURCES:
                raise DataError(
                    f"{source['id']}: G15 write source has invalid required resource "
                    f"{required_resource!r}"
                )
            spec["required_resource"] = required_resource
            if subtype_name == "single_resource_write":
                spec["scenario_resources"] = [required_resource]
                spec["required_sequence"] = (
                    f"{required_resource} user context/status turn (write request 금지) -> "
                    "neutral contextual acknowledgement assistant -> resource name 없는 "
                    "지시어 기반 최초 explicit ACCESS_REQUEST final user turn"
                )
            else:
                distractors = [
                    resource
                    for resource in SUPPORTED_RESOURCE_ORDER
                    if resource != required_resource
                ]
                distractor_resource = distractors[index % len(distractors)]
                spec["scenario_resources"] = [distractor_resource, required_resource]
                spec["distractor_resource"] = distractor_resource
                spec["required_sequence"] = (
                    f"{distractor_resource} user context turn (write request 금지) -> "
                    "neutral contextual acknowledgement assistant -> "
                    f"{required_resource} user context turn (write request 금지) -> "
                    "neutral contextual acknowledgement assistant -> resource name 없는 "
                    "지시어 기반 최초 explicit ACCESS_REQUEST final user turn"
                )
        elif subtype_name == "single_resource_howto":
            scenario_resource = SUPPORTED_RESOURCE_ORDER[index % len(SUPPORTED_RESOURCE_ORDER)]
            spec["scenario_resources"] = [scenario_resource]
            spec["required_sequence"] = (
                f"{scenario_resource} user context/status turn (execution request 금지) -> "
                "neutral contextual acknowledgement assistant -> resource name 없는 "
                "지시어 기반 방법/절차/조건 final user question"
            )
        elif subtype_name == "multi_resource_howto":
            resource_a = SUPPORTED_RESOURCE_ORDER[index % len(SUPPORTED_RESOURCE_ORDER)]
            resource_b = SUPPORTED_RESOURCE_ORDER[(index + 1) % len(SUPPORTED_RESOURCE_ORDER)]
            spec["scenario_resources"] = [resource_a, resource_b]
            spec["required_sequence"] = (
                f"{resource_a} user context turn (execution request 금지) -> neutral contextual "
                f"acknowledgement assistant -> {resource_b} user context turn (execution request "
                "금지) -> neutral contextual acknowledgement assistant -> resource name 없는 "
                "지시어 기반 방법/절차/조건 final user question"
            )
        else:
            raise DataError(f"unsupported G15 subtype {subtype_name!r}")
        specs.append(spec)
    return specs


def prompt_sections(path: Path = PROMPT_PATH) -> tuple[str, str]:
    text = path.read_text(encoding="utf-8")
    blocks = re.findall(r"```text\n(.*?)\n```", text, flags=re.DOTALL)
    if len(blocks) < 2:
        raise DataError("prompt guide must contain system and user template text blocks")
    return blocks[0], blocks[1]


def group_prompt_addendum(group_id: str, path: Path = PROMPT_PATH) -> str:
    text = path.read_text(encoding="utf-8")
    match = re.search(
        rf"^### {re.escape(group_id)}\b.*?\n(.*?)(?=^### G\d{{2}}\b|^## 4\.)",
        text,
        flags=re.MULTILINE | re.DOTALL,
    )
    if not match:
        raise DataError(f"prompt guide has no group-specific section for {group_id}")
    return match.group(1).strip()


def build_prompt(
    group: dict[str, Any],
    count: int,
    sources: list[dict[str, Any]],
    prompt_path: Path = PROMPT_PATH,
    multi_turn_subtype: dict[str, Any] | None = None,
    scenario_specs: list[dict[str, Any]] | None = None,
) -> str:
    system, user_template = prompt_sections(prompt_path)
    is_structured_g15 = group["id"] == "G15" and multi_turn_subtype is not None
    if is_structured_g15:
        system = re.sub(
            r"\n출력:\n.*\Z",
            (
                "\n출력:\n"
                "- JSONL만 출력하고 설명 문장은 출력하지 않는다.\n"
                "- 각 record에는 canonical conversation array만 포함한다.\n"
                "- ID, difficulty, label, target, provenance, subtype, scenario metadata는 "
                "pipeline이 부착한다."
            ),
            system,
            flags=re.DOTALL,
        )
    if is_structured_g15 and scenario_specs is None:
        scenario_specs = build_g15_scenario_specs(multi_turn_subtype, sources, count)
    prompt_sources = sources
    if scenario_specs is not None:
        selected_source_ids = {spec["source_seed_id"] for spec in scenario_specs}
        prompt_sources = [seed for seed in sources if seed["id"] in selected_source_ids]
    examples = "\n".join(
        json.dumps(
            (
                {"id": seed["id"], "intent": seed["intent"], "target": seed["target"]}
                if is_structured_g15
                else {
                    "id": seed["id"],
                    "intent": seed["intent"],
                    "difficulty": seed["difficulty"],
                    "conversation": seed["conversation"],
                    "target": seed["target"],
                }
            ),
            ensure_ascii=False,
        )
        for seed in prompt_sources
    )
    user = user_template.format(
        GROUP_ID=group["id"],
        GROUP_NAME=group["name"],
        N=count,
        GOAL=group.get("goal", ""),
        GENERATION_RULES="\n".join(f"- {item}" for item in group.get("generation_rules", [])),
        ANTI_PATTERNS="\n".join(f"- {item}" for item in group.get("anti_patterns", [])),
        SEED_EXAMPLES=examples,
    )
    if is_structured_g15:
        user = user.replace(
            "같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.\n", ""
        ).replace(
            "generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.",
            "scenario spec마다 같은 순서로 conversation 하나를 생성하라.",
        )
    addendum = group_prompt_addendum(group["id"], prompt_path)
    constraints = "\n\n".join(
        immutable_target_constraint(seed) for seed in prompt_sources
    )
    subtype_requirement = ""
    if multi_turn_subtype is not None:
        subtype_requirement = (
            "\n\nMULTI-TURN SUBTYPE REQUIREMENT\n"
            f"multi_turn_subtype = {multi_turn_subtype['name']}\n"
            "resource-count requirement: "
            f"{multi_turn_subtype['resource_count_requirement']}\n"
            "final-action requirement: "
            f"{multi_turn_subtype['final_turn_requirement']}\n"
            f"target requirement: {multi_turn_subtype['target_requirement']}\n"
            "이 generation job에서는 지정된 subtype 구조만 사용한다."
        )
    scenario_requirement = ""
    if scenario_specs is not None:
        rendered_specs = "\n".join(
            json.dumps(spec, ensure_ascii=False) for spec in scenario_specs
        )
        scenario_requirement = (
            "\n\nSTRUCTURED SCENARIO SPECS\n"
            "아래 spec은 pipeline이 미리 결정했다. source conversation을 재구성하거나 "
            "paraphrase하지 말고, 각 spec을 같은 순서로 자연스러운 conversation 하나로만 "
            "표현한다. scenario_resources의 순서를 바꾸지 않는다. 마지막 reference turn에는 "
            "DEV_DB, PROD_DB, VPN 이름을 직접 쓰지 않는다.\n"
            f"{rendered_specs}"
        )
    return (
        f"SYSTEM\n{system}\n\nUSER\n{user}"
        f"\n\nIMMUTABLE TARGET CONSTRAINTS\n{constraints}"
        f"\n\nGROUP-SPECIFIC SOURCE INSTRUCTIONS\n{addendum}"
        f"{subtype_requirement}"
        f"{scenario_requirement}"
    )


def immutable_target_constraint(seed: dict[str, Any]) -> str:
    target = seed["target"]
    arguments = target.get("arguments", {})
    return "\n".join(
        (
            f"source_seed_id = {seed['id']}",
            f"required_decision = {target.get('decision')}",
            f"required_tool_name = {target.get('tool_name')}",
            f"required_type = {arguments.get('type')}",
            f"required_resource = {arguments.get('resource')}",
            "The generated conversation must preserve every required value above.",
        )
    )


def normalized_text(text: str) -> str:
    normalized = unicodedata.normalize("NFKC", text).casefold()
    return re.sub(r"\s+", " ", normalized).strip()


def conversation_text(record: dict[str, Any]) -> str:
    conversation = record.get("conversation") or []
    return "\n".join(
        str(message.get("content", "")) for message in conversation if isinstance(message, dict)
    )
