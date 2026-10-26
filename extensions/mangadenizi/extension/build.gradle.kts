plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
val androidJar = providers.environmentVariable("ANDROID_JAR")
val androidStubs by configurations.creating
configurations.compileOnly { extendsFrom(androidStubs) }
configurations.testCompileOnly { extendsFrom(androidStubs) }
dependencies {
    compileOnly(project(":host-contract"))
    if (androidJar.isPresent) androidStubs(files(androidJar.get()))
    else androidStubs("com.google.android:android:4.1.1.4")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    compileOnly("org.jsoup:jsoup:1.18.3")
    compileOnly("org.json:json:20240303")
    testImplementation(project(":host-contract"))
    testImplementation("org.jetbrains.compose.runtime:runtime-desktop:1.7.3")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.jsoup:jsoup:1.18.3")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { useJUnit() }
tasks.register("exportClasspath") {
    dependsOn(":host-contract:jar", "jar")
    doLast {
        val jars = configurations.compileClasspath.get().files.filter { it.extension == "jar" }
        layout.buildDirectory.file("dex-classpath.txt").get().asFile.writeText(jars.joinToString("\n") { it.absolutePath } + "\n")
    }
}
