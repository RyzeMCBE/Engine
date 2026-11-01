plugins {
    `java-library`
}

group = "io.github.ryzemcbe.smoke"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    // No other repositories, no local JARs, no GitHub Packages.
    implementation("io.github.ryzemcbe:engine-server:0.14.3-beta.1")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
