plugins { application }
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
dependencies { implementation("org.smali:dexlib2:2.5.2") }
application { mainClass = "FixLoaderForTest" }

sourceSets.main { java.srcDir("../kitsugi-mex/src/main/java") }
dependencies { compileOnly(files(providers.gradleProperty("hostApiJar"))) }
