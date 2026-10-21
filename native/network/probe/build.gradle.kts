plugins { id("com.android.application") version "8.13.2" }

val stageProductionJava by tasks.registering(Sync::class) {
    from("../../../app/src/main/java") { include("ca/psiphon/Tun2SocksJniLoader.java") }
    into(layout.buildDirectory.dir("generated/productionJava"))
}
android {
    namespace = "com.molido.vpn.networkprobe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "26.3.11579264"
    defaultConfig {
        applicationId = "com.molido.vpn.networkprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "local-packet-fixtures"
        testInstrumentationRunner = "com.molido.vpn.networkprobe.NetworkProbe"
        ndk { abiFilters += "x86_64" }
    }
    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/productionJava"))
    externalNativeBuild {
        cmake {
            path = file("../../../app/src/main/cpp/badvpn/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.named("preBuild").configure { dependsOn(stageProductionJava) }
