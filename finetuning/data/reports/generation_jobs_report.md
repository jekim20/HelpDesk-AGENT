# Generation Job Report

- Mode: FULL_PLAN_ONLY
- Jobs: 18
- Planned candidates: 1000
- Plan groups: 15
- Full plan target: 1000
- Full group target sum: 1000
- G15 subgroup sum: 100
- Errors: 0

| Group | Category | Multi-turn subtype | Count | Source seeds |
| --- | --- | --- | ---: | --- |
| G01_BATCH_001 | access_status_general | - | 90 | S001, S002, S003, S004, S005, S008, S010 |
| G02_BATCH_001 | access_status_distractor_contrastive | - | 60 | S006, S007, S009 |
| G03_BATCH_001 | access_request_general | - | 90 | S011, S012, S013, S014, S015, S021, S022, S054, S055 |
| G04_BATCH_001 | access_request_info_to_action_override | - | 20 | S020 |
| G05_BATCH_001 | access_request_reason_context | - | 70 | S014, S015, S021, S022, S054, S055 |
| G06_BATCH_001 | access_request_intent_reason_conflict | - | 80 | S016, S017, S018 |
| G07_BATCH_001 | incident_general | - | 80 | S023, S024, S025, S026, S027, S031 |
| G08_BATCH_001 | incident_info_to_action_override | - | 20 | S032 |
| G09_BATCH_001 | incident_contrastive_distractor | - | 70 | S028, S030 |
| G10_BATCH_001 | account_support_general | - | 60 | S056, S057, S058, S059 |
| G11_BATCH_001 | account_support_contrastive_conflict | - | 40 | S057, S058 |
| G12_BATCH_001 | no_tool_policy_howto | - | 90 | S033, S034, S035, S036, S037, S038, S039, S040, S046, S060 |
| G13_BATCH_001 | no_tool_unsupported | - | 60 | S041, S042, S051 |
| G14_BATCH_001 | no_tool_ambiguous_implicit_write | - | 70 | S048, S049, S052, S053, S061 |
| G15_BATCH_001 | multi_turn | single_resource_write | 25 | S011, S012, S013, S014, S015, S016, S017, S018, S019, S020, S021, S022, S043, S045, S054, S055 |
| G15_BATCH_001 | multi_turn | multi_resource_write | 25 | S011, S012, S013, S014, S015, S016, S017, S018, S019, S020, S021, S022, S043, S045, S054, S055 |
| G15_BATCH_001 | multi_turn | single_resource_howto | 25 | S033, S034, S035, S036, S037, S038, S039, S040, S041, S042, S046, S048, S049, S051, S052, S053, S060, S061 |
| G15_BATCH_001 | multi_turn | multi_resource_howto | 25 | S033, S034, S035, S036, S037, S038, S039, S040, S041, S042, S046, S048, S049, S051, S052, S053, S060, S061 |

## Final prompts

Each prompt contains `SYSTEM`, `USER`, and group-specific source instructions.

<details><summary>G01_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G01 / access_status_general
생성 개수: 90

목표:
현재 사용자의 VPN/DEV_DB/PROD_DB 접근 권한 상태 조회를 다양한 자연어 표현에서 식별한다.

반드시 지킬 규칙:
- 확인해줘/있는지 봐줘/쓸 수 있어?/접근 가능한가? 등 표현과 어순을 다양화한다.
- resource literal과 자연어 별칭(개발 DB, 운영 DB)을 적절히 섞는다.
- 정책 질문, 신청 요청, 장애 접수로 의미를 바꾸지 않는다.

피해야 할 패턴:
- 동일 템플릿에서 resource만 교체
- 모든 문장을 명령형으로 생성

