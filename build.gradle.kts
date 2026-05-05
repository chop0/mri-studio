import com.google.protobuf.gradle.id

plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.google.protobuf") version "0.9.4"
}

group = "ax.xz.mri"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
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
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("software.coley:bento-fx:0.10.1")
    implementation("org.codehaus.janino:janino:3.1.12")
    implementation("org.codehaus.janino:commons-compiler:3.1.12")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

// The .proto schema is owned by the C-server repo dir; both sides codegen
// from the same file so the wire contract can never drift.
sourceSets {
    main {
        proto {
            srcDir("../mri-rp-server/proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
}

application {
    mainClass = "ax.xz.mri.MriStudioApp"
    mainModule = "ax.xz.mri"
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // Forward redpitaya.smoke.host (and friends) so the live integration
    // test can opt-in via the gradle command line.
    systemProperties(System.getProperties().filterKeys {
        (it as String).startsWith("redpitaya.")
    } as Map<String, Any>)
    // Tests don't run on the JavaFX application thread; allow UnifiedStateManager
    // dispatches without that guard firing.
    systemProperty("ax.xz.mri.state.bypass-fx-check", "true")
}

tasks.register<JavaExec>("runOptimiser") {
    group = "application"
    description = "Runs the Java optimiser CLI."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ax.xz.mri.optimisation.cli.OptimiserCliMain")
}

// Package the modular JavaFX app as a proper native macOS .app bundle via jpackage.
// jpackage produces a native launcher (not a shell script) so the process registers
// with macOS Launch Services under this bundle identifier.
tasks.register<Exec>("packageApp") {
    group = "distribution"
    description = "Creates a native .app bundle via jpackage."
    dependsOn("installDist")

    val installDir = layout.buildDirectory.dir("install/mri-studio/lib").get().asFile
    val outDir = layout.buildDirectory.dir("jpackage").get().asFile

    doFirst {
        outDir.deleteRecursively()
        outDir.mkdirs()
    }

    // Use the JDK the application is compiled against (24), not Gradle's own JDK.
    val launcher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(24)
    }
    val jdkRoot = launcher.map { it.metadata.installationPath.asFile.absolutePath }
    val jpackageBin = jdkRoot.map { "$it/bin/jpackage" }

    // Bypass jlink entirely — it can't handle automatic modules like commons-compiler.
    // Use the full JDK as the runtime image.
    commandLine(
        jpackageBin.get(),
        "--type", "app-image",
        "--name", "MriStudio",
        "--app-version", "1.0.0",
        "--vendor", "ax.xz.mri",
        "--runtime-image", jdkRoot.get(),
        "--module-path", installDir.absolutePath,
        "--module", "ax.xz.mri/ax.xz.mri.MriStudioApp",
        "--dest", outDir.absolutePath,
        "--mac-package-identifier", "ax.xz.mri.studio",
        "--mac-package-name", "MriStudio",
        "--java-options", "--enable-native-access=javafx.graphics",
        "--java-options", "-Dapple.awt.application.name=MriStudio"
    )
}
