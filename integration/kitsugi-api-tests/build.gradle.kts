plugins {
    kotlin("jvm") version "2.2.10"
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDir("../kitsugi/src/main/kotlin")
            kotlin.include("**/GolgeBahcesiApi.kt")
        }
        test {
            kotlin.srcDir("../kitsugi/src/test/kotlin")
            kotlin.include("**/GolgeBahcesiApiTest.kt")
        }
    }
}

sourceSets.test { resources.srcDir("../kitsugi/src/test/resources") }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

tasks.test { useJUnit() }
