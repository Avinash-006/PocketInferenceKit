import com.android.build.api.artifact.SingleArtifact
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask

plugins {
    id("com.android.library")
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
