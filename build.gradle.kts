// 선택 사항: 인터넷이 되는 환경에서 표준 방식으로 빌드할 때 쓴다.
// 오프라인이면 ./build.sh 쪽이 더 빠르고 결과물도 동일하다(모장 매핑 + 네임스페이스 마킹).
// paperweight-userdev 버전은 https://github.com/PaperMC/paperweight 최신 2.x 로 맞출 것.
plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.17"
}

group = "ghastraft"
version = "1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Purpur 서버지만 NMS/API 표면은 Paper 와 동일하므로 paper devBundle 로 컴파일한다.
    paperweight.paperDevBundle("1.21.8-R0.1-SNAPSHOT")
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

tasks.compileJava {
    options.encoding = "UTF-8"
}

// 빌드 후 테스트 서버에 바로 설치
tasks.register<Copy>("install") {
    dependsOn(tasks.jar)
    from(tasks.jar)
    into(file("C:/Users/hamst/Downloads/GCBServer/TestSVR/plugins"))
    rename { "GhastRaft.jar" }
}
