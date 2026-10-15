plugins {
    id("artio.java-library")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    // Keep patched framework/transitive versions while the next Spring Boot release train is pending.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
    implementation(platform("tools.jackson:jackson-bom:3.1.7"))
    constraints {
        implementation("org.apache.tomcat.embed:tomcat-embed-core:11.0.25")
        implementation("org.apache.tomcat.embed:tomcat-embed-websocket:11.0.25")
        implementation("at.yawk.lz4:lz4-java:1.11.4")
        implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    }
    implementation("org.springframework.boot:spring-boot-starter-classic")
    implementation("org.springframework.boot:spring-boot-jackson2")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Core exports metrics through the vendor-neutral OTLP protocol when enabled.
    // Prometheus scraping and Grafana dashboards belong to the optional Docker add-on.
    implementation("io.micrometer:micrometer-registry-otlp")
    testImplementation("org.springframework.boot:spring-boot-starter-test-classic")
}

tasks.named<Jar>("jar") { enabled = false }
