# AI 요약·번역 실측 기록 (Claude Haiku 5.5)

실제 Claude API 를 부른 결과입니다(과금). 임시 스택 `sc-ai`(8090)에서 표본 문서 5개(연도 없는 회의록·영문 README·CSV·2쪽 PDF·Word)로 쟀습니다.
환경: MacBook, Docker Desktop(다른 프로젝트 컨테이너와 같은 VM) — 지연은 같은 실행 안에서만 비교하세요.

## 최종 설정 (요약 effort low · 번역 effort medium, 번역은 notes → translation 구조화 출력)

[runs-final/comparison.md](runs-final/comparison.md) — 3번 실행, 모든 확인 3/3 통과.

| 항목 | 값 |
|---|---|
| 호출당 지연(HTTP 왕복, 토큰 세기 포함) 중앙값 | 3,287ms |
| 실행당 비용(요약 4 · 번역 4) 평균 | $0.00302 (호출당 약 $0.0004) |
| 같은 버전 다시 요약(캐시) | 20~24ms, AI 호출 없음 |

## 찾은 결함과 근거 (시간 순)

| ID | 무엇 | 근거 | 결과 |
|---|---|---|---|
| AI-06 | 번역이 API 내부 문서 표기(`<document>…<document_content>`)째로 옴 | [live-measurement-before-AI-06.md](live-measurement-before-AI-06.md) | 프롬프트 + 감싼 표기를 벗김 |
| AI-07 | 번역 결과에 생각·정정이 섞임("Wait, I must translate …") | [live-measurement-before-AI-07.md](live-measurement-before-AI-07.md), [runs-1](runs-1/comparison.md)~[3](runs-3/comparison.md) | 아래 표 |
| AI-08 | 요약이 문서에 없는 연도를 적음("2024년 9월 24일") | runs-1~3, dates-*.json | 원문에 없는 연도를 뗌 |
| AI-09 | 번역 전체가 코드 울타리(```` ``` ````)에 싸임 | [live-measurement-before-AI-09.md](live-measurement-before-AI-09.md) | 프롬프트 + 울타리를 벗김 |

### AI-07 — 한국어 → 일본어·중국어 번역 (CSV·Word 각 10번, `qa/scripts/ai-translate-repeat.mjs`)

| 설정 | 문제 | 지연 중앙값 |
|---|---:|---:|
| 번역 effort medium | 3/20 | 2,741ms |
| 번역 effort high | 2/20 | 3,219ms |
| medium + "파일 이름은 확장자 있는 것만 그대로" 규칙 | 5/20 | 2,963ms |
| **medium + notes → translation 구조화 출력** | **0/20** | 2,659ms |

문제는 모두 Word 문서의 따옴표 안 폴더 이름 "온보딩"에서 — 음역("温保")하다 멈추고 고민을 결과에 적은 뒤 다시 썼습니다.
effort 를 올려도 줄지 않았고, 어려운 낱말을 먼저 정할 자리(notes)를 주자 사라졌습니다(10번 모두 "入职"류로 옮김). runs-1·2 에서는 effort low 가 CSV 일본어 번역 6번 중 3번 같은 문제를 냈습니다.

### AI-08 — 연도 없는 회의록 요약 (10번, `qa/scripts/ai-summary-dates.mjs`)

| 설정 | 연도를 지어냄 |
|---|---:|
| v1 시스템 프롬프트 "날짜는 문서 그대로" | 4/10 |
| v2 같은 규칙을 요청 문장에도 | 5/10 |
| v3 v1 + 생각 끔 | 8/10 |
| **v1 + 원문에 없는 연도를 뗌** | **0/10** |

runs-1~3 에서 요약 effort medium 이 low 보다 자주 연도를 적어(9번 중 5번 대 9번 중 1번) 요약은 low 로 두었습니다.
연도를 떼는 처리는 원문 글자를 아는 텍스트·Word 에만 적용됩니다(PDF 는 원본을 그대로 보내므로 남는 한계).

## 파일

- `runs-*/`: `qa/scripts/ai-live.mjs` 를 effort 설정마다 여러 번 돌린 원자료와 `comparison.md`(`qa/scripts/ai-effort-compare.mjs`)
- `live-measurement-before-AI-0n.*`: 각 결함을 찾은 실행
- `dates-*.json`, `translate-*.json`: 반복 확인 원자료(결과 글 포함)
- `failing-tests-before-fix.txt`, `frontend-failing-before-fix.txt`: 구현 전 시험 결과
