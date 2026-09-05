plugins { `java-library` }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
dependencies {
    api("com.google.code.gson:gson:2.14.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
sourceSets.test { resources.srcDir("test-fixtures") }
tasks.test { useJUnitPlatform() }
