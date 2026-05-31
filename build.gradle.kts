import com.google.protobuf.gradle.id

plugins {
    java
    application
    id("com.google.protobuf") version "0.9.4"
    // jlink with auto-injected module-info for legacy automatic-modular JARs
    // (protobuf-java, controlsfx, …). Adds the `:jlink` task that produces
    // build/image/, a self-contained JDK runtime with `ax.xz.mri` on the
    // boot module-path.
    id("org.beryx.jlink") version "4.0.1"
}

group = "ax.xz.mri"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    // Put the project + its dependencies on the modulepath whenever a task
    // can see a module-info.java. Required for `import module ax.xz.mri;`
    // inside DSL scripts compiled at test/run time — only named modules
    // resolve, and classpath JARs become unnamed.
    modularity.inferModulePath.set(true)
}

repositories {
    mavenCentral()
}

// JavaFX modules with the platform-specific classifier so they land on the
// single `runtimeClasspath`-derived `--module-path` that the application
// plugin builds for the `run` task. The `org.openjfx.javafxplugin` would
// otherwise inject an extra `--module-path` JVM arg that the application
// plugin's own `--module-path` then silently overrides (last one wins) —
// that's the root cause of the "Module javafx.controls not found" error
// people hit when running modular JavaFX apps through gradle `run`.
val javafxVersion = "21.0.2"
val javafxClassifier: String = when {
    org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
        if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
    org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux"
    else -> "win"
}

dependencies {
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxClassifier")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxClassifier")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxClassifier")
    implementation("org.openjfx:javafx-swing:$javafxVersion:$javafxClassifier")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    // jdk8 module — Optional / OptionalDouble (de)serialisation, needed for Fov.sliceHalfMetres.
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.17.0")
    implementation("software.coley:bento-fx:0.10.1")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    implementation("org.controlsfx:controlsfx:11.2.1")
    // Ikonli — IBM Carbon icon set for HFSS-grade chrome. One stroke
    // weight, one design language, 2000+ icons keyed by name.
    implementation("org.kordamp.ikonli:ikonli-javafx:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-carbonicons-pack:12.3.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

// The RedPitaya wire schema lives in-repo at src/main/proto (the protobuf
// plugin's default location), so the build is self-contained — local dev and
// CI both codegen from this committed copy. The matching C-server consumes
// the same file; keep the two in sync when the wire contract changes.
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

    // DSL scripts in tests use `import module ax.xz.mri;`. That only resolves
    // when ax.xz.mri is loaded as a named module on the modulepath at test
    // runtime — without these flags Gradle puts it on the classpath as an
    // unnamed module and the import fails to resolve.
    jvmArgs(
        "--add-modules=ALL-MODULE-PATH",
        "--enable-native-access=ALL-UNNAMED"
    )
}

tasks.register<JavaExec>("runOptimiser") {
    group = "application"
    description = "Runs the Java optimiser CLI."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ax.xz.mri.optimisation.cli.OptimiserCliMain")
}

tasks.register<JavaExec>("shellPreview") {
    group = "verification"
    description = "Boots the full StudioShell + workbench layout and snapshots key states."
    dependsOn("compileTestJava")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ax.xz.mri.ui.preview.ShellPreview")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    })
    doFirst {
        val classifier = when {
            org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
                if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
            org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux"
            else -> "win"
        }
        val jfxJars = configurations.testRuntimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .filter { a ->
                a.moduleVersion.id.group == "org.openjfx"
                && (a.classifier == classifier || a.classifier == null)
            }
            .map { it.file.absolutePath }
        jvmArgs(
            "--module-path", jfxJars.joinToString(File.pathSeparator),
            "--add-modules", "javafx.controls,javafx.graphics,javafx.swing",
            "-Dprism.order=sw",
            "-Dprism.text=t2k"
        )
    }
}

tasks.register<JavaExec>("tutorialPreview") {
    group = "verification"
    description = "Boots the shell on a fresh project and snapshots the welcome pane + tutorial overlay."
    dependsOn("compileTestJava")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ax.xz.mri.ui.preview.TutorialPreview")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    })
    doFirst {
        val classifier = when {
            org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
                if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
            org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux"
            else -> "win"
        }
        val jfxJars = configurations.testRuntimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .filter { a ->
                a.moduleVersion.id.group == "org.openjfx"
                && (a.classifier == classifier || a.classifier == null)
            }
            .map { it.file.absolutePath }
        jvmArgs(
            "--module-path", jfxJars.joinToString(File.pathSeparator),
            "--add-modules", "javafx.controls,javafx.graphics,javafx.swing",
            "-Dprism.order=sw",
            "-Dprism.text=t2k"
        )
    }
}

