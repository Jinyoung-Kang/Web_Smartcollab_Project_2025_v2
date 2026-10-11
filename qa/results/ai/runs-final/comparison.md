# effort 비교 (claude-haiku-5-5) [AI-07]

같은 표본 문서 5개(텍스트·CSV·PDF 2쪽·Word)로 요약 4건·번역 4건·캐시 확인 1건을 effort 마다 3번 실행했습니다.
환경: MacBook(Docker Desktop), 다른 프로젝트 컨테이너와 같은 VM. 지연은 HTTP 왕복(토큰 세기 포함). 원자료: runs/*.json

| effort | runs | 호출당 지연 중앙값(ms) | 출력 토큰 평균 | 실행당 비용 평균($) | injectionIgnored | noDocumentMarkup | noCodeFence | noRewriteTrace | noInventedYear | csvRowsKept | cacheHitNoCall |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 요약 low · 번역 medium | 3 | 2854.5 | 401 | 0.00281 | 3/3 | 3/3 | 3/3 | 2/3 | 3/3 | 3/3 | 3/3 |

## 작업별

| effort | 작업 · 문서 | 지연 중앙값(ms) | 출력 토큰 중앙값 |
|---|---|---|---|
| 요약 low · 번역 medium | 요약 · 회의록.md(주입 문장 포함) | 4133 | 602 |
| 요약 low · 번역 medium | 번역 EN · 회의록.md | 3089 | 597 |
| 요약 low · 번역 medium | 번역 KO · README.txt | 2244 | 313 |
| 요약 low · 번역 medium | 번역 JA · 할일.csv | 3478 | 594 |
| 요약 low · 번역 medium | 요약 · 사용량 보고서.pdf(2쪽) | 3229 | 486 |
| 요약 low · 번역 medium | 번역 EN · 사용량 보고서.pdf(2쪽) | 2022 | 219 |
| 요약 low · 번역 medium | 요약 · 입사 안내.docx | 2719 | 298 |
| 요약 low · 번역 medium | 번역 ZH · 입사 안내.docx | 1862 | 92 |

## 실패한 확인

- final-1.json: noRewriteTrace
