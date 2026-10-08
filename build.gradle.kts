plugins {
    kotlin("jvm") version "2.0.21"
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.json:json:20240303")
}

application {
    mainClass.set("collector.MainKt")
}
