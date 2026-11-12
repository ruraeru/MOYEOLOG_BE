package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Slf4j
public class FileService {

    public static final String UPLOAD_URL_PREFIX = "/uploads/";

    /** 업로드 디렉터리 바로 아래의 평범한 파일 이름만 허용 (경로 구분자, 선행 점 불가) */
    private static final Pattern PLAIN_FILE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$");

    private static final int HEADER_LENGTH = 12;

    /** 파일 내용(매직 바이트)으로 판별한 이미지 종류 */
    public enum ImageType {
        JPEG("image/jpeg", ".jpg"),
        PNG("image/png", ".png"),
        GIF("image/gif", ".gif"),
        WEBP("image/webp", ".webp");

        private final String mimeType;
        private final String extension;

        ImageType(String mimeType, String extension) {
            this.mimeType = mimeType;
            this.extension = extension;
        }

        public String getMimeType() {
            return mimeType;
        }

        public String getExtension() {
            return extension;
        }
    }

    public record ImageData(String mimeType, String base64) {
    }

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    /**
     * 이미지를 저장하고 저장된 파일 이름을 반환합니다.
     * 클라이언트가 보낸 파일 이름과 Content-Type 은 신뢰하지 않으며,
     * 파일 내용으로 종류(JPEG/PNG/GIF/WEBP)를 판별해 확장자를 정합니다.
     */
    public String storeFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("업로드할 파일이 비어 있습니다.");
        }

        try (InputStream in = file.getInputStream()) {
            byte[] header = in.readNBytes(HEADER_LENGTH);
            ImageType type = detectImageType(header)
                    .orElseThrow(() -> new BadRequestException("JPEG, PNG, GIF, WEBP 이미지만 업로드할 수 있습니다."));

            Path root = uploadRoot();
            Files.createDirectories(root);

            String fileName = UUID.randomUUID() + type.getExtension();
            Path targetPath = root.resolve(fileName);
            try (OutputStream out = Files.newOutputStream(targetPath, StandardOpenOption.CREATE_NEW)) {
                out.write(header);
                in.transferTo(out);
            }
            return fileName;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store file", e);
        }
    }

    /** 매직 바이트로 이미지 종류를 판별합니다. */
    public static Optional<ImageType> detectImageType(byte[] header) {
        if (header == null) {
            return Optional.empty();
        }
        if (startsWith(header, 0, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return Optional.of(ImageType.JPEG);
        }
        if (startsWith(header, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return Optional.of(ImageType.PNG);
        }
        if (startsWith(header, 0, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(header, 0, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of(ImageType.GIF);
        }
        if (startsWith(header, 0, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && startsWith(header, 8, "WEBP".getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of(ImageType.WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (data.length < offset + prefix.length) {
            return false;
        }
        return Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
    }

    /** "/uploads/<파일 이름>" 형태이고 파일 이름이 평범한 이름인지 확인합니다. */
    public static boolean isUploadUrl(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(UPLOAD_URL_PREFIX)) {
            return false;
        }
        String fileName = imageUrl.substring(UPLOAD_URL_PREFIX.length());
        return PLAIN_FILE_NAME.matcher(fileName).matches() && !fileName.contains("..");
    }

    /**
     * "/uploads/<파일 이름>" 을 업로드 디렉터리 안의 실제 경로로 바꿉니다.
     * 형식이 다르거나 정규화한 경로가 업로드 디렉터리를 벗어나면 empty 입니다.
     */
    private Optional<Path> resolveUploadedFile(String imageUrl) {
        if (!isUploadUrl(imageUrl)) {
            return Optional.empty();
        }
        try {
            Path root = uploadRoot();
            Path resolved = root.resolve(imageUrl.substring(UPLOAD_URL_PREFIX.length())).normalize();
            if (!resolved.startsWith(root) || resolved.equals(root)) {
                return Optional.empty();
            }
            if (Files.exists(resolved)) {
                // 심볼릭 링크 등을 따라간 실제 경로도 업로드 디렉터리 안이어야 한다.
                Path realRoot = root.toRealPath();
                if (!resolved.toRealPath().startsWith(realRoot)) {
                    return Optional.empty();
                }
            }
            return Optional.of(resolved);
        } catch (InvalidPathException | IOException e) {
            return Optional.empty();
        }
    }

    private Path uploadRoot() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    /** 업로드된 이미지를 읽어 종류와 Base64 내용을 반환합니다. 업로드 디렉터리 밖의 경로나 이미지가 아닌 파일은 empty 입니다. */
    public Optional<ImageData> loadImage(String imageUrl) {
        Optional<Path> path = resolveUploadedFile(imageUrl);
        if (path.isEmpty() || !Files.isRegularFile(path.get())) {
            return Optional.empty();
        }
        try {
            byte[] content = Files.readAllBytes(path.get());
            return detectImageType(content)
                    .map(type -> new ImageData(type.getMimeType(), Base64.getEncoder().encodeToString(content)));
        } catch (IOException e) {
            log.warn("Could not read uploaded file: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public String getFileAsBase64(String imageUrl) {
        return loadImage(imageUrl).map(ImageData::base64).orElse(null);
    }

    /**
     * 업로드된 파일을 삭제합니다. "/uploads/<파일 이름>" 형태가 아니면(외부 URL, data URL 등) 아무 것도 하지 않습니다.
     *
     * @return 실제로 파일을 삭제했으면 true
     */
    public boolean deleteFile(String imageUrl) {
        Optional<Path> path = resolveUploadedFile(imageUrl);
        if (path.isEmpty()) {
            return false;
        }
        try {
            return Files.isRegularFile(path.get()) && Files.deleteIfExists(path.get());
        } catch (IOException e) {
            log.warn("Could not delete uploaded file {}: {}", path.get().getFileName(), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 현재 트랜잭션이 커밋된 뒤에 파일을 삭제합니다 (롤백되면 파일을 남겨 둠).
     * 트랜잭션 밖에서 호출하면 즉시 삭제합니다.
     */
    public void deleteFileAfterCommit(String imageUrl) {
        if (!isUploadUrl(imageUrl)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteFile(imageUrl);
                }
            });
        } else {
            deleteFile(imageUrl);
        }
    }
}
