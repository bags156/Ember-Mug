plugins {
    id("com.android.application")
}

// Release builds are made by .github/workflows/release.yml, which passes the version from the
// git tag.
val releaseVersion: String? = System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() }

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

    // The signing key is deliberately public and committed (signing/README.md explains the
    // trade-off). Every build, debug or release, from any machine, is signed the same way, so any
    // build installs over any other.
    signingConfigs {
        create("open") {
            storeFile = rootProject.file("signing/public-release.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "embermug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("open")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("open")
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
