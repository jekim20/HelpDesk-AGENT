# DevDesk QLoRA augmentation pipeline

This directory builds and validates small, auditable augmentation batches. The seed JSONL,
augmentation plan, and generation prompt guide remain the source of truth. The pipeline does
not train a model or invoke RunPod.

## Safety boundaries

- Only `split_hint=seed_train_candidate` records can be generation sources.
- Held-out and historical regression records fail leakage validation.
- Labels are copied from the selected source seed; the generator cannot choose labels.
- Raw generator output is stored separately under `data/generated/raw/`.
- Every generated candidate records provider, model, and generation mode provenance.
- Every generated candidate records `generation_stage=pilot|production` and a non-empty
  `generation_run_id`.
- Machine validation does not imply human approval.
- New candidates default to `review_status=UNREVIEWED`.
- Only external candidates with `review_status=KEEP` enter the approved training export.
- `generation_mode=dry-run` records are always excluded from that export.
- A machine failure on `review_status=DROP` is reported as `MACHINE_REJECTED` and is non-blocking;
  the same failure on `UNREVIEWED`, `KEEP`, or `REWRITE` remains blocking.
- Literal resource consistency checks run only for groups listed in
  `config/validation_config.json`; G06 is enabled and multi-resource G15 is excluded.
- Candidate generation is limited to 50 records per command.
- The default provider is deterministic `dry-run`; it does not call an external API.
- No API key is written to logs or reports. `.env` is already ignored by the repository.

## Commands

Run from the repository root.

```bash
# Validate the seed dataset
python3 finetuning/scripts/validate_seed.py

# Inspect the recommended 35-record generation plan; no API call
python3 finetuning/scripts/build_generation_jobs.py --dry-run

# Generate the five local dry-run batches
python3 finetuning/scripts/generate_candidates.py --group G01 --count 5
python3 finetuning/scripts/generate_candidates.py --group G06 --count 10
python3 finetuning/scripts/generate_candidates.py --group G10 --count 5
python3 finetuning/scripts/generate_candidates.py --group G12 --count 5
python3 finetuning/scripts/generate_candidates.py --group G15 --count 10

# Validate and summarize all generated batches
python3 finetuning/scripts/validate_candidates.py
python3 finetuning/scripts/analyze_dataset.py

# Validator regression tests
python3 -m unittest discover -s finetuning/scripts -p 'test_*.py'
```

An external small-batch call is opt-in:

```bash
OPENAI_API_KEY=... DEV_DESK_AUGMENTATION_MODEL=... \
python3 finetuning/scripts/generate_candidates.py \
  --provider openai-compatible --stage pilot --run-id pilot_v1 \
  --group G06 --count 10 --batch 002
```

The external generator returns only conversation content and source metadata. The pipeline
attaches the target from the selected source seed and the validator checks it again.

Machine-validated review data is written to
`data/processed/machine_validated_candidates.jsonl`. Reviewers may set `review_status` to
`KEEP`, `REWRITE`, `DROP`, or `UNREVIEWED` on generated external candidates. Validated KEEP
records are separated into `approved_pilot_candidates.jsonl` and
`approved_production_candidates.jsonl`. `approved_training_candidates.jsonl` is production-only;
pilot, dry-run, failed, and unreviewed records never enter the training export.

Generation and review metadata remains in the approved candidate export for auditing. The
model-facing formatter writes only `conversation` and `target` to
`data/processed/approved_training_model_input.jsonl`, so metadata such as
`generation_subtype` is not part of the fine-tuning model input.

## Current integration caveat

G11 defines explicit account-support action as `ACCOUNT_SUPPORT` even when words such as
`실패` or `오류` are present. The current Java deterministic classifier checks failure words
before account-support words, so it can emit `INCIDENT` before the fine-tuned model is called.
This pipeline intentionally does not alter Java behavior or change the source labels.
