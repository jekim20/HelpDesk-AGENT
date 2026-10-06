# DevDesk QLoRA Augmentation Generation Prompts v0.1

> `augmentation_plan.yaml`의 각 generation group을 LLM에 전달할 때 사용하는 프롬프트 설계 가이드다.
> 목표는 같은 seed의 단순 말바꾸기만 양산하는 것이 아니라, 정답 의미를 유지하면서 구조·어순·실무 화법·오도성 단서를 다양화하는 것이다.

## 1. 공통 System Instruction

```text
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
```

## 2. 공통 User Prompt Template

```text
generation_group: {GROUP_ID} / {GROUP_NAME}
생성 개수: {N}

목표:
{GOAL}

반드시 지킬 규칙:
{GENERATION_RULES}

피해야 할 패턴:
{ANTI_PATTERNS}

참고 seed:
{SEED_EXAMPLES}

각 결과는 의미가 중복되지 않도록 구조적으로 다양하게 생성하라.
같은 seed에서 파생된 결과에는 동일한 source_seed_id를 기록하라.
generation_group_id는 같은 생성 batch의 sibling을 추적할 수 있게 기록하라.
```

## 3. 그룹별 추가 지시

### G01 — ACCESS_STATUS general
- 현재 권한 상태 조회만 생성한다.
- `권한 있어?`, `지금 들어갈 수 있어?`, `사용 가능 상태야?` 등 화법을 분산한다.
- 신청/정책/장애 접수로 의미가 바뀌면 안 된다.

### G02 — ACCESS_STATUS distractor / contrastive
- 장애·업무·배포 맥락은 distractor일 뿐이다.
- 실제 장애 접수 행동은 들어가면 안 된다.
- `장애` 한 단어에만 편중하지 않는다.

### G03 — ACCESS_REQUEST general
- 명시적 Write cue가 반드시 존재해야 한다.
- `신청하고 싶어`처럼 희망만 표현하는 데이터는 생성하지 않는다.

### G04 — ACCESS_REQUEST info→action override
- 정보 질문 표현을 등장시킨 뒤 명시적으로 부정하고 실제 실행을 요구한다.
- 예: `신청 방법 말고 실제 요청을 등록해줘.`

### G05 — ACCESS_REQUEST reason context
- 일반 사유 의미군을 균형 있게 쓴다.
- 신규 프로젝트 / 협업 / 배포 / 데이터 확인 / 담당 변경 / 업무 배정 등.

### G06 — ACCESS_REQUEST intent-reason conflict
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

### G07 — INCIDENT general
- 실제 failure 현상 + 명시적 장애 접수 행동이 모두 있어야 한다.
- 단순 정책 질문은 생성하지 않는다.

### G08 — INCIDENT info→action override
- `절차 말고`, `설명은 됐고`, `가이드 말고 지금` 등의 구조 뒤에 실제 장애 접수 행동을 둔다.

### G09 — INCIDENT contrastive / distractor
- 권한/승인/로그인 같은 다른 intent 단서를 넣되 실제 행동은 INCIDENT다.
- `권한은 있는데 접속 실패` 한 구조만 반복하지 않는다.

### G10 — ACCOUNT_SUPPORT general
- 계정/로그인 문제 + 명시적 계정 지원 Ticket 실행 요청.
- target은 항상 `type=ACCOUNT_SUPPORT`, `resource=ACCOUNT_SUPPORT`.
- `ACCOUNT`, `LOGIN` 등의 임의 resource를 만들지 않는다.
- `티켓 하나 올려줘`, `요청 처리해줘`처럼 ticket type이 불명확한 generic 표현만으로 끝내지 않는다.
- `계정 지원`, `계정 지원 티켓`, `계정 관련 지원 요청`처럼 ACCOUNT_SUPPORT action임을 문맥상 충분히 식별할 수 있게 생성한다.
- 띄어쓰기와 기본적인 한국어 문장 자연스러움을 유지한다.

### G11 — ACCOUNT_SUPPORT contrastive / conflict
- `실패`, `오류` 등 INCIDENT shortcut을 포함할 수 있다.
- 하지만 사용자의 명시적 행동은 계정 지원 Ticket이어야 한다.
- VPN/DB 자체의 장애 접수를 ACCOUNT_SUPPORT로 바꾸면 오라벨이다.

### G12 — NO_TOOL policy / how-to
- 권한/장애/계정/Ticket 같은 단어를 일부러 섞어 hard negative를 만든다.
- 실제 실행 cue는 금지한다.

### G13 — NO_TOOL unsupported
- 지원 resource와 이름이 비슷한 미지원 resource를 일부 포함한다.
- 지원하지 않는 action도 포함하되 기존 Tool로 임의 치환하지 않는다.
- ACCOUNT_SUPPORT는 실제 지원 type이므로 unsupported로 만들지 않는다.

### G14 — NO_TOOL ambiguous / implicit write
- 실행 의도가 불분명하거나 Write 희망만 표현한다.
- `신청하고 싶어`, `지원받고 싶어`, `DB 좀 처리해줘` 같은 유형.
- 실제 서비스에서는 clarification 후보지만 이번 target은 NO_TOOL이다.

