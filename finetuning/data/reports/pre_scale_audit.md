# DevDesk QLoRA Pre-Scale Audit

- Audit date: 2026-09-08 (Asia/Seoul)
- Scope: augmentation data/configuration/prompts/jobs/validation only
- External API calls: none
- Java Agent changes: none
- Final decision: **NO-GO**

The source-leakage and G11 target-contract checks pass after one prompt-example cleanup.
The current machine-validation baseline also has zero blocking failures. Pilot and production
exports are now explicitly separated. Production-scale generation is nevertheless not ready to
start because the complete `finetuning/` tree is currently untracked by Git; the remaining blocker
is reproducible freeze state rather than dataset lineage or label-contract correctness.

## 1. Leakage audit

### Checked files and surfaces

- `data/seeds/devdesk_qlora_seed_dataset_v0.4.jsonl`
- `config/augmentation_plan.yaml`
- `prompts/augmentation_generation_prompts.md`, including every group addendum and example
- `scripts/pipeline_common.py` source selection and prompt construction
- `scripts/build_generation_jobs.py`
- `scripts/generate_candidates.py`
- `scripts/validate_candidates.py`
- `data/reports/generation_jobs_full_plan.jsonl`
- `data/reports/generation_jobs_report.md`
- all current `data/generated/*.jsonl` candidate files
- `data/processed/approved_training_candidates.jsonl`
- `data/processed/approved_training_model_input.jsonl`

### Checked blocked source IDs

| ID | Split | Intent |
| --- | --- | --- |
| S029 | heldout_candidate | INCIDENT |
| S044 | historical_regression_candidate | ACCESS_REQUEST |
| S047 | heldout_candidate | INCIDENT |
| S050 | heldout_candidate | ACCESS_STATUS |
| S062 | heldout_candidate | ACCOUNT_SUPPORT |
| S063 | heldout_candidate | INCIDENT |
| S064 | heldout_candidate | ACCOUNT_SUPPORT |
| S065 | heldout_candidate | NO_TOOL |

### Results

- Blocked IDs used by any of the 18 full-plan generation jobs: **0**
- Blocked IDs present in the augmentation plan: **0**
- Blocked IDs present in the prompt guide/addenda: **0**
- Blocked IDs present in generated final prompts: **0**
- Exact blocked conversation copies in the plan, prompt guide, or final prompts: **0**
- Current generated candidates with a blocked `source_seed_id`: **0**
- Current approved candidates with a blocked `source_seed_id`: **0**
- Exact blocked conversation copies in current generated candidates: **0**

The post-fix lexical comparison between blocked conversations and generation-job prompt examples
found no exact copy. The highest `SequenceMatcher` similarity was 0.6923 (S062 versus training
seed S056); this is an audit signal, not a semantic classifier. Manual review confirmed that the
remaining examples describe the shared account-support boundary without copying a held-out
conversation.

### Issue found and correction

The prompt guide contained this rejected-output example:

`로그인 실패해. 계정 지원 티켓 만들어줘.`

It was a too-direct shortening of held-out S062 (`로그인이 계속 실패해...`). The literal example
was replaced with an abstract input-type description:

`failure 표현과 명시적인 계정 지원 실행 요청이 함께 있는 문장`

No held-out/historical record or label was changed. A regression test now checks blocked source
IDs, source lists, exact serialized conversations, literal conversation text, and the removed
S062-like example across the plan, prompt guide, and built final prompts.

## 2. G11 ACCOUNT_SUPPORT versus INCIDENT contract audit

G11 is configured as `account_support_contrastive_conflict` with `intent=ACCOUNT_SUPPORT`.
Its training contract is:

```text
decision = TOOL
tool_name = createTicket
arguments.type = ACCOUNT_SUPPORT
arguments.resource = ACCOUNT_SUPPORT
```

The selected G11 sources are only S057 and S058. Both are `seed_train_candidate`, both have
`intent=ACCOUNT_SUPPORT`, and both carry the exact contract above. No held-out or historical ID,
including S062, is selected or rendered as a prompt example.

The full G11 prompt includes all four immutable constraints:

- `required_decision = TOOL`
- `required_tool_name = createTicket`
- `required_type = ACCOUNT_SUPPORT`
- `required_resource = ACCOUNT_SUPPORT`

The generator output does not own the target. Even if a raw mock response supplies an INCIDENT
target beside failure wording, `assemble_candidates` discards that target and attaches the source
seed target. This was verified deterministically without an API call.

The opposite boundary is also intact: G07's selected training sources all have
`arguments.type=INCIDENT`, and the G09 prompt explicitly requires a real incident action rather
than an account-support action. This audit does not change the Java deterministic classifier;
the known Java precedence caveat remains outside the dataset-contract scope.

Current generated data contains no G11 candidate batch, so this conclusion is based on the frozen
seed, plan, constructed final prompt, and deterministic assembly test—not on a live G11 sample.

## 3. Source split and deterministic checks

Seed validation result:

```text
Status: PASS
Total: 65
seed_train_candidate: 57
heldout_candidate: 7
historical_regression_candidate: 1
```

The common source selector filters to `split_hint=seed_train_candidate` before applying group
intent/difficulty selection. `validate_augmentation_sources` independently rejects held-out and
historical sources. G15's subtype selector also requires `source_split=seed_train_candidate`.

Added deterministic coverage verifies:

- G11 selects only ACCOUNT_SUPPORT training seeds.
- Held-out/historical records are excluded from source selection and final prompts.
- Failure wording does not alter the source-owned ACCOUNT_SUPPORT target.
- A raw model-supplied target cannot replace the pipeline-owned target.
- Incident training sources retain INCIDENT targets.

