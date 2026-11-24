plugins {
    id("com.android.application")
}

val releaseKeystore = providers.environmentVariable("LITESAIL_RELEASE_KEYSTORE")
val releaseStorePassword = providers.environmentVariable("LITESAIL_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("LITESAIL_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("LITESAIL_RELEASE_KEY_PASSWORD").orElse(releaseStorePassword)

android {
    namespace = "com.joel.litesail"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.joel.litesail"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.6.0"
    }

    buildFeatures {
        viewBinding = true
    }

    val releaseSigning = releaseKeystore.orNull?.let { path ->
        signingConfigs.create("release") {
            storeFile = file(path)
            storePassword = releaseStorePassword.orNull
            keyAlias = releaseKeyAlias.orNull
            keyPassword = releaseKeyPassword.orNull
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }

        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        val output = variant.outputs.single()
        output.outputFileName.set(output.versionName.map { version -> "LiteSail-v$version.apk" })
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
}
