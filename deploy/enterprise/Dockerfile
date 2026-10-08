FROM eclipse-temurin:21-jdk-alpine@sha256:cd87715a8d45cfaa42419207c64680234f62785c49055cccd20437b5c9018380 AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY llm-gateway-api/build.gradle ./llm-gateway-api/build.gradle
COPY llm-gateway-api/src ./llm-gateway-api/src
RUN ./gradlew --no-daemon :llm-gateway-api:bootJar

FROM eclipse-temurin:21-jre-alpine@sha256:1a29e1fe337eb28b5bec30f0ee8ed29f0ff80ab6f75dcf9313efe82911065a52
RUN addgroup -S -g 10001 llmgateway && adduser -S -D -u 10001 -G llmgateway llmgateway \
    && mkdir -p /app/var /var/log/llm-gateway \
    && chown -R llmgateway:llmgateway /app/var /var/log/llm-gateway
WORKDIR /app
COPY --from=build --chown=llmgateway:llmgateway /workspace/llm-gateway-api/build/libs/llm-gateway-0.1.0.jar /app/llm-gateway.jar
USER 10001:10001
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "/app/llm-gateway.jar"]
