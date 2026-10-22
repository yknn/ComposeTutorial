plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "android.serialport"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Builds the native "serial_port" shared library via CMake.
    // Requires the NDK and CMake to be installed (SDK Manager).
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(libs.androidx.annotation)
}
