// BRouter routing engine, vendored from https://github.com/abrensch/brouter (MIT, see LICENSE and README.md).
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:none")
    options.isWarnings = false
}
