package com.github.lisama569.usagebillingenginejava.domain;

import java.time.Instant;

/** 某个账号+计量项下的一个价格版本，单价为最小货币单位（分）/单位用量。 */
public record PriceVersion(
        String accountId,
        String metric,
        int version,
        long unitPriceMinor,
        Instant effectiveFrom,
        Instant createdAt
) {
}
