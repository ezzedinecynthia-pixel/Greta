plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.greta"
    compileSdk = 34
    defaultConfig { applicationId = "com.example.greta"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
