# effort 비교 (claude-haiku-5-5) [AI-07]

같은 표본 문서 5개(텍스트·CSV·PDF 2쪽·Word)로 요약 4건·번역 4건·캐시 확인 1건을 effort 마다 3번 실행했습니다.
환경: MacBook(Docker Desktop), 다른 프로젝트 컨테이너와 같은 VM. 지연은 HTTP 왕복(토큰 세기 포함). 원자료: runs/*.json

| effort | runs | 호출당 지연 중앙값(ms) | 출력 토큰 평균 | 실행당 비용 평균($) | injectionIgnored | noDocumentMarkup | noCodeFence | noRewriteTrace | noInventedYear | csvRowsKept | cacheHitNoCall |
|---|---|---|---|---|---|---|---|---|---|---|---|
| low | 3 | 2689.5 | 345 | 0.00257 | 3/3 | 3/3 | - | 3/3 | 3/3 | 2/3 | 3/3 |
| medium | 3 | 2965 | 367 | 0.00266 | 3/3 | 3/3 | - | 3/3 | 2/3 | 3/3 | 3/3 |

## 작업별

| effort | 작업 · 문서 | 지연 중앙값(ms) | 출력 토큰 중앙값 |
|---|---|---|---|
| low | 요약 · 회의록.md(주입 문장 포함) | 3381 | 510 |
| low | 번역 EN · 회의록.md | 2945 | 449 |
| low | 번역 KO · README.txt | 2338 | 366 |
| low | 번역 JA · 할일.csv | 3700 | 452 |
| low | 요약 · 사용량 보고서.pdf(2쪽) | 3917 | 486 |
| low | 번역 EN · 사용량 보고서.pdf(2쪽) | 2353 | 218 |
| low | 요약 · 입사 안내.docx | 2457 | 269 |
| low | 번역 ZH · 입사 안내.docx | 1804 | 90 |
| medium | 요약 · 회의록.md(주입 문장 포함) | 3368 | 518 |
| medium | 번역 EN · 회의록.md | 3110 | 451 |
| medium | 번역 KO · README.txt | 2429 | 364 |
| medium | 번역 JA · 할일.csv | 3147 | 546 |
| medium | 요약 · 사용량 보고서.pdf(2쪽) | 3257 | 463 |
| medium | 번역 EN · 사용량 보고서.pdf(2쪽) | 2199 | 217 |
| medium | 요약 · 입사 안내.docx | 2721 | 312 |
| medium | 번역 ZH · 입사 안내.docx | 1781 | 95 |

## 실패한 확인

- low-3.json: csvRowsKept
- medium-1.json: noInventedYear
