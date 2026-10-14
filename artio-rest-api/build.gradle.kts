plugins {
    id("artio.spring-boot-app")
    id("artio.integration-test")
}

dependencies {
    implementation("software.amazon.awssdk:s3:2.55.14")
    implementation("software.amazon.awssdk:url-connection-client:2.55.14")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.6.0")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    implementation(project(":artio-kernel"))
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
    implementation(project(":artio-extensions"))
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-graphql")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.kafka:spring-kafka")
    compileOnly("com.github.docker-java:docker-java-core:3.7.1")
    compileOnly("com.github.docker-java:docker-java-transport-httpclient5:3.7.1")
    testImplementation("com.github.docker-java:docker-java-core:3.7.1")
    testImplementation("com.github.docker-java:docker-java-transport-httpclient5:3.7.1")
}
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("artio-rest-api.jar")
    mainClass.set("io.artio.api.ArtioRestApiApplication")
}

tasks.register<org.springframework.boot.gradle.tasks.bundling.BootJar>("extensionControllerBootJar") {
    group = "build"
    description = "Builds the isolated Docker lifecycle controller application."
    archiveFileName.set("artio-extension-controller.jar")
    mainClass.set("io.artio.api.extensions.controller.ExtensionControllerApplication")
    // The privileged controller must not publish the commerce/OpenAPI surface through auto-configuration.
    classpath = (sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath)
        .filter { !it.name.startsWith("springdoc-") }
    targetJavaVersion.set(tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
        .flatMap { it.targetJavaVersion })
}

tasks.named("assemble") { dependsOn("extensionControllerBootJar") }

tasks.named<Test>("integrationTest") {
    dependsOn("extensionControllerBootJar")
    systemProperty("artio.test.controller-jar", layout.buildDirectory.file("libs/artio-extension-controller.jar").get().asFile.absolutePath)
}
