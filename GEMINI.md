# Moyelog BE - Project Documentation & History

This file documents the foundational setup, architectural decisions, and deployment procedures for the Moyelog Backend project.

## 1. Project Overview
- **Framework:** Spring Boot 4.0.6
- **Language:** Java 25 (Eclipse Temurin)
- **Database:** MySQL 8.0
- **Build Tool:** Gradle
- **Containerization:** Docker & Docker Compose

## 2. Work History & Resolved Issues

### Phase 1: Initial Setup & Bug Fixes
- **Issue:** Application terminated immediately after startup.
  - **Cause:** Missing web dependencies; the application had nothing to keep the process alive.
  - **Resolution:** Added `spring-boot-starter-web` to `build.gradle`.
- **Issue:** Local startup failure due to Database connection.
  - **Cause:** MySQL 8.0 security restriction (`Public Key Retrieval is not allowed`).
  - **Resolution:** Appended `&allowPublicKeyRetrieval=true` to the JDBC URL in `application.yml`.
- **Issue:** Compilation errors during Docker build.
  - **Cause:** Missing imports in `JwtAuthenticationFilter.java` (Jakarta Servlet and Lombok).
  - **Resolution:** Added required imports for `HttpServletRequest`, `HttpServletResponse`, `FilterChain`, `ServletException`, and `RequiredArgsConstructor`.

### Phase 2: Containerization (Docker)
- **Dockerfile:** Implemented a multi-stage build to minimize image size.
  - Stage 1: Build using JDK 25.
  - Stage 2: Runtime using JRE 25.
- **Docker Compose:** Orchestrated the application and database.
  - Configured `db` (MySQL) and `app` (Spring Boot) services.
  - Implemented **Health Checks** to ensure the DB is ready before the app starts.
  - **Exposed Ports:** 8080 (App) and 3306 (DB) for external access.
  - **Database Name:** Updated from `moyelog` to `moyeolog` as per user request.

### Phase 3: Nginx & SSL (HTTPS)
- **Nginx:** Added as a reverse proxy to handle incoming traffic on ports 80 and 443.
- **Let's Encrypt:** Integrated using Certbot for automated SSL certificate issuance and renewal.
- **Security:** The Spring Boot application (`app` service) is no longer exposed directly to the internet; all traffic must pass through Nginx.
- **Initialization:** Created `init-letsencrypt.sh` to automate the first-time SSL setup.

### Phase 4: Feature Enhancements
- **Schedule Update:**
- Implemented `PUT /api/schedules/{id}` for schedule modification.
- Added strict author validation in `ScheduleService` to ensure only owners can update or delete schedules.

## 3. Deployment Guide

### Running with Docker & Nginx (Recommended)
1. **Initialize SSL Certificates (First time only):**
   Ensure you have a bash-compatible environment (like Git Bash on Windows) and run:
   ```bash
   chmod +x init-letsencrypt.sh
   ./init-letsencrypt.sh
   ```
   *Note: This script will prompt you to confirm the domain and email.*

2. **Start the environment:**
   ```bash
   docker compose up -d
   ```

3. **Check logs:**
   ```bash
   docker compose logs -f
   ```

### Running Locally (Development)
1. Ensure local MySQL is running.
2. Run the application: `./gradlew bootRun`.
3. Note: Ensure Docker is NOT using port 3306 or 8080 to avoid conflicts.

## 4. Connection Information (Docker Environment)
- **Domain:** `https://moyeolog.kro.kr` (Proxy to `app:8080`)
- **API Server (Internal):** `http://app:8080`
- **MySQL Database:** `localhost:3306`
  - **Database Name:** `moyeolog`
  - **Username:** `root`
  - **Password:** `rootpassword`

## 5. Future Roadmap
- **CI/CD:** Implementation of GitHub Actions for automated building and deployment.
- **Image Persistence:** Integration of Docker Volumes or AWS S3 for persistent image storage.
- **Security:** Hardening Spring Security and JWT implementation.

---

## 📐 Development Conventions

### Commit Message Rule
- 모든 커밋 메시지는 **한글**로 작성합니다.
- `feat:`, `fix:`, `chore:`, `refactor:` 등의 접두어(Prefix)는 영문으로 유지하되, 이후 설명은 한글을 사용합니다.
  - 예: `feat: 모임 초대 API 구현`, `fix: 404 모델 미정의 오류 수정`
