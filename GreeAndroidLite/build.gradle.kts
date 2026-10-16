plugins { id("com.android.application") version "9.4.1" }

android {
    namespace = "com.greeac"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.greeac"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
