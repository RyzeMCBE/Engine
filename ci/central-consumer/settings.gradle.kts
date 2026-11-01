import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "engine-server-central-consumer"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The ONLY artifact repository allowed for this independent consumer.
        mavenCentral()
    }
}
