plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "ax.xz.mri"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

javafx {
    version = "21.0.2"
    modules("javafx.controls", "javafx.graphics")
}

dependencies {
    implementation("io.github.mkpaz:atlantafx-base:2.0.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
}

application {
    mainClass = "ax.xz.mri.MriStudioApp"
    mainModule = "ax.xz.mri"
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
