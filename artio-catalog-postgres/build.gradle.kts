plugins {
    id("artio.spring-adapter")
    id("artio.integration-test")
}

dependencies {
    implementation(project(":artio-catalog"))
}