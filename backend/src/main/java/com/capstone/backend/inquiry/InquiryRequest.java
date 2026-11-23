package com.capstone.backend.inquiry;

/**
 * POST /api/v1/inquiries 요청 본문 (API 명세 4.7). 값 검사는 {@link InquiryService}가 한다.
 *
 * @param productionAssistanceRequired 없으면 false
 */
public record InquiryRequest(String boardId, DesiredPosting desiredPosting, String industry, String businessName,
                             String contact, Boolean productionAssistanceRequired, String message,
                             Boolean privacyConsent, String privacyConsentVersion) {

    /** half: FIRST(상반월) / SECOND(하반월) */
    public record DesiredPosting(Integer year, Integer month, String half) {
    }
}
