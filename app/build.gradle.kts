plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.bags156.embermug"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.bags156.embermug"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // No runtime dependencies. Tests only.
    testImplementation("junit:junit:4.13.2")
}
