# One Dockerfile for both services; pick one with --build-arg MODULE=gateway|backend.

# Stage 1: build the jar with the Maven Wrapper (full JDK).
FROM eclipse-temurin:21-jdk AS build
ARG MODULE
WORKDIR /src
COPY . .
# Cache downloaded dependencies between builds.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl ${MODULE} -am package -DskipTests \
 && cp ${MODULE}/target/${MODULE}-*.jar /app.jar

# Stage 2: run it on a small Java runtime, as a non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
USER app
COPY --from=build /app.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
