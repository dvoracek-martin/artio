plugins { id("artio.java-library") }

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, platform("org.testcontainers:testcontainers-bom:1.20.4"))
    add(integrationTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter")
    add(integrationTest.implementationConfigurationName, "org.testcontainers:junit-jupiter")
    add(integrationTest.implementationConfigurationName, "org.testcontainers:postgresql")
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests using Testcontainers."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") { dependsOn(integrationTestTask) }
