package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileServiceTest {

    private static final byte[] PNG = concat(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, "rest-of-png".getBytes());
    private static final byte[] JPEG = concat(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, "rest-of-jpeg".getBytes());
    private static final byte[] GIF = "GIF89a-rest-of-gif".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP = concat("RIFF".getBytes(), new byte[]{1, 2, 3, 4}, "WEBPVP8 ".getBytes());

    @TempDir
    Path tempDir;

    private Path uploadDir;
    private FileService fileService;

    @BeforeEach
    void setUp() throws Exception {
        uploadDir = Files.createDirectory(tempDir.resolve("uploads"));
        fileService = new FileService();
        ReflectionTestUtils.setField(fileService, "uploadDir", uploadDir.toString());
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    @Test
    @DisplayName("매직 바이트로 종류를 판별하고 확장자는 판별 결과로 정한다 (클라이언트 파일 이름/Content-Type 무시)")
    void storeFile_DetectsTypeFromContent() throws Exception {
        // 파일 이름과 Content-Type 은 거짓이지만 내용은 PNG
        String name = fileService.storeFile(new MockMultipartFile("image", "../../evil.html", "text/html", PNG));

        assertThat(name).matches("[0-9a-f-]{36}\\.png");
        assertThat(Files.readAllBytes(uploadDir.resolve(name))).isEqualTo(PNG);
        // 업로드 디렉터리 밖에는 아무 것도 만들어지지 않는다
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("uploads");
        }

        assertThat(fileService.storeFile(new MockMultipartFile("image", "a.png", "image/png", JPEG))).endsWith(".jpg");
        assertThat(fileService.storeFile(new MockMultipartFile("image", "a.exe", "image/png", GIF))).endsWith(".gif");
        assertThat(fileService.storeFile(new MockMultipartFile("image", null, null, WEBP))).endsWith(".webp");
    }

    @Test
    @DisplayName("이미지가 아닌 내용은 Content-Type/확장자가 이미지여도 400")
    void storeFile_RejectsNonImages() throws Exception {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes();
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes();
        byte[] riffButNotWebp = concat("RIFF".getBytes(), new byte[]{1, 2, 3, 4}, "WAVEfmt ".getBytes());

        assertThatThrownBy(() -> fileService.storeFile(new MockMultipartFile("image", "a.png", "image/png", html)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fileService.storeFile(new MockMultipartFile("image", "a.svg", "image/svg+xml", svg)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fileService.storeFile(new MockMultipartFile("image", "a.webp", "image/webp", riffButNotWebp)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fileService.storeFile(new MockMultipartFile("image", "a.png", "image/png", new byte[]{(byte) 0x89, 'P'})))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fileService.storeFile(new MockMultipartFile("image", "a.png", "image/png", new byte[0])))
                .isInstanceOf(BadRequestException.class);

        try (var files = Files.list(uploadDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("getFileAsBase64 는 업로드 디렉터리 안의 이미지만 읽는다")
    void getFileAsBase64_ConfinedToUploadDir() throws Exception {
        String name = fileService.storeFile(new MockMultipartFile("image", "a.png", "image/png", PNG));
        Path secret = Files.write(tempDir.resolve("secret.png"), PNG);
        Files.createDirectory(uploadDir.resolve("sub"));
        Files.write(uploadDir.resolve("sub").resolve("inner.png"), PNG);

        assertThat(fileService.getFileAsBase64("/uploads/" + name)).isEqualTo(Base64.getEncoder().encodeToString(PNG));
        assertThat(fileService.loadImage("/uploads/" + name)).get()
                .extracting(FileService.ImageData::mimeType).isEqualTo("image/png");

        assertThat(fileService.getFileAsBase64("/uploads/../secret.png")).isNull();
        assertThat(fileService.getFileAsBase64("/uploads/../../etc/passwd")).isNull();
        assertThat(fileService.getFileAsBase64("/uploads/sub/inner.png")).isNull();
        assertThat(fileService.getFileAsBase64("../secret.png")).isNull();
        assertThat(fileService.getFileAsBase64(secret.toString())).isNull();
        assertThat(fileService.getFileAsBase64("/etc/passwd")).isNull();
        assertThat(fileService.getFileAsBase64("/uploads/")).isNull();
        assertThat(fileService.getFileAsBase64("/uploads/missing.png")).isNull();
        assertThat(fileService.getFileAsBase64("data:image/png;base64,AAAA")).isNull();
        assertThat(fileService.getFileAsBase64(null)).isNull();
        assertThat(fileService.getFileAsBase64("")).isNull();
    }

    @Test
    @DisplayName("업로드 디렉터리 안의 심볼릭 링크로 바깥 파일을 읽을 수 없다")
    void getFileAsBase64_SymlinkEscape() throws Exception {
        Path secret = Files.write(tempDir.resolve("secret.png"), PNG);
        Files.createSymbolicLink(uploadDir.resolve("link.png"), secret);

        assertThat(fileService.getFileAsBase64("/uploads/link.png")).isNull();
        assertThat(fileService.deleteFile("/uploads/link.png")).isFalse();
        assertThat(secret).exists();
    }

    @Test
    @DisplayName("이미지가 아닌 파일은 업로드 디렉터리 안에 있어도 Base64 로 내보내지 않는다")
    void getFileAsBase64_NonImageContent() throws Exception {
        Files.write(uploadDir.resolve("notes.txt"), "plain text".getBytes());
        assertThat(fileService.getFileAsBase64("/uploads/notes.txt")).isNull();
    }

    @Test
    @DisplayName("deleteFile 은 업로드 디렉터리 안의 파일만 삭제한다")
    void deleteFile_ConfinedToUploadDir() throws Exception {
        String name = fileService.storeFile(new MockMultipartFile("image", "a.png", "image/png", PNG));
        Path secret = Files.write(tempDir.resolve("secret.png"), PNG);

        assertThat(fileService.deleteFile("/uploads/../secret.png")).isFalse();
        assertThat(fileService.deleteFile(secret.toString())).isFalse();
        assertThat(fileService.deleteFile("https://k.kakaocdn.net/profile.jpg")).isFalse();
        assertThat(fileService.deleteFile(null)).isFalse();
        assertThat(secret).exists();

        assertThat(fileService.deleteFile("/uploads/" + name)).isTrue();
        assertThat(uploadDir.resolve(name)).doesNotExist();
        assertThat(fileService.deleteFile("/uploads/" + name)).isFalse();

        // 트랜잭션 밖에서는 즉시 삭제
        String another = fileService.storeFile(new MockMultipartFile("image", "a.png", "image/png", PNG));
        fileService.deleteFileAfterCommit("/uploads/" + another);
        assertThat(uploadDir.resolve(another)).doesNotExist();
    }
}
