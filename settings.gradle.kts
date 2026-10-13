pluginManagement {
    repositories { mavenCentral() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "artio"
include(
    "artio-kernel",
    "artio-persistence",
    "artio-observability",
    "artio-events",
    "artio-catalog",
    "artio-catalog-postgres",
    "artio-catalog-opensearch",
    "artio-pricing",
    "artio-pricing-postgres",
    "artio-inventory",
    "artio-inventory-postgres",
    "artio-bundles",
    "artio-bundles-postgres",
    "artio-orders",
    "artio-users",
    "artio-payments",
    "artio-notifications",
    "artio-rest-api",
    "artio-storefront",
    "artio-backoffice",
    "artio-docker",
)
