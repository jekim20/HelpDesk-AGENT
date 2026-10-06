# prod_v1 Abort Report

- Pipeline version: `qlora-augmentation-v1`
- Frozen commit: `31a501ba19b8e34982260766e1e23846d505ae3f`
- Generation run ID: `prod_v1`
- Generation stage: `production`
- Run status: **ABORTED**
- Generated: **60**
- G11 generated: **40**
- G11 human-approved before abort: **11**
- G15 generated: **20**
- G15 review status at abort: **UNREVIEWED 20**

## Abort reason

The frozen v1 G15 multi-turn contract and validator did not prevent an explicit write
request before the final user turn or an assistant response that claimed or promised
Tool execution. The structural validator therefore accepted semantically inconsistent
multi-turn histories.

The issue was systematic in `G15_BATCH_101`: all five `multi_resource_write` records
contained an explicit ACCESS_REQUEST before the final user turn and an assistant reply
such as `접근 요청을 접수할게요`. The final user turn then requested the same action again.

`G15_BATCH_103` also contained assistant execution claims in NO_TOOL how-to histories,
including:

- `AUG_G15_103_0002`: `PROD_DB 접근 요청을 등록해드릴게요.`
- `AUG_G15_103_0004`: `DEV_DB 접근 신청을 진행하겠습니다.`
- `AUG_G15_103_0005`: `PROD_DB 요청 등록해드릴게요.`

## Preservation and disposition

- Candidate and raw provider responses are preserved byte-for-byte under this directory.
- The frozen v1 validation and analysis reports for all 60 production records are preserved in `reports/`.
- `manifest.sha256` records checksums for every preserved candidate, raw, and report snapshot.
- Existing candidate conversations, targets, provenance, and review statuses were not changed.
- These records are quarantined from the active generated dataset.
- No `prod_v1` record is eligible for production-approved or final training export.
- The archived records may be used only as audit evidence and local regression fixtures.

## Frozen v1 validation snapshot

```text
Pilot generated: 126
Pilot approved: 52
Production generated: 60
Production approved: 11
Production remaining: 940
External UNREVIEWED: 20
Blocking failures: 0
```

The zero blocking-failure result above documents the v1 validator gap; it is not an
approval of the quarantined records.
