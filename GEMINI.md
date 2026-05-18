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

## 3. Deployment Guide

### Running with Docker (Recommended)
To build and start the entire environment (App + DB):
```bash
docker compose up --build -d
```

To stop and remove containers:
```bash
docker compose down
```

To reset the database (delete all data and volumes):
```bash
docker compose down -v
```

### Running Locally (Development)
1. Ensure local MySQL is running: `brew services start mysql`.
2. Run the application: `./gradlew bootRun`.
3. Note: Ensure Docker is NOT using port 3306 to avoid conflicts.

## 4. Connection Information (Docker Environment)
- **API Server:** `http://localhost:8080`
- **MySQL Database:** `localhost:3306`
  - **Database Name:** `moyeolog`
  - **Username:** `root`
  - **Password:** `rootpassword`

## 5. Future Roadmap
- **CI/CD:** Implementation of GitHub Actions for automated building and deployment.
- **Image Persistence:** Integration of Docker Volumes or AWS S3 for persistent image storage.
- **Security:** Hardening Spring Security and JWT implementation.
