package com.moyeolog.moyelog_BE.datepoll;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyeolog.moyelog_BE.datepoll.DatePollDtos.*;
import com.moyeolog.moyelog_BE.dto.ScheduleRequest;
import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.event.ChangeEvent;
import com.moyeolog.moyelog_BE.event.LiveTopics;
import com.moyeolog.moyelog_BE.event.NotificationEvent;
import com.moyeolog.moyelog_BE.event.NotificationType;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ConflictException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.GroupMemberRepository;
import com.moyeolog.moyelog_BE.repository.GroupRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.service.AiAnalysisSupport;
import com.moyeolog.moyelog_BE.service.GeminiService;
import com.moyeolog.moyelog_BE.service.ItemNotFoundException;
import com.moyeolog.moyelog_BE.service.ScheduleService;
import com.moyeolog.moyelog_BE.validation.CodePointSizeValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 모임 날짜 조율.
 * - 보기·고르기·추천·만들기: 모임 멤버. 재촉·정하기·지우기: 그 조율을 만든 사람 또는 모임장(방장).
 * - 집계(날짜별 인원, best)는 코드가 한다. AI 는 집계를 받아 요약과 추천 이유만 만든다.
 * - 지금 모임 멤버의 선택만 센다(나간 사람의 선택은 GroupService 가 지우지만, 읽을 때도 한 번 더 거른다).
 */
@Service
@RequiredArgsConstructor
public class DatePollService {

