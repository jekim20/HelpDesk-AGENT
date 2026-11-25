# 격리 Guard OFF/ON 평가

이 실험은 운영 코드의 Guard 설정을 변경하지 않는다. JUnit 평가기가 실제 `PromptInjectionGuard`와 `UserInputSafetyAdvisor` 객체에 같은 corpus 입력을 전달하고, OFF는 두 Guard를 평가 경로에서만 우회하며 ON은 서비스 순서(Prompt Injection → Privacy)로 적용한다.

## 재현

저장소 루트에서 실행한다. 첫 실행은 아래 날짜 run 디렉터리가 없어야 하며, 기존 결과를 덮어쓰지 않도록 매번 새 `SECURITY_GUARD_EVAL_OUTPUT` 경로를 지정한다.

```bash
SECURITY_GUARD_EVAL_OUTPUT="$PWD/evaluation/security-guard-off-on/runs/<new-run-id>" \
  ./gradlew --no-daemon test --rerun-tasks \
  --tests '*SecurityGuardComparativeEvaluationTest' \
  --tests '*PromptInjectionGuardTest' \
  --tests '*UserInputSafetyAdvisorTest'
```

원본 입력은 `src/test/resources/security/prompt_injection_cases.json`과 `src/test/resources/quantitative/privacy-*.json`에서 읽는다. 결과에는 개인정보나 입력 원문을 복사하지 않고 dataset/ID와 판정만 저장한다.

## 지표 정의와 제한

- `TP/FP/FN/TN`, 탐지율 `TP/(TP+FN)`, 정상 오탐률 `FP/(FP+TN)`은 dataset의 `expected` 라벨과 실제 Guard 판정을 비교한다.
- Prompt Injection 18건은 모두 공격 차단 기대 입력이므로 이 데이터만으로 정상 오탐률은 계산할 수 없다.
- 테스트는 LLM을 호출하지 않는다. actual model ASR은 **측정되지 않음**이다. mock effect는 공격 라벨 입력이 가상의 취약 downstream sink까지 도달하면 성공으로 표기하는 강제 테스트 값이며 ASR로 해석하면 안 된다.
- 모델이 스스로 공격을 거절하는 경우는 테스트에 없으므로 Guard 탐지/사전 차단 성과에 포함하지 않는다.
- 오탐률은 corpus 38개 정상 입력에서의 결과이며 운영 사용자 요청 분포를 대표하지 않는다.

## J08 후속 개선

2026-11-25에 `privacy-independent-v2/J08`의 상태 문의 오탐을 같은 기존 corpus와 새 독립 합성 corpus로 재검증했다. 상세 전후 수치, 소스·데이터 hash, 실행 로그 및 한계는 [후속 결과](runs/20261125-privacy-fix/report.md)를 본다.
