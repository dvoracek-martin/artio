plugins {
    id("artio.spring-adapter")
    id("artio.integration-test")
}

dependencies {
    implementation(project(":artio-catalog"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    add("integrationTestImplementation", platform("org.testcontainers:testcontainers-bom:1.20.4"))
    add("integrationTestImplementation", "org.testcontainers:junit-jupiter")
}