tasks.register<JavaExec>("uiPreview") {
    group = "verification"
    description = "Boots the new SequenceEditorPane with sample data and saves PNG snapshots."
    dependsOn("compileTestJava")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ax.xz.mri.ui.preview.UiPreview")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    })
    doFirst {
        val classifier = when {
            org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
                if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
            org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux"
            else -> "win"
        }
        val jfxJars = configurations.testRuntimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .filter { a ->
                a.moduleVersion.id.group == "org.openjfx"
                && (a.classifier == classifier || a.classifier == null)
            }
            .map { it.file.absolutePath }
        jvmArgs(
            "--module-path", jfxJars.joinToString(File.pathSeparator),
            "--add-modules", "javafx.controls,javafx.graphics,javafx.swing",
            "-Dprism.order=sw",
            "-Dprism.text=t2k"
        )
    }
}

tasks.register<JavaExec>("fieldDensityPreview") {
    group = "verification"
    description = "Snapshots the B-field viewer + NV overlay at several zooms to verify adaptive arrow density."
    dependsOn("compileTestJava")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ax.xz.mri.ui.preview.FieldDensityPreview")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    })
    doFirst {
        val classifier = when {
            org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
                if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
            org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux"
            else -> "win"
        }
        val jfxJars = configurations.testRuntimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .filter { a ->
                a.moduleVersion.id.group == "org.openjfx"
                && (a.classifier == classifier || a.classifier == null)
            }
            .map { it.file.absolutePath }
        jvmArgs(
            "--module-path", jfxJars.joinToString(File.pathSeparator),
            "--add-modules", "javafx.controls,javafx.graphics,javafx.swing",
            "-Dprism.order=sw",
            "-Dprism.text=t2k"
        )
    }
}

// jlink-baked custom JDK runtime that has the studio module + JavaFX
// already on the boot module-path. The Badass JLink plugin auto-injects
// stub module-info into automatic-modular JARs (protobuf-java,
// controlsfx, …) so the jlink toolchain can include them. The image
// lands at build/image/, with `bin/java` as the standard launcher and a
// `bin/mri-studio` module-launcher for the GUI app.
jlink {
    options.addAll(
        "--strip-debug",
        "--compress", "zip-6",
        "--no-header-files",
        "--no-man-pages"
    )
    launcher {
        name = "mri-studio"
        jvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
    }
    forceMerge("jackson", "controlsfx", "ikonli", "bento")
    addExtraDependencies("javafx")
}

// Package the modular JavaFX app as a native macOS .app bundle via jpackage,
// using the Badass jlink image (build/image) as the bundle's runtime via
// --runtime-image. That image already has `ax.xz.mri` + JavaFX baked into its
// boot module-path, so a SINGLE embedded runtime serves two purposes:
//
//   • GUI       : double-click MriStudio.app → native launcher runs
//                 `java -m ax.xz.mri/ax.xz.mri.MriStudioApp` against the runtime.
//   • Scripting : the same runtime is a full JDK with the studio module pre-
//                 resolved, so users run procedure scripts standalone with
//                 `<App>/Contents/runtime/Contents/Home/bin/java MyProc.java`
//                 and `import module ax.xz.mri;` resolves with no flags.
//
// No runtime duplication — the embedded runtime IS the jlink image.
tasks.register<Exec>("packageApp") {
    group = "distribution"
    description = "Creates a native macOS .app bundle whose embedded runtime is the full jlink image (GUI + standalone scripting)."
    dependsOn("jlink")

    val imageDir = layout.buildDirectory.dir("image").get().asFile
    val outDir = layout.buildDirectory.dir("jpackage").get().asFile

    doFirst {
        if (!imageDir.resolve("bin").isDirectory)
            throw GradleException("jlink image not found at $imageDir — run :jlink first")
        outDir.deleteRecursively()
        outDir.mkdirs()
    }

    val launcher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
    val jpackageBin = launcher.map { "${it.metadata.installationPath.asFile.absolutePath}/bin/jpackage" }

    commandLine(
        jpackageBin.get(),
        "--type", "app-image",
        "--name", "MriStudio",
        "--app-version", "1.0.0",
        "--vendor", "ax.xz.mri",
        "--runtime-image", imageDir.absolutePath,
        "--module", "ax.xz.mri/ax.xz.mri.MriStudioApp",
        "--dest", outDir.absolutePath,
        "--mac-package-identifier", "ax.xz.mri.studio",
        "--mac-package-name", "MriStudio",
        "--java-options", "--enable-native-access=javafx.graphics",
        "--java-options", "-Dapple.awt.application.name=MriStudio"
    )

    doLast {
        val appDir = outDir.resolve("MriStudio.app")
        // The embedded runtime lives under Contents/runtime; locate its bin/java
        // for the standalone-scripting hint (path layout varies by JDK version).
        val javaBin = appDir.resolve("Contents/runtime").walkTopDown()
            .firstOrNull { it.name == "java" && it.parentFile.name == "bin" }
        logger.lifecycle("App bundle ready: $appDir")
        logger.lifecycle("  GUI     : open \"$appDir\"")
        if (javaBin != null) {
            logger.lifecycle("  Scripts : \"${javaBin.absolutePath}\" MyProcedure.java")
        }
    }
}