참고 seed:
{"id": "S001", "intent": "ACCESS_STATUS", "difficulty": ["DIRECT"], "conversation": [{"role": "user", "content": "내 DEV_DB 권한 상태 알려줘."}], "target": {"decision": "TOOL", "tool_name": "getAccessStatus", "arguments": {"resource": "DEV_DB"}}}
{"id": "S002", "intent": "ACCESS_STATUS", "difficulty": ["DIRECT"], "conversation": [{"role": "user", "content": "내 PROD_DB 접근 권한 있는지 확인해줘."}], "target": {"decision": "TOOL", "tool_name": "getAccessStatus", "arguments": {"resource": "PROD_DB"}}}
{"id": "S003", "intent": "ACCESS_STATUS", "difficulty": ["DIRECT"], "conversation": [{"role": "user", "content": "VPN 권한 상태 좀 봐줘."}], "target": {"decision": "TOOL", "tool_name": "getAccessStatus", "arguments": {"resource": "VPN"}}}
{"id": "S004", "intent": "ACCESS_STATUS", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "내가 개발 DB에 들어갈 수 있는 상태인지 확인해줄래?"}], "target": {"decision": "TOOL", "tool_name": "getAccessStatus", "arguments": {"resource": "DEV_DB"}}}
{"id": "S005", "intent": "ACCESS_STATUS", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "운영 DB 사용 권한이 지금 승인돼 있는지 알고 싶어."}], "target": {"decision": "TOOL", "tool_name": "getAccessStatus", "arguments": {"resource": "PROD_DB"}}}
{"id": "S008", "intent": "ACCESS_STATUS", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "PROD_DB에 접근할 수 있는 상태인지 체크 부탁해."}], "target": {"decision": "TOOL", "tool_name": "getAccessStatus", "arguments": {"resource": "PROD_DB"}}}
{"id": "S010", "intent": "ACCESS_STATUS", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "개발 DB 지금 내가 쓸 수 있는 상태인지 봐줘."}], "target": {"decision": "TOOL", "tool_name": "getAccessStatus", "arguments": {"resource": "DEV_DB"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S001
required_decision = TOOL
required_tool_name = getAccessStatus
required_type = None
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S002
required_decision = TOOL
required_tool_name = getAccessStatus
required_type = None
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S003
required_decision = TOOL
required_tool_name = getAccessStatus
required_type = None
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S004
required_decision = TOOL
required_tool_name = getAccessStatus
required_type = None
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S005
required_decision = TOOL
required_tool_name = getAccessStatus
required_type = None
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S008
required_decision = TOOL
required_tool_name = getAccessStatus
required_type = None
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S010
required_decision = TOOL
required_tool_name = getAccessStatus
required_type = None
required_resource = DEV_DB
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- 현재 권한 상태 조회만 생성한다.
- `권한 있어?`, `지금 들어갈 수 있어?`, `사용 가능 상태야?` 등 화법을 분산한다.
- 신청/정책/장애 접수로 의미가 바뀌면 안 된다.
```

</details>

<details><summary>G02_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G02 / access_status_distractor_contrastive
생성 개수: 60

목표:
장애/배포/업무 맥락이 있어도 실제 행동이 현재 권한 조회이면 getAccessStatus를 선택한다.

반드시 지킬 규칙:
- 오도성 업무 맥락은 넣되 실제 장애 접수 요청은 넣지 않는다.
- 장애/오류/배포 등 특정 단어 하나에 편중하지 않는다.

피해야 할 패턴:
- 항상 '장애 대응 때문에'로 시작

참고 seed:
{"id": "S006", "intent": "ACCESS_STATUS", "difficulty": ["DISTRACTOR"], "conversation": [{"role": "user", "content": "오늘 배포 일정이 급한데, 우선 내 DEV_DB 권한부터 확인해줘."}], "target": {"decision": "TOOL", "tool_name": "getAccessStatus", "arguments": {"resource": "DEV_DB"}}}
{"id": "S007", "intent": "ACCESS_STATUS", "difficulty": ["NO_TOOL_LOOKALIKE"], "conversation": [{"role": "user", "content": "VPN 접속 가능한 권한이 내 계정에 있는지 확인해줘."}], "target": {"decision": "TOOL", "tool_name": "getAccessStatus", "arguments": {"resource": "VPN"}}}
{"id": "S009", "intent": "ACCESS_STATUS", "difficulty": ["DISTRACTOR"], "conversation": [{"role": "user", "content": "장애 대응 업무를 맡았는데, 내가 PROD_DB 권한을 이미 갖고 있는지 먼저 봐줘."}], "target": {"decision": "TOOL", "tool_name": "getAccessStatus", "arguments": {"resource": "PROD_DB"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S006
required_decision = TOOL
required_tool_name = getAccessStatus
required_type = None
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S007
required_decision = TOOL
required_tool_name = getAccessStatus
required_type = None
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S009
required_decision = TOOL
required_tool_name = getAccessStatus
required_type = None
required_resource = PROD_DB
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- 장애·업무·배포 맥락은 distractor일 뿐이다.
- 실제 장애 접수 행동은 들어가면 안 된다.
- `장애` 한 단어에만 편중하지 않는다.
```

</details>

<details><summary>G03_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G03 / access_request_general
생성 개수: 90

목표:
지원 resource에 대한 명시적인 권한 신청/접근 요청 실행을 식별한다.

반드시 지킬 규칙:
- 신청해줘/요청 넣어줘/등록해줘/티켓 올려줘 등 명시적 실행 cue를 다양화한다.
- 희망 진술만 있는 문장은 제외한다.

피해야 할 패턴:
- 신청 방법/절차 질문을 섞어 target 의미를 바꿈

참고 seed:
{"id": "S011", "intent": "ACCESS_REQUEST", "difficulty": ["DIRECT"], "conversation": [{"role": "user", "content": "DEV_DB 권한 신청해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S012", "intent": "ACCESS_REQUEST", "difficulty": ["DIRECT"], "conversation": [{"role": "user", "content": "PROD_DB 접근 요청 등록해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S013", "intent": "ACCESS_REQUEST", "difficulty": ["DIRECT"], "conversation": [{"role": "user", "content": "VPN 사용 권한 신청해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}}
{"id": "S014", "intent": "ACCESS_REQUEST", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "개발 DB를 사용할 수 있게 접근 요청을 넣어줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S015", "intent": "ACCESS_REQUEST", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "운영 DB 접근이 필요해. 권한 요청을 등록해줄래?"}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S021", "intent": "ACCESS_REQUEST", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "PROD_DB 사용 승인을 받을 수 있도록 접근 티켓을 만들어줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S022", "intent": "ACCESS_REQUEST", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "VPN을 쓸 수 있게 권한 요청 건 하나 올려줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}}
{"id": "S054", "intent": "ACCESS_REQUEST", "difficulty": ["REASON_CONTEXT", "PARAPHRASE"], "conversation": [{"role": "user", "content": "신규 프로젝트에 합류해서 DEV_DB를 써야 해. 권한 신청해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S055", "intent": "ACCESS_REQUEST", "difficulty": ["REASON_CONTEXT", "PARAPHRASE"], "conversation": [{"role": "user", "content": "타 팀 요청으로 자료 확인이 필요해서 그런데, PROD_DB 접근 요청 넣어줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S011
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S012
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S013
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S014
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S015
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S021
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S022
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S054
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S055
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- 명시적 Write cue가 반드시 존재해야 한다.
- `신청하고 싶어`처럼 희망만 표현하는 데이터는 생성하지 않는다.
```

</details>

<details><summary>G04_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G04 / access_request_info_to_action_override
생성 개수: 20

목표:
정보/방법 질문을 언급한 뒤 그것을 부정하고 실제 권한 신청 실행을 요구하는 문장을 처리한다.

반드시 지킬 규칙:
- ‘방법 말고’, ‘설명은 됐고’, ‘절차 말고 실제로’ 같은 override 구조를 다양화한다.
- 최종 행동은 반드시 ACCESS_REQUEST 실행이어야 한다.

피해야 할 패턴:
- 실제 실행 cue 없이 정보 질문으로 끝남

참고 seed:
{"id": "S020", "intent": "ACCESS_REQUEST", "difficulty": ["NO_TOOL_LOOKALIKE"], "conversation": [{"role": "user", "content": "DEV_DB 권한 신청 방법 말고, 실제 신청 요청을 지금 등록해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S020
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- 정보 질문 표현을 등장시킨 뒤 명시적으로 부정하고 실제 실행을 요구한다.
- 예: `신청 방법 말고 실제 요청을 등록해줘.`
```

</details>

<details><summary>G05_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G05 / access_request_reason_context
생성 개수: 70

목표:
다양한 일반 업무 사유가 있어도 권한 신청 행동을 안정적으로 식별한다.

반드시 지킬 규칙:
- 신규 프로젝트, 타 팀 협업, 업무 배정, 배포 준비, 데이터 확인, 담당 업무 변경 등을 고르게 사용한다.
- 장애/오류 사유에 편중하지 않는다.

피해야 할 패턴:
- 사유가 모두 장애 대응

참고 seed:
{"id": "S014", "intent": "ACCESS_REQUEST", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "개발 DB를 사용할 수 있게 접근 요청을 넣어줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S015", "intent": "ACCESS_REQUEST", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "운영 DB 접근이 필요해. 권한 요청을 등록해줄래?"}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S021", "intent": "ACCESS_REQUEST", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "PROD_DB 사용 승인을 받을 수 있도록 접근 티켓을 만들어줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S022", "intent": "ACCESS_REQUEST", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "VPN을 쓸 수 있게 권한 요청 건 하나 올려줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}}
{"id": "S054", "intent": "ACCESS_REQUEST", "difficulty": ["REASON_CONTEXT", "PARAPHRASE"], "conversation": [{"role": "user", "content": "신규 프로젝트에 합류해서 DEV_DB를 써야 해. 권한 신청해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S055", "intent": "ACCESS_REQUEST", "difficulty": ["REASON_CONTEXT", "PARAPHRASE"], "conversation": [{"role": "user", "content": "타 팀 요청으로 자료 확인이 필요해서 그런데, PROD_DB 접근 요청 넣어줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S014
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S015
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S021
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S022
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S054
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S055
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- 일반 사유 의미군을 균형 있게 쓴다.
- 신규 프로젝트 / 협업 / 배포 / 데이터 확인 / 담당 변경 / 업무 배정 등.
```

</details>

<details><summary>G06_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G06 / access_request_intent_reason_conflict
생성 개수: 80

목표:
INCIDENT를 연상시키는 사유보다 사용자의 명시적인 권한 신청 행동을 우선한다.

반드시 지킬 규칙:
- 장애 대응/오류 분석/접속 실패 조사/복구/문제 재현/로그 확인 등 오도성 의미군을 다양화한다.
- 오도성 단서는 문장 앞/중간/뒤에 다양하게 배치한다.
- 최종 실행 행동은 반드시 권한 신청임이 명확해야 한다.
- 권한 신청/접근 권한 요청/권한 요청/접근 요청처럼 ACCESS_REQUEST임을 문장 안에서 명시한다.
- Agent에게 권한 신청 실행을 명시적으로 요구하는 요청형 문장으로 만든다.
- source seed별 required_decision/required_tool_name/required_type/required_resource를 immutable constraint로 따른다.
- required_resource의 DEV_DB/PROD_DB/VPN 값을 그대로 사용하고 다른 supported resource로 바꾸거나 추가하지 않는다.
- 생성 문장에 명시한 resource는 target label의 required_resource와 반드시 일치해야 한다.
- 한국어로 자연스러운 사내 HelpDesk 요청을 만들고 sibling의 문장 구조와 어휘를 충분히 다양화한다.

피해야 할 패턴:
- ‘장애’ 단어만 반복
- 실제 장애 접수 행동까지 함께 포함
- 요청 좀 해줘/요청 올려줘/처리해줘처럼 ticket type이 불분명한 generic cue만으로 끝남
- 실제 권한 부여로 오해할 수 있는 ‘권한 만들어줘’ 표현
- 접근 권한 요청할게/신청할게처럼 사용자가 자신이 행동하겠다는 진술
- 권한 신청할 필요가 있어/신청하고 싶어처럼 필요나 희망만 표현하고 끝남
- 문장 끝이 요청해줘/처리해줘여서 구체적인 Write action이 불분명함
- required_resource 대신 다른 supported resource를 사용하거나 둘 이상의 supported resource를 함께 사용
- sibling에서 단어 하나만 교체한 near-paraphrase

참고 seed:
{"id": "S016", "intent": "ACCESS_REQUEST", "difficulty": ["INTENT_REASON_CONFLICT"], "conversation": [{"role": "user", "content": "운영 장애 대응 업무 때문에 PROD_DB 권한 신청해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S017", "intent": "ACCESS_REQUEST", "difficulty": ["INTENT_REASON_CONFLICT"], "conversation": [{"role": "user", "content": "VPN 접근 요청 하나 올려줘. 장애 대응 당번이라 오늘 필요해."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}}
{"id": "S018", "intent": "ACCESS_REQUEST", "difficulty": ["INTENT_REASON_CONFLICT"], "conversation": [{"role": "user", "content": "접속 실패 원인을 분석하는 업무인데 DEV_DB 권한이 없어. 접근 권한 신청해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S016
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S017
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S018
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- INCIDENT를 연상시키는 오도성 단서를 반드시 포함한다.
- 장애/오류/접속 실패/복구/문제 재현/로그 분석 등 의미군을 순환한다.
- 정답 행동은 반드시 명확한 권한 신청이어야 한다.
- `권한 신청`, `접근 권한 요청`, `권한 요청`, `접근 요청`처럼 ACCESS_REQUEST임을 문장 안에서 명시한다.
- `요청 좀 해줘`, `요청 올려줘`, `처리해줘`처럼 ticket type이 불분명한 generic cue만으로 끝내지 않는다.
- 실제 권한 부여로 오해할 수 있는 `권한 만들어줘` 표현은 생성하지 않는다.
- `접근 권한 요청할게`, `신청할게`처럼 사용자가 자신이 행동하겠다는 진술은 생성하지 않는다.
- `권한 신청할 필요가 있어`, `신청하고 싶어`처럼 필요나 희망만 표현한 문장은 생성하지 않는다.
- 문장 끝이 단순히 `요청해줘`, `처리해줘`여서 어떤 Write action인지 모호한 문장은 생성하지 않는다.
- Agent에게 권한 신청 실행을 명시적으로 요구해야 한다.
- 좋은 실행 표현: `PROD_DB 권한 신청해줘.`, `DEV_DB 접근 권한 요청 넣어줘.`, `VPN 접근 요청 등록해줘.`
- source seed마다 제공되는 `required_decision`, `required_tool_name`, `required_type`, `required_resource`는 immutable target constraint다.
- `required_resource`를 반드시 그대로 사용하고 DEV_DB/PROD_DB/VPN을 서로 바꾸지 않는다.
- `required_resource` 이외의 다른 supported resource를 새로 넣지 않는다.
- 생성 문장에 명시된 resource는 target label의 `required_resource`와 반드시 일치해야 한다.
- 한국어로 부자연스러운 표현을 피한다.
- sibling은 단어 하나만 바꾼 near-paraphrase가 아니라 문장 구조·어순·사유 의미군까지 다양화한다.
```

</details>

<details><summary>G07_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G07 / incident_general
생성 개수: 80

목표:
실제 서비스/접속 failure를 장애 Ticket으로 접수해 달라는 실행 요청을 식별한다.

반드시 지킬 규칙:
- 연결 실패, 끊김, 쿼리 오류, 반복 오류, 부분 기능 장애 등 현상 표현을 다양화한다.
- 접수해줘/티켓 만들어줘/문제로 남겨줘 등 실행 cue를 다양화한다.

피해야 할 패턴:
- 장애 절차/정책 질문으로 의미가 바뀜

참고 seed:
{"id": "S023", "intent": "INCIDENT", "difficulty": ["DIRECT"], "conversation": [{"role": "user", "content": "VPN 접속이 계속 실패해. 장애 접수해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "INCIDENT", "resource": "VPN"}}}
{"id": "S024", "intent": "INCIDENT", "difficulty": ["DIRECT"], "conversation": [{"role": "user", "content": "PROD_DB 접속 중 오류가 나. 장애 티켓 등록해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "INCIDENT", "resource": "PROD_DB"}}}
{"id": "S025", "intent": "INCIDENT", "difficulty": ["DIRECT"], "conversation": [{"role": "user", "content": "DEV_DB에 연결이 안 돼. 문제 접수해줄래?"}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "INCIDENT", "resource": "DEV_DB"}}}
{"id": "S026", "intent": "INCIDENT", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "VPN이 자꾸 끊겨서 업무를 못 하고 있어. 장애 건으로 등록해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "INCIDENT", "resource": "VPN"}}}
{"id": "S027", "intent": "INCIDENT", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "운영 DB 연결 오류가 반복돼. 이 문제 티켓 하나 만들어줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "INCIDENT", "resource": "PROD_DB"}}}
{"id": "S031", "intent": "INCIDENT", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "PROD_DB 연결 문제가 재현돼. 장애 티켓으로 남겨줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "INCIDENT", "resource": "PROD_DB"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S023
required_decision = TOOL
required_tool_name = createTicket
required_type = INCIDENT
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S024
required_decision = TOOL
required_tool_name = createTicket
required_type = INCIDENT
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S025
required_decision = TOOL
required_tool_name = createTicket
required_type = INCIDENT
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S026
required_decision = TOOL
required_tool_name = createTicket
required_type = INCIDENT
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S027
required_decision = TOOL
required_tool_name = createTicket
required_type = INCIDENT
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S031
required_decision = TOOL
required_tool_name = createTicket
required_type = INCIDENT
required_resource = PROD_DB
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- 실제 failure 현상 + 명시적 장애 접수 행동이 모두 있어야 한다.
- 단순 정책 질문은 생성하지 않는다.
```

</details>

<details><summary>G08_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G08 / incident_info_to_action_override
생성 개수: 20

목표:
장애 절차 설명을 부정하고 실제 장애 접수를 요구하는 override 구조를 처리한다.

반드시 지킬 규칙:
- ‘절차 말고’, ‘설명 필요 없고’, ‘가이드 말고 지금 접수’ 등 표현을 다양화한다.

피해야 할 패턴:
- 정보 질문으로 종료

참고 seed:
{"id": "S032", "intent": "INCIDENT", "difficulty": ["NO_TOOL_LOOKALIKE"], "conversation": [{"role": "user", "content": "VPN 장애 대응 절차 설명은 필요 없고, 지금 장애 티켓을 접수해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "INCIDENT", "resource": "VPN"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S032
required_decision = TOOL
required_tool_name = createTicket
required_type = INCIDENT
required_resource = VPN
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- `절차 말고`, `설명은 됐고`, `가이드 말고 지금` 등의 구조 뒤에 실제 장애 접수 행동을 둔다.
```

</details>

<details><summary>G09_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G09 / incident_contrastive_distractor
생성 개수: 70

목표:
권한/승인/로그인 등의 표현이 섞여도 실제 요청이 장애 접수이면 INCIDENT를 선택한다.

반드시 지킬 규칙:
- 권한은 정상인데 기능 오류, 로그인은 되지만 쿼리 오류, 간헐적 연결 실패 등 다양한 실패 구조를 사용한다.

피해야 할 패턴:
- ‘권한은 있는데 접속 실패’ 동일 문장 구조 반복

참고 seed:
{"id": "S028", "intent": "INCIDENT", "difficulty": ["CONTRASTIVE"], "conversation": [{"role": "user", "content": "PROD_DB 권한은 있는데 접속이 계속 실패해. 장애 접수해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "INCIDENT", "resource": "PROD_DB"}}}
{"id": "S030", "intent": "INCIDENT", "difficulty": ["DISTRACTOR"], "conversation": [{"role": "user", "content": "오늘 배포가 있어서 급해. VPN 접속 자체가 안 되니 장애 접수부터 해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "INCIDENT", "resource": "VPN"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S028
required_decision = TOOL
required_tool_name = createTicket
required_type = INCIDENT
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S030
required_decision = TOOL
required_tool_name = createTicket
required_type = INCIDENT
required_resource = VPN
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- 권한/승인/로그인 같은 다른 intent 단서를 넣되 실제 행동은 INCIDENT다.
- `권한은 있는데 접속 실패` 한 구조만 반복하지 않는다.
```

</details>

<details><summary>G10_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G10 / account_support_general
생성 개수: 60

목표:
계정/로그인 문제에 대해 명시적으로 지원 Ticket 실행을 요청하면 ACCOUNT_SUPPORT를 선택한다.

반드시 지킬 규칙:
- 계정 잠김, 로그인 문제, 사내 계정 지원 등 표현을 다양화한다.
- Write action이므로 명시적 실행 cue가 있어야 한다.
- 현재 DevDesk contract에 맞춰 resource는 ACCOUNT_SUPPORT로 고정한다.

피해야 할 패턴:
- ACCOUNT 또는 LOGIN 같은 임의 resource 생성
- 지원받고 싶다는 희망만으로 Tool 호출

참고 seed:
{"id": "S056", "intent": "ACCOUNT_SUPPORT", "difficulty": ["DIRECT"], "conversation": [{"role": "user", "content": "내 계정이 잠겼어. 계정 지원 티켓 만들어줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCOUNT_SUPPORT", "resource": "ACCOUNT_SUPPORT"}}}
{"id": "S057", "intent": "ACCOUNT_SUPPORT", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "회사 계정 로그인이 안 돼. 계정 지원 요청 접수해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCOUNT_SUPPORT", "resource": "ACCOUNT_SUPPORT"}}}
{"id": "S058", "intent": "ACCOUNT_SUPPORT", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "사내 계정에 문제가 있어. 지원 티켓 하나 올려줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCOUNT_SUPPORT", "resource": "ACCOUNT_SUPPORT"}}}
{"id": "S059", "intent": "ACCOUNT_SUPPORT", "difficulty": ["INFO_TO_ACTION_OVERRIDE"], "conversation": [{"role": "user", "content": "계정 지원 절차 설명 말고, 실제 지원 티켓을 지금 만들어줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCOUNT_SUPPORT", "resource": "ACCOUNT_SUPPORT"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S056
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCOUNT_SUPPORT
required_resource = ACCOUNT_SUPPORT
The generated conversation must preserve every required value above.

source_seed_id = S057
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCOUNT_SUPPORT
required_resource = ACCOUNT_SUPPORT
The generated conversation must preserve every required value above.

source_seed_id = S058
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCOUNT_SUPPORT
required_resource = ACCOUNT_SUPPORT
The generated conversation must preserve every required value above.

source_seed_id = S059
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCOUNT_SUPPORT
required_resource = ACCOUNT_SUPPORT
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- 계정/로그인 문제 + 명시적 계정 지원 Ticket 실행 요청.
- target은 항상 `type=ACCOUNT_SUPPORT`, `resource=ACCOUNT_SUPPORT`.
- `ACCOUNT`, `LOGIN` 등의 임의 resource를 만들지 않는다.
- `티켓 하나 올려줘`, `요청 처리해줘`처럼 ticket type이 불명확한 generic 표현만으로 끝내지 않는다.
- `계정 지원`, `계정 지원 티켓`, `계정 관련 지원 요청`처럼 ACCOUNT_SUPPORT action임을 문맥상 충분히 식별할 수 있게 생성한다.
- 띄어쓰기와 기본적인 한국어 문장 자연스러움을 유지한다.
```

</details>

<details><summary>G11_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G11 / account_support_contrastive_conflict
생성 개수: 40

목표:
‘실패/오류’ 단어가 있어도 사용자가 명시적으로 계정 지원 Ticket을 요청하면 ACCOUNT_SUPPORT를 선택한다.

반드시 지킬 규칙:
- 로그인 실패/계정 오류 등 INCIDENT를 연상시킬 수 있는 단서를 포함한다.
- 최종 실행 요청은 명확한 계정 지원이어야 한다.

피해야 할 패턴:
- VPN/DB 자체의 장애 접수를 ACCOUNT_SUPPORT로 오라벨

참고 seed:
{"id": "S057", "intent": "ACCOUNT_SUPPORT", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "회사 계정 로그인이 안 돼. 계정 지원 요청 접수해줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCOUNT_SUPPORT", "resource": "ACCOUNT_SUPPORT"}}}
{"id": "S058", "intent": "ACCOUNT_SUPPORT", "difficulty": ["PARAPHRASE"], "conversation": [{"role": "user", "content": "사내 계정에 문제가 있어. 지원 티켓 하나 올려줘."}], "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCOUNT_SUPPORT", "resource": "ACCOUNT_SUPPORT"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S057
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCOUNT_SUPPORT
required_resource = ACCOUNT_SUPPORT
The generated conversation must preserve every required value above.

source_seed_id = S058
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCOUNT_SUPPORT
required_resource = ACCOUNT_SUPPORT
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- `실패`, `오류` 등 INCIDENT shortcut을 포함할 수 있다.
- 하지만 사용자의 명시적 행동은 계정 지원 Ticket이어야 한다.
- VPN/DB 자체의 장애 접수를 ACCOUNT_SUPPORT로 바꾸면 오라벨이다.
```

</details>

<details><summary>G12_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G12 / no_tool_policy_howto
생성 개수: 90

목표:
권한/장애/계정 관련 단어가 있어도 정책·절차·조건·이유·소요시간 질문이면 Tool을 호출하지 않는다.

반드시 지킬 규칙:
- 지원 resource와 Tool 관련 단어를 일부 포함해 hard negative를 만든다.
- 실제 실행 요청 cue가 들어가면 안 된다.

피해야 할 패턴:
- 신청해줘/접수해줘/만들어줘 같은 명시적 실행 cue 포함

참고 seed:
{"id": "S033", "intent": "NO_TOOL", "difficulty": ["DIRECT"], "conversation": [{"role": "user", "content": "VPN 승인에는 얼마나 걸려?"}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S034", "intent": "NO_TOOL", "difficulty": ["DIRECT"], "conversation": [{"role": "user", "content": "PROD_DB 접근 정책 알려줘."}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S035", "intent": "NO_TOOL", "difficulty": ["NO_TOOL_LOOKALIKE"], "conversation": [{"role": "user", "content": "DEV_DB 권한 신청 절차가 어떻게 돼?"}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S036", "intent": "NO_TOOL", "difficulty": ["NO_TOOL_LOOKALIKE"], "conversation": [{"role": "user", "content": "VPN 장애 대응 절차를 알려줘."}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S037", "intent": "NO_TOOL", "difficulty": ["NO_TOOL_LOOKALIKE"], "conversation": [{"role": "user", "content": "PROD_DB에 접근할 때 별도 권한이 필요한 이유가 뭐야?"}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S038", "intent": "NO_TOOL", "difficulty": ["POLICY"], "conversation": [{"role": "user", "content": "운영 DB 접근 승인을 받으려면 어떤 조건이 필요해?"}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S039", "intent": "NO_TOOL", "difficulty": ["POLICY"], "conversation": [{"role": "user", "content": "VPN 사용 신청은 보통 어떤 절차로 진행돼?"}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S040", "intent": "NO_TOOL", "difficulty": ["POLICY"], "conversation": [{"role": "user", "content": "DEV_DB와 PROD_DB 접근 정책 차이가 뭐야?"}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S046", "intent": "NO_TOOL", "difficulty": ["MULTI_TURN", "NO_TOOL_LOOKALIKE"], "conversation": [{"role": "user", "content": "DEV_DB 권한 상태 확인해줘."}, {"role": "assistant", "content": "DEV_DB 권한 상태를 확인했습니다."}, {"role": "user", "content": "그 권한 신청 방법은 어떻게 돼?"}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S060", "intent": "NO_TOOL", "difficulty": ["POLICY", "ACCOUNT_SUPPORT_LOOKALIKE"], "conversation": [{"role": "user", "content": "계정이 잠겼을 때 지원 요청은 어떤 절차로 진행돼?"}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S033
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S034
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S035
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S036
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S037
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S038
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S039
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S040
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S046
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S060
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- 권한/장애/계정/Ticket 같은 단어를 일부러 섞어 hard negative를 만든다.
- 실제 실행 cue는 금지한다.
```

</details>

<details><summary>G13_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G13 / no_tool_unsupported
생성 개수: 60

목표:
지원하지 않는 resource 또는 지원하지 않는 Tool 행동을 기존 schema에 억지로 매핑하지 않는다.

반드시 지킬 규칙:
- resource 하위 예시에는 STAGING_DB, TEST_DB, QA_DB, GPU_SERVER 등 근접 이름을 포함한다.
- 지원하지 않는 행동은 현재 Tool schema에 존재하지 않는 요청으로 구성한다.
- ACCOUNT_SUPPORT는 실제 지원 type이므로 unsupported type으로 취급하지 않는다.

피해야 할 패턴:
- 미지원 resource를 DEV_DB/PROD_DB로 임의 치환

참고 seed:
{"id": "S041", "intent": "NO_TOOL", "difficulty": ["UNSUPPORTED"], "conversation": [{"role": "user", "content": "프린터 사용 권한 상태 알려줘."}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S042", "intent": "NO_TOOL", "difficulty": ["UNSUPPORTED"], "conversation": [{"role": "user", "content": "GPU_SERVER 권한 신청해줘."}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S051", "intent": "NO_TOOL", "difficulty": ["UNSUPPORTED", "NEAR_NAME_RESOURCE"], "conversation": [{"role": "user", "content": "STAGING_DB 권한 상태 알려줘."}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S041
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S042
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S051
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- 지원 resource와 이름이 비슷한 미지원 resource를 일부 포함한다.
- 지원하지 않는 action도 포함하되 기존 Tool로 임의 치환하지 않는다.
- ACCOUNT_SUPPORT는 실제 지원 type이므로 unsupported로 만들지 않는다.
```

</details>

<details><summary>G14_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
JSONL만 출력한다. 설명 문장은 출력하지 않는다.
각 record에는 다음 필드를 포함한다.
- id
- source_seed_id
- generation_group_id
- generation_category
- intent
- difficulty
- conversation
- target

USER
generation_group: G14 / no_tool_ambiguous_implicit_write
생성 개수: 70

목표:
resource/type/action이 불충분하거나 Write 희망만 표현된 경우 성급하게 Tool을 실행하지 않는다.

반드시 지킬 규칙:
- ‘DB 좀 처리해줘’, ‘VPN이 필요해’, ‘계정 지원받고 싶어’ 등 모호성을 다양화한다.
- 실제 서비스에서는 clarification 후보지만 이번 routing target은 NO_TOOL이다.

피해야 할 패턴:
- 실제로는 target이 명확한 문장을 NO_TOOL로 라벨

참고 seed:
{"id": "S048", "intent": "NO_TOOL", "difficulty": ["AMBIGUOUS"], "conversation": [{"role": "user", "content": "DB 관련해서 좀 처리해줘."}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S049", "intent": "NO_TOOL", "difficulty": ["AMBIGUOUS"], "conversation": [{"role": "user", "content": "VPN이 필요해."}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S052", "intent": "NO_TOOL", "difficulty": ["AMBIGUOUS", "TICKET_LOOKALIKE"], "conversation": [{"role": "user", "content": "DB 문제 있는 것 같은데 티켓 하나 만들어줘."}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S053", "intent": "NO_TOOL", "difficulty": ["AMBIGUOUS", "IMPLICIT_WRITE"], "conversation": [{"role": "user", "content": "VPN 사용 권한 신청하고 싶어."}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S061", "intent": "NO_TOOL", "difficulty": ["AMBIGUOUS", "IMPLICIT_WRITE", "ACCOUNT_SUPPORT_LOOKALIKE"], "conversation": [{"role": "user", "content": "계정 지원받고 싶어."}], "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S048
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S049
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S052
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S053
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S061
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
- 실행 의도가 불분명하거나 Write 희망만 표현한다.
- `신청하고 싶어`, `지원받고 싶어`, `DB 좀 처리해줘` 같은 유형.
- 실제 서비스에서는 clarification 후보지만 이번 target은 NO_TOOL이다.
```

</details>

<details><summary>G15_single_resource_write_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
- JSONL만 출력하고 설명 문장은 출력하지 않는다.
- 각 record에는 canonical conversation array만 포함한다.
- ID, difficulty, label, target, provenance, subtype, scenario metadata는 pipeline이 부착한다.

USER
generation_group: G15 / multi_turn
생성 개수: 25

목표:
대화 context에서 resource와 행동을 복원하고, 중간 전환이 있어도 마지막 사용자 의도를 올바르게 처리한다.

반드시 지킬 규칙:
- 항상 바로 직전 resource만 참조하는 단순 패턴으로 쏠리지 않게 한다.
- Write subtype은 ACCESS_REQUEST, how-to subtype은 NO_TOOL target을 유지한다.
- 마지막 user turn만 읽어서는 정답을 확정하기 어려운 사례를 포함한다.
- 한 batch에서 같은 conversation skeleton을 반복하지 않는다.
- 단어 몇 개만 바꾼 sibling paraphrase를 만들지 않는다.
- Write target은 `신청해줘`, `요청 등록해줘` 등 명시적인 실행 요구를 사용한다.
- NO_TOOL target은 `방법`, `절차`, `조건`, `과정` 등을 묻는 정보 요청이어야 하며 실제 실행 요청을 섞지 않는다.
- assistant history도 자연스러운 한국어와 띄어쓰기를 유지한다.

피해야 할 패턴:
- 모든 예시가 PROD_DB → 그 권한 구조

참고 seed:
{"id": "S011", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S012", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S013", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}}
{"id": "S014", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S015", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S016", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S017", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}}
{"id": "S018", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S019", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S020", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S021", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S022", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}}
{"id": "S043", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S045", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S054", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S055", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
scenario spec마다 같은 순서로 conversation 하나를 생성하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S011
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S012
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S013
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S014
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S015
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S016
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S017
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S018
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S019
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S020
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S021
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S022
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S043
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S045
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S054
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S055
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
개별 generation job은 augmentation plan에서 지정된 `multi_turn_subtype` 하나만 생성한다.
- resource 개수, 마지막 user turn 행동, target은 job에 전달된 subtype 요구사항을 따른다.
- 생성 개수는 runtime `--count`를 유일한 기준으로 사용한다.
- pipeline이 먼저 정한 structured scenario spec을 conversation으로 자연스럽게 표현만 한다.
- source seed conversation은 생성 예문으로 사용하지 않는다. source seed는 eligible label과 target provenance에만 사용한다.
- spec의 `scenario_resources` 순서와 `required_resource`를 바꾸지 않는다.
- 마지막 reference user turn에서는 DEV_DB, PROD_DB, VPN 이름을 직접 반복하지 않는다.
- LLM은 canonical `conversation` array만 생성한다. plain-text 대화 문자열이나 difficulty/label/metadata는 생성하지 않는다.

추가 원칙:
- 마지막 user turn만 읽어서는 정답을 확정하기 어려운 사례를 충분히 포함한다.
- Write subtype은 ACCESS_REQUEST, how-to subtype은 NO_TOOL target을 유지한다.
- `PROD_DB → 그 권한` 구조만 반복하지 않는다.
- 한 batch에서 같은 conversation skeleton을 반복하지 않고, 단어 몇 개만 바꾼 sibling paraphrase를 만들지 않는다.
- Write target은 `신청해줘`, `요청 등록해줘` 등 명시적인 실행 요구를 사용한다.
- NO_TOOL target은 `방법`, `절차`, `조건`, `과정` 등을 묻는 정보 요청으로 끝내고 실제 실행 요청을 섞지 않는다.
- assistant history도 자연스러운 한국어와 띄어쓰기를 유지한다.

MULTI-TURN SUBTYPE REQUIREMENT
multi_turn_subtype = single_resource_write
resource-count requirement: supported resource 정확히 1개
final-action requirement: 마지막 user turn은 명시적인 권한 Write 실행 요청
target requirement: TOOL / ACCESS_REQUEST
이 generation job에서는 지정된 subtype 구조만 사용한다.

STRUCTURED SCENARIO SPECS
아래 spec은 pipeline이 미리 결정했다. source conversation을 재구성하거나 paraphrase하지 말고, 각 spec을 같은 순서로 자연스러운 conversation 하나로만 표현한다. scenario_resources의 순서를 바꾸지 않는다. 마지막 reference turn에는 DEV_DB, PROD_DB, VPN 이름을 직접 쓰지 않는다.
{"scenario_index": 1, "source_seed_id": "S011", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 2, "source_seed_id": "S012", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 3, "source_seed_id": "S013", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}, "required_resource": "VPN", "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 4, "source_seed_id": "S014", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 5, "source_seed_id": "S015", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 6, "source_seed_id": "S016", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 7, "source_seed_id": "S017", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}, "required_resource": "VPN", "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 8, "source_seed_id": "S018", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 9, "source_seed_id": "S019", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 10, "source_seed_id": "S020", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 11, "source_seed_id": "S021", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 12, "source_seed_id": "S022", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}, "required_resource": "VPN", "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 13, "source_seed_id": "S043", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 14, "source_seed_id": "S045", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 15, "source_seed_id": "S054", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 16, "source_seed_id": "S055", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 17, "source_seed_id": "S011", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 18, "source_seed_id": "S012", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 19, "source_seed_id": "S013", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}, "required_resource": "VPN", "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 20, "source_seed_id": "S014", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 21, "source_seed_id": "S015", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 22, "source_seed_id": "S016", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 23, "source_seed_id": "S017", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}, "required_resource": "VPN", "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 24, "source_seed_id": "S018", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 25, "source_seed_id": "S019", "generation_subtype": "single_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
```

</details>

<details><summary>G15_multi_resource_write_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
- JSONL만 출력하고 설명 문장은 출력하지 않는다.
- 각 record에는 canonical conversation array만 포함한다.
- ID, difficulty, label, target, provenance, subtype, scenario metadata는 pipeline이 부착한다.

USER
generation_group: G15 / multi_turn
생성 개수: 25

목표:
대화 context에서 resource와 행동을 복원하고, 중간 전환이 있어도 마지막 사용자 의도를 올바르게 처리한다.

반드시 지킬 규칙:
- 항상 바로 직전 resource만 참조하는 단순 패턴으로 쏠리지 않게 한다.
- Write subtype은 ACCESS_REQUEST, how-to subtype은 NO_TOOL target을 유지한다.
- 마지막 user turn만 읽어서는 정답을 확정하기 어려운 사례를 포함한다.
- 한 batch에서 같은 conversation skeleton을 반복하지 않는다.
- 단어 몇 개만 바꾼 sibling paraphrase를 만들지 않는다.
- Write target은 `신청해줘`, `요청 등록해줘` 등 명시적인 실행 요구를 사용한다.
- NO_TOOL target은 `방법`, `절차`, `조건`, `과정` 등을 묻는 정보 요청이어야 하며 실제 실행 요청을 섞지 않는다.
- assistant history도 자연스러운 한국어와 띄어쓰기를 유지한다.

피해야 할 패턴:
- 모든 예시가 PROD_DB → 그 권한 구조

참고 seed:
{"id": "S011", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S012", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S013", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}}
{"id": "S014", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S015", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S016", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S017", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}}
{"id": "S018", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S019", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S020", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S021", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S022", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}}
{"id": "S043", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S045", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}
{"id": "S054", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}}
{"id": "S055", "intent": "ACCESS_REQUEST", "target": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
scenario spec마다 같은 순서로 conversation 하나를 생성하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S011
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S012
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S013
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S014
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S015
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S016
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S017
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S018
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S019
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S020
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S021
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S022
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = VPN
The generated conversation must preserve every required value above.

source_seed_id = S043
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S045
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

source_seed_id = S054
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = DEV_DB
The generated conversation must preserve every required value above.

source_seed_id = S055
required_decision = TOOL
required_tool_name = createTicket
required_type = ACCESS_REQUEST
required_resource = PROD_DB
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
개별 generation job은 augmentation plan에서 지정된 `multi_turn_subtype` 하나만 생성한다.
- resource 개수, 마지막 user turn 행동, target은 job에 전달된 subtype 요구사항을 따른다.
- 생성 개수는 runtime `--count`를 유일한 기준으로 사용한다.
- pipeline이 먼저 정한 structured scenario spec을 conversation으로 자연스럽게 표현만 한다.
- source seed conversation은 생성 예문으로 사용하지 않는다. source seed는 eligible label과 target provenance에만 사용한다.
- spec의 `scenario_resources` 순서와 `required_resource`를 바꾸지 않는다.
- 마지막 reference user turn에서는 DEV_DB, PROD_DB, VPN 이름을 직접 반복하지 않는다.
- LLM은 canonical `conversation` array만 생성한다. plain-text 대화 문자열이나 difficulty/label/metadata는 생성하지 않는다.

추가 원칙:
- 마지막 user turn만 읽어서는 정답을 확정하기 어려운 사례를 충분히 포함한다.
- Write subtype은 ACCESS_REQUEST, how-to subtype은 NO_TOOL target을 유지한다.
- `PROD_DB → 그 권한` 구조만 반복하지 않는다.
- 한 batch에서 같은 conversation skeleton을 반복하지 않고, 단어 몇 개만 바꾼 sibling paraphrase를 만들지 않는다.
- Write target은 `신청해줘`, `요청 등록해줘` 등 명시적인 실행 요구를 사용한다.
- NO_TOOL target은 `방법`, `절차`, `조건`, `과정` 등을 묻는 정보 요청으로 끝내고 실제 실행 요청을 섞지 않는다.
- assistant history도 자연스러운 한국어와 띄어쓰기를 유지한다.

MULTI-TURN SUBTYPE REQUIREMENT
multi_turn_subtype = multi_resource_write
resource-count requirement: supported resource 2개 이상
final-action requirement: 마지막 user turn은 resource 이름을 반복하지 않고 지시어로 가장 최근 resource를 참조하는 명시적 권한 Write 실행 요청
target requirement: TOOL / ACCESS_REQUEST
이 generation job에서는 지정된 subtype 구조만 사용한다.

STRUCTURED SCENARIO SPECS
아래 spec은 pipeline이 미리 결정했다. source conversation을 재구성하거나 paraphrase하지 말고, 각 spec을 같은 순서로 자연스러운 conversation 하나로만 표현한다. scenario_resources의 순서를 바꾸지 않는다. 마지막 reference turn에는 DEV_DB, PROD_DB, VPN 이름을 직접 쓰지 않는다.
{"scenario_index": 1, "source_seed_id": "S011", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["PROD_DB", "DEV_DB"], "distractor_resource": "PROD_DB", "required_sequence": "PROD_DB user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 2, "source_seed_id": "S012", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["VPN", "PROD_DB"], "distractor_resource": "VPN", "required_sequence": "VPN user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 3, "source_seed_id": "S013", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}, "required_resource": "VPN", "scenario_resources": ["DEV_DB", "VPN"], "distractor_resource": "DEV_DB", "required_sequence": "DEV_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 4, "source_seed_id": "S014", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["VPN", "DEV_DB"], "distractor_resource": "VPN", "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 5, "source_seed_id": "S015", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["DEV_DB", "PROD_DB"], "distractor_resource": "DEV_DB", "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 6, "source_seed_id": "S016", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["VPN", "PROD_DB"], "distractor_resource": "VPN", "required_sequence": "VPN user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 7, "source_seed_id": "S017", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}, "required_resource": "VPN", "scenario_resources": ["DEV_DB", "VPN"], "distractor_resource": "DEV_DB", "required_sequence": "DEV_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 8, "source_seed_id": "S018", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["VPN", "DEV_DB"], "distractor_resource": "VPN", "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 9, "source_seed_id": "S019", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["DEV_DB", "PROD_DB"], "distractor_resource": "DEV_DB", "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 10, "source_seed_id": "S020", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["VPN", "DEV_DB"], "distractor_resource": "VPN", "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 11, "source_seed_id": "S021", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["DEV_DB", "PROD_DB"], "distractor_resource": "DEV_DB", "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 12, "source_seed_id": "S022", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}, "required_resource": "VPN", "scenario_resources": ["PROD_DB", "VPN"], "distractor_resource": "PROD_DB", "required_sequence": "PROD_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 13, "source_seed_id": "S043", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["DEV_DB", "PROD_DB"], "distractor_resource": "DEV_DB", "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 14, "source_seed_id": "S045", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["VPN", "PROD_DB"], "distractor_resource": "VPN", "required_sequence": "VPN user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 15, "source_seed_id": "S054", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["PROD_DB", "DEV_DB"], "distractor_resource": "PROD_DB", "required_sequence": "PROD_DB user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 16, "source_seed_id": "S055", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["VPN", "PROD_DB"], "distractor_resource": "VPN", "required_sequence": "VPN user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 17, "source_seed_id": "S011", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["PROD_DB", "DEV_DB"], "distractor_resource": "PROD_DB", "required_sequence": "PROD_DB user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 18, "source_seed_id": "S012", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["VPN", "PROD_DB"], "distractor_resource": "VPN", "required_sequence": "VPN user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 19, "source_seed_id": "S013", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}, "required_resource": "VPN", "scenario_resources": ["DEV_DB", "VPN"], "distractor_resource": "DEV_DB", "required_sequence": "DEV_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 20, "source_seed_id": "S014", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["VPN", "DEV_DB"], "distractor_resource": "VPN", "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 21, "source_seed_id": "S015", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["DEV_DB", "PROD_DB"], "distractor_resource": "DEV_DB", "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 22, "source_seed_id": "S016", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["VPN", "PROD_DB"], "distractor_resource": "VPN", "required_sequence": "VPN user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 23, "source_seed_id": "S017", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "VPN"}}, "required_resource": "VPN", "scenario_resources": ["DEV_DB", "VPN"], "distractor_resource": "DEV_DB", "required_sequence": "DEV_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 24, "source_seed_id": "S018", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"}}, "required_resource": "DEV_DB", "scenario_resources": ["VPN", "DEV_DB"], "distractor_resource": "VPN", "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
{"scenario_index": 25, "source_seed_id": "S019", "generation_subtype": "multi_resource_write", "target_contract": {"decision": "TOOL", "tool_name": "createTicket", "arguments": {"type": "ACCESS_REQUEST", "resource": "PROD_DB"}}, "required_resource": "PROD_DB", "scenario_resources": ["DEV_DB", "PROD_DB"], "distractor_resource": "DEV_DB", "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 explicit ACCESS_REQUEST user turn"}
```

</details>

<details><summary>G15_single_resource_howto_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
- JSONL만 출력하고 설명 문장은 출력하지 않는다.
- 각 record에는 canonical conversation array만 포함한다.
- ID, difficulty, label, target, provenance, subtype, scenario metadata는 pipeline이 부착한다.

USER
generation_group: G15 / multi_turn
생성 개수: 25

목표:
대화 context에서 resource와 행동을 복원하고, 중간 전환이 있어도 마지막 사용자 의도를 올바르게 처리한다.

반드시 지킬 규칙:
- 항상 바로 직전 resource만 참조하는 단순 패턴으로 쏠리지 않게 한다.
- Write subtype은 ACCESS_REQUEST, how-to subtype은 NO_TOOL target을 유지한다.
- 마지막 user turn만 읽어서는 정답을 확정하기 어려운 사례를 포함한다.
- 한 batch에서 같은 conversation skeleton을 반복하지 않는다.
- 단어 몇 개만 바꾼 sibling paraphrase를 만들지 않는다.
- Write target은 `신청해줘`, `요청 등록해줘` 등 명시적인 실행 요구를 사용한다.
- NO_TOOL target은 `방법`, `절차`, `조건`, `과정` 등을 묻는 정보 요청이어야 하며 실제 실행 요청을 섞지 않는다.
- assistant history도 자연스러운 한국어와 띄어쓰기를 유지한다.

피해야 할 패턴:
- 모든 예시가 PROD_DB → 그 권한 구조

참고 seed:
{"id": "S033", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S034", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S035", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S036", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S037", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S038", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S039", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S040", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S041", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S042", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S046", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S048", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S049", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S051", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S052", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S053", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S060", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S061", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
scenario spec마다 같은 순서로 conversation 하나를 생성하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S033
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S034
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S035
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S036
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S037
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S038
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S039
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S040
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S041
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S042
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S046
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S048
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S049
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S051
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S052
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S053
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S060
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S061
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
개별 generation job은 augmentation plan에서 지정된 `multi_turn_subtype` 하나만 생성한다.
- resource 개수, 마지막 user turn 행동, target은 job에 전달된 subtype 요구사항을 따른다.
- 생성 개수는 runtime `--count`를 유일한 기준으로 사용한다.
- pipeline이 먼저 정한 structured scenario spec을 conversation으로 자연스럽게 표현만 한다.
- source seed conversation은 생성 예문으로 사용하지 않는다. source seed는 eligible label과 target provenance에만 사용한다.
- spec의 `scenario_resources` 순서와 `required_resource`를 바꾸지 않는다.
- 마지막 reference user turn에서는 DEV_DB, PROD_DB, VPN 이름을 직접 반복하지 않는다.
- LLM은 canonical `conversation` array만 생성한다. plain-text 대화 문자열이나 difficulty/label/metadata는 생성하지 않는다.

추가 원칙:
- 마지막 user turn만 읽어서는 정답을 확정하기 어려운 사례를 충분히 포함한다.
- Write subtype은 ACCESS_REQUEST, how-to subtype은 NO_TOOL target을 유지한다.
- `PROD_DB → 그 권한` 구조만 반복하지 않는다.
- 한 batch에서 같은 conversation skeleton을 반복하지 않고, 단어 몇 개만 바꾼 sibling paraphrase를 만들지 않는다.
- Write target은 `신청해줘`, `요청 등록해줘` 등 명시적인 실행 요구를 사용한다.
- NO_TOOL target은 `방법`, `절차`, `조건`, `과정` 등을 묻는 정보 요청으로 끝내고 실제 실행 요청을 섞지 않는다.
- assistant history도 자연스러운 한국어와 띄어쓰기를 유지한다.

MULTI-TURN SUBTYPE REQUIREMENT
multi_turn_subtype = single_resource_howto
resource-count requirement: supported resource 정확히 1개
final-action requirement: 마지막 user turn은 해당 resource의 방법, 절차 또는 조건을 묻는 정보 요청이며 실행 요청을 포함하지 않음
target requirement: NO_TOOL
이 generation job에서는 지정된 subtype 구조만 사용한다.

STRUCTURED SCENARIO SPECS
아래 spec은 pipeline이 미리 결정했다. source conversation을 재구성하거나 paraphrase하지 말고, 각 spec을 같은 순서로 자연스러운 conversation 하나로만 표현한다. scenario_resources의 순서를 바꾸지 않는다. 마지막 reference turn에는 DEV_DB, PROD_DB, VPN 이름을 직접 쓰지 않는다.
{"scenario_index": 1, "source_seed_id": "S033", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 2, "source_seed_id": "S034", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 3, "source_seed_id": "S035", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 4, "source_seed_id": "S036", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 5, "source_seed_id": "S037", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 6, "source_seed_id": "S038", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 7, "source_seed_id": "S039", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 8, "source_seed_id": "S040", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 9, "source_seed_id": "S041", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 10, "source_seed_id": "S042", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 11, "source_seed_id": "S046", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 12, "source_seed_id": "S048", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 13, "source_seed_id": "S049", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 14, "source_seed_id": "S051", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 15, "source_seed_id": "S052", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 16, "source_seed_id": "S053", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 17, "source_seed_id": "S060", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 18, "source_seed_id": "S061", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 19, "source_seed_id": "S033", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 20, "source_seed_id": "S034", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 21, "source_seed_id": "S035", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 22, "source_seed_id": "S036", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 23, "source_seed_id": "S037", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB"], "required_sequence": "PROD_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 24, "source_seed_id": "S038", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN"], "required_sequence": "VPN user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 25, "source_seed_id": "S039", "generation_subtype": "single_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB"], "required_sequence": "DEV_DB user context/status turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
```

</details>

<details><summary>G15_multi_resource_howto_PLAN_001</summary>

```text
SYSTEM
당신은 DevDesk AI Agent의 Tool-Calling 학습 데이터 생성기다.

목표는 자연스러운 한국어 사내 HelpDesk 요청을 생성하는 것이다.
각 예시는 주어진 generation category의 의미와 target label을 절대 바꾸면 안 된다.

지원 Tool:
1) getAccessStatus(resource)
   - resource: VPN, DEV_DB, PROD_DB

2) createTicket(type, resource)
   - ACCESS_REQUEST: resource = VPN | DEV_DB | PROD_DB
   - INCIDENT: resource = VPN | DEV_DB | PROD_DB
   - ACCOUNT_SUPPORT: resource = ACCOUNT_SUPPORT

이번 학습 target에서는 reason, userId, ToolContext를 생성하지 않는다.

실행 원칙:
- 상태 조회(Read)는 현재 상태를 묻는 의도가 충분히 명확하면 진술형도 허용한다.
- Ticket 생성(Write)은 신청해줘/접수해줘/등록해줘/올려줘/만들어줘 등 명시적 실행 cue가 있어야 한다.
- 정책/절차/설명/조건 질문은 NO_TOOL이다.
- 지원하지 않는 resource/action을 기존 Tool에 억지로 매핑하지 않는다.

자연스러움:
- 실제 회사 사용자가 챗봇에 입력할 법한 한국어를 쓴다.
- 기계번역투와 지나치게 정제된 문어체를 피한다.
- 짧은 문장, 두 문장 요청, 구어체를 섞는다.

다양성:
- 동일한 문장 골격에서 resource만 교체하지 않는다.
- 어순, 요청 방식, 배경 정보의 위치, 문장 길이를 다양화한다.
- 특정 키워드 하나를 label shortcut으로 반복하지 않는다.

출력:
- JSONL만 출력하고 설명 문장은 출력하지 않는다.
- 각 record에는 canonical conversation array만 포함한다.
- ID, difficulty, label, target, provenance, subtype, scenario metadata는 pipeline이 부착한다.

USER
generation_group: G15 / multi_turn
생성 개수: 25

목표:
대화 context에서 resource와 행동을 복원하고, 중간 전환이 있어도 마지막 사용자 의도를 올바르게 처리한다.

반드시 지킬 규칙:
- 항상 바로 직전 resource만 참조하는 단순 패턴으로 쏠리지 않게 한다.
- Write subtype은 ACCESS_REQUEST, how-to subtype은 NO_TOOL target을 유지한다.
- 마지막 user turn만 읽어서는 정답을 확정하기 어려운 사례를 포함한다.
- 한 batch에서 같은 conversation skeleton을 반복하지 않는다.
- 단어 몇 개만 바꾼 sibling paraphrase를 만들지 않는다.
- Write target은 `신청해줘`, `요청 등록해줘` 등 명시적인 실행 요구를 사용한다.
- NO_TOOL target은 `방법`, `절차`, `조건`, `과정` 등을 묻는 정보 요청이어야 하며 실제 실행 요청을 섞지 않는다.
- assistant history도 자연스러운 한국어와 띄어쓰기를 유지한다.

피해야 할 패턴:
- 모든 예시가 PROD_DB → 그 권한 구조

참고 seed:
{"id": "S033", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S034", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S035", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S036", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S037", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S038", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S039", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S040", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S041", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S042", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S046", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S048", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S049", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S051", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S052", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S053", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S060", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}
{"id": "S061", "intent": "NO_TOOL", "target": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
scenario spec마다 같은 순서로 conversation 하나를 생성하라.

IMMUTABLE TARGET CONSTRAINTS
source_seed_id = S033
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S034
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S035
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S036
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S037
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S038
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S039
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S040
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S041
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S042
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S046
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S048
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S049
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S051
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S052
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S053
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S060
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

source_seed_id = S061
required_decision = NO_TOOL
required_tool_name = None
required_type = None
required_resource = None
The generated conversation must preserve every required value above.

GROUP-SPECIFIC SOURCE INSTRUCTIONS
개별 generation job은 augmentation plan에서 지정된 `multi_turn_subtype` 하나만 생성한다.
- resource 개수, 마지막 user turn 행동, target은 job에 전달된 subtype 요구사항을 따른다.
- 생성 개수는 runtime `--count`를 유일한 기준으로 사용한다.
- pipeline이 먼저 정한 structured scenario spec을 conversation으로 자연스럽게 표현만 한다.
- source seed conversation은 생성 예문으로 사용하지 않는다. source seed는 eligible label과 target provenance에만 사용한다.
- spec의 `scenario_resources` 순서와 `required_resource`를 바꾸지 않는다.
- 마지막 reference user turn에서는 DEV_DB, PROD_DB, VPN 이름을 직접 반복하지 않는다.
- LLM은 canonical `conversation` array만 생성한다. plain-text 대화 문자열이나 difficulty/label/metadata는 생성하지 않는다.

추가 원칙:
- 마지막 user turn만 읽어서는 정답을 확정하기 어려운 사례를 충분히 포함한다.
- Write subtype은 ACCESS_REQUEST, how-to subtype은 NO_TOOL target을 유지한다.
- `PROD_DB → 그 권한` 구조만 반복하지 않는다.
- 한 batch에서 같은 conversation skeleton을 반복하지 않고, 단어 몇 개만 바꾼 sibling paraphrase를 만들지 않는다.
- Write target은 `신청해줘`, `요청 등록해줘` 등 명시적인 실행 요구를 사용한다.
- NO_TOOL target은 `방법`, `절차`, `조건`, `과정` 등을 묻는 정보 요청으로 끝내고 실제 실행 요청을 섞지 않는다.
- assistant history도 자연스러운 한국어와 띄어쓰기를 유지한다.

MULTI-TURN SUBTYPE REQUIREMENT
multi_turn_subtype = multi_resource_howto
resource-count requirement: supported resource 2개 이상
final-action requirement: 두 resource는 서로 다른 turn에서 순차적으로 제시하고, 마지막 user turn은 지시어로 가장 최근 resource의 방법 또는 절차를 묻는 정보 요청이며 실행 요청을 포함하지 않음
target requirement: NO_TOOL
이 generation job에서는 지정된 subtype 구조만 사용한다.

STRUCTURED SCENARIO SPECS
아래 spec은 pipeline이 미리 결정했다. source conversation을 재구성하거나 paraphrase하지 말고, 각 spec을 같은 순서로 자연스러운 conversation 하나로만 표현한다. scenario_resources의 순서를 바꾸지 않는다. 마지막 reference turn에는 DEV_DB, PROD_DB, VPN 이름을 직접 쓰지 않는다.
{"scenario_index": 1, "source_seed_id": "S033", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB", "PROD_DB"], "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 2, "source_seed_id": "S034", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB", "VPN"], "required_sequence": "PROD_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 3, "source_seed_id": "S035", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN", "DEV_DB"], "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 4, "source_seed_id": "S036", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB", "PROD_DB"], "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 5, "source_seed_id": "S037", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB", "VPN"], "required_sequence": "PROD_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 6, "source_seed_id": "S038", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN", "DEV_DB"], "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 7, "source_seed_id": "S039", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB", "PROD_DB"], "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 8, "source_seed_id": "S040", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB", "VPN"], "required_sequence": "PROD_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 9, "source_seed_id": "S041", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN", "DEV_DB"], "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 10, "source_seed_id": "S042", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB", "PROD_DB"], "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 11, "source_seed_id": "S046", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB", "VPN"], "required_sequence": "PROD_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 12, "source_seed_id": "S048", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN", "DEV_DB"], "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 13, "source_seed_id": "S049", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB", "PROD_DB"], "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 14, "source_seed_id": "S051", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB", "VPN"], "required_sequence": "PROD_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 15, "source_seed_id": "S052", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN", "DEV_DB"], "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 16, "source_seed_id": "S053", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB", "PROD_DB"], "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 17, "source_seed_id": "S060", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB", "VPN"], "required_sequence": "PROD_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 18, "source_seed_id": "S061", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN", "DEV_DB"], "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 19, "source_seed_id": "S033", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB", "PROD_DB"], "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 20, "source_seed_id": "S034", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB", "VPN"], "required_sequence": "PROD_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 21, "source_seed_id": "S035", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN", "DEV_DB"], "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 22, "source_seed_id": "S036", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB", "PROD_DB"], "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 23, "source_seed_id": "S037", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["PROD_DB", "VPN"], "required_sequence": "PROD_DB user turn -> assistant -> VPN user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 24, "source_seed_id": "S038", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["VPN", "DEV_DB"], "required_sequence": "VPN user turn -> assistant -> DEV_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
{"scenario_index": 25, "source_seed_id": "S039", "generation_subtype": "multi_resource_howto", "target_contract": {"decision": "NO_TOOL", "tool_name": null, "arguments": {}}, "scenario_resources": ["DEV_DB", "PROD_DB"], "required_sequence": "DEV_DB user turn -> assistant -> PROD_DB user turn -> assistant -> resource name 없는 지시어 기반 방법/절차/조건 user question"}
```

</details>

## Errors

- None
