package com.jerryhupdev.vehicle_savvy.client;

import com.jerryhupdev.vehicle_savvy.dto.VpicDecodeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class NhtsaClient {
    private final RestClient vpic;

    public NhtsaClient(@Value("${app.nhtsa.vpic-base-url}") String vpicBaseUrl) {
        this.vpic = RestClient.builder()
                .baseUrl(vpicBaseUrl)
                .build();
    }

    public VpicDecodeResponse decodeVin(String vin) {
        return vpic.get()
                .uri("/vehicles/DecodeVinValues/{vin}?format=json", vin)
                .retrieve()
                .body(VpicDecodeResponse.class);
    }
}