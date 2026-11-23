package com.capstone.backend.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.ApiResponse;

/**
 * POST /api/v1/inquiries (명세 4.7)를 실제 PostgreSQL로 끝까지 실행한다.
 * 연락처는 가짜 값만 쓴다 (AGENTS.md 12).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class InquiryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

    static final String CONTACT = "demo@example.com";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InquiryController controller;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE inquiry, store, board_population, board_cell, population_fact, board, cell, grid_spec, dataset_info");
        jdbc.execute("""
                INSERT INTO board (board_id, name, city, dong, address, latitude, longitude, purpose, face_count, posting_fee)
                VALUES (1, '상업 게시대', '안양시', '가동', '주소1', 37.40, 126.95, 'COMMERCIAL', 5, NULL),
                       (2, '행정 게시대', '군포시', '나동', '주소2', 37.36, 126.93, 'ADMINISTRATIVE', 2, NULL),
                       (3, '겸용 게시대', '시흥시', '다동', '주소3', 37.38, 126.80, 'MIXED', 3, NULL)
                """);
    }

    static InquiryRequest request() {
        return new InquiryRequest("1", new InquiryRequest.DesiredPosting(2026, 12, "FIRST"), "EDUCATION",
                "시연용 가상 학원", CONTACT, true, "시연용 가짜 문의입니다.", true, "demo-v1");
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ---- 접수 (201)

    @Test
    void newInquiryIsCreated() {
        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.create(newKey(), request());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = res.getBody().data();
        assertThat(data.get("inquiryId")).isNotNull();
        assertThat(data.get("status")).isEqualTo("RECEIVED");
        assertThat(data.get("boardId")).isEqualTo("1");
        assertThat(data.get("desiredPosting")).isEqualTo(Map.of("year", 2026, "month", 12, "half", "FIRST"));
        assertThat((String) data.get("receivedAt")).endsWith("+09:00");
        assertThat(data.get("notice")).isEqualTo(InquiryService.NOTICE);
        assertThat(count()).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM inquiry");
        assertThat(row.get("contact")).isEqualTo(CONTACT);
        assertThat(row.get("consent_version")).isEqualTo("demo-v1");
        assertThat(row.get("consent_at")).isNotNull();
        assertThat(row.get("production_assistance_required")).isEqualTo(true);
    }

    @Test
    void responseNeverContainsContact() {
        Map<String, Object> data = controller.create(newKey(), request()).getBody().data();

        assertThat(data).doesNotContainKey("contact");
        assertThat(data.toString()).doesNotContain(CONTACT);
    }

    @Test
    void mixedBoardAcceptsInquiry() {
        InquiryRequest mixed = with(r -> new InquiryRequest("3", r.desiredPosting(), r.industry(), r.businessName(),
                r.contact(), r.productionAssistanceRequired(), r.message(), r.privacyConsent(), r.privacyConsentVersion()));

        assertThat(controller.create(newKey(), mixed).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void optionalValuesDefault() {
        InquiryRequest minimal = new InquiryRequest("1", new InquiryRequest.DesiredPosting(2026, 1, "SECOND"),
                "CAFE", "  ", "010-1234-5678", null, "", true, "demo-v1");

        assertThat(controller.create(newKey(), minimal).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM inquiry");
        assertThat(row.get("business_name")).isNull();
        assertThat(row.get("message")).isNull();
        assertThat(row.get("production_assistance_required")).isEqualTo(false);
    }

    // ---- 중복 방지 (Idempotency-Key)

    @Test
    void sameKeySameBodyReturnsExistingWithoutInsert() {
        String key = newKey();
        Map<String, Object> first = controller.create(key, request()).getBody().data();

        ResponseEntity<ApiResponse<Map<String, Object>>> again = controller.create(key, request());

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().data().get("inquiryId")).isEqualTo(first.get("inquiryId"));
        assertThat(again.getBody().data().get("receivedAt")).isEqualTo(first.get("receivedAt"));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyIsConflict() {
        String key = newKey();
        controller.create(key, request());
        InquiryRequest changed = with(r -> new InquiryRequest(r.boardId(), r.desiredPosting(), r.industry(),
                r.businessName(), r.contact(), r.productionAssistanceRequired(), "내용을 바꾼 문의", r.privacyConsent(),
                r.privacyConsentVersion()));

        assertThatThrownBy(() -> controller.create(key, changed))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void differentKeySameBodyIsNewInquiry() {
        controller.create(newKey(), request());
        controller.create(newKey(), request());

        assertThat(count()).isEqualTo(2);
    }

    // ---- 게시대 검사

    @Test
    void administrativeBoardIsNotAllowed() {
        InquiryRequest admin = with(r -> new InquiryRequest("2", r.desiredPosting(), r.industry(), r.businessName(),
                r.contact(), r.productionAssistanceRequired(), r.message(), r.privacyConsent(), r.privacyConsentVersion()));

        assertThatThrownBy(() -> controller.create(newKey(), admin))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.code()).isEqualTo("INQUIRY_NOT_ALLOWED");
                });
        assertThat(count()).isZero();
    }

    @Test
    void unknownBoardIsBoardNotFound() {
        InquiryRequest unknown = with(r -> new InquiryRequest("999", r.desiredPosting(), r.industry(), r.businessName(),
                r.contact(), r.productionAssistanceRequired(), r.message(), r.privacyConsent(), r.privacyConsentVersion()));

        assertThatThrownBy(() -> controller.create(newKey(), unknown))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.code()).isEqualTo("BOARD_NOT_FOUND");
                });
        assertThat(count()).isZero();
    }

    // ---- 값 검사 (400 VALIDATION_ERROR)

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "not-a-uuid", "6ba7b810-9dad-11d1-80b4-00c04fd430c8"}) // 마지막은 UUID v1
    void missingOrInvalidKeyIsValidationError(String key) {
        assertValidationError(key, request());
        assertValidationError(null, request());
    }

    static Stream<Arguments> invalidRequests() {
        InquiryRequest r = request();
        InquiryRequest.DesiredPosting p = r.desiredPosting();
        return Stream.of(
                Arguments.of("본문 없음", null),
                Arguments.of("boardId 숫자 아님", copy(r, "abc", p, r.industry(), r.contact(), r.message(), r.privacyConsent(), r.privacyConsentVersion())),
                Arguments.of("희망 시기 없음", copy(r, "1", null, r.industry(), r.contact(), r.message(), r.privacyConsent(), r.privacyConsentVersion())),
                Arguments.of("연도 2025", copy(r, "1", new InquiryRequest.DesiredPosting(2025, 12, "FIRST"), r.industry(), r.contact(), r.message(), r.privacyConsent(), r.privacyConsentVersion())),
                Arguments.of("13월", copy(r, "1", new InquiryRequest.DesiredPosting(2026, 13, "FIRST"), r.industry(), r.contact(), r.message(), r.privacyConsent(), r.privacyConsentVersion())),
                Arguments.of("half 잘못됨", copy(r, "1", new InquiryRequest.DesiredPosting(2026, 12, "MIDDLE"), r.industry(), r.contact(), r.message(), r.privacyConsent(), r.privacyConsentVersion())),
                Arguments.of("업종 빈 값", copy(r, "1", p, " ", r.contact(), r.message(), r.privacyConsent(), r.privacyConsentVersion())),
                Arguments.of("업종 51자", copy(r, "1", p, "가".repeat(51), r.contact(), r.message(), r.privacyConsent(), r.privacyConsentVersion())),
                Arguments.of("연락처 없음", copy(r, "1", p, r.industry(), null, r.message(), r.privacyConsent(), r.privacyConsentVersion())),
                Arguments.of("연락처 형식 아님", copy(r, "1", p, r.industry(), "연락주세요", r.message(), r.privacyConsent(), r.privacyConsentVersion())),
                Arguments.of("메시지 301자", copy(r, "1", p, r.industry(), r.contact(), "가".repeat(301), r.privacyConsent(), r.privacyConsentVersion())),
                Arguments.of("동의 안 함", copy(r, "1", p, r.industry(), r.contact(), r.message(), false, r.privacyConsentVersion())),
                Arguments.of("동의 값 없음", copy(r, "1", p, r.industry(), r.contact(), r.message(), null, r.privacyConsentVersion())),
                Arguments.of("동의 버전 없음", copy(r, "1", p, r.industry(), r.contact(), r.message(), r.privacyConsent(), "")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void invalidRequestIsValidationError(String name, InquiryRequest request) {
        assertValidationError(newKey(), request);
    }

    @Test
    void messageOfExactly300CharactersIsAccepted() {
        InquiryRequest r = request();
        InquiryRequest longest = copy(r, "1", r.desiredPosting(), r.industry(), r.contact(), "가".repeat(300),
                r.privacyConsent(), r.privacyConsentVersion());

        assertThat(controller.create(newKey(), longest).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo@example.com", "010-1234-5678", "01012345678", "02-123-4567", "031-123-4567"})
    void phoneAndEmailContactsAreAccepted(String contact) {
        InquiryRequest r = request();
        InquiryRequest withContact = copy(r, "1", r.desiredPosting(), r.industry(), contact, r.message(),
                r.privacyConsent(), r.privacyConsentVersion());

        assertThat(controller.create(newKey(), withContact).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void invalidContactErrorDoesNotEchoValue() {
        InquiryRequest r = request();
        String secret = "비밀연락처-abc";
        InquiryRequest bad = copy(r, "1", r.desiredPosting(), r.industry(), secret, r.message(), r.privacyConsent(),
                r.privacyConsentVersion());

        assertThatThrownBy(() -> controller.create(newKey(), bad))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).doesNotContain(secret));
    }

    // ---- helpers

    private void assertValidationError(String key, InquiryRequest request) {
        assertThatThrownBy(() -> controller.create(key, request))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("VALIDATION_ERROR");
                });
        assertThat(count()).isZero();
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inquiry", Integer.class);
    }

    private static InquiryRequest with(UnaryOperator<InquiryRequest> change) {
        return change.apply(request());
    }

    private static InquiryRequest copy(InquiryRequest r, String boardId, InquiryRequest.DesiredPosting posting,
                                       String industry, String contact, String message, Boolean consent,
                                       String consentVersion) {
        return new InquiryRequest(boardId, posting, industry, r.businessName(), contact,
                r.productionAssistanceRequired(), message, consent, consentVersion);
    }
}
