plugins { application }
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
dependencies { implementation("org.smali:dexlib2:2.5.2")
    implementation("com.google.guava:guava:33.4.8-jre") }
application { mainClass = "FixLoaderForTest" }

sourceSets.main { java.srcDir("../kitsugi-mex/src/main/java") }
dependencies { compileOnly(files(providers.gradleProperty("hostApiJar"))) }

dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { useJUnit() }
