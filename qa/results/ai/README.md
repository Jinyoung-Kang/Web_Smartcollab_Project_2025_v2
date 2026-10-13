# AI 요약·번역 실측 기록 (Claude Haiku 5.5)

실제 Claude API 를 부른 결과입니다(과금). 임시 스택 `sc-ai`(8090)에서 표본 문서 5개(연도 없는 회의록·영문 README·CSV·2쪽 PDF·Word)로 쟀습니다.
환경: MacBook, Docker Desktop(다른 프로젝트 컨테이너와 같은 VM) — 지연은 같은 실행 안에서만 비교하세요.

## 최종 설정 (요약 effort low · 번역 effort medium, 번역은 notes → translation 구조화 출력·파일 이름 없이)

[runs-final/comparison.md](runs-final/comparison.md) — 3번 실행, 모든 확인 3/3 통과.

| 항목 | 값 |
|---|---|
| 호출당 지연(HTTP 왕복, 토큰 세기 포함) 중앙값 | 3,102ms |
| 실행당 비용(요약 4 · 번역 4) 평균 | $0.00311 (호출당 약 $0.0004) |
| 같은 버전 다시 요약(캐시) | 12~22ms, AI 호출 없음 |

## 찾은 결함과 근거 (시간 순)

| ID | 무엇 | 근거 | 결과 |
|---|---|---|---|
| AI-06 | 번역이 API 내부 문서 표기(`<document>…<document_content>`)째로 옴 | [live-measurement-before-AI-06.md](live-measurement-before-AI-06.md) | 프롬프트 + 감싼 표기를 벗김 |
| AI-07 | 번역 결과에 생각·정정이 섞임("Wait, I must translate …") | [live-measurement-before-AI-07.md](live-measurement-before-AI-07.md), [runs-1](runs-1/comparison.md)~[3](runs-3/comparison.md) | 아래 표 |
| AI-08 | 요약이 문서에 없는 연도를 적음("2024년 9월 24일") | runs-1~3, dates-*.json | 원문에 없는 연도를 뗌 |
| AI-09 | 번역 전체가 코드 울타리(```` ``` ````)에 싸임 | [live-measurement-before-AI-09.md](live-measurement-before-AI-09.md) | 프롬프트 + 울타리를 벗김 |
| AI-11 | 한국어 요약에 일본어 조사가 섞임("증가분の 대부분은") — 개인·팀 드라이브 기능 점검에서 발견 | [summary-script-before-AI-11.txt](summary-script-before-AI-11.txt) 5/10(프롬프트 4/10, effort medium 2/10) | 한글 바로 뒤의 "の"를 "의"로 → [after](summary-script-after-AI-11.txt) 0/10 |
| AI-10 | 번역 첫 줄에 옮긴 파일 이름("週次会議メモ.txt")이 붙음 — 화면 확인에서 발견 | [title-before-AI-10.txt](title-before-AI-10.txt) 1/10 | 번역 요청에 파일 이름을 보내지 않음 → [title-after-AI-10.json](title-after-AI-10.json) 0/10 |
| AI-12 | 거의 한국어인 PDF 의 한국어 번역이 문서 대신 용어표 `{"e-Friend Expert": "e-Friend Expert"}` 하나 — 운영 사용자 신고 | [AI-12/before.json](AI-12/before.json) 2/2 실패(JSON 한 줄 · 출력 한도까지 반복 413) | 프롬프트 + 불완전 판정 → [prompt-guard](AI-12/prompt-guard.json) 3번 중 1번 빈 번역(502) → + 한 번 다시 요청 → [retry-final](AI-12/retry-final.json) 6/6 |
| AI-13 | AI-12 를 고친 뒤 같은 PDF 의 영어 번역이 한국어 원문을 거의 그대로 옮김 — 운영 화면 신고 | [AI-13/before-pdf.json](AI-13/before-pdf.json) 3번 중 2번 원문 남음(한글이 글자의 21·51%) | PDF 는 서버가 뽑은 글자로 번역(ADR-0016) + 결과 글자 체계 확인·다시 요청 → [after](AI-13/after.json) 9/9 |

### AI-07 — 한국어 → 일본어·중국어 번역 (CSV·Word 각 10번, `qa/scripts/ai-translate-repeat.mjs`)

| 설정 | 문제 | 지연 중앙값 |
|---|---:|---:|
| 번역 effort medium | 3/20 | 2,741ms |
| 번역 effort high | 2/20 | 3,219ms |
| medium + "파일 이름은 확장자 있는 것만 그대로" 규칙 | 5/20 | 2,963ms |
| **medium + notes → translation 구조화 출력** | **0/20** | 2,659ms |
| 위 + 파일 이름 없이(AI-10, 최종) | 0/20 | 2,579ms |

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

### AI-12 — 거의 한국어인 PDF(8쪽, 한국어 + 영어 용어·C++ 코드) 번역 (`qa/scripts/ai-translate-pdf.mjs`)

| 단계 | 한국어 | 영어·일본어 | 비고 |
|---|---|---|---|
| 수정 전 | 0/2 (JSON 한 줄 90자 · 413 은 65,156 토큰 과금, 135초) | — (신고자 확인: 영어 정상) | |
| 프롬프트 + 불완전 판정 | 2/3 (2,758 · 3,471자) | — | 실패 1번은 notes 만 쓰고 translation 이 빔 → 502 |
| **+ 짧게 끝난 빈·불완전 답 한 번 다시 요청(최종)** | **4/4** (2,639~2,872자, 출력 2,593~2,788 토큰, 8.3~9.2초) | 영어 1/1 (5,590자) · 일본어 1/1 (2,688자) | 다시 요청은 일어나지 않음 |

같은 PDF 의 입력은 약 15,370 토큰, 번역 1번 약 $0.003. 최종 한국어 4번 중 1번은 결과가 2쪽부터 시작해 1쪽 표지 머리글(약 100자)이 빠진 것으로 보입니다 —
본문·끝은 다른 실행과 같았습니다(결과 글 전체를 남기지 않아 확정하지 못함). 원문 PDF·결과 글은 사용자 문서라 공개 저장소에 남기지 않고 길이·한글 비율·결과 유형만 적었습니다.
실측 원자료는 처음에 결과 앞뒤 글까지 적는 임시 스크립트로 모은 뒤 글을 지웠고, 같은 측정을 다시 하는 스크립트(결과 글을 남기지 않음)를 `qa/scripts` 에 두었습니다.

### AI-13 — 같은 PDF 의 영어 번역에 원문이 남음 (`qa/scripts/ai-translate-pdf.mjs`, 프롬프트 실험은 API 직접)

| 단계 | 영어 | 한국어 | 일본어 · 중국어 | 입력 토큰 |
|---|---|---|---|---:|
| 수정 전(PDF 원본) | 1/3 (원문 남음 2번: 한글 51%·21%) | — | — | 15,349 |
| 시험: 뽑은 글자를 .txt 로(프롬프트 그대로) | 2/3 (원문·번역 병기 1번: 한글 20%, 출력 7,355 토큰) | 2/2 | — | 2,962 |
| 시험: API 직접, 프롬프트 v0 · v1(병기 금지) | 6/6 · 6/6 | — | — | 2,962 · 3,047 |
| **수정 뒤(PDFBox 로 뽑은 글자 + 글자 체계 확인·다시 요청 + v1)** | **4/4** (한글 0%) | **3/3** | **1/1 · 1/1** | 약 3,030 |

수정 뒤 다시 요청은 일어나지 않았습니다. 원문을 남긴 답은 한글이 글자의 20~51%, 제대로 된 영어 번역은 0~0.3% 라 판정 기준을 10%(20자 이상)로 두었습니다.
원문과 번역을 나란히 적은 답은 오히려 길어(7,355 토큰, 출력 한도의 절반 넘게) AI-12 의 "출력이 한도의 1/4 이하일 때만 다시 요청" 조건을 뺐습니다.
번역 1번 비용은 약 $0.003 에서 약 $0.002 로 줄었습니다(입력이 약 1/5, 출력이 비용 대부분). 이번 작업의 실제 호출은 앱 17번 + API 직접 18번입니다(약 $0.08).

## 파일

- `runs-*/`: `qa/scripts/ai-live.mjs` 를 effort 설정마다 여러 번 돌린 원자료와 `comparison.md`(`qa/scripts/ai-effort-compare.mjs`)
- `live-measurement-before-AI-0n.*`: 각 결함을 찾은 실행
- `dates-*.json`, `translate-*.json`: 반복 확인 원자료(결과 글 포함)
- `AI-12/`: 거의 한국어인 PDF 번역 — 수정 전·중간·최종(결과 글 없음)
- `AI-13/`: 같은 PDF 의 영어 번역 — 수정 전·텍스트 시험·프롬프트 실험·수정 뒤(결과 글 없음)
- `failing-tests-before-fix.txt`, `frontend-failing-before-fix.txt`: 구현 전 시험 결과
