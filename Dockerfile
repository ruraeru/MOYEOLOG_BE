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

# 루트가 아닌 전용 사용자(uid/gid 1001)로 실행한다.
# 업로드 디렉터리(/app/uploads)는 이 사용자 소유로 미리 만들어 둔다.
# (볼륨을 마운트하는 경우 볼륨 안의 기존 파일도 1001:1001 소유여야 쓸 수 있다.)
RUN addgroup -S -g 1001 moyeolog \
    && adduser -S -D -H -u 1001 -G moyeolog moyeolog \
    && mkdir -p /app/uploads \
    && chown -R 1001:1001 /app

COPY --from=build --chown=1001:1001 /app/build/libs/*.jar app.jar

USER 1001:1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
