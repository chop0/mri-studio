plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    // module-info present -> compile on the module path so `requires ax.xz.mri`
    // and `import module ax.xz.mri;` resolve against the studio's named module.
    modularity.inferModulePath.set(true)
}

repositories {
    mavenCentral()
}

dependencies {
    // The starters are templates written against the studio's public DSL
    // surface (ax.xz.mri's exported packages). Compile them against the real
    // module so a renamed/retyped API breaks the build here, not at the user's
    // runtime. This is compile-only: nothing consumes the compiled starters —
    // the root build copies the *sources* into mri-studio's resources.
    implementation(project(":"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
