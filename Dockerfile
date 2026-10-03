# syntax=docker/dockerfile:1
# 1) 프론트엔드 빌드 (Vite)
# Node 24(LTS, 보안 지원 2028-04-30까지). 22 는 적극 지원이 2025-10-21 에 끝났습니다(3차 점검).
FROM node:24-bookworm-slim AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# 2) 백엔드 빌드 (Gradle) — 프론트 산출물을 static/ 으로 포함
FROM eclipse-temurin:21-jdk-noble AS api
WORKDIR /app
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle ./gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY backend/src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN ./gradlew --no-daemon -q bootJar -x test

# 3) 실행 이미지 (JRE, 비루트 사용자)
FROM eclipse-temurin:21-jre-noble
RUN groupadd --system app && useradd --system --gid app --uid 10001 app \
    && mkdir -p /data && chown app:app /data
WORKDIR /app
COPY --from=api /app/build/libs/smartcollab.jar app.jar
USER app
EXPOSE 8080
# allowRestrictedHeaders: Azure SDK(JDK HttpClient)가 Content-Length 를 직접 지정하도록 허용 — 없으면 요청마다 경고 로그
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Djdk.httpclient.allowRestrictedHeaders=content-length"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
