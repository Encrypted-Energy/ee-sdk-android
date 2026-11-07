plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.encryptedenergy.sdk"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
        // No targetSdk: a library inherits the host app's target. Setting one
        // here would be ignored by the consumer and only lie about our reach.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // One publishable variant, sources attached so partners can read the KDoc
    // contracts (the API docs ARE the product) straight from their IDE.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Zero runtime dependencies is the pitch: the AAR pulls in nothing but the
    // Kotlin stdlib the host already ships. junit is test-only and never
    // leaves the test source set, so it is not transitive to consumers.
    testImplementation(libs.junit)
}

group = "com.encryptedenergy"
version = "0.2.0"

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.encryptedenergy"
            artifactId = "gateway-sdk"
            version = "0.1.0"
            // The release component only exists after AGP configures it.
            afterEvaluate {
                from(components["release"])
            }
        }
    }
    repositories {
        // GitHub Packages. URL and credentials come from the environment so
        // nothing secret is committed; the default URL is public info, not a
        // secret, and only keeps a bare Studio sync from failing.
        maven {
            name = "GitHubPackages"
            url = uri(
                System.getenv("GITHUB_PACKAGES_URL")
                    ?: "https://maven.pkg.github.com/encrypted-energy/ee-sdk-android"
            )
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
