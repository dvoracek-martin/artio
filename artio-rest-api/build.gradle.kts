plugins {
    id("artio.spring-boot-app")
    id("artio.integration-test")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(project(":artio-kernel"))
    implementation(project(":artio-persistence"))
    implementation(project(":artio-observability"))
    implementation(project(":artio-events"))
    implementation(project(":artio-catalog"))
    implementation(project(":artio-catalog-postgres"))
    implementation(project(":artio-catalog-opensearch"))
    implementation(project(":artio-pricing"))
    implementation(project(":artio-pricing-postgres"))
    implementation(project(":artio-inventory"))
    implementation(project(":artio-inventory-postgres"))
    implementation(project(":artio-bundles"))
    implementation(project(":artio-bundles-postgres"))
    implementation(project(":artio-orders"))
    implementation(project(":artio-users"))
    implementation(project(":artio-payments"))
    implementation(project(":artio-notifications"))
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-mail")
}
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("artio-rest-api.jar")
}
