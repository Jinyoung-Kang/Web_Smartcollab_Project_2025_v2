#!/usr/bin/env bash
# [H-1] 문서 추출·추출 요약 측정. 먼저 cd backend && ./gradlew compileJava 로 클래스를 만든 뒤 저장소 루트에서 실행합니다.
# 사용: qa/scripts/probes/run-document-memory.sh > qa/results/review-2026-10-12/<이름>.txt
set -euo pipefail
OUT=$(mktemp -d)
# ApiException·ErrorCode 가 쓰는 Spring 클래스(HttpStatus 등)만 Gradle 캐시에서 찾아 붙입니다
# 버전은 백엔드가 실제로 쓰는 것(Gradle 이 고른 것)으로 고정합니다 — 캐시에 다른 프로젝트의 버전이 함께 있음
SPRING=$(cd backend && ./gradlew -q dependencyInsight --dependency spring-web --configuration runtimeClasspath | head -1 | sed -E 's/.*spring-web:([^ ]+).*/\1/')
jar() { find ~/.gradle/caches/modules-2 -name "$1-$SPRING.jar" | head -1; }
CP="backend/build/classes/java/main:$(jar spring-web):$(jar spring-core):$(jar spring-context)"
javac -d "$OUT" -cp "$CP" qa/scripts/probes/DocumentMemoryProbe.java
for mode in docx-bomb text-2mb docx-front-bomb; do
  for xmx in 64m 128m 256m 512m 1536m; do
    java -Xmx$xmx -cp "$OUT:$CP" com.smartcollab.ai.DocumentMemoryProbe "$mode"
  done
done
rm -rf "$OUT"
