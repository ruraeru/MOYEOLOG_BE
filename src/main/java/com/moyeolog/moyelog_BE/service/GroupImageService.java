package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 모임·토픽 이미지 저장 도우미. 실제 저장은 FileService 공개 메서드만 쓴다.
 * - 저장 전에 매직 바이트를 먼저 검사한다 (해요체 오류).
 * - 트랜잭션 안에서 저장한 파일은 롤백되면 지운다 (고아 파일 방지).
 */
@Service
@RequiredArgsConstructor
public class GroupImageService {

    static final String INVALID_IMAGE_MESSAGE = "JPG, PNG, GIF, WEBP 이미지만 올릴 수 있어요.";

    private static final Pattern DATA_URL = Pattern.compile("^data:image/(png|jpeg|jpg|gif|webp);base64,", Pattern.CASE_INSENSITIVE);

    private final FileService fileService;

    public static boolean isDataUrl(String value) {
        return value != null && DATA_URL.matcher(value).lookingAt();
    }

    /** 업로드 파일이 이미지인지 저장 전에 확인한다. 비어 있으면 아무 것도 하지 않는다. */
    public void requireImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return;
        }
        try (InputStream in = file.getInputStream()) {
            if (FileService.detectImageType(in.readNBytes(12)).isEmpty()) {
                throw new BadRequestException(INVALID_IMAGE_MESSAGE);
            }
        } catch (IOException e) {
            throw new BadRequestException(INVALID_IMAGE_MESSAGE);
        }
    }

    /** 멀티파트 이미지를 저장하고 "/uploads/..." 를 돌려준다. 롤백되면 파일을 지운다. */
    public String store(MultipartFile file) {
        requireImage(file);
        String url = FileService.UPLOAD_URL_PREFIX + fileService.storeFile(file);
        deleteOnRollback(url);
        return url;
    }

    /** data URL 을 디코드해 매직 바이트를 확인한 뒤 파일로 저장한다. 이미지가 아니면 400. */
    public String storeDataUrl(String dataUrl) {
        byte[] bytes = fileService.decodeImageDataUrl(dataUrl)
                .orElseThrow(() -> new BadRequestException(INVALID_IMAGE_MESSAGE));
        return storeBytes(bytes);
    }

    /** 이미 올라가 있는 업로드 파일을 새 파일로 복사한다 (원본이 지워져도 깨지지 않게). 없거나 이미지가 아니면 empty. */
    public Optional<String> copyUpload(String uploadUrl) {
        return fileService.loadImage(uploadUrl)
                .map(image -> storeBytes(Base64.getDecoder().decode(image.base64())));
    }

    public String storeBytes(byte[] bytes) {
        String url = fileService.storeImage(bytes);
        deleteOnRollback(url);
        return url;
    }

    public void deleteAfterCommit(String url) {
        fileService.deleteFileAfterCommit(url);
    }

    private void deleteOnRollback(String url) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    fileService.deleteFile(url);
                }
            }
        });
    }
}
