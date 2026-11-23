package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.entity.GroupTopic;
import com.moyeolog.moyelog_BE.repository.GroupTopicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 일회성 변환: 예전에 DB 에 data URL(base64)로 저장된 토픽 이미지를 /uploads 파일로 옮긴다.
 * 앱 시작 때마다 돌지만 이미 /uploads 인 토픽은 대상이 아니므로 변환이 끝나면 아무 일도 하지 않는다.
 * 이미지가 아닌 내용(매직 바이트 불일치)은 이미지 없음(NULL)으로 바꾼다. 토픽 하나씩 짧은 트랜잭션으로 처리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupTopicImageMigration implements ApplicationRunner {

    private final GroupTopicRepository groupTopicRepository;
    private final FileService fileService;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(ApplicationArguments args) {
        List<UUID> ids;
        try {
            ids = groupTopicRepository.findIdsWithDataUrlImage();
        } catch (RuntimeException e) {
            log.warn("[topic-image-migration] skipped: {}", e.getClass().getSimpleName());
            return;
        }
        if (ids.isEmpty()) {
            return;
        }
        log.info("[topic-image-migration] converting {} topic image(s)", ids.size());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int converted = 0;
        int cleared = 0;
        for (UUID id : ids) {
            try {
                Boolean ok = tx.execute(status -> convert(id));
                if (Boolean.TRUE.equals(ok)) converted++;
                else if (Boolean.FALSE.equals(ok)) cleared++;
            } catch (RuntimeException e) {
                log.warn("[topic-image-migration] topic {} failed: {}", id, e.getClass().getSimpleName());
            }
        }
        log.info("[topic-image-migration] done: converted={}, cleared={}", converted, cleared);
    }

    /** @return true 변환, false 이미지가 아니라 비움, null 대상 아님 */
    private Boolean convert(UUID id) {
        GroupTopic topic = groupTopicRepository.findById(id).orElse(null);
        if (topic == null || topic.getImageUrl() == null || !topic.getImageUrl().startsWith("data:")) {
            return null;
        }
        String url = fileService.decodeImageDataUrl(topic.getImageUrl())
                .map(fileService::storeImage)
                .orElse(null);
        groupTopicRepository.updateImageUrl(id, url);
        return url != null;
    }
}
