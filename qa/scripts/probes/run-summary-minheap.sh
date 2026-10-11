#!/usr/bin/env bash
# [H-1] 2MB 텍스트 추출 요약이 성공하는 최소 힙. 먼저 cd backend && ./gradlew compileJava, 저장소 루트에서 실행
set -euo pipefail
OUT=$(mktemp -d)
SPRING=$(cd backend && ./gradlew -q dependencyInsight --dependency spring-web --configuration runtimeClasspath | head -1 | sed -E 's/.*spring-web:([^ ]+).*/\1/')
jar() { find ~/.gradle/caches/modules-2 -name "$1-$SPRING.jar" | head -1; }
CP="backend/build/classes/java/main:$(jar spring-web):$(jar spring-core):$(jar spring-context)"
javac -d "$OUT" -cp "$CP" qa/scripts/probes/DocumentMemoryProbe.java
for xmx in 24m 32m 40m 48m 64m 80m 96m 112m; do java -Xmx$xmx -cp "$OUT:$CP" com.smartcollab.ai.DocumentMemoryProbe text-2mb; done
