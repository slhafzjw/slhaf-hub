plugins {
    kotlin("jvm") version "2.2.20"
    application
}

val kotlinVersion = "2.2.20"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))

    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies-maven:$kotlinVersion")

    implementation("com.google.guava:guava:28.2-jre")

    implementation("io.ktor:ktor-server-core-jvm:2.3.13")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.13")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("work.slhaf.hub.CliHostKt")
}

val runCli by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run the script CLI host"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("work.slhaf.hub.CliHostKt")
    workingDir = projectDir
}

val runWeb by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run the script web host"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("work.slhaf.hub.WebHostKt")
    workingDir = projectDir
}
