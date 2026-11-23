package com.capstone.backend.inquiry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.backend.board.BoardRepository;
import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.Codes;

/**
 * 광고 신청 문의 접수 (명세 4.7). 예약·결제·지자체 신청이 아니라 문의 저장만 한다.
 *
 * 중복 방지(Idempotency-Key):
 * - 처음 온 키 → 저장하고 201
 * - 같은 키·같은 내용(payload_hash) → 저장하지 않고 기존 문의를 200으로 돌려준다
 * - 같은 키·다른 내용 → 409 IDEMPOTENCY_CONFLICT
 * 동시에 같은 키가 두 번 와도 UNIQUE 제약 + ON CONFLICT DO NOTHING으로 한 건만 저장된다.
 *
 * contact(개인정보)는 응답·오류 메시지·로그에 넣지 않는다.
 * industry 목록은 명세에서 미확정이라 빈 값·길이만 검사한다(목록이 정해지면 검사 추가).
 */
@Service
public class InquiryService {

    static final String NOTICE = "문의가 접수됐습니다. 게시는 보통 2주 단위이며 지자체 절차에 따라 시기가 정해집니다.";

    /** OpenAPI DesiredPosting.year minimum */
    static final int MIN_POSTING_YEAR = 2026;
    static final int MAX_MESSAGE_LENGTH = 300;
    /** 아래 상한은 V4 migration의 컬럼 길이와 같다. 넘으면 DB 오류(500) 대신 400으로 막는다. */
    static final int MAX_INDUSTRY_LENGTH = 50;
    static final int MAX_BUSINESS_NAME_LENGTH = 100;
    static final int MAX_CONTACT_LENGTH = 100;
    static final int MAX_CONSENT_VERSION_LENGTH = 50;

    private static final List<String> HALVES = List.of("FIRST", "SECOND");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    /** 국내 전화번호: 0으로 시작, 하이픈 선택 (예: 010-1234-5678, 02-123-4567, 0312345678) */
    private static final Pattern PHONE = Pattern.compile("0[0-9]{1,2}-?[0-9]{3,4}-?[0-9]{4}");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final NamedParameterJdbcTemplate jdbc;
    private final BoardRepository boards;

    public InquiryService(NamedParameterJdbcTemplate jdbc, BoardRepository boards) {
        this.jdbc = jdbc;
        this.boards = boards;
    }

    /** created: 새로 저장했으면 true(201), 같은 키 재전송이면 false(200) */
    public record Result(boolean created, Map<String, Object> data) {
    }

    /** 저장된 문의 중 응답과 중복 판별에 필요한 값 (contact는 읽지 않는다) */
    private record Stored(long id, long boardId, int year, int month, String half, String payloadHash,
                          OffsetDateTime createdAt) {
    }

    @Transactional
    public Result create(String idempotencyKey, InquiryRequest request) {
        UUID key = parseKey(idempotencyKey);
        Valid v = validate(request);

        BoardRepository.Board board = boards.get(v.boardId());
        if (!Codes.canInquire(board.purpose())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INQUIRY_NOT_ALLOWED", "행정용 게시대는 광고 문의를 받지 않습니다.");
        }

        String hash = v.hash();
        Optional<Stored> existing = find(key);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        Optional<Stored> inserted = insert(key, v, hash);
        if (inserted.isEmpty()) {
            // 동시에 들어온 같은 키 요청이 먼저 저장했다
            return replay(find(key).orElseThrow(), hash);
        }
        return new Result(true, response(inserted.get()));
    }

