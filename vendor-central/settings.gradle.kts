pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
rootProject.name = "engine-server-original-source-dependencies"
include(
    "ryze-transport-common",
    "ryze-netty-transport-nethernet",
    "ryze-okaeri-configs-core",
    "ryze-okaeri-configs-yaml-snakeyaml",
    "ryze-libdatachannel-java"
)
