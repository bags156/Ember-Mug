plugins {
    id("com.android.application")
}

// Release builds are made by .github/workflows/release.yml, which passes the version from the
// git tag and the signing key from repository secrets. Nothing secret lives in this repository.
val releaseVersion: String? = System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() }
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

/** "1.2.3" -> 10203, so a newer tag always installs over an older one. */
fun versionCodeOf(version: String): Int {
    val parts = version.split(".").map { it.toInt() }
    require(parts.size == 3 && parts.all { it in 0..99 }) { "Version must look like 1.2.3, got $version" }
    return parts[0] * 10000 + parts[1] * 100 + parts[2]
}

android {
    namespace = "io.github.bags156.embermug"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.bags156.embermug"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersion?.let { versionCodeOf(it) } ?: 1
        versionName = releaseVersion ?: "0.1.0"
    }

    signingConfigs {
        if (signingKeystore != null) {
            create("release") {
                storeFile = file(signingKeystore)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = "embermug"
                keyPassword = System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the key (any build outside the release workflow) the release APK is unsigned.
            signingConfig = signingConfigs.findByName("release")
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
