plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Android 11 is API 30. minSdk 26 stays so older supported phones are not dropped.
// Override with -PaivaMinSdk=24 only for a deliberate compatibility build.
val aivaMinSdk = (project.findProperty("aivaMinSdk") as String?)?.toInt() ?: 26

android {
    namespace = "com.tanveer.aiva"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tanveer.aiva"
        minSdk = aivaMinSdk
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            // Minification stays off. A previous CodeAssist/R8 toolchain crashed at runtime on
            // Android 11 with InputStream.readAllBytes() (API 33) after shrinking. This sandbox
            // has no Android SDK, so a minified release cannot be smoke-tested here. Do not
            // enable isMinifyEnabled until a minified build is installed on API 30 and API 36.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
