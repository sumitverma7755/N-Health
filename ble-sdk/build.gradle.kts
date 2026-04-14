plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = "com.nhealth"
version = "0.1.1"

android {
    namespace = "com.nhealth.blesdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.nhealth"
                artifactId = "nhealth-ble-sdk"
                version = project.version.toString()

                pom {
                    name.set("N Health BLE SDK")
                    description.set("Utility SDK for BLE scan snapshots and GATT value decoding.")
                    url.set("https://github.com/sumitverma7755/N-Health")
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/sumitverma7755/N-Health")

                credentials {
                    username = (findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
                    password = (findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
