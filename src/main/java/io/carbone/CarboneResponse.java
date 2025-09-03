package io.carbone;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(force = true)
class CarboneResponse {

    private final boolean success;
    private final String error;
    private final String code;
    private final CarboneResponseData data;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    protected static class CarboneResponseData {
        String templateId;
        String renderId;
    }
}

