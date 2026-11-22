package com.jerryhupdev.vehicle_savvy.dto;

public record VehicleResponse(
    String vin,
    Integer year,
    String make,
    String model,
    String bodyClass,
    String fuelType
) {}