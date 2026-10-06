# Dataset Analysis

- Total generated: 134
- Passed: 117
- Warnings: 5
- Failed: 0
- Machine validated: 122
- Machine rejected: 12
- Blocking failures: 0
- Approved training export: 0
- Pilot generated: 134
- Pilot approved: 58
- Production generated: 0
- Production approved: 0
- Production target: 1000
- Production remaining: 1000
- Exact duplicates: 1
- Normalized duplicates: 0
- Near duplicates: 10

## By group

- G01: 10
- G06: 39
- G10: 15
- G11: 5
- G12: 10
- G15: 43

## By intent

- ACCESS_REQUEST: 65
- ACCESS_STATUS: 10
- ACCOUNT_SUPPORT: 20
- NO_TOOL: 27

## By difficulty

- DIRECT: 18
- HOW_TO: 5
- INFO_TO_ACTION_OVERRIDE: 8
- INTENT_REASON_CONFLICT: 39
- MULTI_TURN: 43
- NO_TOOL_LOOKALIKE: 22
- PARAPHRASE: 21
- POLICY: 5

## By resource

- ACCOUNT_SUPPORT: 20
- DEV_DB: 20
- PROD_DB: 41
- VPN: 14

## By ticket type

- ACCESS_REQUEST: 65
- ACCOUNT_SUPPORT: 20

## By source seed

- S001: 2
- S002: 2
- S003: 2
- S004: 2
- S005: 2
- S011: 3
- S012: 2
- S016: 14
- S017: 12
- S018: 13
- S033: 6
- S034: 4
- S035: 2
- S036: 2
- S037: 2
- S043: 11
- S045: 10
- S046: 11
- S056: 5
- S057: 5
- S058: 6
- S059: 4

## By review status

- DROP: 5
- KEEP: 58
- REWRITE: 24
- UNREVIEWED: 35

## By generation subtype

- multi_resource_howto: 7
- multi_resource_write: 7
- single_resource_howto: 7
- single_resource_write: 7

## Schema failures

- AUG_G06_004_0002: AUG_G06_004_0002: conversation resource DEV_DB does not match target resource PROD_DB
- AUG_G15_005_0001: exact duplicate text with AUG_G15_010_0001
- AUG_G15_006_0002: AUG_G15_006_0002: single_resource_write requires exactly 1 supported resource, found ['DEV_DB', 'PROD_DB']
- AUG_G15_007_0001: AUG_G15_007_0001: multi_resource_write latest explicit resource DEV_DB does not match target resource PROD_DB
- AUG_G15_009_0001: AUG_G15_009_0001: multi_resource_howto requires at least 5 conversation turns; AUG_G15_009_0001: multi_resource_howto first user turn must not list multiple supported resources; AUG_G15_009_0001: multi_resource_howto resources must appear sequentially in different user turns
- AUG_G15_009_0002: AUG_G15_009_0002: multi_resource_howto requires at least 5 conversation turns; AUG_G15_009_0002: multi_resource_howto first user turn must not list multiple supported resources; AUG_G15_009_0002: multi_resource_howto resources must appear sequentially in different user turns
- AUG_G15_010_0001: exact duplicate text with AUG_G15_005_0001
- AUG_G15_011_0001: AUG_G15_011_0001: multi_resource_write latest explicit resource VPN does not match target resource PROD_DB
- AUG_G15_011_0002: AUG_G15_011_0002: multi_resource_write final user turn must not repeat a supported resource; AUG_G15_011_0002: multi_resource_write latest explicit resource DEV_DB does not match target resource PROD_DB
- AUG_G15_013_0001: AUG_G15_013_0001: multi_resource_howto requires at least 5 conversation turns; AUG_G15_013_0001: multi_resource_howto final user turn must not repeat a supported resource
- AUG_G15_013_0002: AUG_G15_013_0002: multi_resource_howto requires at least 5 conversation turns; AUG_G15_013_0002: multi_resource_howto final user turn must not repeat a supported resource
- AUG_G15_019_0001: AUG_G15_019_0001: multi_resource_write final user turn must not repeat a supported resource
