# RAG Retrieval Quality Evaluation

## 1. Purpose

이 평가는 HelpDesk Agent가 업무 정책 문서를 안정적으로 검색하는지 확인하기 위해 수행했다. 실제 OpenAI API 없이 실행되는 deterministic CI regression과 OpenAI embedding을 사용하는 opt-in live evaluation을 분리하고, dev set에서 관찰한 threshold 후보가 unseen holdout에서도 일반화되는지 검증했다. 마지막으로 query별 raw similarity score를 비교해 단순 threshold 조정의 한계를 분석했다.

## 2. Evaluation Layers

### 2.1 Deterministic Regression

Corpus 기반 deterministic lexical embedding과 Golden Query를 사용하므로 외부 API나 secret 없이 CI에서 재현할 수 있다.

| Queries | Hit@1 | Hit@3 | Recall@3 | MRR |
|---:|---:|---:|---:|---:|
| 16 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |

이 결과는 검색 metric 계산과 corpus retrieval의 회귀를 검증하기 위한 것이며 실제 OpenAI embedding 품질이나 일반적인 RAG 정확도를 의미하지 않는다.

### 2.2 Live OpenAI Baseline

Production과 동일한 similarity threshold `0.35`에서 얻은 live baseline은 다음과 같다.

| Queries | Hit@1 | Hit@3 | Recall@3 | MRR | Empty | Rank1 Miss |
|---:|---:|---:|---:|---:|---:|---:|
| 16 | 0.6875 | 0.8125 | 0.8125 | 0.7500 | 3 | 5 |

별도로 구성한 negative query 5건에서는 false positive가 없었으며 FPR은 `0.0000`이었다.

## 3. Threshold Diagnostic

동일한 dev query와 `topK=5`를 유지하고 threshold만 변경해 retrieval recall과 unrelated-query false positive의 trade-off를 관찰했다.

| Threshold | Hit@1 | Hit@3 | Recall@3 | MRR | Empty | Rank1 Miss | Negative FP |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.35 | 0.6875 | 0.8125 | 0.8125 | 0.7500 | 3 | 5 | 0/5 |
| 0.30 | 0.8125 | 0.9375 | 0.9375 | 0.8750 | 1 | 3 | 0/5 |
| 0.25 | 0.8125 | 1.0000 | 1.0000 | 0.9063 | 0 | 3 | 1/5 |
| 0.20 | 0.8125 | 1.0000 | 1.0000 | 0.9063 | 0 | 3 | 1/5 |
| 0.15 | 0.8125 | 1.0000 | 1.0000 | 0.9063 | 0 | 3 | 1/5 |

Dev set에서는 `0.30`이 `0.35`보다 retrieval metric이 높았고, `0.25` 이하에서는 unrelated query false positive가 발생했다. 이에 `0.30`을 검증 후보로만 선택하고 production에는 반영하지 않은 채 별도의 unseen holdout으로 평가했다. 이 결과만으로 `0.30`을 최적 threshold라고 판단하지 않는다.

## 4. Unseen Holdout Evaluation

Dev set 결과를 확인한 뒤 고정한 threshold `0.30`으로 unseen positive 12건과 negative 8건을 평가했다.

| Positive Queries | Hit@1 | Hit@3 | Recall@3 | MRR | Empty | Rank1 Miss |
|---:|---:|---:|---:|---:|---:|---:|
| 12 | 0.5833 | 0.9167 | 0.9167 | 0.7361 | 1 | 5 |

| Negative Queries | False Positives | FPR |
|---:|---:|---:|
| 8 | 2 | 0.2500 |

관련 문서는 대부분 Top3 안에서 검색됐지만 Top1 ranking 성능은 낮았고, unseen negative query에서 false positive가 발생했다. 따라서 dev set에서 선택한 `0.30` 후보가 holdout에서 충분히 일반화됐다고 볼 수 없어 production 반영을 보류했다. 이 결과의 실패 사례는 수정하거나 제외하지 않았다.

## 5. Score Diagnostic

문제가 관찰된 positive query 8건과 unseen negative query 2건을 test-only threshold `0.0`으로 검색해 `Document.getScore()`의 raw similarity score를 비교했다.

| Relevant Score | Min | Max | Mean | Median |
|---|---:|---:|---:|---:|
| Positive 8 queries | 0.2542 | 0.4107 | 0.3425 | 0.3669 |

| Relevant Rank | Rank1 | Rank2 | Rank3 | Rank4+ | Not Found |
|---|---:|---:|---:|---:|---:|
| Positive 8 queries | 0 | 6 | 2 | 0 | 0 |

| Negative Top1 Score | Min | Max | Mean |
|---|---:|---:|---:|
| Negative 2 queries | 0.3083 | 0.3135 | 0.3109 |

- Lowest relevant score: `0.2542`
- Highest negative Top1 score: `0.3135`
- Score overlap: `YES`
- Relevant score below `0.35` / `0.30` / `0.25`: `3` / `2` / `0`
- Negative Top1 score at or above `0.35` / `0.30` / `0.25`: `0` / `2` / `2`

현재 corpus와 평가 범위에서는 정상 query의 relevant-document score와 unrelated query의 Top1 score가 겹쳤다. 즉, 단일 similarity threshold 조정만으로 검색 재현율과 범위 밖 질문의 오탐을 동시에 해결하기 어렵다는 것을 확인했다. 이는 현재 embedding, corpus, query set에서의 관찰이며 threshold로 문제를 일반적으로 해결할 수 없다는 뜻은 아니다.

## 6. Decision

- `0.30` 후보는 production에 반영하지 않는다.
- Production similarity threshold는 기존 `0.35`를 유지한다.
- `0.35`를 최적값이라고 판단한 것이 아니라 holdout에서 후보의 일반화를 확인하지 못했기 때문에 기존 값을 유지하는 보수적 선택이다.
- Threshold-only tuning은 여기서 종료한다.

## 7. Remaining Limitations

- 정책 문서 4개로 구성된 소규모 corpus와 제한된 positive/negative query를 사용했다.
- 일부 query는 여러 의도가 섞인 multi-intent 성격을 가진다.
- Source-level retrieval 평가이며 answer faithfulness 평가는 포함하지 않는다.
- Deterministic metric과 실제 OpenAI embedding metric은 같은 의미가 아니다.
- Ranking과 knowledge-scope 판단을 별도로 개선할 여지가 있다.

## 8. Future Work

1. Ranking 개선
2. Knowledge scope 및 out-of-domain detection
3. Multi-intent query 처리
4. Single-source와 multi-source 평가 분리
5. 더 큰 corpus와 별도 holdout을 이용한 재검증
