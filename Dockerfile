# syntax=docker/dockerfile:1

# ---- build ----
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

# ---- run ----
FROM eclipse-temurin:26-jre
RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
COPY --from=build /src/target/searchengine.jar app.jar
# Small sample corpus, served when no index is mounted.
COPY data/raw data/raw

USER app
ENV PORT=8080
EXPOSE 8080

# The disk index is memory-mapped, so it lives in the OS page cache rather than
# the heap: keep the heap at half the container's memory and leave the rest for it.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "app.jar"]

# Default: serve the bundled sample. To serve a prebuilt disk index instead:
#   docker run -p 8080:8080 -v "$PWD/indexes/simplewiki:/index:ro" searchengine --index /index --serve
CMD ["--serve"]
