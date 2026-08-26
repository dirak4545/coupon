plugins {
	kotlin("jvm") version "2.4.0"
	kotlin("plugin.spring") version "2.4.0"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7" //의존성을 자동 관리
	kotlin("plugin.jpa") version "2.4.0"
	id("com.google.cloud.tools.jib") version "3.5.4"
}

group = "com.apiece"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(26)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	runtimeOnly("com.mysql:mysql-connector-j")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("io.mockk:mockk:1.13.13")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")// 기본적으로 닫혀 있지만 이렇게 열어주어서 상속, 프록시 기술 사용가능
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

jib {
	from {
		image = "eclipse-temurin:26-jre"
		platforms {
			platform {
				architecture = "arm64"
				os = "linux"
			}
		}
	}
	to {
		image = "coupon-service:latest"
		tags = setOf("latest", project.version.toString())
	}
	container {
		ports = listOf("8080")
		creationTime = "USE_CURRENT_TIMESTAMP"
	}
}
