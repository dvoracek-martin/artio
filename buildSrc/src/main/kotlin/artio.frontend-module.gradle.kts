plugins { base }

val npmCommand = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "npm.cmd" else "npm"

val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "build"
    description = "Installs the locked Angular dependencies."
    workingDir(projectDir)
    commandLine(npmCommand, "ci", "--no-audit", "--no-fund")
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
}

val npmBuild = tasks.register<Exec>("npmBuild") {
    group = "build"
    description = "Builds the Angular application in production mode."
    dependsOn(npmInstall)
    workingDir(projectDir)
    commandLine(npmCommand, "run", "build")
    inputs.dir("src")
    inputs.files("package.json", "package-lock.json", "angular.json", "tsconfig.json", "tsconfig.app.json", "tailwind.config.js", "postcss.config.js")
    outputs.dir("dist")
}

if (file("tsconfig.spec.json").exists()) {
    val npmTest = tasks.register<Exec>("npmTest") {
        group = "verification"
        description = "Runs the Angular application unit tests."
        dependsOn(npmInstall)
        workingDir(projectDir)
        commandLine(npmCommand, "test")
        inputs.dir("src")
        inputs.files("package.json", "package-lock.json", "angular.json", "tsconfig.json", "tsconfig.spec.json")
    }
    tasks.named("check") { dependsOn(npmTest) }
}

tasks.named("build") { dependsOn(npmBuild) }
