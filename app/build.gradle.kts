plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "dev.nozzlenaut.androidklipper"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.nozzlenaut.androidklipper"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-poc"

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
        }
        extractPackages("klipper_vendor")
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    testImplementation("junit:junit:4.13.2")
}
