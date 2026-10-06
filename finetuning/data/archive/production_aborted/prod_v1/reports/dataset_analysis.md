# Dataset Analysis

- Total generated: 186
- Passed: 165
- Warnings: 9
- Failed: 0
- Machine validated: 174
- Machine rejected: 12
- Blocking failures: 0
- Approved training export: 11
- Pilot generated: 126
- Pilot approved: 52
- Production generated: 60
- Production approved: 11
- Production target: 1000
- Production remaining: 940
- Exact duplicates: 1
- Normalized duplicates: 0
- Near duplicates: 12

## By group

- G01: 10
- G06: 39
- G10: 15
- G11: 45
- G12: 10
- G15: 55

## By intent

- ACCESS_REQUEST: 71
- ACCESS_STATUS: 10
- ACCOUNT_SUPPORT: 60
- NO_TOOL: 33

## By difficulty

- DIRECT: 18
- HOW_TO: 5
- INFO_TO_ACTION_OVERRIDE: 8
- INTENT_REASON_CONFLICT: 39
- MULTI_TURN: 55
- NO_TOOL_LOOKALIKE: 28
- PARAPHRASE: 61
- POLICY: 5

## By resource

- ACCOUNT_SUPPORT: 60
- DEV_DB: 22
- PROD_DB: 43
- VPN: 16

## By ticket type

- ACCESS_REQUEST: 71
- ACCOUNT_SUPPORT: 60

## By source seed

- S001: 2
- S002: 2
- S003: 2
- S004: 2
- S005: 2
- S011: 3
- S012: 2
- S013: 2
- S014: 2
- S015: 2
- S016: 14
- S017: 12
- S018: 13
- S033: 6
- S034: 4
- S035: 4
- S036: 4
- S037: 4
- S043: 11
- S045: 10
- S046: 11
- S056: 5
- S057: 24
- S058: 27
- S059: 4

## By review status

- DROP: 7
- KEEP: 63
- REWRITE: 49
- UNREVIEWED: 55

## By generation subtype

- multi_resource_howto: 10
- multi_resource_write: 10
- single_resource_howto: 10
- single_resource_write: 10

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
