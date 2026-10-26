plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    compileOnly(libs.json)
    testImplementation(libs.json)
    testImplementation(libs.junit)
}
