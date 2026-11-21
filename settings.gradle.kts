rootProject.name = "Engine"

// Engine's dependencies are resolved from Maven Central. The Git repositories
// for Protocol and StateUpdater remain independent release projects.
include(":api", ":server", ":codegen", ":data")