## 4. Pilot versus production separation

Current generated inventory:

| Item | Count |
| --- | ---: |
| Total generated candidates | 121 |
| `generation_mode=external` | 86 |
| `generation_mode=dry-run` | 35 |
| `generation_stage=pilot` | 121 |
| `generation_stage=production` | 0 |
| KEEP | 48 |
| REWRITE | 21 |
| DROP | 17 |
| UNREVIEWED | 35 |
| Machine rejected | 12 |
| Approved pilot export | 48 |
| Approved production export | 0 |
| Final training export | 0 |

Existing protections work as designed:

- dry-run records are excluded from approved export;
- machine failures are excluded;
- only machine-valid external KEEP records enter approved export;
- raw responses and rejected pilot artifacts remain preserved.

The pilot/production lineage blocker is resolved with `generation_stage=pilot|production` and a
non-empty `generation_run_id`. All 121 active pre-production candidates were backfilled as
`pilot/pilot_v1`; archived failed-pilot and legacy artifacts were not modified. External generation
now requires explicit `--stage` and `--run-id` before provider initialization.

Machine-valid external KEEP records are routed as follows:

- `approved_pilot_candidates.jsonl`: pilot only, currently 48
- `approved_production_candidates.jsonl`: production only, currently 0
- `approved_training_candidates.jsonl`: production-approved alias, currently 0

The production target and remaining count are computed only from production-stage candidates.
Pilot, dry-run, failed-pilot, and legacy counts do not reduce the 1,000-candidate production quota.

## 5. Freeze readiness

### Passed checks

- Augmentation plan total: **1,000 / 1,000**
- G15 total: **100**, four subtypes × 25
- Generation jobs: **18**, plan build PASS
- Generation model: defaults to `gpt-4o-mini` and can be explicitly pinned with
  `DEV_DESK_AUGMENTATION_MODEL=gpt-4o-mini`
- Current generated schema-invalid records: **0**
- Current production model-facing records: **0**, because production generation has not started
- Pilot formatter regression: canonical conversation/target is preserved while stage/run metadata is excluded
- Model-facing keys: exactly `conversation`, `target`; generation/review/scenario metadata excluded
- Workflow remains machine validation → human review → approved export
- Candidate validation: PASS, blocking failures **0**

Current validation baseline:

```text
Total: 121
PASS: 104
WARN_NEAR_DUPLICATE: 5
Machine validated: 109
Machine rejected: 12
Blocking failures: 0
Pilot generated: 121
Pilot approved: 48
Production generated: 0
Production approved: 0
Production target: 1000
Production remaining: 1000
Approved training export: 0
Training model input: 0
```

Pipeline regression result:

```text
Ran 49 tests
OK
```

### Reproducibility snapshot

| File | SHA-256 |
| --- | --- |
| seed dataset | `ea0ecdd32292ff9f4ddaa2ce0cf698942b54d5c97a619672d92fe7b9a101d0b9` |
| augmentation plan | `80bbe537fb30dafb3809ba2e01d612b681ff8b06cd52d4538c7b8fc52e67e957` |
| generation prompt guide | `94f43cf21cebc4581285613324c59ad597f3c3929802a14262be1498a0ccbaa6` |
| pipeline common | `a46c55edee735b255008e69f05c7aa8a775df68ef852ffa0b37d169055c9de5f` |
| generation-job builder | `73ae256519fa273a968ad106bf9d741306cfb9be440d374cccea34de1f81480b` |
| candidate generator | `c6cf55da4480dcc58bcdb7d0dc3a3f29a787cfa04379ced9b3a90ec9d8083a15` |
| candidate validator | `66087e13da068b3f73037fbf322b453bec16aac3b0eb4baed437a56e9d076220` |
| dataset analyzer | `75176fdefab045f32d5b16c1c35d4597d674b1711677a56a07d7ce0ac1e447af` |
| pipeline tests | `3711b76508f4a9a0aa552d729bc31bcd9a500bb69b5cc3172ecbb2b55a5480a5` |

The snapshot identifies the audited content, but it is not yet a repository freeze: `git status`
reports the entire `finetuning/` directory as untracked (`?? finetuning/`). A commit/tag or an
equivalent immutable artifact manifest is required before a reproducible production run.

## 6. Findings, changes, and final decision

### Changes made by this audit

1. Removed the S062-like literal conversation from the rejected-output prompt example and replaced
   it with a generalized input-type description.
2. Added exact leakage/source-split regression coverage.
3. Added G11 source, target ownership, and opposing INCIDENT contract coverage.
4. Rebuilt the full-plan job report and reran seed, candidate, dataset, and unit-test checks.
5. Added required stage/run provenance, backfilled active candidates as `pilot/pilot_v1`, and
   separated pilot, production, and final training exports.
6. Added production-only quota accounting and stage-separated validation/analysis metrics.

No seed label, held-out record, Java code, augmentation taxonomy, target quantity, or generation
prompt semantics was changed.

### Decision: NO-GO

Leakage, G11 target ownership, canonical schema, plan totals, pilot/production separation, and the
zero-blocking-failure baseline are ready. The 48 pilot KEEP records no longer enter the final
training export, and the production remaining count is 1,000.

Production-scale external generation must still not start until the audited `finetuning/` content
is placed under an immutable versioned freeze (commit/tag or equivalent checksum manifest retained
with the run). After that remaining freeze requirement is met without changing the audited
contracts, the audit's technical checks support a GO decision.
