# Build stage
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app

# 1. 빌드 설정 파일만 먼저 복사
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

# 실행 권한 부여
RUN chmod +x ./gradlew

# 2. 의존성 미리 다운로드 (소스가 변경되어도 라이브러리는 다시 받지 않도록 캐싱)
# BuildKit 캐시를 사용하여 다운로드 속도 극대화 및 --no-daemon으로 메모리 절약
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon

# 3. 소스 코드 복사
COPY src src

# 4. 애플리케이션 빌드
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar -x test --no-daemon --parallel

# Runtime stage
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]