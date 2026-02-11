package com.lynkvertx.pvece.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for energy storage calculation.
 * All empirical coefficients and standard values are externalized here,
 * making the calculation engine fully configurable via application.yml.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "pvece.calculation.storage")
public class StorageCalculationConfig {

    /** Empirical coefficient for ESS max power = loadPeakPower * this value */
    private BigDecimal empiricalCoefficient = new BigDecimal("0.8");

    /** Default charging power per vehicle in kW (slow charging, used as fallback) */
    private BigDecimal defaultChargingPowerKw = new BigDecimal("7");

    /** Fast charger rated power (kW) */
    private BigDecimal fastChargerPowerKw = new BigDecimal("120");

    /** Slow charger rated power (kW) */
    private BigDecimal slowChargerPowerKw = new BigDecimal("7");

    /** Ultra-fast charger rated power (kW) */
    private BigDecimal ultraFastChargerPowerKw = new BigDecimal("350");

    /** Time slot interval in minutes for load curve */
    private int timeSlotIntervalMinutes = 15;

    /** ESS unit cost in yuan/kWh (for economic calculation) */
    private BigDecimal essUnitCostYuanPerKwh = new BigDecimal("1500");

    /** ESS annual maintenance cost ratio (of initial investment) */
    private BigDecimal essAnnualMaintenanceRatio = new BigDecimal("0.02");

    /**
     * Standard transformer capacities by country (kVA).
     * If not configured, defaults are provided here.
     */
    private Map<String, List<Integer>> standardTransformerSizes = defaultTransformerSizes();

    /**
     * Standard ESS models by country.
     * Each entry is [powerKw, capacityKwh].
     * When sizing ESS, the calculated power/capacity is rounded up to these standard models.
     */
    private Map<String, List<int[]>> standardEssSizes = defaultEssSizes();

    private static Map<String, List<int[]>> defaultEssSizes() {
        Map<String, List<int[]>> map = new HashMap<>();
        // CN standard ESS models: {power kW, capacity kWh}
        map.put("CN", Arrays.asList(new int[]{100, 215}, new int[]{125, 261}));
        // JP — same defaults for now, can be overridden in yml
        map.put("JP", Arrays.asList(new int[]{100, 215}, new int[]{125, 261}));
        // UK — same defaults for now
        map.put("UK", Arrays.asList(new int[]{100, 215}, new int[]{125, 261}));
        return map;
    }

    private static Map<String, List<Integer>> defaultTransformerSizes() {
        Map<String, List<Integer>> map = new HashMap<>();
        // Chinese mainland standard transformer capacities (kVA)
        map.put("CN", Arrays.asList(
            30, 50, 80, 100, 125, 160, 200, 250, 315, 400, 500,
            630, 800, 1000, 1250, 1600, 2000, 2500, 3150
        ));
        // Japan standard transformer capacities (kVA)
        map.put("JP", Arrays.asList(
            30, 50, 75, 100, 150, 200, 300, 500, 750, 1000, 1500, 2000, 3000
        ));
        // UK standard transformer capacities (kVA)
        map.put("UK", Arrays.asList(
            25, 50, 100, 200, 315, 500, 800, 1000, 1500, 2000, 2500
        ));
        return map;
    }
}
