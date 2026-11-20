import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
	java
	kotlin("jvm") version "2.2.0" apply false
	id("org.jetbrains.dokka") version "2.1.0" apply false
	id("org.jetbrains.dokka-javadoc") version "2.1.0" apply false
}
val jvmVersion = project.property("jvm_version") as String

val version = project.property("version") as String
val rootGroupId = project.property("group_id") as String
group = rootGroupId
description = "基于 Netty 与 Kotlin 协程的 Web 服务器及 Jakarta Servlet 容器（父项目）"



subprojects {
	plugins.apply("idea")
	plugins.apply("java")
	plugins.apply("maven-publish")
	plugins.apply("signing")
	plugins.apply("org.jetbrains.kotlin.jvm")
	plugins.apply("org.jetbrains.dokka")
	plugins.apply("org.jetbrains.dokka-javadoc")
	repositories {
		maven { setUrl("https://repository.mingliqiye.com/repository/maven-public/") }
	}
	dependencies {
		"implementation"("com.mingliqiye.logger:logger-slf4j-api:1.0.3-S2.0.20")
		"testImplementation"(kotlin("test"))
		"testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
		"testImplementation"("org.apache.logging.log4j:log4j-slf4j2-impl:2.26.1")
		"testImplementation"("org.bouncycastle:bcpkix-jdk18on:1.78.1")
		"testImplementation"("io.netty:netty-pkitesting:4.2.19.Final")
		"testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
	}

	the<SourceSetContainer>().named("test") {
		resources.srcDir(rootDir.resolve("test-log-resources"))
	}

	sourceSets {
		named("test") { resources.srcDir("test-log-resources") }
	}

	java.withSourcesJar()


	extensions.configure<JavaPluginExtension> {
		toolchain {
			languageVersion.set(JavaLanguageVersion.of(jvmVersion))
		}
	}
	extensions.configure<KotlinJvmProjectExtension> {
		jvmToolchain(jvmVersion.toInt())
	}
	extensions.configure<SourceSetContainer> {
		named("test") {
			resources {
				srcDir("test-log-resources")
			}
		}
	}
	tasks.register<Jar>("javaDocJar") {
		description = "javaDocJar"
		group = "build"
		archiveClassifier.set("javadoc")
		dependsOn("dokkaGeneratePublicationJavadoc")
		from(project.layout.buildDirectory.get().asFile.resolve("dokka/javadoc"))
	}
	tasks.register<Jar>("kotlinDocJar") {
		description = "kotlinDocJar"
		group = "build"
		archiveClassifier.set("kotlindoc")
		dependsOn("dokkaGeneratePublicationHtml")
		from(project.layout.buildDirectory.get().asFile.resolve("dokka/html"))
	}
	tasks.named<Test>("test") {
		useJUnitPlatform()
	}
	tasks.named("build") {
		dependsOn("javaDocJar", "kotlinDocJar")
	}

	tasks.withType<org.gradle.jvm.tasks.Jar> {
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
		from("LICENSE") { into("META-INF") }
		from("NOTICE") { into("META-INF") }
		manifest {
			attributes(
				mapOf(
					"Specification-Title" to project.name,
					"Specification-Version" to project.version,
					"Specification-Vendor" to "minglipro",
					"Specification-Build-Time" to LocalDateTime.now()
						.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSS")),
					"Specification-Package-Name" to project.group,
					"Specification-Package-Id" to rootGroupId,
					"Specification-Build-Number" to "1",
					"Specification-Build-OS" to System.getProperty("os.name"),
					"Specification-Build-Java" to System.getProperty("java.version"),
					"Specification-Build-Java-Version" to jvmVersion,
					"Specification-Build-Java-Vendor" to System.getProperty("java.vendor"),
					"Specification-Build-Java-Vendor-URL" to System.getProperty("java.vendor.url"),
					"Implementation-Title" to project.name,
					"Implementation-Version" to project.version,
					"Implementation-Package-Name" to project.group,
					"Implementation-Package-Id" to rootGroupId,
					"Implementation-Vendor" to "minglipro",
					"Implementation-Build-Time" to LocalDateTime.now()
						.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSS"))
				)
			)
		}
	}

	extensions.configure<PublishingExtension> {
		publications {
			create<MavenPublication>("mavenJava") {
				from(components["java"])
				artifact(tasks.named("javaDocJar"))
				artifact(tasks.named("kotlinDocJar"))
					tasks.findByName("fatjar")?.let {
						artifact(it)
				}
				groupId = rootGroupId
				group = project.group
				pom {
					url = "https://github.com/minglipro/netty-web-servlet"
					licenses {
						license {
							name = "The Apache License, Version 2.0"
							url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
						}
					}
					developers {
						developer {
							id = "minglipro"
							name = "mingli"
							email = "minglipro@163.com"
						}
					}
					scm {
						connection = "scm:git:https://github.com/minglipro/netty-web-servlet.git"
						developerConnection = "scm:git:https://git.mingliqiye.com:minglipro/netty-web-servlet.git"
						url = "https://github.com/minglipro/netty-web-servlet"
					}
				}
			}
		}
	}

	extensions.configure<SigningExtension> {
		val keyId = System.getenv("SIGNING_KEY_ID")
		project.ext.set("signing.keyId", keyId)
		useGpgCmd()
		sign(extensions.getByType(PublishingExtension::class.java).publications)
	}
}
