plugins {
    id("java")
    id("groovy")
}

group = "ru.kazantsev.nsd.modules"
version = "1.0-SNAPSHOT"

repositories {
    maven {
        url = uri("https://maven.pkg.github.com/exeki/*")
        credentials {
            username = System.getenv("GITHUB_USERNAME")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
    mavenCentral()
}

dependencies {
    implementation("ru.kazantsev.nsd.sdk:global_variables:1.5.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.0")
    implementation("org.apache.groovy:groovy-all:4.0.21")
    implementation(files("aspose-word-19.4-jdk17.jar"))
}


