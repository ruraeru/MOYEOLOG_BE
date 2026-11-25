package com.moyeolog.moyelog_BE.message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    /** a(보는 사람)와 b 사이 메시지 중 a 가 지우지 않은 것 */
    String BETWEEN = "((m.sender.id = :a AND m.recipient.id = :b AND m.hiddenBySender = false) "
            + "OR (m.sender.id = :b AND m.recipient.id = :a AND m.hiddenByRecipient = false))";

    /** a 가 볼 수 있는 a·b 사이 메시지, 최신순 */
    @Query("SELECT m FROM Message m WHERE " + BETWEEN + " ORDER BY m.createdAt DESC, m.id DESC")
    List<Message> findBetween(@Param("a") UUID a, @Param("b") UUID b, Pageable pageable);

    /** 두 사람 사이 메시지 중 before 보다 이전 것, 최신순 */
    @Query("SELECT m FROM Message m WHERE " + BETWEEN + " AND m.createdAt < :before ORDER BY m.createdAt DESC, m.id DESC")
    List<Message> findBetweenBefore(@Param("a") UUID a, @Param("b") UUID b, @Param("before") LocalDateTime before,
                                    Pageable pageable);

    @Query("SELECT DISTINCT m.recipient.id FROM Message m WHERE m.sender.id = :me AND m.hiddenBySender = false")
    List<UUID> findRecipientIdsOf(@Param("me") UUID me);

    @Query("SELECT DISTINCT m.sender.id FROM Message m WHERE m.recipient.id = :me AND m.hiddenByRecipient = false")
    List<UUID> findSenderIdsTo(@Param("me") UUID me);

    /** [보낸 사람 id, 안 읽은 수] */
    @Query("SELECT m.sender.id, COUNT(m) FROM Message m WHERE m.recipient.id = :me AND m.readAt IS NULL AND m.hiddenByRecipient = false GROUP BY m.sender.id")
    List<Object[]> countUnreadBySender(@Param("me") UUID me);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.recipient.id = :me AND m.readAt IS NULL AND m.hiddenByRecipient = false")
    long countUnread(@Param("me") UUID me);

    /** 대화 지우기(내 쪽만): 내가 보낸 것 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Message m SET m.hiddenBySender = true WHERE m.sender.id = :me AND m.recipient.id = :friend")
    int hideSent(@Param("me") UUID me, @Param("friend") UUID friend);

    /** 대화 지우기(내 쪽만): 내가 받은 것. 안 읽은 것은 읽음으로 돌려 배지에서 뺀다 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Message m SET m.hiddenByRecipient = true, m.readAt = COALESCE(m.readAt, :now) "
            + "WHERE m.sender.id = :friend AND m.recipient.id = :me")
    int hideReceived(@Param("me") UUID me, @Param("friend") UUID friend, @Param("now") LocalDateTime now);

    /** 두 사람이 모두 지운 메시지는 행을 없앤다 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Message m WHERE m.hiddenBySender = true AND m.hiddenByRecipient = true AND "
            + "((m.sender.id = :a AND m.recipient.id = :b) OR (m.sender.id = :b AND m.recipient.id = :a))")
    int deleteHiddenByBoth(@Param("a") UUID a, @Param("b") UUID b);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Message m SET m.readAt = :now WHERE m.sender.id = :friend AND m.recipient.id = :me AND m.readAt IS NULL")
    int markRead(@Param("me") UUID me, @Param("friend") UUID friend, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Message m WHERE m.sender.id = :userId OR m.recipient.id = :userId")
    int deleteAllInvolving(@Param("userId") UUID userId);
}
