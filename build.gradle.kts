plugins {
    alias(libs.plugins.spotless)
    id("pocketinferencekit.documentation")
}

allprojects {
    group = "io.github.Avinash-006"
    version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()
}

spotless {
    kotlin {
        target("*/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts", "build-logic/**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(libs.versions.ktlint.get())
    }
}

val detektCli =
    configurations.create("detektCli") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

dependencies {
    add(detektCli.name, libs.detekt.cli)
    add("dokka", project(":locallm-core"))
    add("dokka", project(":locallm-models"))
    add("dokka", project(":locallm-compose"))
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Runs static analysis over the Android Kotlin sources."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    val sourceSets = listOf("locallm-core", "locallm-models", "locallm-compose", "sample").map { "$it/src/main/kotlin" }
    inputs.files(sourceSets.map(::file))
    inputs.file("config/detekt.yml")
    args("--input", sourceSets.joinToString(","), "--config", "config/detekt.yml", "--build-upon-default-config")
}
