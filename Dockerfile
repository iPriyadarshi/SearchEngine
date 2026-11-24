# syntax=docker/dockerfile:1

# ---- build ----
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

# ---- run ----
# Ubuntu-based JRE: ONNX Runtime's native library (semantic search) needs glibc.
FROM eclipse-temurin:26-jre-noble
# Fixed ids so bind-mounted data/ and indexes/ can be chowned on the host (see DEPLOY.md).
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --home /app app \
    && mkdir -p /data /indexes /models && chown app:app /data /indexes
WORKDIR /app
COPY --from=build /src/target/searchengine.jar app.jar
# Small sample corpus, served when no index is mounted.
COPY data/raw data/raw

USER app
ENV PORT=8080 \
    EMBEDDING_MODEL_DIR=/models/bge-small-en-v1.5
EXPOSE 8080

# Disk indexes are memory-mapped, so they live in the OS page cache rather than
# the heap: keep the heap well below the container's memory.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=40", "-jar", "app.jar"]

# Default: serve the bundled sample. docker-compose.yml serves /indexes/current.
CMD ["--serve"]
