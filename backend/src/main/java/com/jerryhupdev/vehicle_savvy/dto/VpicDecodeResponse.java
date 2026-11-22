package com.jerryhupdev.vehicle_savvy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VpicDecodeResponse(
    @JsonProperty("Results") List<VpicResult> results
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VpicResult(
        @JsonProperty("Make") String make,
        @JsonProperty("Model") String model,
        @JsonProperty("ModelYear") String modelYear,
        @JsonProperty("BodyClass") String bodyClass,
        @JsonProperty("FuelTypePrimary") String fuelType,
        @JsonProperty("ErrorCode") String errorCode,
        @JsonProperty("ErrorText") String errorText
    ) {}
}