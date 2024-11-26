import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val gcsVersion = "2.36.1"
val logstashVersion = "8.0"
val tokenValidationVersion = "5.0.13"
val googleCloudVersion = "5.8.0"

repositories {
    mavenCentral()
}

plugins {
    val kotlinVersion = "2.0.21"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.spring") version kotlinVersion
    id("org.springframework.boot") version "3.4.0"
    id("io.spring.dependency-management") version "1.1.6"
    idea
}

dependencies {
    implementation(kotlin("stdlib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.google.cloud:spring-cloud-gcp-starter-storage:$googleCloudVersion")
    implementation("org.projectreactor:reactor-spring:1.0.1.RELEASE")

    implementation("ch.qos.logback:logback-classic")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("no.nav.security:token-validation-spring:$tokenValidationVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "org.junit.vintage")
    }
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
}

idea {
    module {
        isDownloadJavadoc = true
    }
}

java.sourceCompatibility = JavaVersion.VERSION_17

tasks.withType<KotlinCompile> {
    kotlinOptions{
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.getByName<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    this.archiveFileName.set("app.jar")
}
