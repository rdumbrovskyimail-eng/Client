// >>> FILE: app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.client.app"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.client.app"
        minSdk = 28
        targetSdk = 36

        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-28"
                )

                cppFlags += listOf(
                    "-std=c++20",
                    "-O3",
                    "-fvisibility=hidden",
                    "-flto"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file(
                "src/main/cpp/CMakeLists.txt"
            )
            version = "3.22.1"
        }
    }

    // AUD-070:
    //
    // Silero ONNX is loaded through AssetManager.openFd()
    // and memory-mapped.
    //
    // openFd() requires an uncompressed asset.
    androidResources {
        noCompress += "onnx"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // AUD-011: release signing is explicit and has NO debug fallback.
    // Missing keystore or any required credential makes release validation fail.
    signingConfigs {
        create("release") {
            val keystorePath =
                providers.environmentVariable("RELEASE_KEYSTORE_PATH")
                    .orNull
                    ?: rootProject.file("release.keystore").absolutePath

            val keystoreFile = file(keystorePath)
            val storePassword =
                providers.environmentVariable("KEYSTORE_PASSWORD").orNull
            val keyAlias =
                providers.environmentVariable("KEY_ALIAS").orNull
            val keyPassword =
                providers.environmentVariable("KEY_PASSWORD").orNull

            if (
                keystoreFile.isFile &&
                !storePassword.isNullOrBlank() &&
                !keyAlias.isNullOrBlank() &&
                !keyPassword.isNullOrBlank()
            ) {
                storeFile = keystoreFile
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    val validateReleaseSigning =
        tasks.register("validateReleaseSigning") {
            doLast {
                val keystorePath =
                    System.getenv("RELEASE_KEYSTORE_PATH")
                        ?.takeIf { it.isNotBlank() }
                        ?: rootProject.file("release.keystore").absolutePath

                val keystoreFile = file(keystorePath)
                require(keystoreFile.isFile) {
                    "AUD-011: production release keystore is missing: $keystorePath"
                }
                require(!System.getenv("KEYSTORE_PASSWORD").isNullOrBlank()) {
                    "AUD-011: KEYSTORE_PASSWORD is missing"
                }
                require(!System.getenv("KEY_ALIAS").isNullOrBlank()) {
                    "AUD-011: KEY_ALIAS is missing"
                }
                require(!System.getenv("KEY_PASSWORD").isNullOrBlank()) {
                    "AUD-011: KEY_PASSWORD is missing"
                }
            }
        }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )

            signingConfig =
                signingConfigs.getByName(
                    "release"
                )
        }

        debug {
            isMinifyEnabled = false
        }
    }
    tasks.matching { task ->
        task.name == "assembleRelease" ||
        task.name == "bundleRelease" ||
        task.name == "signRelease"
    }.configureEach {
        dependsOn(validateReleaseSigning)
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_17

        targetCompatibility =
            JavaVersion.VERSION_17
    }

    packaging {
        // AUD-061: keep native libraries uncompressed so AGP can preserve
        // 16 KB zip alignment for APK/AAB packaging.
        jniLibs {
            useLegacyPackaging = false
        }

        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl
                .JvmTarget
                .JVM_17
        )

        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
}

dependencies {

    implementation(
        "androidx.core:core-ktx:1.15.0"
    )

    implementation(
        "androidx.activity:activity-compose:1.10.0"
    )

    implementation(
        "androidx.lifecycle:lifecycle-runtime-compose:2.8.7"
    )

    implementation(
        "androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7"
    )

    val composeBom =
        platform(
            "androidx.compose:compose-bom:2025.02.00"
        )

    implementation(
        composeBom
    )

    implementation(
        "androidx.compose.ui:ui"
    )

    implementation(
        "androidx.compose.ui:ui-graphics"
    )

    implementation(
        "androidx.compose.material3:material3"
    )

    implementation(
        "androidx.compose.material:material-icons-extended:1.7.8"
    )

    implementation(
        "androidx.compose.animation:animation"
    )

    implementation(
        "androidx.navigation:navigation-compose:2.8.5"
    )

    implementation(
        "com.google.dagger:hilt-android:2.57.2"
    )

    ksp(
        "com.google.dagger:hilt-android-compiler:2.57.2"
    )

    implementation(
        "androidx.hilt:hilt-navigation-compose:1.2.0"
    )

    implementation(
        "com.squareup.okhttp3:okhttp:4.12.0"
    )

    implementation(
        "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0"
    )

    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1"
    )

    implementation(
        "androidx.datastore:datastore-preferences:1.1.2"
    )

    implementation(
        "com.jakewharton.timber:timber:5.0.1"
    )

    implementation(
        "com.microsoft.onnxruntime:onnxruntime-android:1.29.0"
    )

    implementation(
        "com.mikepenz:multiplatform-markdown-renderer-m3:0.31.0"
    )

    implementation(
        "androidx.media:media:1.7.0"
    )
}