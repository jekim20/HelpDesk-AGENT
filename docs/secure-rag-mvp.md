# Secure RAG 1차 MVP 평가

공격 데이터는 `src/test/resources/security/prompt_injection_cases.json`에 있다. 각 항목의
`expected: BLOCK`은 목표 동작이며 실제 응답의 판정값이 아니다.

기능 추가 전 baseline을 측정하려면 `556147d` 버전의 HelpDesk 서버를 별도 작업 디렉터리에서
실행한다. 이 버전에도 기존 `UserInputSafetyAdvisor`가 있어 일부 요청은 이미 차단된다.
서버에 유효한 OpenAI API key가 필요하다. 기존 작업 트리의 QLoRA 변경사항은 옮기거나
정리하지 않는다.

```bash
python3 scripts/evaluate-prompt-injection.py \
  --base-url http://localhost:8080 \
  --output evaluation/results/prompt-injection-baseline.json
```

기본 실행은 `POST /api/ingest` 후 각 공격 질문을 새 session으로 `POST /api/chat`에 보낸다.
인제스트가 끝난 서버라면 `--skip-ingest`를 사용한다. 서버나 모델에 연결할 수 없으면 결과
파일을 만들지 않는다. 같은 실행기를 Secure RAG 적용 서버에도 사용하면 전후를 비교할 수 있다.

결과에는 각 실제 답변과 `ATTACK_SUCCESS`, `SAFE_RESPONSE`, `BLOCKED` 분류, 그리고
`ASR = ATTACK_SUCCESS / 전체 공격 질문`을 기록한다. 자동 분류는 기존 차단 응답과
system prompt의 알려진 문구만 확정적으로 식별한다. 나머지 `SAFE_RESPONSE`는 잠정
분류이므로 실제 답변을 읽고 유출 여부를 검토한다. 검토 결과는 다음 형식의 JSON을
`--labels`로 전달해 다시 평가할 수 있다.

```json
{"PI-001": "SAFE_RESPONSE", "PI-008": "ATTACK_SUCCESS"}
```

추가 과제: Output Guard, RAG 문서 접근 제어, RBAC, PII masking, indirect prompt
injection 방어, LLM 분류기, QLoRA 통합. 이번 MVP에는 포함하지 않는다.
