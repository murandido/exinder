plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.exinder"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.exinder"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 shrinks the APK (most of it is the unused part of the
            // extended icon set). Room, Compose and Media3 ship their own keep rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // There is no release key in the repository: release builds are
            // signed with the local debug key, which is fine for sideloading
            // your own build. To publish, add your own signing config.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        // Quality gate: errors fail the build, and so does any warning that
        // isn't in the baseline (the warnings that existed when the gate was added).
        abortOnError = true
        warningsAsErrors = true
        baseline = file("lint-baseline.xml")
        // "A newer version is available" checks would fail CI whenever a library
        // releases; Dependabot handles updates instead.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

ksp {
    // Every database version's schema is kept in the repository, so migrations
    // can be written (and checked) against the exact previous schema.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)
    implementation(libs.coil.svg)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}

// ─── ktlint ──────────────────────────────────────────────────────────────────
// Run through the ktlint CLI rather than a Gradle plugin, so it doesn't depend
// on the Kotlin Gradle plugin (this project uses AGP's built-in Kotlin).
// The rules come from the root .editorconfig.

val ktlint: Configuration by configurations.creating

dependencies {
    ktlint(libs.ktlint.cli) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        }
    }
}

val ktlintSources = listOf("app/src/**/*.kt", "**/*.kts", "!**/build/**")

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Checks Kotlin code style with ktlint."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    args(ktlintSources)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Fixes Kotlin code style with ktlint."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    workingDir = rootDir
    args(listOf("-F") + ktlintSources)
}
