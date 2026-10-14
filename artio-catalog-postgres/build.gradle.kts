plugins {
    id("artio.spring-adapter")
    id("artio.integration-test")
}

dependencies {
    implementation(project(":artio-catalog"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
}
