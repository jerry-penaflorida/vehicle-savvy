package com.jerryhupdev.vehicle_savvy.controller;

import com.jerryhupdev.vehicle_savvy.dto.VehicleResponse;
import com.jerryhupdev.vehicle_savvy.service.VehicleService;
import org.springframework.web.bind.annotation.*;

@RestController 
@RequestMapping ("/api/vehicles")
public class VehicleController {
    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping("/{vin}")
    public VehicleResponse getVehicle(@PathVariable String vin) {
        return vehicleService.decode(vin);
    }
}
