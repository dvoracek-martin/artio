plugins { base }

allprojects {
    group = "io.artio"
    version = "0.1.0-SNAPSHOT"
}

subprojects { apply(plugin = "artio.java-library") }

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs integration tests in all modules that define them."
    dependsOn(":artio-rest-api:integrationTest")
}
