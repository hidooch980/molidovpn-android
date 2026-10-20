plugins { id("com.android.application") version "8.13.2" }

val probeAbi = providers.gradleProperty("probeAbi").getOrElse("x86_64")
require(probeAbi in listOf("x86_64", "arm64-v8a", "armeabi-v7a"))
android {
    namespace = "com.molido.vpn.nativeprobe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.molido.vpn.nativeprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "native-inspection-only"
        testInstrumentationRunner = "com.molido.vpn.nativeprobe.NativeProbe"
        ndk { abiFilters += probeAbi }
    }
    sourceSets {
        getByName("main") {
            java.srcDir("../../../app/src/main/java")
            java.include("com/molido/vpn/OpenVpnCore.java", "com/molido/vpn/VpnProfilePreflight.java")
            jniLibs.srcDir("jniLibs")
            assets.srcDir("assets")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
