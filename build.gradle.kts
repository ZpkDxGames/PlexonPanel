import org.gradle.api.tasks.bundling.Zip

plugins {
    base
}

group = "io.github.zpkdxgames"
version = "0.1.0"

allprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register<Zip>("releaseBundle") {
    group = "distribution"
    description = "Builds a GitHub-ready source and binary release bundle."
    dependsOn(":agent:jar", ":agent:test")

    archiveBaseName.set("PlexonPanel")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("release")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(layout.projectDirectory) {
        exclude(
            ".gradle/**",
            ".git/**",
            "**/build/**",
            "gradle/distribution/**",
            ".idea/**",
            "mock-gateway/.gateway-key.json",
            "mock-gateway/node_modules/**",
            "*.iml",
            "PlexonPanel-*.zip"
        )
        into("PlexonPanel-${project.version}")
    }

    from(layout.projectDirectory.dir("gradle/distribution")) {
        into("PlexonPanel-${project.version}")
        rename { sourceName -> "." + sourceName.removeSuffix(".txt") }
    }

    from(project(":agent").layout.buildDirectory.file("libs/PlexonPanel-${project.version}.jar")) {
        into("PlexonPanel-${project.version}/release")
    }

    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
