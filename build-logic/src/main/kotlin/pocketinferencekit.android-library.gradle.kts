import com.android.build.api.artifact.SingleArtifact
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask

plugins {
    id("com.android.library")
    id("org.jetbrains.dokka")
    `maven-publish`
    signing
}

val versions = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    compileSdk {
        version =
            release(
                versions
                    .findVersion("android-compile")
                    .get()
                    .requiredVersion
                    .toInt(),
            ) {
                minorApiLevel =
                    versions
                        .findVersion("android-compile-minor")
                        .get()
                        .requiredVersion
                        .toInt()
            }
    }
    defaultConfig {
        minSdk =
            versions
                .findVersion("android-min")
                .get()
                .requiredVersion
                .toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val abiRuntime =
    configurations.create("abiRuntime") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

dependencies {
    add(abiRuntime.name, versions.findLibrary("kotlin-metadata-jvm").get())
    add(abiRuntime.name, versions.findLibrary("asm").get())
    add(abiRuntime.name, versions.findLibrary("asm-tree").get())
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val archive = variant.artifacts.get(SingleArtifact.AAR)
        val generatedJar = layout.buildDirectory.dir("abi/release-jar")
        val unpack =
            tasks.register<Sync>("extractReleaseApiJar") {
                from(archive.map { zipTree(it.asFile) }) { include("classes.jar") }
                into(generatedJar)
            }
        val apiFile = "${project.name}.api"
        val abi =
            tasks.register<KotlinApiBuildTask>("apiBuild") {
                dependsOn(unpack)
                inputJar.set(generatedJar.map { it.file("classes.jar") })
                outputApiFile.set(layout.buildDirectory.file("abi/$apiFile"))
                runtimeClasspath.from(abiRuntime)
                nonPublicMarkers.add("androidx.annotation.RestrictTo")
            }
        val expected = layout.projectDirectory.file("api/$apiFile")
        val compare =
            tasks.register<KotlinApiCompareTask>("apiCheck") {
                group = "verification"
                projectApiFile.set(expected)
                generatedApiFile.set(abi.flatMap { it.outputApiFile })
            }
        tasks.register<Copy>("apiDump") {
            group = "build"
            from(abi.flatMap { it.outputApiFile })
            into(expected.asFile.parentFile)
        }
        tasks.named("check") { dependsOn(compare) }
    }
}

android {
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

val projectUrl = rootProject.file("docs/PROJECT_URL.txt").readText().trim()
val documentationJar =
    tasks.register<Jar>("dokkaJavadocJar") {
        archiveClassifier.set("javadoc")
        dependsOn(tasks.named("dokkaGeneratePublicationHtml"))
        from(layout.buildDirectory.dir("dokka/html"))
    }

val releasePublication =
    publishing.publications.register<MavenPublication>("release") {
        artifactId = project.name.replaceFirst("locallm-", "pocketinferencekit-")
        artifact(documentationJar)
        pom {
            name.set("PocketInferenceKit ${project.name.removePrefix("locallm-")}")
            description.set("Kotlin Android SDK for on-device language model inference.")
            url.set(projectUrl)
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
            developers {
                developer {
                    id.set("Avinash-006")
                    name.set("Avinash Dola")
                    url.set("https://github.com/Avinash-006")
                }
            }
            scm {
                url.set(projectUrl)
                connection.set("scm:git:$projectUrl.git")
                developerConnection.set("scm:git:$projectUrl.git")
            }
        }
    }

afterEvaluate {
    releasePublication.configure { from(components["release"]) }
}

publishing.repositories {
    maven {
        name = "verification"
        url =
            rootProject.layout.buildDirectory
                .dir("verification-repository")
                .get()
                .asFile
                .toURI()
    }
    maven {
        name = "central"
        url =
            rootProject.layout.buildDirectory
                .dir("central-repository")
                .get()
                .asFile
                .toURI()
    }
}

val signingKey = providers.environmentVariable("CENTRAL_SIGNING_KEY").orNull
if (!signingKey.isNullOrBlank()) {
    signing {
        useInMemoryPgpKeys(signingKey, providers.environmentVariable("CENTRAL_SIGNING_PASSWORD").orNull)
        sign(releasePublication.get())
    }
}
