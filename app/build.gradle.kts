import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.poporpepopo.photobackup"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.poporpepopo.photobackup"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    // The release key lives outside the repository and its password in the macOS
    // Keychain (see README), so neither is ever written into the project. They are only
    // looked up when a release task was asked for, so debug builds work without them.
    val wantsRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    signingConfigs {
        create("release") {
            storeFile = file("${System.getProperty("user.home")}/.android/keystores/photobackup-release.jks")
            keyAlias = "photobackup"
            if (wantsRelease) {
                val password = providers.exec {
                    commandLine("security", "find-generic-password", "-s", "photobackup-release-keystore", "-a", "release", "-w")
                }.standardOutput.asText.get().trim()
                storePassword = password
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            // Not debuggable (the default for release): adb cannot run code as this app, so
            // it cannot use the Keystore key that protects the SMB password.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { test ->
            // SmbIntegrationTest runs only when these point at a test SMB server.
            listOf("SMB_TEST_PORT", "SMB_TEST_ROOT").forEach { name ->
                providers.environmentVariable(name).orNull?.let { test.environment(name, it) }
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

room {
    // Exported so future schema changes can be checked and migrated, not guessed.
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.exifinterface)

    // SMB2/3 client. smbj logs through slf4j; with no slf4j provider on the classpath
    // those logs go nowhere, which also keeps protocol details out of logcat.
    implementation(libs.smbj)
    // smbj's NTLM needs MD4, which Android's JCA does not ship. We hand smbj the
    // BouncyCastle lightweight API directly (BCSecurityProvider) instead of going via JCA.
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
