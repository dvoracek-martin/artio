plugins {
    id("artio.java-library")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Core exports metrics through the vendor-neutral OTLP protocol when enabled.
    // Prometheus scraping and Grafana dashboards belong to the optional Docker add-on.
    implementation("io.micrometer:micrometer-registry-otlp")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<Jar>("jar") { enabled = false }
