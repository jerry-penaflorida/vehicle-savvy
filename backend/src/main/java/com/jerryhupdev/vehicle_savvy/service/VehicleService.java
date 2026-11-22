package com.jerryhupdev.vehicle_savvy.service;

import com.jerryhupdev.vehicle_savvy.client.NhtsaClient;
import com.jerryhupdev.vehicle_savvy.dto.VehicleResponse;
import com.jerryhupdev.vehicle_savvy.dto.VpicDecodeResponse.VpicResult;
import org.springframework.stereotype.Service;

@Service
public class VehicleService {
    private final NhtsaClient nhtsaClient;

    public VehicleService(NhtsaClient nhtsaClient) {
        this.nhtsaClient = nhtsaClient;
    }

    public VehicleResponse decode(String vin) {
        VpicResult r = nhtsaClient.decodeVin(vin).results().get(0);
        return new VehicleResponse(
            vin,
            parseYear(r.modelYear()),
            blankToNull(r.make()),
            blankToNull(r.model()),
            blankToNull(r.bodyClass()),
            blankToNull(r.fuelType())
        );
    }

    private static Integer parseYear(String s) {
        return (s == null || s.isBlank()) ? null : Integer.valueOf(s);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;   // NHTSA sends "" for unknown fields
    }
}