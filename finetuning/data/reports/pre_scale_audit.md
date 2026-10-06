# DevDesk QLoRA Augmentation Pipeline v1 Pre-Scale Audit

- Audit date: 2026-09-09 (Asia/Seoul)
- Scope: frozen `finetuning/` augmentation pipeline, active candidates, reports, exports, and Git freeze state
- External API calls: none
- Code, prompt, validator, seed, and candidate changes: none
- Audit artifact updated: this report only
- Final decision: **GO**

All requested pre-scale gates pass. Held-out/historical leakage is zero, the G11
ACCOUNT_SUPPORT target contract is preserved, pilot and production data are separated,
candidate validation has zero blocking failures, all 49 pipeline tests pass, and the
pipeline is frozen by a Git commit and the `qlora-augmentation-v1` tag.

## 1. Gate summary

| Gate | Expected | Observed | Result |
| --- | ---: | ---: | --- |
| Held-out/historical leakage | 0 | 0 | PASS |
| G11 ACCOUNT_SUPPORT contract | PASS | PASS | PASS |
| Pilot/production separation | PASS | PASS | PASS |
| Pilot generated | 126 | 126 | PASS |
| Pilot approved | 52 | 52 | PASS |
| Production generated | 0 | 0 | PASS |
| Production approved | 0 | 0 | PASS |
| Production target | 1,000 | 1,000 | PASS |
| Production remaining | 1,000 | 1,000 | PASS |
| Blocking failures | 0 | 0 | PASS |
| Pipeline tests | PASS | 49/49 PASS | PASS |
| `finetuning/` Git tracked | required | 96 tracked paths; clean baseline | PASS |
| Freeze commit | required | `31a501ba19b8e34982260766e1e23846d505ae3f` | PASS |
| `qlora-augmentation-v1` tag | required | exists and resolves to freeze commit | PASS |

## 2. Held-out and historical leakage audit

### Checked files and surfaces

- `finetuning/data/seeds/devdesk_qlora_seed_dataset_v0.4.jsonl`
- `finetuning/config/augmentation_plan.yaml`
- `finetuning/prompts/augmentation_generation_prompts.md`, including examples and addenda
- `finetuning/scripts/pipeline_common.py`
- `finetuning/scripts/build_generation_jobs.py`
- `finetuning/scripts/generate_candidates.py`
- all 18 in-memory full-plan generation jobs and their final prompts
- `finetuning/data/reports/generation_jobs_full_plan.jsonl`
- all active `finetuning/data/generated/*.jsonl` records
- stage-separated processed exports

### Blocked source IDs checked

| ID | Split |
| --- | --- |
| S029 | heldout_candidate |
| S044 | historical_regression_candidate |
| S047 | heldout_candidate |
| S050 | heldout_candidate |
| S062 | heldout_candidate |
| S063 | heldout_candidate |
| S064 | heldout_candidate |
| S065 | heldout_candidate |

### Result

- Blocked IDs in augmentation plan, prompt guide/addenda, or built final prompts: **0**
- Blocked IDs selected by any generation job: **0**
- Active generated records using a blocked `source_seed_id`: **0**
- Exact blocked conversation copies in plan/prompt/final-prompt surfaces: **0**
- Exact or normalized blocked conversation copies in active generated records: **0**
- Generation-job build errors: **0**

The audit also reviewed the ACCOUNT_SUPPORT/login-failure prompt area. It retains the
general contract and eligible training-seed examples without copying the held-out S062
conversation. No leakage correction was required in this audit.

## 3. G11 ACCOUNT_SUPPORT contract audit

G11 remains `account_support_contrastive_conflict`. Its required target is:

```json
{
  "decision": "TOOL",
  "tool_name": "createTicket",
  "arguments": {
    "type": "ACCOUNT_SUPPORT",
    "resource": "ACCOUNT_SUPPORT"
  }
}
```

Deterministic checks confirmed:

- G11 source selection returns only S057 and S058.
- Both sources are `seed_train_candidate`, have `intent=ACCOUNT_SUPPORT`, and carry the exact target above.
- No held-out/historical source is eligible or selected.
- The final G11 prompt includes `required_decision=TOOL`, `required_tool_name=createTicket`,
  `required_type=ACCOUNT_SUPPORT`, and `required_resource=ACCOUNT_SUPPORT` for each source.
- Failure wording such as login failure/account error does not change the pipeline-owned target.
- The LLM does not own `decision`, `tool_name`, `type`, or `resource`; candidate assembly attaches the source target.
- All 5 current G11 candidates retain the exact ACCOUNT_SUPPORT target.

Result: **PASS**. This is a fine-tuning dataset contract audit; no Java classifier was changed.

## 4. Pilot and production separation

The active inventory and in-memory validation produced:

| Metric | Count |
| --- | ---: |
| Total active generated | 126 |
| Machine validated | 114 |
| Machine rejected | 12 |
| Blocking failures | 0 |
| Pilot generated | 126 |
| Pilot approved | 52 |
| Production generated | 0 |
| Production approved | 0 |
| Production target | 1,000 |
| Production remaining | 1,000 |

Separation checks:

- Every active record has `generation_stage=pilot|production` and a non-empty `generation_run_id`.
- All 52 approved pilot records are routed only to `approved_pilot_candidates.jsonl`.
- `approved_production_candidates.jsonl` contains 0 records.
- `approved_training_candidates.jsonl` and model-input export contain 0 records because training export is production-only.
- Pilot, dry-run, rejected/archive, and legacy artifacts do not reduce the production quota.
- G15's production target remains 100 within the unchanged total target of 1,000.

Result: **PASS**.

## 5. Validation and pipeline tests

Candidate validation was executed in memory to avoid rewriting datasets or generated reports:

```text
Status: PASS
Total: 126
Machine validated: 114
Machine rejected: 12
Blocking failures: 0
Pilot generated: 126
Pilot approved: 52
Production generated: 0
Production approved: 0
Production target: 1000
Production remaining: 1000
```

The full-plan builder also passed with 18 jobs, 1,000 planned production candidates,
G15=100, and zero build errors.

Pipeline regression command and result:

```text
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest finetuning/scripts/test_pipeline.py
Ran 49 tests in 0.020s
OK
```

Result: **PASS**.

## 6. Git freeze audit

The audit started from a clean Git worktree. `git ls-files finetuning` returned 96 tracked
paths, including this audit report, so the `finetuning/` tree is under version control.

Freeze evidence:

```text
commit: 31a501ba19b8e34982260766e1e23846d505ae3f
subject: feat: freeze qlora augmentation pipeline v1
committed: 2026-09-09T09:12:31+09:00
tag: qlora-augmentation-v1
tag target: 31a501ba19b8e34982260766e1e23846d505ae3f
```

The tag resolves exactly to the freeze commit. Updating this report after the audit is the
only working-tree change produced by the audit; no generation input, code, prompt, validator,
seed, candidate, or processed dataset was changed.

## 7. Findings and decision

### Findings

- No new leakage, target-contract, stage-separation, validation, test, tracking, commit, or tag issue was found.
- The previous NO-GO reason is resolved: the `finetuning/` tree is tracked and an immutable freeze commit/tag exists.
- No corrective implementation or dataset mutation was needed.

### Final decision: GO

The QLoRA augmentation pipeline v1 satisfies every requested production pre-scale gate.
Production generation may start from the exact `qlora-augmentation-v1` freeze, with external
generation continuing to require explicit production stage/run provenance and the existing
machine-validation → human-review → production-approved export flow.
