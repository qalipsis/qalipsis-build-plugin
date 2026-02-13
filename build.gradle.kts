/*
 * QALIPSIS
 * Copyright (C) 2025 AERIS IT Solutions GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

import org.jetbrains.kotlin.gradle.plugin.extraProperties
import org.jreleaser.model.Active
import org.jreleaser.model.Signing
import org.jreleaser.model.api.deploy.maven.MavenCentralMavenDeployer

plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "1.9.25"
    `maven-publish`
    id("org.jreleaser") version "1.18.0"
}

group = "io.qalipsis"
version = property("version") as String

description = "Gradle Plugin to build QALIPSIS modules"

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        register("qalipsis-build") {
            id = "io.qalipsis.build"
            implementationClass = "io.qalipsis.gradle.build.QalipsisBuildPlugin"
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}

java {
    withJavadocJar()
    withSourcesJar()
}

jreleaser {
    gitRootSearch.set(true)

    release {
        // One least one enabled release provider is mandatory, so let's use Github and disable
        // all the options.
        github {
            skipRelease.set(true)
            skipTag.set(true)
            uploadAssets.set(Active.NEVER)
            token.set("dummy")
        }
    }

    val enableSign = !extraProperties.has("qalipsis.sign") || extraProperties.get("qalipsis.sign") != "false"
    if (enableSign) {
        signing {
            active.set(Active.ALWAYS)
            mode.set(Signing.Mode.MEMORY)
            armored = true
        }
    }

    deploy {
        maven {
            mavenCentral {
                register("qalipsis-releases") {
                    active.set(Active.RELEASE_PRERELEASE)
                    namespace.set("io.qalipsis")
                    applyMavenCentralRules.set(true)
                    stage.set(MavenCentralMavenDeployer.Stage.UPLOAD)
                    stagingRepository(layout.buildDirectory.dir("staging-deploy").get().asFile.path)
                }
            }
            nexus2 {
                register("qalipsis-snapshots") {
                    active.set(Active.SNAPSHOT)
                    // Here we are using our own repository, because the maven central snapshot repo
                    // is too often not available.
                    url.set("https://maven.qalipsis.com/repository/oss-snapshots/")
                    snapshotUrl.set("https://maven.qalipsis.com/repository/oss-snapshots/")
                    applyMavenCentralRules.set(true)
                    verifyPom.set(false)
                    snapshotSupported.set(true)
                    closeRepository.set(true)
                    releaseRepository.set(true)
                    stagingRepository(layout.buildDirectory.dir("staging-deploy").get().asFile.path)
                }
            }
        }
    }
}

// https://jreleaser.org/guide/latest/examples/maven/maven-central.html#_gradle
publishing {
    repositories {
        maven {
            // Local repository to store the artifacts before they are released by JReleaser.
            name = "PreRelease"
            setUrl(rootProject.layout.buildDirectory.dir("staging-deploy"))
        }
    }
}


project.afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {

                    name.set(project.name)
                    description.set(project.description)

                    if (version.toString().endsWith("-SNAPSHOT")) {
                        this.withXml {
                            this.asNode().appendNode("distributionManagement").appendNode("repository").apply {
                                this.appendNode("id", "qalipsis-oss-snapshots")
                                this.appendNode("name", "QALIPSIS OSS Snapshots")
                                this.appendNode("url", "https://maven.qalipsis.com/repository/oss-snapshots")
                            }
                        }
                    }
                    url.set("https://qalipsis.io")
                    licenses {
                        license {
                            name.set("GNU AFFERO GENERAL PUBLIC LICENSE, Version 3 (AGPL-3.0)")
                            url.set("http://opensource.org/licenses/AGPL-3.0")
                        }
                    }
                    developers {
                        developer {
                            id.set("ericjesse")
                            name.set("Eric Jessé")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/qalipsis/qalipsis-build-plugin.git")
                        url.set("https://github.com/qalipsis/qalipsis-build-plugin.git/")
                    }
                }
            }
        }
    }
}