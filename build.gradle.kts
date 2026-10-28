plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    id("io.ktor.plugin") version "3.1.1"
}

group = "org.mobyle"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.0")
    implementation("io.ktor:ktor-server-netty:2.3.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.0")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.0")
    implementation("io.ktor:ktor-server-status-pages:2.3.0")
    implementation("io.ktor:ktor-server-cors:2.3.0")
    implementation("io.ktor:ktor-server-websockets:2.3.0")
    implementation("io.ktor:ktor-server-auth:2.3.0")
    // Observability (B11.2): request ids, request logs and Prometheus metrics.
    implementation("io.ktor:ktor-server-call-id:2.3.0")
    implementation("io.ktor:ktor-server-call-logging:2.3.0")
    implementation("io.ktor:ktor-server-metrics-micrometer:2.3.0")
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.4")
    implementation("ch.qos.logback:logback-classic:1.2.10")

    // Ktor Client (for TMDB API calls)
    implementation("io.ktor:ktor-client-core:2.3.0")
    implementation("io.ktor:ktor-client-cio:2.3.0")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.0")
    implementation("io.ktor:ktor-client-logging:2.3.0")

    // Koin for Dependency Injection
    implementation("io.insert-koin:koin-core:3.5.6")
    implementation("io.insert-koin:koin-ktor:3.5.6")
    implementation("io.insert-koin:koin-logger-slf4j:3.5.6")

    // Database - Exposed ORM + PostgreSQL
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.56.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")

    // Versioned schema migrations (src/main/resources/db/migration)
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")

    // RSS/XML parsing
    implementation("org.jsoup:jsoup:1.18.1")

    // BCrypt for password hashing
    implementation("at.favre.lib:bcrypt:0.10.2")

    testImplementation("io.ktor:ktor-server-tests:2.3.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("io.ktor:ktor-client-mock:2.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.h2database:h2:2.2.224")
    // Real PostgreSQL for migration tests, without Docker
    testImplementation("io.zonky.test:embedded-postgres:2.0.7")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.4.0"))
}

configurations.all {
    resolutionStrategy {
        force("io.netty:netty-codec-http:4.1.129.Final")
        force("io.netty:netty-handler:4.1.129.Final")
        force("io.netty:netty-codec:4.1.129.Final")
        force("io.netty:netty-transport:4.1.129.Final")
        force("io.netty:netty-buffer:4.1.129.Final")
        force("io.netty:netty-common:4.1.129.Final")
    }
}

tasks.test {
    useJUnitPlatform()
}

// flyway-core and flyway-database-postgresql both ship META-INF/services plugin
// files; without merging, the fat JAR keeps only one and Flyway finds no migrations.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    mergeServiceFiles()
}

application {
    mainClass.set("org.mobyle.MainKt")
}
