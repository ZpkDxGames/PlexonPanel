plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.112-stable")
}

tasks.jar {
    archiveBaseName.set("PlexonChats-Integration-API")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
