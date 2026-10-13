plugins { id("artio.spring-adapter") }

dependencies {
    implementation(project(":artio-catalog"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}
