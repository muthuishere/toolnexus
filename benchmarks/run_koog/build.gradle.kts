plugins {
    kotlin("jvm") version "2.3.0"
    application
}

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

// keep build output (incl. installDist) out of the repo
layout.buildDirectory.set(file(System.getProperty("user.home") + "/.cache/tn-bench-2026-11/build-koog"))

dependencies {
    implementation("ai.koog:koog-agents:1.3.0")
    implementation("ai.koog:agents-mcp-jvm:1.3.0-beta")
}

application { mainClass.set("bench.KoogBenchKt") }