### G15 — Multi-turn
개별 generation job은 augmentation plan에서 지정된 `multi_turn_subtype` 하나만 생성한다.
- resource 개수, 마지막 user turn 행동, target은 job에 전달된 subtype 요구사항을 따른다.
- 생성 개수는 runtime `--count`를 유일한 기준으로 사용한다.
- pipeline이 먼저 정한 structured scenario spec을 conversation으로 자연스럽게 표현만 한다.
- source seed conversation은 생성 예문으로 사용하지 않는다. source seed는 eligible label과 target provenance에만 사용한다.
- spec의 `scenario_resources` 순서와 `required_resource`를 바꾸지 않는다.
- 마지막 reference user turn에서는 DEV_DB, PROD_DB, VPN 이름을 직접 반복하지 않는다.
- LLM은 canonical `conversation` array만 생성한다. plain-text 대화 문자열이나 difficulty/label/metadata는 생성하지 않는다.
- Assistant turn policy는 `neutral contextual acknowledgement only`다. Assistant는 문맥을 확인하거나 정보를 안내할 수 있지만 Tool을 실행했다고 말하거나 실행을 약속하면 안 된다.

추가 원칙:
- 마지막 user turn만 읽어서는 정답을 확정하기 어려운 사례를 충분히 포함한다.
- Write subtype은 ACCESS_REQUEST, how-to subtype은 NO_TOOL target을 유지한다.
- `PROD_DB → 그 권한` 구조만 반복하지 않는다.
- 한 batch에서 같은 conversation skeleton을 반복하지 않고, 단어 몇 개만 바꾼 sibling paraphrase를 만들지 않는다.
- Write target은 `신청해줘`, `요청 등록해줘` 등 명시적인 실행 요구를 사용한다.
- Write subtype의 explicit execution/write request는 반드시 마지막 user turn에만 둔다.
- Write subtype의 마지막 user turn 이전에는 신청해줘/요청해줘/등록해줘/접수해줘/티켓 만들어줘/요청 올려줘 같은 실행 요구를 넣지 않는다.
- `single_resource_write`의 final 이전 user turn은 status/context only다.
- `multi_resource_write`는 distractor resource context → required resource context → reference 기반 final write 순서를 지킨다.
- NO_TOOL target은 `방법`, `절차`, `조건`, `과정` 등을 묻는 정보 요청으로 끝내고 실제 실행 요청을 섞지 않는다.
- How-to subtype은 conversation 전체에서 실제 실행 요청을 만들지 않는다.
- 모든 subtype에서 assistant가 `접수할게요`, `접수했습니다`, `등록해드릴게요`, `등록했습니다`, `신청을 진행하겠습니다`, `요청을 생성하겠습니다`, `티켓을 만들겠습니다`, `처리하겠습니다`처럼 Tool 실행 또는 실행 약속을 표현하면 안 된다.
- `요청할 수 있습니다`, `신청 방법을 안내할 수 있습니다`, `DEV_DB 접근도 필요한 상황이군요` 같은 가능성·정보·문맥 확인은 허용한다.
- assistant history도 자연스러운 한국어와 띄어쓰기를 유지한다.

좋은 구조:

```text
User: PROD_DB 상태가 궁금해.
Assistant: PROD_DB 접근에 관한 문의군요.
User: DEV_DB도 필요한 상황이야.
Assistant: DEV_DB 접근도 필요한 상황이군요.
User: 그 권한 신청해줘.
```

나쁜 구조:

```text
User: DEV_DB도 필요해. 신청해줘.
Assistant: DEV_DB 요청을 접수할게요.
User: 그 요청을 등록해줘.
```

## 4. 생성 후 자동 검증에서 탈락시킬 예

```text
입력: PROD_DB 신청 방법 알려줘.
target: ACCESS_REQUEST
```
→ 정보 질문인데 Tool label이므로 탈락.

```text
입력 유형: failure 표현과 명시적인 계정 지원 실행 요청이 함께 있는 문장
target: INCIDENT
```
→ 명시적 계정 지원 행동과 label이 충돌하므로 탈락.

```json
{
  "decision": "TOOL",
  "tool_name": "createTicket",
  "arguments": {
    "type": "ACCOUNT_SUPPORT",
    "resource": "ACCOUNT"
  }
}
```
→ 현재 DevDesk contract와 불일치. resource는 `ACCOUNT_SUPPORT`여야 한다.

```text
입력: STAGING_DB 권한 확인해줘.
target: getAccessStatus(resource=DEV_DB)
```
→ unsupported resource hallucination이므로 탈락.

## 5. 생성 운영 원칙

- 한 번에 1,000개를 생성하지 않는다.
- group별 20~50개 단위 batch로 생성하고 품질을 확인한 뒤 계속한다.
- 각 batch에 고유 `generation_group_id`를 남긴다.
- 같은 seed의 sibling은 이후 train/validation에서 반드시 같은 split에 둔다.
- held-out 생성에는 이 문서의 train generation 예시를 그대로 재사용하지 않는다.
