val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "dev.nozzlenaut.androidklipper"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "dev.nozzlenaut.androidklipper"
        minSdk = 24
        targetSdk = 35
        versionCode = ciRunNumber ?: 1
        versionName = if (ciRunNumber != null) "0.0.1-poc-ci$ciRunNumber" else "0.0.1-poc-local"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install("pyserial==3.4")
            install("greenlet==3.0.1")
            install("cffi==1.15.1")
            install("Jinja2==3.1.6")
            install("MarkupSafe==3.0.3")
            install("tornado==6.5.5")
            install("streaming-form-data==1.19.1")
            install("distro==1.9.0")
            install("inotify-simple==2.0.1")
            install("importlib_metadata==8.7.0")
        }
        extractPackages("klipper_vendor")
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    testImplementation("junit:junit:4.13.2")
}
