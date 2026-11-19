# Build stage
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Copy Gradle wrapper and build config
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
# Strip CRLF (Windows checkouts) so the wrapper runs on Linux
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# Download dependencies (cached unless build files change)
RUN ./gradlew dependencies --no-daemon || true

# Copy source and build fat JAR
COPY src src
RUN ./gradlew buildFatJar --no-daemon

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Install Python and pip for the Filmow scraper
RUN apk add --no-cache python3 py3-pip

# Copy scraper scripts and install dependencies
COPY scripts scripts
RUN pip3 install --no-cache-dir --break-system-packages -r scripts/requirements.txt

# Create non-root user for security
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup
USER appuser

# Copy the fat JAR from build stage
COPY --from=build /app/build/libs/*-all.jar app.jar

# App reads PORT from env (Railway); default 8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
