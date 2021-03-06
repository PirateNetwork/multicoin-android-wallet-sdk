plugins {
    id("com.android.application")
    id("zcash.android-build-conventions")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    id("androidx.navigation.safeargs")
    id("com.osacky.fladle")
}

android {
    defaultConfig {
        applicationId = "cash.z.ecc.android.sdk.demoapp"
        minSdk = 21 // Different from the SDK min
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        viewBinding = true
    }

    flavorDimensions.add("network")

    productFlavors {
        // would rather name them "testnet" and "mainnet" but product flavor names cannot start with the word "test"
        create("zcashtestnet") {
            dimension = "network"
            applicationId = "cash.z.ecc.android.sdk.demoapp.testnet"
            matchingFallbacks.addAll(listOf("zcashtestnet", "debug"))
        }

        create("zcashmainnet") {
            dimension = "network"
            applicationId = "cash.z.ecc.android.sdk.demoapp.mainnet"
            matchingFallbacks.addAll(listOf("zcashmainnet", "release"))
        }

        create("piratenet") {
            dimension = "network"
            applicationId = "cash.z.ecc.android.sdk.demoapp.piratenet"
            matchingFallbacks.addAll(listOf("piratenet", "release"))
        }
    }

    buildTypes {
        getByName("release").apply {
            isMinifyEnabled = project.property("IS_MINIFY_APP_ENABLED").toString().toBoolean()
            proguardFiles.addAll(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    File("proguard-project.txt")
                )
            )
        }
    }

    kotlinOptions {
        jvmTarget = libs.versions.java.get()
        allWarningsAsErrors = project.property("IS_TREAT_WARNINGS_AS_ERRORS").toString().toBoolean()
    }

    lint {
        baseline = File("lint-baseline.xml")
    }
}

dependencies {
    // SDK
    implementation(projects.sdkLib)

    // sample mnemonic plugin
    implementation(libs.zcashwalletplgn)
    implementation(libs.bip39)

    // Android
    implementation(libs.androidx.core)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.material)
    androidTestImplementation(libs.bundles.androidx.test)

    implementation(libs.bundles.grpc)
}

// Firebase Test Lab has min and max values that might differ from our project's
// These are determined by `gcloud firebase test android models list`
@Suppress("MagicNumber", "PropertyName", "VariableNaming")
val FIREBASE_TEST_LAB_MIN_API = 23

@Suppress("MagicNumber", "PropertyName", "VariableNaming")
val FIREBASE_TEST_LAB_MAX_API = 30

val firebaseTestLabKeyPath = project.properties["ZCASH_FIREBASE_TEST_LAB_API_KEY_PATH"].toString()
if (firebaseTestLabKeyPath.isNotBlank()) {
    val minSdkVersion = run {
        val buildMinSdk =
            project.properties["ANDROID_MIN_SDK_VERSION"].toString().toInt()
        buildMinSdk.coerceAtLeast(FIREBASE_TEST_LAB_MIN_API).toString()
    }
    val targetSdkVersion = run {
        val buildTargetSdk =
            project.properties["ANDROID_TARGET_SDK_VERSION"].toString().toInt()
        buildTargetSdk.coerceAtMost(FIREBASE_TEST_LAB_MAX_API).toString()
    }

    fladle {
        serviceAccountCredentials.set(File(firebaseTestLabKeyPath))

        configs {
            create("sanityConfig") {
                clearPropertiesForSanityRobo()

                debugApk.set(
                    project.provider {
                        "${buildDir}/outputs/apk/zcashmainnet/release/demo-app-zcashmainnet-release.apk"
                    }
                )

                testTimeout.set("5m")

                devices.addAll(
                    mapOf("model" to "NexusLowRes", "version" to minSdkVersion),
                    mapOf("model" to "NexusLowRes", "version" to targetSdkVersion)
                )

                flankVersion.set(libs.versions.flank.get())
            }
        }
    }
}