    static final int MAX_DAYS = 31;
    private static final String NOT_FOUND = "날짜 조율을 찾을 수 없어요. 삭제됐을 수 있어요.";
    private static final Map<String, Object> AI_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string"),
                    "recommended", Map.of("type", "array", "items", Map.of(
                            "type", "object",
                            "properties", Map.of("date", Map.of("type", "string"), "reason", Map.of("type", "string")),
                            "required", List.of("date", "reason")))),
            "required", List.of("summary", "recommended"));

    private final DatePollRepository pollRepository;
    private final DatePollVoteRepository voteRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final ScheduleService scheduleService;
    private final GeminiService geminiService;
    private final AiAnalysisSupport aiAnalysisSupport;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** 진행 중인 것(최근에 만든 순) 다음에 최근에 정해진 것 5개까지 */
    @Transactional(readOnly = true)
    public List<DatePollSummary> list(UUID userId, UUID groupId) {
        Group group = requireMember(groupId, userId);
        List<User> members = members(groupId);
        List<DatePoll> polls = new ArrayList<>(
                pollRepository.findAllByGroup_IdAndStatusOrderByCreatedAtDesc(groupId, DatePoll.Status.OPEN));
        polls.addAll(pollRepository.findTop5ByGroup_IdAndStatusOrderByConfirmedAtDesc(groupId, DatePoll.Status.CONFIRMED));
        return polls.stream().map(poll -> summary(poll, tally(poll, members), userId, members.size(),
                canManage(group, poll, userId))).toList();
    }

    @Transactional
    public DatePollDetail create(UUID userId, UUID groupId, CreateRequest request) {
        Group group = requireMember(groupId, userId);
        User me = userRepository.getReferenceById(userId);
        LocalDate start = request.startDate(), end = request.endDate();
        if (start.isBefore(LocalDate.now())) {
            throw new BadRequestException("시작 날짜는 오늘이거나 그 뒤여야 해요.");
        }
        if (end.isBefore(start)) {
            throw new BadRequestException("끝 날짜는 시작 날짜와 같거나 그 뒤여야 해요.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new BadRequestException("후보 기간은 " + MAX_DAYS + "일까지 잡을 수 있어요.");
        }

        DatePoll poll = pollRepository.save(DatePoll.builder()
                .group(group).creator(me)
                .title(request.title().trim())
                .startDate(start).endDate(end)
                .status(DatePoll.Status.OPEN)
                .build());

        List<User> members = members(groupId);
        notify(members, userId, groupId, poll.getId(), nickname(members, userId) + "님이 '" + shortTitle(poll)
                + "' 날짜 조율을 시작했어요. 가능한 날을 골라 주세요.");
        publishChange(groupId, members);
        return detail(poll, members, userId);
    }

    @Transactional(readOnly = true)
    public DatePollDetail get(UUID userId, UUID groupId, UUID pollId) {
        requireMember(groupId, userId);
        return detail(findPoll(groupId, pollId, false), members(groupId), userId);
    }

    /** 내 선택을 통째로 바꾼다. 빈 목록 = '가능한 날 없음'으로 응답. */
    @Transactional
    public DatePollDetail vote(UUID userId, UUID groupId, UUID pollId, VotesRequest request) {
        requireMember(groupId, userId);
        // 같은 사람이 동시에 두 번 저장해도 한 벌만 남도록 조율 행을 잠근다.
        DatePoll poll = findPoll(groupId, pollId, true);
        requireOpen(poll);
        Set<LocalDate> dates = new TreeSet<>(request.dates());
        for (LocalDate date : dates) {
            if (!poll.contains(date)) {
                throw new BadRequestException("후보 기간 밖의 날짜는 고를 수 없어요.");
            }
        }

        voteRepository.deleteMine(pollId, userId);
        User me = userRepository.getReferenceById(userId);
        if (dates.isEmpty()) {
            voteRepository.save(DatePollVote.builder().poll(poll).user(me).date(null).build());
        } else {
            voteRepository.saveAll(dates.stream()
                    .map(d -> DatePollVote.builder().poll(poll).user(me).date(d).build()).toList());
        }
        voteRepository.flush();

        List<User> members = members(groupId);
        publishChange(groupId, members);
        return detail(poll, members, userId);
    }

    /**
     * AI 추천. LLM 을 기다리는 동안 DB 트랜잭션을 잡지 않고(AiAnalysisSupport), 같은 조율에 추천이 진행 중이면 409.
     * LLM 이 후보 밖 날짜를 주면 버리고, 남는 것이 없으면 best 로 채운다.
     */
    public DatePollDetail recommend(UUID userId, UUID groupId, UUID pollId) {
        return aiAnalysisSupport.run("datepoll:" + pollId,
                () -> prepareRecommendation(userId, groupId, pollId),
                input -> geminiService.generateJson(input.prompt(), AI_SCHEMA, "datepoll:" + pollId),
                (input, result) -> saveRecommendation(userId, groupId, pollId, input, result));
    }

    @Transactional
    public void remind(UUID userId, UUID groupId, UUID pollId) {
        Group group = requireMember(groupId, userId);
        DatePoll poll = findPoll(groupId, pollId, false);
        requireManager(group, poll, userId, "조율을 만든 사람이나 모임장만 응답을 재촉할 수 있어요.");
        requireOpen(poll);
        List<User> members = members(groupId);
        Tally tally = tally(poll, members);
        List<User> waiting = members.stream().filter(u -> !tally.responded().containsKey(u.getId())).toList();
        notify(waiting, userId, groupId, poll.getId(), nickname(members, userId) + "님이 '" + shortTitle(poll)
                + "' 날짜 조율에 응답을 기다리고 있어요. 가능한 날을 골라 주세요.");
    }

    /** 날짜를 정하고 모임 일정을 만든다. 일정 만들기는 ScheduleService 를 그대로 거친다(검증·참여자 알림·실시간). */
    @Transactional
    public DatePollDetail confirm(UUID userId, UUID groupId, UUID pollId, ConfirmRequest request) {
        Group group = requireMember(groupId, userId);
        DatePoll poll = findPoll(groupId, pollId, true);
        requireManager(group, poll, userId, "조율을 만든 사람이나 모임장만 날짜를 정할 수 있어요.");
        requireOpen(poll);
        if (!poll.contains(request.date())) {
            throw new BadRequestException("후보 기간 안의 날짜로 정해 주세요.");
        }

        LocalDateTime start = request.date().atTime(request.startTime());
        // 끝 시각을 안 주면 한 시간짜리 일정으로 만든다.
        LocalDateTime end = request.endTime() == null ? start.plusHours(1) : request.date().atTime(request.endTime());
        List<User> members = members(groupId);
        Tally tally = tally(poll, members);
        List<UUID> participants = members.stream()
                .map(User::getId)
                .filter(id -> tally.responded().getOrDefault(id, Set.of()).contains(request.date()))
                .toList();

        UUID scheduleId = scheduleService.createSchedule(userId, ScheduleRequest.builder()
                .title(poll.getTitle())
                .startTime(start).endTime(end)
                .groupId(groupId)
                .participantIds(participants)
                .build()).getId();
        poll.confirm(request.date(), scheduleId);
        pollRepository.flush();

        // 일정 참여자는 일정 생성 경로의 '참여자로 추가' 알림을 이미 받으므로 확정 알림에서 뺀다.
        notify(members.stream().filter(u -> !participants.contains(u.getId())).toList(), userId, groupId, poll.getId(),
                nickname(members, userId) + "님이 '" + shortTitle(poll)
                        + "' 날짜를 " + label(request.date()) + "로 정했어요.");
        publishChange(groupId, members);
        return detail(poll, members, userId);
    }

    /** 조율과 선택을 지운다. 이미 만든 일정은 남는다. */
    @Transactional
    public void delete(UUID userId, UUID groupId, UUID pollId) {
        Group group = requireMember(groupId, userId);
        DatePoll poll = findPoll(groupId, pollId, true);
        requireManager(group, poll, userId, "조율을 만든 사람이나 모임장만 날짜 조율을 지울 수 있어요.");
        voteRepository.deleteAllByPollId(pollId);
        pollRepository.delete(poll);
        pollRepository.flush();
        publishChange(groupId, members(groupId));
    }

    /**
     * 회원 탈퇴: 그 사람의 선택을 지우고, 그 사람이 만든 조율은 지금 모임장에게 넘긴다.
     * 모임 이양·삭제(GroupService, @Order(20)) 뒤에 돌아야 새 모임장이 정해져 있다.
     */
    @EventListener
    @Order(30)
    @Transactional
    public void onUserDeleting(UserDeletingEvent event) {
        UUID userId = event.userId();
        voteRepository.deleteAllByUserId(userId);
        for (DatePoll poll : pollRepository.findAllByCreator_Id(userId)) {
            User owner = poll.getGroup().getCreatedBy();
            if (owner.getId().equals(userId)) {
                voteRepository.deleteAllByPollId(poll.getId());
                pollRepository.delete(poll);
            } else {
                poll.changeCreator(owner);
            }
        }
        pollRepository.flush();
    }

    // ─── AI ─────────────────────────────────────────────────────────

    /** 읽기 트랜잭션에서 꺼낸 추천 입력. 엔티티가 아니라 값이다. */
    /** names: 멤버 닉네임(LLM 이 이름 속에 넣은 공백을 되돌릴 때 쓴다) */
    record AiInput(String prompt, String sourceHash, List<LocalDate> best, Map<LocalDate, Integer> counts,
                   int memberCount, int respondedCount, List<String> names) {
    }

    private AiInput prepareRecommendation(UUID userId, UUID groupId, UUID pollId) {
        requireMember(groupId, userId);
        DatePoll poll = findPoll(groupId, pollId, false);
        List<User> members = members(groupId);
        Tally tally = tally(poll, members);
        if (tally.responded().isEmpty()) {
            throw new BadRequestException("아직 응답한 사람이 없어요. 응답이 모이면 추천을 받을 수 있어요.");
        }
        List<DateTally> dates = dateTallies(poll, members, tally);
        Map<LocalDate, Integer> counts = new LinkedHashMap<>();
        dates.forEach(d -> counts.put(d.date(), d.count()));

        StringBuilder data = new StringBuilder();
        for (DateTally d : dates) {
            data.append(d.date()).append(" (").append(weekday(d.date())).append("): ").append(d.count()).append("명 가능");
            if (!d.voters().isEmpty()) {
                data.append(" - ").append(String.join(", ", d.voters().stream().map(PollUser::nickname).toList()));
            }
            data.append('\n');
        }
        List<String> waiting = members.stream()
                .filter(u -> !tally.responded().containsKey(u.getId())).map(User::getNickname).toList();

        String prompt = "You are helping a Korean friend group choose one date to meet. "
                + "Below is the availability poll. The counting is already done; do not recount or invent dates.\n"
                + "Poll title: " + poll.getTitle() + "\n"
                + "Members: " + members.size() + ", responded: " + tally.responded().size() + "\n"
                + "Not responded yet: " + (waiting.isEmpty() ? "(none)" : String.join(", ", waiting)) + "\n"
                + "Candidate dates (date (weekday): available count - names):\n" + data
                + "Rules:\n"
                + "1. summary: 2 to 4 complete sentences IN KOREAN (polite 해요체) describing the availability, "
                + "mentioning who has not responded if relevant. End EVERY sentence with ~요 (e.g. 가능해요, 않았어요); "
                + "never use ~습니다 or ~네요.\n"
                + "2. recommended: 1 to 3 dates, best first. Each item has date (MUST be one of the candidate dates above, "
                + "format yyyy-MM-dd) and reason (one short sentence IN KOREAN). Prefer dates where more members are available. "
                + "NEVER recommend a date whose available count is 0.\n"
                + "3. Write summary and reasons ONLY in Korean Hangul. Do not use Chinese characters (Hanja), Japanese or English words. "
                + "State counts exactly as given above. Write member names exactly as given and attach particles directly "
                + "(e.g. 민지는, not 민지 는). Write dates like 11월 29일 (no space between a number and 월/일/명).\n"
                + "Return the result STRICTLY as a JSON object: "
                + "{\"summary\": string, \"recommended\": [{\"date\": string, \"reason\": string}]}.";
        return new AiInput(prompt, tally.hash(), best(dates), counts, members.size(), tally.responded().size(),
                members.stream().map(User::getNickname).toList());
    }

    private DatePollDetail saveRecommendation(UUID userId, UUID groupId, UUID pollId, AiInput input,
                                              Map<String, Object> result) {
        requireMember(groupId, userId);
        DatePoll poll = findPoll(groupId, pollId, true);

        List<Recommendation> recommended = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();
        if (result.get("recommended") instanceof List<?> items) {
            for (Object item : items) {
                if (recommended.size() == 3) break;
                if (!(item instanceof Map<?, ?> map)) continue;
                LocalDate date = parseDate(map.get("date"));
                // 후보 밖 날짜와 가능한 사람이 0명인 날은 버린다(LLM 이 실제로 0명인 날을 추천한 적이 있다).
                if (date == null || input.counts().getOrDefault(date, 0) == 0 || !seen.add(date)) continue;
                String reason = clip(tidy(text(map.get("reason")), input.names()), 200);
                recommended.add(new Recommendation(date,
                        usable(reason) ? reason : fallbackReason(input, date)));
            }
        }
        if (recommended.isEmpty()) {
            input.best().forEach(d -> recommended.add(new Recommendation(d, fallbackReason(input, d))));
        }
        String summary = clip(tidy(text(result.get("summary")), input.names()), 600);
        if (!usable(summary)) {
            summary = input.memberCount() + "명 중 " + input.respondedCount() + "명이 응답했어요.";
        }

        poll.saveAi(summary, writeRecommendations(recommended), input.sourceHash());
        pollRepository.flush();
        List<User> members = members(groupId);
        publishChange(groupId, members);
        return detail(poll, members, userId);
    }

    /** 한자(U+4E00–9FFF)·가나(U+3040–30FF). 프롬프트로 막아도 qwen 이 가끔 섞는다(예: '모두出席할'). */
    private static final java.util.regex.Pattern CJK_NOT_KOREAN = java.util.regex.Pattern.compile("[\u4e00-\u9fff\u3040-\u30ff]");

    /** 비었거나 한자·가나가 섞인 문장은 쓰지 않고 문장 전체를 기본 문구로 바꾼다(글자만 지우면 '모두할'처럼 어색해진다). */
    static boolean usable(String text) {
        return !text.isEmpty() && !CJK_NOT_KOREAN.matcher(text).find();
    }

    /** 다른 이유(LLM 문장)와 형식을 맞춰 한 문장으로 쓴다 */
    private static String fallbackReason(AiInput input, LocalDate date) {
        return input.memberCount() + "명 중 " + input.counts().getOrDefault(date, 0) + "명이 가능해요.";
    }

    /** 숫자와 단위 사이('11 월', '2 명')의 공백 */
    private static final java.util.regex.Pattern NUMBER_UNIT_SPACE = java.util.regex.Pattern.compile("(\\d) +(월|일|명|시|분)");
    /** 영문·숫자로 끝나는 이름과 조사 사이('큐에이20b 는')의 공백 */
    private static final java.util.regex.Pattern NAME_PARTICLE_SPACE =
            java.util.regex.Pattern.compile("([0-9A-Za-z]) +(은|는|이|가|을|를|와|과|도|의|에게|만)(?=[\\s.,!?]|$)");

    /** LLM(qwen)이 한국어를 토큰 단위로 띄어 쓰는 것('11 월 29 일', '20b 는')을 붙인다 */
    static String tidy(String text) {
        return tidy(text, List.of());
    }

    /**
     * tidy + 멤버 닉네임 속에 끼어든 공백('큐에이 20a', '큐 에이20a')을 원래 닉네임으로 되돌린다.
     * 이름을 먼저 붙여야 뒤의 이름·조사 규칙이 '큐에이20a 가' 를 '큐에이20a가' 로 붙인다.
     * 공백 없는 글자 3개 이상인 닉네임만 고친다(짧은 이름은 '가 나' 처럼 평범한 두 낱말과 헷갈릴 수 있어서).
     * 줄여 부른 이름('20c')이나 틀리게 쓴 이름은 되돌리지 못한다.
     */
    static String tidy(String text, List<String> names) {
        String result = text;
        List<String> sorted = names == null ? List.of() : names.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String name : sorted) {
            int[] letters = name.codePoints().filter(c -> !Character.isWhitespace(c)).toArray();
            if (letters.length < 3) continue;
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < letters.length; i++) {
                if (i > 0) regex.append("\\s*");
                regex.append(java.util.regex.Pattern.quote(new String(Character.toChars(letters[i]))));
            }
            result = java.util.regex.Pattern.compile(regex.toString()).matcher(result)
                    .replaceAll(java.util.regex.Matcher.quoteReplacement(name));
        }
        result = NUMBER_UNIT_SPACE.matcher(result).replaceAll("$1$2");
        return NAME_PARTICLE_SPACE.matcher(result).replaceAll("$1$2");
    }

    private String writeRecommendations(List<Recommendation> recommended) {
        try {
            return objectMapper.writeValueAsString(recommended.stream()
                    .map(r -> Map.of("date", r.date().toString(), "reason", r.reason())).toList());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Recommendation> readRecommendations(String json) {
        if (json == null) return List.of();
        try {
            List<Map<String, String>> raw = objectMapper.readValue(json, new TypeReference<>() {
            });
            return raw.stream().map(m -> new Recommendation(LocalDate.parse(m.get("date")), m.get("reason"))).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static LocalDate parseDate(Object value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String clip(String text, int max) {
        return CodePointSizeValidator.length(text) > max ? CodePointSizeValidator.truncate(text, max) : text;
    }

    // ─── 집계 ───────────────────────────────────────────────────────

    /**
     * @param responded 응답한 멤버 → 고른 날짜(빈 집합 = 가능한 날 없음)
     * @param hash      선택 전체의 지문. AI 추천 뒤에 바뀌었는지(stale) 판단한다.
     */
    private record Tally(Map<UUID, Set<LocalDate>> responded, String hash) {
    }

    private Tally tally(DatePoll poll, List<User> members) {
        Set<UUID> memberIds = new HashSet<>();
        members.forEach(u -> memberIds.add(u.getId()));
        Map<UUID, Set<LocalDate>> responded = new HashMap<>();
        for (DatePollVote vote : voteRepository.findAllByPoll(poll.getId())) {
            UUID id = vote.getUser().getId();
            if (!memberIds.contains(id)) continue;
            Set<LocalDate> dates = responded.computeIfAbsent(id, k -> new TreeSet<>());
            if (vote.getDate() != null) dates.add(vote.getDate());
        }
        List<String> lines = new ArrayList<>();
        responded.forEach((id, dates) -> lines.add(id + ":" + dates));
        Collections.sort(lines);
        return new Tally(responded, AiAnalysisSupport.fingerprint(String.join("\n", lines), null));
    }

    /** 후보 기간의 모든 날짜(0명인 날 포함), 가능한 사람은 모임 가입 순서 */
    private static List<DateTally> dateTallies(DatePoll poll, List<User> members, Tally tally) {
        List<DateTally> result = new ArrayList<>();
        for (LocalDate d = poll.getStartDate(); !d.isAfter(poll.getEndDate()); d = d.plusDays(1)) {
            LocalDate day = d;
            List<PollUser> voters = members.stream()
                    .filter(u -> tally.responded().getOrDefault(u.getId(), Set.of()).contains(day))
                    .map(DatePollService::pollUser).toList();
            result.add(new DateTally(day, voters.size(), voters));
        }
        return result;
    }

    /** 인원 많은 순, 같으면 이른 날짜. 1명 이상인 날만, 최대 3개 */
    private static List<LocalDate> best(List<DateTally> dates) {
        return dates.stream()
                .filter(d -> d.count() > 0)
                .sorted(Comparator.comparingInt(DateTally::count).reversed().thenComparing(DateTally::date))
                .limit(3)
                .map(DateTally::date)
                .toList();
    }

    private static DatePollSummary summary(DatePoll poll, Tally tally, UUID viewerId, int memberCount, boolean canManage) {
        return new DatePollSummary(poll.getId(), poll.getTitle(), poll.getStartDate(), poll.getEndDate(),
                poll.getStatus(), tally.responded().size(), memberCount, tally.responded().containsKey(viewerId),
                poll.getConfirmedDate(), poll.getScheduleId(), poll.getCreatedAt(), canManage);
    }

    private DatePollDetail detail(DatePoll poll, List<User> members, UUID viewerId) {
        Tally tally = tally(poll, members);
        List<DateTally> dates = dateTallies(poll, members, tally);
        List<PollUser> responded = new ArrayList<>(), notResponded = new ArrayList<>();
        for (User u : members) {
            (tally.responded().containsKey(u.getId()) ? responded : notResponded).add(pollUser(u));
        }
        Ai ai = poll.getAiGeneratedAt() == null ? null : new Ai(poll.getAiSummary(),
                readRecommendations(poll.getAiRecommended()), poll.getAiGeneratedAt(),
                !tally.hash().equals(poll.getAiSourceHash()));
        return new DatePollDetail(poll.getId(), poll.getTitle(), poll.getStartDate(), poll.getEndDate(),
                poll.getStatus(), responded.size(), members.size(), tally.responded().containsKey(viewerId),
                poll.getConfirmedDate(), poll.getScheduleId(), poll.getCreatedAt(),
                pollUser(poll.getCreator()), List.copyOf(tally.responded().getOrDefault(viewerId, Set.of())),
                dates, best(dates), responded, notResponded, ai, canManage(poll.getGroup(), poll, viewerId));
    }

    // ─── Internal Helpers ──────────────────────────────────────────

    private Group requireMember(UUID groupId, UUID userId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ItemNotFoundException("모임을 찾을 수 없어요."));
        if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)) {
            throw new UnauthorizedAccessException("모임 멤버만 할 수 있어요.");
        }
        return group;
    }

    /** 재촉·정하기·지우기: 그 조율을 만든 사람 또는 모임장 */
    private static boolean canManage(Group group, DatePoll poll, UUID userId) {
        return poll.getCreator().getId().equals(userId) || group.getCreatedBy().getId().equals(userId);
    }

    private static void requireManager(Group group, DatePoll poll, UUID userId, String message) {
        if (!canManage(group, poll, userId)) {
            throw new UnauthorizedAccessException(message);
        }
    }

    /** 알림 문구에 쓸, 실제로 행동한 사람의 닉네임 */
    private String nickname(List<User> members, UUID userId) {
        return members.stream().filter(u -> u.getId().equals(userId)).findFirst()
                .orElseGet(() -> userRepository.getReferenceById(userId)).getNickname();
    }

    private static void requireOpen(DatePoll poll) {
        if (poll.getStatus() != DatePoll.Status.OPEN) {
            throw new ConflictException("이미 날짜가 정해진 조율이에요.");
        }
    }

    private DatePoll findPoll(UUID groupId, UUID pollId, boolean lock) {
        DatePoll poll = (lock ? pollRepository.findByIdForUpdate(pollId) : pollRepository.findById(pollId))
                .orElseThrow(() -> new ItemNotFoundException(NOT_FOUND));
        if (!poll.getGroup().getId().equals(groupId)) {
            throw new ItemNotFoundException(NOT_FOUND);
        }
        return poll;
    }

    /** 지금 모임 멤버(가입 순서, 중복 행 제거) */
    private List<User> members(UUID groupId) {
        Map<UUID, User> members = new LinkedHashMap<>();
        for (GroupMember m : groupMemberRepository.findAllByGroup_IdOrderByJoinedAtAsc(groupId)) {
            members.putIfAbsent(m.getUser().getId(), m.getUser());
        }
        return new ArrayList<>(members.values());
    }

    /** 알림은 새 종류 없이 '일정 참여' 설정을 쓴다(중간 지점과 같다). 누르면 그 모임의 일정 탭에서 그 조율 창이 열린다. */
    private void notify(List<User> recipients, UUID actorId, UUID groupId, UUID pollId, String message) {
        String link = "/groups/" + groupId + "?tab=calendar&poll=" + pollId;
        for (User recipient : recipients) {
            if (recipient.getId().equals(actorId)) continue;
            eventPublisher.publishEvent(new NotificationEvent(recipient.getId(), actorId,
                    NotificationType.SCHEDULE_PARTICIPANT, message, link));
        }
    }

    /** 커밋 뒤 실시간 갱신: 모임 멤버에게 group:{id} */
    private void publishChange(UUID groupId, List<User> members) {
        eventPublisher.publishEvent(ChangeEvent.of(members.stream().map(User::getId).toList(), LiveTopics.group(groupId)));
    }

    private static PollUser pollUser(User user) {
        return new PollUser(user.getId(), user.getNickname(), user.getProfileImage());
    }

    private static String shortTitle(DatePoll poll) {
        String title = poll.getTitle().trim();
        return CodePointSizeValidator.length(title) > 30 ? CodePointSizeValidator.truncate(title, 30) + "…" : title;
    }

    private static String weekday(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
    }

    /** 11월 30일(토) */
    private static String label(LocalDate date) {
        return date.getMonthValue() + "월 " + date.getDayOfMonth() + "일(" + weekday(date) + ")";
    }
}
