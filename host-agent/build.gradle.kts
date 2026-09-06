plugins { application }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
dependencies {
    implementation(project(":protocol"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
application { mainClass.set("io.github.zpkdxgames.plexonpanel.host.HostMain") }
tasks.test { useJUnitPlatform() }
tasks.jar {
    dependsOn(":protocol:jar")
    archiveBaseName.set("plexonpanel-host")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) { into("META-INF") }
    from(rootProject.layout.projectDirectory.file("licenses/Apache-2.0.txt")) {
        into("META-INF/licenses")
        rename { "gson-Apache-2.0.txt" }
    }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    manifest { attributes("Main-Class" to application.mainClass.get(), "Implementation-Version" to project.version) }
}
