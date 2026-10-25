plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.encryptedenergy.sdk.sample"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.encryptedenergy.sdk.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // The one dependency that matters: the SDK, consumed exactly the way a
    // partner consumes the published AAR. Deliberately nothing else — no
    // androidx, no Compose — so this module doubles as proof that the SDK
    // imposes zero transitive baggage on a host.
    implementation(project(":sdk"))
}