    private static UUID parseKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("Idempotency-Key 헤더가 필요합니다.");
        }
        try {
            UUID key = UUID.fromString(raw.trim());
            if (key.version() != 4) {
                throw ApiException.badRequest("Idempotency-Key는 UUID v4여야 합니다.");
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Idempotency-Key는 UUID v4여야 합니다.");
        }
    }

    /** 검사를 통과한 요청 값. 문자열은 앞뒤 공백을 지우고, 빈 선택 값은 null로 둔다. */
    private record Valid(long boardId, int year, int month, String half, String industry, String businessName,
                         String contact, boolean productionAssistanceRequired, String message,
                         String consentVersion) {

        /** 같은 키·같은 내용 판별용. 필드 사이에 본문에 나올 수 없는 구분자(U+001F)를 넣는다. */
        String hash() {
            String canonical = String.join("\u001F", String.valueOf(boardId), String.valueOf(year),
                    String.valueOf(month), half, industry, String.valueOf(businessName), contact,
                    String.valueOf(productionAssistanceRequired), String.valueOf(message), consentVersion);
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
            }
        }
    }

    private static Valid validate(InquiryRequest r) {
        if (r == null) {
            throw ApiException.badRequest("문의 내용이 없습니다.");
        }
        String boardId = trimToNull(r.boardId());
        if (boardId == null || !DIGITS.matcher(boardId).matches()) {
            throw ApiException.badRequest("boardId가 올바르지 않습니다.");
        }

        InquiryRequest.DesiredPosting posting = r.desiredPosting();
        if (posting == null || posting.year() == null || posting.month() == null || posting.half() == null) {
            throw ApiException.badRequest("desiredPosting(year, month, half)이 필요합니다.");
        }
        if (posting.year() < MIN_POSTING_YEAR) {
            throw ApiException.badRequest("desiredPosting.year는 " + MIN_POSTING_YEAR + " 이상이어야 합니다.");
        }
        if (posting.month() < 1 || posting.month() > 12) {
            throw ApiException.badRequest("desiredPosting.month는 1~12여야 합니다.");
        }
        if (!HALVES.contains(posting.half())) {
            throw ApiException.badRequest("desiredPosting.half는 FIRST 또는 SECOND여야 합니다.");
        }

        String industry = required(r.industry(), "industry", MAX_INDUSTRY_LENGTH);
        String businessName = optional(r.businessName(), "businessName", MAX_BUSINESS_NAME_LENGTH);

        // contact 값은 개인정보라 오류 메시지에 넣지 않는다
        String contact = trimToNull(r.contact());
        if (contact == null || contact.length() > MAX_CONTACT_LENGTH
                || !(EMAIL.matcher(contact).matches() || PHONE.matcher(contact).matches())) {
            throw ApiException.badRequest("contact는 전화번호 또는 이메일 형식이어야 합니다.");
        }

        String message = optional(r.message(), "message", MAX_MESSAGE_LENGTH);

        if (!Boolean.TRUE.equals(r.privacyConsent())) {
            throw ApiException.badRequest("개인정보 수집·이용에 동의해야 문의를 접수할 수 있습니다.");
        }
        String consentVersion = required(r.privacyConsentVersion(), "privacyConsentVersion", MAX_CONSENT_VERSION_LENGTH);

        long id;
        try {
            id = Long.parseLong(boardId);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("boardId가 올바르지 않습니다.");
        }
        return new Valid(id, posting.year(), posting.month(), posting.half(), industry, businessName, contact,
                Boolean.TRUE.equals(r.productionAssistanceRequired()), message, consentVersion);
    }

    private static String required(String value, String field, int maxLength) {
        String v = trimToNull(value);
        if (v == null) {
            throw ApiException.badRequest(field + "가 필요합니다.");
        }
        return checkLength(v, field, maxLength);
    }

    private static String optional(String value, String field, int maxLength) {
        String v = trimToNull(value);
        return v == null ? null : checkLength(v, field, maxLength);
    }

    private static String checkLength(String value, String field, int maxLength) {
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw ApiException.badRequest(field + "는 " + maxLength + "자 이하여야 합니다.");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private Result replay(Stored stored, String hash) {
        if (!stored.payloadHash().equals(hash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "같은 Idempotency-Key로 다른 내용의 문의가 이미 접수됐습니다.");
        }
        return new Result(false, response(stored));
    }

    private Optional<Stored> find(UUID key) {
        return jdbc.query("""
                        SELECT id, board_id, posting_year, posting_month, posting_half, payload_hash, created_at
                        FROM inquiry WHERE idempotency_key = :key
                        """,
                new MapSqlParameterSource("key", key), (rs, i) -> stored(rs)).stream().findFirst();
    }

    /** 같은 키가 이미 있으면 저장하지 않고 빈 값을 돌려준다(UNIQUE 제약). */
    private Optional<Stored> insert(UUID key, Valid v, String hash) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("boardId", v.boardId())
                .addValue("year", v.year())
                .addValue("month", v.month())
                .addValue("half", v.half())
                .addValue("industry", v.industry())
                .addValue("businessName", v.businessName())
                .addValue("contact", v.contact())
                .addValue("assist", v.productionAssistanceRequired())
                .addValue("message", v.message())
                .addValue("consentVersion", v.consentVersion())
                .addValue("key", key)
                .addValue("hash", hash);
        return jdbc.query("""
                        INSERT INTO inquiry (board_id, posting_year, posting_month, posting_half, industry, business_name,
                                             contact, production_assistance_required, message, consent_version, consent_at,
                                             idempotency_key, payload_hash)
                        VALUES (:boardId, :year, :month, :half, :industry, :businessName,
                                :contact, :assist, :message, :consentVersion, now(),
                                :key, :hash)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING id, board_id, posting_year, posting_month, posting_half, payload_hash, created_at
                        """,
                p, (rs, i) -> stored(rs)).stream().findFirst();
    }

    private static Stored stored(ResultSet rs) throws SQLException {
        return new Stored(rs.getLong("id"), rs.getLong("board_id"), rs.getInt("posting_year"),
                rs.getInt("posting_month"), rs.getString("posting_half"), rs.getString("payload_hash"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    /** 명세 4.7 응답. contact는 넣지 않는다. */
    private static Map<String, Object> response(Stored s) {
        Map<String, Object> posting = new LinkedHashMap<>();
        posting.put("year", s.year());
        posting.put("month", s.month());
        posting.put("half", s.half());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inquiryId", String.valueOf(s.id()));
        data.put("status", "RECEIVED");
        data.put("boardId", String.valueOf(s.boardId()));
        data.put("desiredPosting", posting);
        data.put("receivedAt", s.createdAt().atZoneSameInstant(SEOUL).toOffsetDateTime()
                .truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        data.put("notice", NOTICE);
        return data;
    }
}
