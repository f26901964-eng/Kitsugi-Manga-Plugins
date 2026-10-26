plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    // The unmodified host MangaModels source uses mutableStateOf, not UI APIs.
    // This compile-only JVM variant is never included in the extension DEX.
    compileOnly("org.jetbrains.compose.runtime:runtime-desktop:1.7.3")
}
