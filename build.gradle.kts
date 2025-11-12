plugins {
    kotlin("jvm") version "2.2.0"  // Latest stable
    kotlin("plugin.spring") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("org.springframework.boot") version "3.5.7"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ru.driics"
version = "0.0.1-SNAPSHOT"
description = "aiTrader"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    google()
    maven {
        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(platform("org.jetbrains.kotlinx:kotlinx-serialization-bom:1.9.0"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm")

    implementation(platform("io.ktor:ktor-bom:3.3.1"))
    implementation("io.ktor:ktor-client-websockets")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-serialization-jackson")

    implementation("ai.koog:koog-spring-boot-starter:0.5.1")

    implementation("io.opentelemetry:opentelemetry-api:1.55.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.55.0")
    implementation("io.opentelemetry.semconv:opentelemetry-semconv:1.37.0")
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.21.0")
    implementation("io.opentelemetry.instrumentation:opentelemetry-ktor-3.0:2.21.0-alpha")
    implementation("io.opentelemetry:opentelemetry-extension-kotlin:1.55.0")

    // Micrometer bridge for OpenTelemetry
    implementation("io.micrometer:micrometer-tracing-bridge-otel:1.6.0")

    // OTLP exporter (Jaeger/Tempo)
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.55.0")

    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
    implementation("io.github.resilience4j:resilience4j-retry:2.3.0")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
    implementation("io.github.resilience4j:resilience4j-ratelimiter:2.3.0")
    implementation("io.github.resilience4j:resilience4j-kotlin:2.3.0")

    implementation("io.micrometer:micrometer-core")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.9.0")
    
    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    
    // Logging
    implementation("org.springframework.boot:spring-boot-starter-logging")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")

    // Cache
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.2")
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.wiremock:wiremock-standalone:3.0.1")
    testImplementation("io.mockk:mockk:1.14.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        val serdeVer = "1.9.0" // should be >= 1.8.0
        when(requested.module.toString()) {
            "org.jetbrains.kotlinx:kotlinx-serialization-json" -> useVersion(serdeVer)
            "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm" -> useVersion(serdeVer)
            "org.jetbrains.kotlinx:kotlinx-serialization-core" -> useVersion(serdeVer)
            "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm" -> useVersion(serdeVer)
            "org.jetbrains.kotlinx:kotlinx-serialization-bom" -> useVersion(serdeVer)
        }
    }
}


tasks.withType<Test> {
    useJUnitPlatform()
}
