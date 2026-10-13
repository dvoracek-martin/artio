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

val artioDomainModules = setOf(
    "artio-catalog",
    "artio-pricing",
    "artio-inventory",
    "artio-bundles",
    "artio-orders",
    "artio-users",
    "artio-payments",
    "artio-notifications",
)

val forbiddenDomainDependencyGroups = listOf(
    "org.springframework",
    "org.hibernate",
    "org.apache.kafka",
    "org.postgresql",
    "org.opensearch",
    "redis.clients",
    "com.stripe",
    "com.paypal",
)

val inspectedDomainConfigurations = setOf(
    "api", "implementation", "compileOnly", "runtimeOnly",
    "annotationProcessor", "compileClasspath", "runtimeClasspath",
)

val verifyModuleBoundaries = tasks.register<io.artio.build.VerifyModuleBoundaries>("verifyModuleBoundaries") {
    group = "verification"
    description = "Ensures domain modules do not depend on frameworks, adapters, or provider integrations."
    violations.set(emptyList())
}

gradle.projectsEvaluated {
    val moduleBoundaryViolations = artioDomainModules.flatMap { moduleName ->
        val domainProject = project(":$moduleName")
        domainProject.configurations
            .filter { it.name in inspectedDomainConfigurations }
            .flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    when (dependency) {
                        is org.gradle.api.artifacts.ProjectDependency -> {
                            val target = dependency.dependencyProject.name
                            if (target != "artio-kernel" && target !in artioDomainModules) {
                                "$moduleName:${configuration.name} -> $target (only artio-kernel or an approved domain module is allowed)"
                            } else null
                        }
                        is org.gradle.api.artifacts.ModuleDependency -> {
                            val group = dependency.group.orEmpty()
                            if (forbiddenDomainDependencyGroups.any { group == it || group.startsWith("$it.") }) {
                                "$moduleName:${configuration.name} -> ${dependency.group}:${dependency.name} (framework, persistence, messaging, or provider dependency belongs outside the domain)"
                            } else null
                        }
                        else -> null
                    }
                }
            }
    }
    verifyModuleBoundaries.configure {
        violations.set(moduleBoundaryViolations)
    }
}

tasks.named("check") {
    dependsOn(verifyModuleBoundaries)
}
