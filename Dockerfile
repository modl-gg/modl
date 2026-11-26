# syntax=docker/dockerfile:1

FROM --platform=$BUILDPLATFORM node:26-bookworm AS panel
WORKDIR /workspace
COPY package.json package-lock.json tsconfig.strict.json ./
COPY panel/package.json panel/
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY proto/gen/ts proto/gen/ts
COPY panel panel
ARG MODL_SOURCE_COMMIT=""
ARG MODL_SOURCE_REPOSITORY=""
RUN npm run build

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS server
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic
COPY LICENSE THIRD_PARTY_NOTICES.md ./
COPY licenses licenses
COPY proto proto
COPY libs libs
COPY minecraft minecraft
COPY backend backend
COPY --from=panel /workspace/panel/dist panel/dist
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew \
    && ./gradlew :backend:bootJar -Pmodl.panelPrebuilt=true --no-daemon \
    && mkdir -p /out \
    && cp "$(find backend/build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar')" /out/modl-server.jar

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 1001 modl \
    && useradd --uid 1001 --gid 1001 --no-create-home --home-dir /data --shell /usr/sbin/nologin modl \
    && mkdir -p /data \
    && chown modl:modl /data
WORKDIR /app
COPY LICENSE THIRD_PARTY_NOTICES.md ./
COPY licenses licenses
COPY --from=server /out/modl-server.jar /app/modl-server.jar
ENV MODL_DATA_DIR=/data \
    MODL_PORT=8080 \
    MODL_DEPLOYMENT=docker
VOLUME /data
EXPOSE 8080
USER modl
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
    CMD curl -fsS -o /dev/null "http://127.0.0.1:${MODL_PORT}/actuator/health" || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+UseG1GC", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/modl-server.jar"]
