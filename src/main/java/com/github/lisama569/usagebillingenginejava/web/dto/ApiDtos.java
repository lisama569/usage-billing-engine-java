package com.github.lisama569.usagebillingenginejava.web.dto;

import java.time.Instant;

/** HTTP 接口的请求/响应 DTO 集合。 */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record IngestEventRequest(String eventId, String accountId, String metric,
                                     Long quantity, Instant eventTime) {
    }

    public record PublishPriceRequest(String accountId, String metric,
                                      Long unitPriceMinor, Instant effectiveFrom) {
    }

    public record ReopenRequest(String reason) {
    }

    public record CorrectionRequest(String correctionId, String type, String metric,
                                    Long signedQuantity, Long signedAmountMinor,
                                    String reason, String linkedEventId) {
    }

    public record ErrorResponse(String errorCode, String message) {
    }
}
