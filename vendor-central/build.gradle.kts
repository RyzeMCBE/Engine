import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    id("java-library")
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

// CI checks out licensed source at pinned commits to ../vendor-upstream.
val sources = rootProject.file("../vendor-upstream")
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.vanniktech.maven.publish")
    group = "io.github.ryzemcbe"
    version = when (name) {
        "ryze-transport-common", "ryze-netty-transport-nethernet" -> "2.0.0.CR6-ryze.1"
        "ryze-okaeri-configs-core", "ryze-okaeri-configs-yaml-snakeyaml" -> "5.0.13-ryze.1"
        "ryze-libdatachannel-java" -> "0.24.6.0-ryze.1"
        else -> error("Unknown vendor project")
    }

    val originalRepo = when {
        name.startsWith("ryze-transport") || name.startsWith("ryze-netty") -> "CloudburstMC/Network"
        name.startsWith("ryze-okaeri") -> "OkaeriPoland/okaeri-configs"
        else -> "opencollab-incubator/libdatachannel-java"
    }
    val upstreamDir = when (name) {
        "ryze-transport-common" -> sources.resolve("nethernet/transport-common")
        "ryze-netty-transport-nethernet" -> sources.resolve("nethernet/transport-nethernet")
        "ryze-okaeri-configs-core" -> sources.resolve("okaeri/core")
        "ryze-okaeri-configs-yaml-snakeyaml" -> sources.resolve("okaeri/yaml-snakeyaml")
        "ryze-libdatachannel-java" -> sources.resolve("libdatachannel")
        else -> error("Unknown upstream directory")
    }
    val sourceLicense = when {
        name.startsWith("ryze-transport") || name.startsWith("ryze-netty") -> sources.resolve("nethernet/LICENSE")
        name.startsWith("ryze-okaeri") -> sources.resolve("okaeri/LICENSE")
        else -> sources.resolve("libdatachannel/LICENSE")
    }
    sourceSets.named("main") {
        java.setSrcDirs(listOf(upstreamDir.resolve("src/main/java")))
        resources.setSrcDirs(listOf(upstreamDir.resolve("src/main/resources")))
    }
    repositories { mavenCentral() }
    configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        if (project.name == "ryze-libdatachannel-java") {
            options.compilerArgs.addAll(listOf(
                "-Agenerate.jni.headers=true",
                "-Agenerate.cache.mode.default=EAGER_PERSISTENT"
            ))
        }
    }
    tasks.withType<Javadoc>().configureEach {
        isFailOnError = false
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
    }
    tasks.withType<Jar>().configureEach {
        from(sourceLicense) {
            into("META-INF")
            rename { "LICENSE" }
        }
    }
    // Original upstream Linux x86_64 WebRTC JNI binary, independently built
    // from the exact pinned native source. Java-only smoke tests do not need it.
    // A public release MUST include the signed JNI classifier alongside the
    // normal Java / sources / Javadoc artifacts; never substitute an empty JAR.
    if (project.name == "ryze-libdatachannel-java" &&
        providers.gradleProperty("vendorCentralRelease").map(String::toBoolean).getOrElse(false)) {
        val originalNativeJar = providers.provider {
            val binaries = sources.resolve("libdatachannel/build/libs")
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith("-linux-glibc-x86_64.jar") }
                .orEmpty()
            check(binaries.size == 1) {
                "Original native classifier missing or ambiguous; first run pinned libdatachannel original-source JNI build"
            }
            binaries.single()
        }
        val nativeDistribution = tasks.register<Jar>("linuxGlibcX8664NativeJar") {
            archiveClassifier.set("linux-glibc-x86_64")
            from(originalNativeJar.map { zipTree(it) }) {
                include("native/libdatachannel-java.so")
            }
            doFirst { check(originalNativeJar.get().length() > 0L) }
        }
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                artifact(nativeDistribution)
            }
        }
    }
    dependencies {
        when (project.name) {
            "ryze-transport-common" -> "api"("io.netty:netty-transport:4.1.130.Final")
            "ryze-libdatachannel-java" -> {
                "implementation"("org.slf4j:slf4j-api:2.0.18")
                "compileOnly"("org.eclipse.jdt:org.eclipse.jdt.annotation:2.4.100")
                "compileOnly"("tel.schich:jni-access-generator:1.2.3")
                "annotationProcessor"("tel.schich:jni-access-generator:1.2.3")
            }
            "ryze-netty-transport-nethernet" -> {
                "api"(project(":ryze-transport-common"))
                "api"(project(":ryze-libdatachannel-java"))
                listOf("common", "buffer", "codec", "codec-haproxy", "codec-http",
                    "transport", "transport-native-unix-common").forEach {
                    "api"("io.netty:netty-$it:4.1.130.Final")
                }
                "api"("net.jodah:expiringmap:0.5.10")
                "implementation"("com.google.code.gson:gson:2.13.2")
                "implementation"("org.bitbucket.b_c:jose4j:0.9.6")
                "implementation"("org.bouncycastle:bcpkix-jdk18on:1.85")
                "compileOnly"("org.jspecify:jspecify:1.0.0")
            }
            "ryze-okaeri-configs-core" -> {
                "compileOnly"("org.projectlombok:lombok:1.18.38")
                "annotationProcessor"("org.projectlombok:lombok:1.18.38")
            }
            "ryze-okaeri-configs-yaml-snakeyaml" -> {
                "api"(project(":ryze-okaeri-configs-core"))
                "api"("org.yaml:snakeyaml:1.33")
                "compileOnly"("org.projectlombok:lombok:1.18.38")
                "annotationProcessor"("org.projectlombok:lombok:1.18.38")
            }
        }
    }
    configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        if (providers.gradleProperty("vendorCentralRelease").map(String::toBoolean).getOrElse(false)) {
            signAllPublications()
        }
        coordinates(project.group.toString(), project.name, project.version.toString())
        pom {
            name.set("Independent source build of " + originalRepo + " / " + project.name)
            description.set("Source build maintained by RyzeMCBE for Engine compatibility, not an official upstream release.")
            url.set("https://github.com/RyzeMCBE/Engine")
            scm {
                connection.set("scm:git:https://github.com/RyzeMCBE/Engine.git")
                developerConnection.set("scm:git:ssh://github.com/RyzeMCBE/Engine.git")
                url.set("https://github.com/RyzeMCBE/Engine")
            }
            licenses {
                license {
                    name.set(when {
                        project.name.startsWith("ryze-okaeri") -> "MIT License"
                        project.name.startsWith("ryze-libdatachannel") -> "Mozilla Public License 2.0"
                        else -> "The Apache License, Version 2.0"
                    })
                    url.set(when {
                        project.name.startsWith("ryze-okaeri") -> "https://opensource.org/licenses/MIT"
                        project.name.startsWith("ryze-libdatachannel") -> "https://www.mozilla.org/en-US/MPL/2.0/"
                        else -> "https://www.apache.org/licenses/LICENSE-2.0"
                    })
                }
            }
            developers {
                developer {
                    name.set("RyzeMCBE Development Studio (build and maintenance)")
                    organization.set("RyzeMCBE")
                    organizationUrl.set("https://github.com/RyzeMCBE")
                }
                developer {
                    name.set("Original project: " + originalRepo)
                    organization.set(originalRepo.substringBefore('/'))
                    organizationUrl.set("https://github.com/" + originalRepo)
                }
            }
        }
    }
}
