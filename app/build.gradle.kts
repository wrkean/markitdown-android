plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.markitdown.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.markitdown.android"
        minSdk = 26          // Chaquopy 17 requires >= 24; 26 avoids legacy launcher icons
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // The Python interpreter is a native library: ABIs must be pinned.
    // `dev` keeps an emulator ABI for development; `prod` ships arm64 only,
    // which roughly halves the APK size for real devices.
    flavorDimensions += "abi"
    productFlavors {
        create("dev") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        create("prod") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a") }
        }
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

chaquopy {
    defaultConfig {
        // Python version must match the interpreter on the build machine.
        // If `python3.12` is not on your PATH, point buildPython at it, e.g.:
        //   buildPython("C:/Python312/python.exe")
        version = "3.12"

        pip {
            // ------------------------------------------------------------------
            // MarkItDown core dependencies (pure Python, offline-safe)
            // ------------------------------------------------------------------
            install("requests")
            install("beautifulsoup4")
            install("markdownify")
            install("charset-normalizer")
            install("defusedxml")

            // ------------------------------------------------------------------
            // Offline file-format support. Transitive dependencies (XlsxWriter,
            // Pillow, lxml, certifi, ...) are resolved automatically; every one
            // of them has an Android wheel in Chaquopy's repository.
            //
            // NOTE: pandas/numpy are deliberately NOT installed (they would add
            // ~30+ MB per ABI). .xlsx is handled by a small openpyxl-based
            // converter bundled in src/main/python (offline_converters.py), and
            // legacy .xls is not supported.
            // ------------------------------------------------------------------
            install("mammoth")            // .docx
            install("python-pptx")        // .pptx
            install("lxml")               // XML internals for pptx
            install("openpyxl")           // .xlsx (pure Python)
            install("pdfminer.six")       // .pdf  (pure-Python text extraction)
            install("olefile")            // Outlook .msg
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
