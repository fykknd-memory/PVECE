package com.lynkvertx.pvece.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynkvertx.pvece.config.StorageCalculationConfig;
import com.lynkvertx.pvece.dto.LoadCurveResultDTO;
import com.lynkvertx.pvece.dto.StorageCalculationRequestDTO;
import com.lynkvertx.pvece.dto.StorageCalculationResultDTO;
import com.lynkvertx.pvece.dto.StorageCalculationResultDTO.LoadCurvePoint;
import com.lynkvertx.pvece.dto.StorageCalculationResultDTO.YearlyEconomicIndicator;
import com.lynkvertx.pvece.dto.V2gCalculationRequestDTO;
import com.lynkvertx.pvece.dto.V2gCalculationResultDTO;
import com.lynkvertx.pvece.dto.V2gVehicleConfigDTO;
import com.lynkvertx.pvece.entity.Project;
import com.lynkvertx.pvece.entity.ProjectElectricityPrice;
import com.lynkvertx.pvece.entity.PvSystemConfig;
import com.lynkvertx.pvece.entity.V2gVehicleConfig;
import com.lynkvertx.pvece.repository.ProjectElectricityPriceRepository;
import com.lynkvertx.pvece.repository.ProjectRepository;
import com.lynkvertx.pvece.repository.PvSystemConfigRepository;
import com.lynkvertx.pvece.repository.V2gVehicleConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * Energy Storage Calculation Service
 *
 * Core calculation engine that determines the optimal energy storage system (ESS) configuration
 * based on photovoltaic system parameters, V1G vehicle charging loads, and time-of-use (TOU) electricity prices.
 *
 * This service is designed for modular reuse (potential MCP packaging).
 * Each calculation step is a separate method with detailed comments explaining the logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageCalculationService {

    private final ProjectRepository projectRepository;
    private final PvSystemConfigRepository pvConfigRepository;
    private final V2gVehicleConfigRepository v2gConfigRepository;
    private final ProjectElectricityPriceRepository priceRepository;
    private final StorageCalculationConfig config;
    private final ObjectMapper objectMapper;

    /**
     * Calculate load curve for a project (standalone endpoint).
     * Loads all required data from DB and returns the load curve + peak power.
     *
     * @param projectId The project to calculate for
     * @return Load curve result with peak power and daily energy
     */
    public LoadCurveResultDTO calculateLoadCurveForProject(Long projectId) {
        List<String> steps = new ArrayList<>();

        // Load input data
        V2gVehicleConfig v2gEntity = v2gConfigRepository.findByProjectId(projectId)
            .orElseThrow(() -> new IllegalArgumentException("V2G vehicle config is required. Please configure it on the Parameters page."));

        List<ProjectElectricityPrice> priceEntities = priceRepository.findByProjectId(projectId);
        if (priceEntities.isEmpty()) {
            throw new IllegalArgumentException("TOU electricity prices are required. Please configure them on the Parameters page.");
        }

        List<V2gVehicleConfigDTO.WeeklyScheduleEntry> weeklySchedule = parseWeeklySchedule(v2gEntity);
        List<TouPricePeriod> touPrices = parseTouPrices(priceEntities);

        int vehicleCount = v2gEntity.getVehicleCount() != null ? v2gEntity.getVehicleCount() : 0;
        BigDecimal batteryCapacityKwh = v2gEntity.getBatteryCapacityKwh() != null
            ? v2gEntity.getBatteryCapacityKwh()
            : BigDecimal.ZERO;
        boolean enableTimeControl = v2gEntity.getEnableTimeControl() != null ? v2gEntity.getEnableTimeControl() : true;

        int fastChargers = v2gEntity.getFastChargers() != null ? v2gEntity.getFastChargers() : 0;
        int slowChargers = v2gEntity.getSlowChargers() != null ? v2gEntity.getSlowChargers() : 0;
        int ultraFastChargers = v2gEntity.getUltraFastChargers() != null ? v2gEntity.getUltraFastChargers() : 0;
        int fastChargersV2g = v2gEntity.getFastChargersV2g() != null ? v2gEntity.getFastChargersV2g() : 0;
        int slowChargersV2g = v2gEntity.getSlowChargersV2g() != null ? v2gEntity.getSlowChargersV2g() : 0;
        int ultraFastChargersV2g = v2gEntity.getUltraFastChargersV2g() != null ? v2gEntity.getUltraFastChargersV2g() : 0;
        int totalV2gPiles = fastChargersV2g + slowChargersV2g + ultraFastChargersV2g;
        boolean v2gEnabled = totalV2gPiles > 0;

        // Calculate total charging power from pile configuration (capped by vehicle count)
        BigDecimal totalChargingPowerKw = calculateTotalChargingPower(fastChargers, slowChargers, ultraFastChargers, vehicleCount);

        steps.add(String.format("Step 1: Vehicle count=%d, battery=%.1fkWh, enableTimeControl=%s",
            vehicleCount, batteryCapacityKwh.doubleValue(), enableTimeControl));
        steps.add(String.format("Step 2: Charging piles — fast:%d(%.0fkW) slow:%d(%.0fkW) ultra:%d(%.0fkW), active piles=%d, total power=%.0fkW",
            fastChargers, config.getFastChargerPowerKw().doubleValue(),
            slowChargers, config.getSlowChargerPowerKw().doubleValue(),
            ultraFastChargers, config.getUltraFastChargerPowerKw().doubleValue(),
            Math.min(vehicleCount, fastChargers + slowChargers + ultraFastChargers),
            totalChargingPowerKw.doubleValue()));

        if (v2gEnabled) {
            steps.add(String.format("Step 2-V2G: V2G piles — fast:%d slow:%d ultra:%d, total V2G piles=%d",
                fastChargersV2g, slowChargersV2g, ultraFastChargersV2g, totalV2gPiles));

            BigDecimal v2gDischargePowerKw = calculateTotalV2gDischargePower(
                fastChargersV2g, slowChargersV2g, ultraFastChargersV2g, vehicleCount, null);
            BigDecimal v2gChargePowerKw = calculateTotalChargingPower(
                fastChargersV2g, slowChargersV2g, ultraFastChargersV2g, vehicleCount);
            // V1G piles: total minus V2G
            BigDecimal v1gChargePowerKw = calculateTotalChargingPower(
                fastChargers - fastChargersV2g, slowChargers - slowChargersV2g,
                ultraFastChargers - ultraFastChargersV2g,
                Math.max(0, vehicleCount - totalV2gPiles));

            DailyLoadCurveResult curveResult = calculateLoadCurveWithV2g(
                weeklySchedule, touPrices, vehicleCount, batteryCapacityKwh, enableTimeControl,
                v1gChargePowerKw, v2gChargePowerKw, v2gDischargePowerKw,
                totalV2gPiles, steps
            );

            BigDecimal dailyArbitrage = curveResult.maxDailyArbitrage;

            steps.add(String.format("Step 3: Load curve peak charge power = %.2fkW", curveResult.peakPowerKw.doubleValue()));
            steps.add(String.format("Step 3a: Daily max energy consumption = %.2fkWh", curveResult.dailyMaxEnergyKwh.doubleValue()));
            steps.add(String.format("Step 3b: V2G daily arbitrage revenue = %.2f元", dailyArbitrage.doubleValue()));

            // Peak discharge power = rated V2G pile capability, not curve-derived
            BigDecimal peakDischarge = v2gDischargePowerKw;
            BigDecimal dailyDischargeEnergy = calculateDailyDischargeEnergy(curveResult.dailyCurves);

            return LoadCurveResultDTO.builder()
                .loadCurve(curveResult.maxEnvelopeCurve)
                .dailyLoadCurves(curveResult.dailyCurves)
                .peakPowerKw(curveResult.peakPowerKw)
                .dailyEnergyKwh(curveResult.dailyMaxEnergyKwh)
                .dailyDischargeEnergyKwh(dailyDischargeEnergy)
                .peakDischargePowerKw(peakDischarge)
                .dailyArbitrageRevenue(dailyArbitrage)
                .v2gEnabled(true)
                .calculationSteps(steps)
                .build();
        }

        // V1G-only path (unchanged)
        DailyLoadCurveResult curveResult = calculateLoadCurve(
            weeklySchedule, touPrices, vehicleCount, batteryCapacityKwh, enableTimeControl, totalChargingPowerKw, steps
        );

        steps.add(String.format("Step 3: Load curve peak power P_all-load-max = %.2fkW", curveResult.peakPowerKw.doubleValue()));
        steps.add(String.format("Step 3a: Daily max energy consumption = %.2fkWh", curveResult.dailyMaxEnergyKwh.doubleValue()));

        return LoadCurveResultDTO.builder()
            .loadCurve(curveResult.maxEnvelopeCurve)
            .dailyLoadCurves(curveResult.dailyCurves)
            .peakPowerKw(curveResult.peakPowerKw)
            .dailyEnergyKwh(curveResult.dailyMaxEnergyKwh)
            .v2gEnabled(false)
            .calculationSteps(steps)
            .build();
    }

    /**
     * Main entry point: orchestrates all calculation steps.
     *
     * Steps:
     * 1. Load all input data (project, PV config, V2G config, TOU prices)
     * 2. Build a 24-hour load curve from V1G charging schedule (15-min intervals)
     * 3. Find peak power from the load curve
     * 4. Determine transformer capacity (auto-calculate if not specified)
     * 5. Calculate ESS max power using empirical coefficient
     * 6. Calculate ESS rated power by subtracting PV peak shaving
     * 7. Validate against transformer capacity constraints
     * 8. Generate 1-20 year economic indicators
     *
     * @param projectId The project to calculate for
     * @param request   Calculation parameters (optimization type, decay, peak shaving, etc.)
     * @return Complete calculation result
     */
    public StorageCalculationResultDTO calculate(Long projectId, StorageCalculationRequestDTO request) {
        List<String> steps = new ArrayList<>();

        // === Step 0: Load input data ===
        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + projectId));

        PvSystemConfig pvConfig = pvConfigRepository.findByProjectId(projectId)
            .orElseThrow(() -> new IllegalArgumentException("PV system config is required. Please configure it on the Parameters page."));

        V2gVehicleConfig v2gEntity = v2gConfigRepository.findByProjectId(projectId)
            .orElseThrow(() -> new IllegalArgumentException("V2G vehicle config is required. Please configure it on the Parameters page."));

        List<ProjectElectricityPrice> priceEntities = priceRepository.findByProjectId(projectId);
        if (priceEntities.isEmpty()) {
            throw new IllegalArgumentException("TOU electricity prices are required. Please configure them on the Parameters page.");
        }

        // Parse V2G schedule data
        List<V2gVehicleConfigDTO.WeeklyScheduleEntry> weeklySchedule = parseWeeklySchedule(v2gEntity);
        List<TouPricePeriod> touPrices = parseTouPrices(priceEntities);

        BigDecimal pvPeakPowerKw = pvConfig.getInstalledCapacityKw() != null
            ? pvConfig.getInstalledCapacityKw()
            : BigDecimal.ZERO;

        int vehicleCount = v2gEntity.getVehicleCount() != null ? v2gEntity.getVehicleCount() : 0;
        BigDecimal batteryCapacityKwh = v2gEntity.getBatteryCapacityKwh() != null
            ? v2gEntity.getBatteryCapacityKwh()
            : BigDecimal.ZERO;
        boolean enableTimeControl = v2gEntity.getEnableTimeControl() != null ? v2gEntity.getEnableTimeControl() : true;

        int fastChargers = v2gEntity.getFastChargers() != null ? v2gEntity.getFastChargers() : 0;
        int slowChargers = v2gEntity.getSlowChargers() != null ? v2gEntity.getSlowChargers() : 0;
        int ultraFastChargers = v2gEntity.getUltraFastChargers() != null ? v2gEntity.getUltraFastChargers() : 0;

        // Determine country for transformer lookup
        String country = priceEntities.get(0).getCountry();
        if (country == null || country.isEmpty()) country = "CN";

        // Calculate total charging power from pile configuration (capped by vehicle count)
        BigDecimal totalChargingPowerKw = calculateTotalChargingPower(fastChargers, slowChargers, ultraFastChargers, vehicleCount);

        steps.add(String.format("Step 1: Vehicle count=%d, battery=%.1fkWh, enableTimeControl=%s",
            vehicleCount, batteryCapacityKwh.doubleValue(), enableTimeControl));
        steps.add(String.format("Step 2: Charging piles — fast:%d(%.0fkW) slow:%d(%.0fkW) ultra:%d(%.0fkW), active piles=%d, total power=%.0fkW",
            fastChargers, config.getFastChargerPowerKw().doubleValue(),
            slowChargers, config.getSlowChargerPowerKw().doubleValue(),
            ultraFastChargers, config.getUltraFastChargerPowerKw().doubleValue(),
            Math.min(vehicleCount, fastChargers + slowChargers + ultraFastChargers),
            totalChargingPowerKw.doubleValue()));
        steps.add(String.format("Step 2a: PV installed capacity = %.2fkW", pvPeakPowerKw.doubleValue()));

        // === Step 1: Calculate per-day load curves ===
        DailyLoadCurveResult curveResult = calculateLoadCurve(
            weeklySchedule, touPrices, vehicleCount, batteryCapacityKwh, enableTimeControl, totalChargingPowerKw, steps
        );
        List<LoadCurvePoint> loadCurve = curveResult.maxEnvelopeCurve;

        // === Step 2: Find peak power in the load curve ===
        BigDecimal loadPeakPowerKw = curveResult.peakPowerKw;
        steps.add(String.format("Step 3: Load curve peak power P_all-load-max = %.2fkW", loadPeakPowerKw.doubleValue()));

        // === Step 3: Determine transformer capacity ===
        boolean transformerAutoCalculated = false;
        BigDecimal transformerCapacityKva;

        if (project.getTransformerCapacity() != null && project.getTransformerCapacity().compareTo(BigDecimal.ZERO) > 0) {
            // Case 2: User specified transformer capacity — use as-is
            transformerCapacityKva = project.getTransformerCapacity();
            steps.add(String.format("Step 4: Transformer capacity (user-specified) = %.0fkVA", transformerCapacityKva.doubleValue()));
        } else {
            // Case 1: No transformer specified — auto-calculate based on peak load
            transformerCapacityKva = selectTransformerCapacity(loadPeakPowerKw, country);
            transformerAutoCalculated = true;
            steps.add(String.format("Step 4: Transformer auto-selected = %.0fkVA (%s standard), based on peak load %.2fkW",
                transformerCapacityKva.doubleValue(), country, loadPeakPowerKw.doubleValue()));
        }

        // === Step 4: Calculate ESS max power ===
        BigDecimal essMaxPowerKw = calculateEssMaxPower(loadPeakPowerKw);
        steps.add(String.format("Step 5: ESS max power = P_all-load-max(%.2f) × coefficient(%.2f) = %.2fkW",
            loadPeakPowerKw.doubleValue(), config.getEmpiricalCoefficient().doubleValue(), essMaxPowerKw.doubleValue()));

        // === Step 5: Calculate ESS rated power ===
        BigDecimal essRatedPowerKw = calculateEssRatedPower(essMaxPowerKw, pvPeakPowerKw);
        steps.add(String.format("Step 6: ESS rated power = ESS max(%.2f) - PV peak(%.2f) = %.2fkW",
            essMaxPowerKw.doubleValue(), pvPeakPowerKw.doubleValue(), essRatedPowerKw.doubleValue()));

        // === Step 6: Validate against transformer capacity ===
        String warning = validateAgainstTransformer(essRatedPowerKw, transformerCapacityKva);
        if (warning != null) {
            steps.add("Step 7: WARNING — " + warning);
        } else {
            steps.add(String.format("Step 7: Validation passed — ESS rated power(%.2f) <= transformer capacity(%.0f)",
                essRatedPowerKw.doubleValue(), transformerCapacityKva.doubleValue()));
        }

        // === Step 7: Calculate raw ESS capacity ===
        BigDecimal chargeDurationHours = "two".equals(request.getChargeMode())
            ? new BigDecimal("4") : new BigDecimal("2");
        BigDecimal essCalculatedCapacityKwh = essRatedPowerKw.multiply(chargeDurationHours).setScale(2, RoundingMode.HALF_UP);
        steps.add(String.format("Step 8: Calculated ESS capacity = rated power(%.2f) × duration(%.0fh) = %.2fkWh (mode: %s)",
            essRatedPowerKw.doubleValue(), chargeDurationHours.doubleValue(), essCalculatedCapacityKwh.doubleValue(),
            "two".equals(request.getChargeMode()) ? "two charges two discharges" : "one charge one discharge"));

        // === Step 8: Round up to standard ESS model ===
        BigDecimal essCalculatedPowerKw = essRatedPowerKw;
        int[] essModel = selectEssConfiguration(essRatedPowerKw, essCalculatedCapacityKwh, country);
        int essModelPower = essModel[0];
        int essModelCapacity = essModel[1];
        int essUnits = essModel[2];
        BigDecimal actualEssPowerKw = new BigDecimal(essModelPower * essUnits);
        BigDecimal actualEssCapacityKwh = new BigDecimal(essModelCapacity * essUnits);
        steps.add(String.format("Step 8a: Standard ESS model selected (%s): %dkW/%dkWh × %d units = %.0fkW / %.0fkWh",
            country, essModelPower, essModelCapacity, essUnits,
            actualEssPowerKw.doubleValue(), actualEssCapacityKwh.doubleValue()));

        // === Step 9: Calculate 1-20 year economic indicators ===
        List<YearlyEconomicIndicator> yearlyEconomics = calculateYearlyEconomics(
            actualEssCapacityKwh, actualEssPowerKw, touPrices,
            request.getAnnualDecayPercent(),
            request.isEnablePeakShaving(),
            request.getPeakShavingSubsidy(),
            request.getChargeMode()
        );
        steps.add(String.format("Step 10: Economic indicators calculated for 20 years, initial investment = %.0f yuan",
            actualEssCapacityKwh.multiply(config.getEssUnitCostYuanPerKwh()).doubleValue()));

        return StorageCalculationResultDTO.builder()
            .essRatedPowerKw(actualEssPowerKw)
            .essCapacityKwh(actualEssCapacityKwh)
            .essCalculatedPowerKw(essCalculatedPowerKw)
            .essCalculatedCapacityKwh(essCalculatedCapacityKwh)
            .essModelPowerKw(new BigDecimal(essModelPower))
            .essModelCapacityKwh(new BigDecimal(essModelCapacity))
            .essUnits(essUnits)
            .loadPeakPowerKw(loadPeakPowerKw)
            .pvPeakPowerKw(pvPeakPowerKw)
            .transformerCapacityKva(transformerCapacityKva)
            .transformerAutoCalculated(transformerAutoCalculated)
            .warning(warning)
            .loadCurve(loadCurve)
            .yearlyEconomics(yearlyEconomics)
            .calculationSteps(steps)
            .build();
    }

    /**
     * Calculate total max charging power, capped by the number of vehicles.
     *
     * When there are more piles than vehicles, not all piles can be active simultaneously.
     * We assign vehicles to the highest-power piles first to maximize peak power.
     *
     * Algorithm:
     * 1. Expand all piles into a list of individual pile powers.
     * 2. Sort descending by power.
     * 3. Take the top min(vehicleCount, totalPiles) piles.
     * 4. Sum their power = total max charging power.
     *
     * Example: 8 vehicles, 1×350kW + 2×120kW + 6×7kW = 9 piles
     *   → Top 8: 350 + 120 + 120 + 7×5 = 625kW
     *
     * @param fastChargers      Number of fast chargers (120kW default)
     * @param slowChargers      Number of slow chargers (7kW default)
     * @param ultraFastChargers Number of ultra-fast chargers (350kW default)
     * @param vehicleCount      Number of vehicles (caps the active piles)
     * @return Total available charging power in kW
     */
    BigDecimal calculateTotalChargingPower(int fastChargers, int slowChargers, int ultraFastChargers, int vehicleCount) {
        // Build a list of individual pile powers
        List<BigDecimal> piles = new ArrayList<>();
        for (int i = 0; i < ultraFastChargers; i++) {
            piles.add(config.getUltraFastChargerPowerKw());
        }
        for (int i = 0; i < fastChargers; i++) {
            piles.add(config.getFastChargerPowerKw());
        }
        for (int i = 0; i < slowChargers; i++) {
            piles.add(config.getSlowChargerPowerKw());
        }

        // Fallback: if no piles configured, use default charging power
        if (piles.isEmpty()) {
            return config.getDefaultChargingPowerKw();
        }

        // Sort descending (highest power first)
        piles.sort(Comparator.reverseOrder());

        // Cap by vehicleCount: only min(vehicleCount, totalPiles) piles can be active
        int activePiles = Math.min(vehicleCount, piles.size());

        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < activePiles; i++) {
            total = total.add(piles.get(i));
        }
        return total;
    }

    /**
     * Calculate per-day V1G charging load curves using a greedy algorithm.
     *
     * For each operating day, builds a separate load curve based on that day's chargeable time ranges.
     * Then computes a max envelope curve (max power at each slot across all days).
     *
     * Algorithm per day:
     * 1. Divide 24 hours into 96 time slots (15 min each).
     * 2. Determine chargeable slots for this specific day.
     * 3. Sort chargeable slots by TOU price ascending (greedy: fill cheapest first).
     * 4. Fill cheapest slots until daily energy demand is met.
     *
     * @param weeklySchedule      V2G weekly schedule entries (with multiple chargeableRanges)
     * @param touPrices            TOU price periods
     * @param vehicleCount         Number of charging vehicles
     * @param batteryKwh           Battery capacity per vehicle (kWh)
     * @param enableTimeControl    If false, all slots are chargeable; if true, only configured ranges
     * @param totalChargingPowerKw Total available charging power from all piles
     * @param steps                Debug steps list (mutated to record calculation steps)
     * @return DailyLoadCurveResult containing per-day curves and max envelope
     */
    DailyLoadCurveResult calculateLoadCurve(
        List<V2gVehicleConfigDTO.WeeklyScheduleEntry> weeklySchedule,
        List<TouPricePeriod> touPrices,
        int vehicleCount,
        BigDecimal batteryKwh,
        boolean enableTimeControl,
        BigDecimal totalChargingPowerKw,
        List<String> steps
    ) {
        int slotsPerDay = 24 * 60 / config.getTimeSlotIntervalMinutes(); // 96 slots

        // Collect per-range minSoc values and determine effective minSoc (max across all ranges)
        int effectiveMinSocPercent = 80; // default
        List<Integer> rangeMinSocs = new ArrayList<>();
        if (weeklySchedule != null) {
            for (V2gVehicleConfigDTO.WeeklyScheduleEntry entry : weeklySchedule) {
                if (entry.isOperating() && entry.getChargeableRanges() != null) {
                    for (V2gVehicleConfigDTO.TimeRange range : entry.getChargeableRanges()) {
                        if (range.getMinSoc() != null && range.getMinSoc() > 0) {
                            rangeMinSocs.add(range.getMinSoc());
                        }
                    }
                }
            }
        }
        if (!rangeMinSocs.isEmpty()) {
            effectiveMinSocPercent = Collections.max(rangeMinSocs);
        }

        // Total daily energy needed: all vehicles charge from 0% to effectiveMinSoc%
        BigDecimal socRange = new BigDecimal(effectiveMinSocPercent)
            .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        BigDecimal dailyEnergyKwh = batteryKwh.multiply(socRange).multiply(new BigDecimal(vehicleCount));

        // Energy per slot capped by total pile power × interval hours
        BigDecimal intervalHours = new BigDecimal(config.getTimeSlotIntervalMinutes())
            .divide(new BigDecimal("60"), 4, RoundingMode.HALF_UP);
        BigDecimal maxEnergyPerSlotKwh = totalChargingPowerKw.multiply(intervalHours);

        if (steps != null) {
            steps.add(String.format("Step 2b: Effective minSOC = %d%% (max across all chargeable ranges, %d ranges found)",
                effectiveMinSocPercent, rangeMinSocs.size()));
            steps.add(String.format("Step 2c: Daily energy demand = %d vehicles × %.1fkWh × %d%% SOC = %.2fkWh",
                vehicleCount, batteryKwh.doubleValue(), effectiveMinSocPercent, dailyEnergyKwh.doubleValue()));
            steps.add(String.format("Step 2d: Max energy per 15-min slot = %.0fkW × %.4fh = %.2fkWh",
                totalChargingPowerKw.doubleValue(), intervalHours.doubleValue(), maxEnergyPerSlotKwh.doubleValue()));
        }

        // Day name mapping for weekly schedule indices
        String[] dayNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

        // Per-day curves map (LinkedHashMap preserves insertion order Mon→Sun)
        Map<String, List<LoadCurvePoint>> dailyCurves = new LinkedHashMap<>();

        if (!enableTimeControl) {
            // Time control disabled: all slots are chargeable, same curve for all 7 days
            if (steps != null) {
                steps.add("Step 2e: Time control DISABLED — all 96 slots are chargeable for all days");
            }
            Set<Integer> allSlots = new HashSet<>();
            for (int i = 0; i < slotsPerDay; i++) allSlots.add(i);

            List<LoadCurvePoint> singleCurve = calculateSingleDayCurve(
                allSlots, touPrices, dailyEnergyKwh, maxEnergyPerSlotKwh, intervalHours, slotsPerDay);
            for (String dayName : dayNames) {
                dailyCurves.put(dayName, singleCurve);
            }
        } else {
            // Time control enabled: compute separate curve for each operating day
            if (weeklySchedule != null && !weeklySchedule.isEmpty()) {
                for (int dayIdx = 0; dayIdx < weeklySchedule.size() && dayIdx < 7; dayIdx++) {
                    V2gVehicleConfigDTO.WeeklyScheduleEntry entry = weeklySchedule.get(dayIdx);
                    String dayName = dayNames[dayIdx];

                    if (!entry.isOperating()) continue;

                    // Build chargeable slots for THIS day only
                    Set<Integer> daySlots = new HashSet<>();
                    if (entry.getChargeableRanges() != null) {
                        for (V2gVehicleConfigDTO.TimeRange range : entry.getChargeableRanges()) {
                            if (range.getStart() != null && range.getEnd() != null
                                && !range.getStart().isEmpty() && !range.getEnd().isEmpty()) {
                                int fromSlot = timeToSlotIndex(range.getStart(), config.getTimeSlotIntervalMinutes());
                                int toSlot = timeToSlotIndex(range.getEnd(), config.getTimeSlotIntervalMinutes());
                                addSlotsInRange(daySlots, fromSlot, toSlot, slotsPerDay);
                            }
                        }
                    }

                    if (daySlots.isEmpty()) {
                        if (steps != null) {
                            steps.add(String.format("Step 2e[%s]: No chargeable slots — zero curve", dayName));
                        }
                        // Zero curve for this day
                        List<LoadCurvePoint> zeroCurve = new ArrayList<>();
                        for (int i = 0; i < slotsPerDay; i++) {
                            zeroCurve.add(new LoadCurvePoint(
                                slotIndexToTime(i, config.getTimeSlotIntervalMinutes()), BigDecimal.ZERO));
                        }
                        dailyCurves.put(dayName, zeroCurve);
                    } else {
                        if (steps != null) {
                            steps.add(String.format("Step 2e[%s]: %d chargeable slots from configured ranges",
                                dayName, daySlots.size()));
                        }
                        List<LoadCurvePoint> dayCurve = calculateSingleDayCurve(
                            daySlots, touPrices, dailyEnergyKwh, maxEnergyPerSlotKwh, intervalHours, slotsPerDay);
                        dailyCurves.put(dayName, dayCurve);
                    }
                }
            }

            if (dailyCurves.isEmpty()) {
                if (steps != null) {
                    steps.add("Step 2f: No operating days found — all curves are zero");
                }
                // Return zero envelope
                List<LoadCurvePoint> zeroCurve = new ArrayList<>();
                for (int i = 0; i < slotsPerDay; i++) {
                    zeroCurve.add(new LoadCurvePoint(
                        slotIndexToTime(i, config.getTimeSlotIntervalMinutes()), BigDecimal.ZERO));
                }
                return new DailyLoadCurveResult(dailyCurves, zeroCurve, BigDecimal.ZERO, BigDecimal.ZERO);
            }
        }

        // Compute max envelope curve: for each slot, take max power across all days
        List<LoadCurvePoint> maxEnvelope = new ArrayList<>();
        for (int i = 0; i < slotsPerDay; i++) {
            BigDecimal maxPower = BigDecimal.ZERO;
            for (List<LoadCurvePoint> dayCurve : dailyCurves.values()) {
                if (i < dayCurve.size()) {
                    BigDecimal power = dayCurve.get(i).getPowerKw();
                    if (power.compareTo(maxPower) > 0) {
                        maxPower = power;
                    }
                }
            }
            maxEnvelope.add(new LoadCurvePoint(
                slotIndexToTime(i, config.getTimeSlotIntervalMinutes()), maxPower));
        }

        // Peak power = max across envelope
        BigDecimal peakPowerKw = findPeakPower(maxEnvelope);

        // Daily max energy = max(sum of each day's energy)
        BigDecimal dailyMaxEnergy = BigDecimal.ZERO;
        for (Map.Entry<String, List<LoadCurvePoint>> entry : dailyCurves.entrySet()) {
            BigDecimal dayEnergy = calculateDailyEnergy(entry.getValue());
            if (dayEnergy.compareTo(dailyMaxEnergy) > 0) {
                dailyMaxEnergy = dayEnergy;
            }
        }

        if (steps != null) {
            steps.add(String.format("Step 2f: Per-day curves computed for %d days, envelope peak=%.2fkW, max daily energy=%.2fkWh",
                dailyCurves.size(), peakPowerKw.doubleValue(), dailyMaxEnergy.doubleValue()));
        }

        return new DailyLoadCurveResult(dailyCurves, maxEnvelope, peakPowerKw, dailyMaxEnergy);
    }

    /**
     * Calculate a single day's load curve using the greedy fill algorithm.
     *
     * Sorts chargeable slots by TOU price ascending and fills cheapest first
     * until the daily energy demand is met.
     *
     * @param chargeableSlotSet  Set of slot indices that are chargeable for this day
     * @param touPrices          TOU price periods
     * @param dailyEnergyKwh    Total energy demand for the day
     * @param maxEnergyPerSlot   Max energy per slot (pile power × interval hours)
     * @param intervalHours      Duration of one slot in hours (0.25 for 15-min)
     * @param slotsPerDay        Total slots per day (96 for 15-min intervals)
     * @return Load curve as list of LoadCurvePoints for this day
     */
    private List<LoadCurvePoint> calculateSingleDayCurve(
        Set<Integer> chargeableSlotSet,
        List<TouPricePeriod> touPrices,
        BigDecimal dailyEnergyKwh,
        BigDecimal maxEnergyPerSlot,
        BigDecimal intervalHours,
        int slotsPerDay
    ) {
        // Build slot data: each slot gets a TOU price
        List<SlotData> slots = new ArrayList<>();
        for (int i = 0; i < slotsPerDay; i++) {
            String timeStr = slotIndexToTime(i, config.getTimeSlotIntervalMinutes());
            BigDecimal price = getTouPriceForSlot(timeStr, touPrices);
            boolean chargeable = chargeableSlotSet.contains(i);
            slots.add(new SlotData(i, timeStr, price, chargeable));
        }

        // Sort chargeable slots by price ascending (greedy: fill cheapest first)
        List<SlotData> chargeableSlots = slots.stream()
            .filter(s -> s.chargeable)
            .sorted(Comparator.comparing(s -> s.price))
            .collect(Collectors.toList());

        // Fill cheapest slots until daily energy demand is met
        BigDecimal remainingEnergy = dailyEnergyKwh;
        BigDecimal[] slotPower = new BigDecimal[slotsPerDay];
        BigDecimal[] slotEnergy = new BigDecimal[slotsPerDay];
        Arrays.fill(slotPower, BigDecimal.ZERO);
        Arrays.fill(slotEnergy, BigDecimal.ZERO);

        // Rated power = max energy per slot / interval (chargers run at full rated power)
        BigDecimal ratedPower = maxEnergyPerSlot.compareTo(BigDecimal.ZERO) > 0
            ? maxEnergyPerSlot.divide(intervalHours, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        for (SlotData slot : chargeableSlots) {
            if (remainingEnergy.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal energyThisSlot = remainingEnergy.min(maxEnergyPerSlot);
            slotPower[slot.index] = ratedPower;     // rated power (charger at full power)
            slotEnergy[slot.index] = energyThisSlot; // actual energy (may be less for last slot)
            remainingEnergy = remainingEnergy.subtract(energyThisSlot);
        }

        // Build the load curve
        List<LoadCurvePoint> curve = new ArrayList<>();
        for (int i = 0; i < slotsPerDay; i++) {
            curve.add(new LoadCurvePoint(
                slotIndexToTime(i, config.getTimeSlotIntervalMinutes()),
                slotPower[i], BigDecimal.ZERO, slotEnergy[i], BigDecimal.ZERO
            ));
        }
        return curve;
    }

    /**
     * Calculate total daily energy from a load curve.
     * Sum of (power × interval hours) for each slot.
     */
    BigDecimal calculateDailyEnergy(List<LoadCurvePoint> loadCurve) {
        BigDecimal intervalHours = new BigDecimal(config.getTimeSlotIntervalMinutes())
            .divide(new BigDecimal("60"), 4, RoundingMode.HALF_UP);
        BigDecimal total = BigDecimal.ZERO;
        for (LoadCurvePoint point : loadCurve) {
            // Use actual energy if available (powerKw may be rated power, not time-averaged)
            if (point.getEnergyKwh() != null && point.getEnergyKwh().compareTo(BigDecimal.ZERO) > 0) {
                total = total.add(point.getEnergyKwh());
            } else {
                total = total.add(point.getPowerKw().multiply(intervalHours));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Find the peak power (maximum value) in the load curve.
     *
     * @param loadCurve The 24-hour load curve
     * @return Maximum power in kW
     */
    BigDecimal findPeakPower(List<LoadCurvePoint> loadCurve) {
        return loadCurve.stream()
            .map(LoadCurvePoint::getPowerKw)
            .max(Comparator.naturalOrder())
            .orElse(BigDecimal.ZERO);
    }

    /**
     * Select the appropriate standard transformer capacity for a given country.
     *
     * The method rounds up the required power (kW) to the nearest standard transformer
     * capacity (kVA) available in the specified country's market.
     * For example, if requiredKw = 1200kW for CN, the result is 1250kVA.
     *
     * @param requiredKw The minimum required capacity in kW
     * @param country    Country code (CN, JP, UK)
     * @return Standard transformer capacity in kVA
     */
    BigDecimal selectTransformerCapacity(BigDecimal requiredKw, String country) {
        List<Integer> sizes = config.getStandardTransformerSizes()
            .getOrDefault(country, config.getStandardTransformerSizes().get("CN"));

        if (sizes == null || sizes.isEmpty()) {
            // Fallback: round up to nearest 100
            return requiredKw.divide(new BigDecimal("100"), 0, RoundingMode.CEILING)
                .multiply(new BigDecimal("100"));
        }

        // Find the smallest standard size >= requiredKw
        for (int size : sizes) {
            if (new BigDecimal(size).compareTo(requiredKw) >= 0) {
                return new BigDecimal(size);
            }
        }

        // If all standard sizes are smaller, return the largest one
        return new BigDecimal(sizes.get(sizes.size() - 1));
    }

    /**
     * Select the optimal standard ESS configuration for a given country.
     *
     * For each standard model available in the country, calculate how many units
     * are needed to satisfy both the required power and required capacity.
     * Pick the model that requires the fewest total units. If tied, pick the one
     * with the lowest total capacity (least overprovisioning).
     *
     * @param requiredPowerKw    Raw calculated ESS power requirement (kW)
     * @param requiredCapacityKwh Raw calculated ESS capacity requirement (kWh)
     * @param country             Country code for standard model lookup
     * @return int[3]: {modelPowerKw, modelCapacityKwh, numberOfUnits}
     */
    int[] selectEssConfiguration(BigDecimal requiredPowerKw, BigDecimal requiredCapacityKwh, String country) {
        List<int[]> models = config.getStandardEssSizes()
            .getOrDefault(country, config.getStandardEssSizes().get("CN"));

        if (models == null || models.isEmpty()) {
            // Fallback: treat required values as a single unit
            return new int[]{
                requiredPowerKw.setScale(0, RoundingMode.CEILING).intValue(),
                requiredCapacityKwh.setScale(0, RoundingMode.CEILING).intValue(),
                1
            };
        }

        int bestModelPower = models.get(0)[0];
        int bestModelCapacity = models.get(0)[1];
        int bestUnits = Integer.MAX_VALUE;
        int bestTotalCapacity = Integer.MAX_VALUE;

        for (int[] model : models) {
            int modelPower = model[0];
            int modelCapacity = model[1];

            // Units needed to cover power requirement
            int unitsForPower = requiredPowerKw.compareTo(BigDecimal.ZERO) <= 0 ? 1 :
                requiredPowerKw.divide(new BigDecimal(modelPower), 0, RoundingMode.CEILING).intValue();
            // Units needed to cover capacity requirement
            int unitsForCapacity = requiredCapacityKwh.compareTo(BigDecimal.ZERO) <= 0 ? 1 :
                requiredCapacityKwh.divide(new BigDecimal(modelCapacity), 0, RoundingMode.CEILING).intValue();

            int units = Math.max(unitsForPower, unitsForCapacity);
            int totalCapacity = units * modelCapacity;

            if (units < bestUnits || (units == bestUnits && totalCapacity < bestTotalCapacity)) {
                bestUnits = units;
                bestModelPower = modelPower;
                bestModelCapacity = modelCapacity;
                bestTotalCapacity = totalCapacity;
            }
        }

        return new int[]{bestModelPower, bestModelCapacity, bestUnits};
    }

    /**
     * Calculate the maximum peak power of the supporting ESS.
     *
     * Formula: P_ess_max = P_all_load_max × empirical_coefficient
     * The empirical coefficient (default 0.8) accounts for the fact that
     * the ESS doesn't need to cover the absolute peak; PV and load diversity help.
     *
     * @param loadPeakPowerKw Peak power from the load curve
     * @return ESS max power in kW
     */
    BigDecimal calculateEssMaxPower(BigDecimal loadPeakPowerKw) {
        return loadPeakPowerKw.multiply(config.getEmpiricalCoefficient())
            .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate the actual rated power of the ESS after PV peak shaving.
     *
     * Formula: P_ess = P_ess_max - P_pv_max
     * The PV system's peak output reduces the required ESS power.
     * If PV fully covers the need, the result is floored at 0.
     *
     * @param essMaxPowerKw ESS max power (from empirical coefficient)
     * @param pvPeakPowerKw PV installed capacity (peak power)
     * @return ESS rated power in kW (non-negative)
     */
    BigDecimal calculateEssRatedPower(BigDecimal essMaxPowerKw, BigDecimal pvPeakPowerKw) {
        BigDecimal result = essMaxPowerKw.subtract(pvPeakPowerKw);
        return result.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : result.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Validate that the ESS rated power does not exceed the transformer capacity.
     *
     * If it does, generate a warning message suggesting parameter adjustments
     * (e.g., reducing the number of charging vehicles).
     *
     * @param essRatedPowerKw  ESS rated power in kW
     * @param transformerKva   Transformer capacity in kVA
     * @return Warning message, or null if within limits
     */
    String validateAgainstTransformer(BigDecimal essRatedPowerKw, BigDecimal transformerKva) {
        if (essRatedPowerKw.compareTo(transformerKva) > 0) {
            BigDecimal excess = essRatedPowerKw.subtract(transformerKva).setScale(2, RoundingMode.HALF_UP);
            return String.format(
                "储能额定功率 (%.2f kW) 超出变压器容量 (%.2f kVA) %.2f kW。建议减少充电车辆数量或增大变压器容量。",
                essRatedPowerKw, transformerKva, excess
            );
        }
        return null;
    }

    /**
     * Calculate 1-20 year economic indicators for the ESS investment.
     *
     * For each year:
     * - Arbitrage revenue = daily price spread × ESS capacity × decay factor × 365
     * - Peak shaving revenue = subsidy × ESS capacity × peak shaving days (if enabled)
     * - Operating cost = initial investment × maintenance ratio × (1 + inflation)
     * - Net profit = arbitrage + peak shaving - operating cost
     * - Cumulative profit = sum of all net profits up to this year
     *
     * @param essCapacityKwh      ESS capacity
     * @param essRatedPowerKw     ESS rated power
     * @param touPrices           TOU price periods
     * @param annualDecayPercent  Annual capacity decay percentage
     * @param enablePeakShaving   Whether peak shaving is enabled
     * @param peakShavingSubsidy  Peak shaving subsidy (yuan/kWh)
     * @param chargeMode          Charge mode ("one" or "two")
     * @return List of 20 yearly economic indicators
     */
    List<YearlyEconomicIndicator> calculateYearlyEconomics(
        BigDecimal essCapacityKwh,
        BigDecimal essRatedPowerKw,
        List<TouPricePeriod> touPrices,
        BigDecimal annualDecayPercent,
        boolean enablePeakShaving,
        BigDecimal peakShavingSubsidy,
        String chargeMode
    ) {
        // Calculate daily price spread: max TOU price - min TOU price
        BigDecimal maxPrice = touPrices.stream()
            .map(p -> p.price)
            .max(Comparator.naturalOrder())
            .orElse(BigDecimal.ONE);
        BigDecimal minPrice = touPrices.stream()
            .map(p -> p.price)
            .min(Comparator.naturalOrder())
            .orElse(BigDecimal.ZERO);
        BigDecimal priceSpread = maxPrice.subtract(minPrice);

        // Daily arbitrage cycles: 1 for "one charge one discharge", 2 for "two charges two discharges"
        int dailyCycles = "two".equals(chargeMode) ? 2 : 1;

        // Initial investment = ESS capacity × unit cost
        BigDecimal initialInvestment = essCapacityKwh.multiply(config.getEssUnitCostYuanPerKwh());

        BigDecimal decayFactor = BigDecimal.ONE.subtract(
            annualDecayPercent.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP)
        );

        BigDecimal cumulativeProfit = BigDecimal.ZERO;
        List<YearlyEconomicIndicator> indicators = new ArrayList<>();

        for (int year = 1; year <= 20; year++) {
            // Capacity remaining after decay: capacity × decayFactor^(year-1)
            BigDecimal effectiveCapacity = essCapacityKwh.multiply(
                decayFactor.pow(year - 1, new MathContext(10))
            );

            // Daily arbitrage revenue = effectiveCapacity × priceSpread × dailyCycles
            BigDecimal dailyArbitrage = effectiveCapacity.multiply(priceSpread)
                .multiply(new BigDecimal(dailyCycles));
            BigDecimal annualArbitrage = dailyArbitrage.multiply(new BigDecimal("365"))
                .setScale(2, RoundingMode.HALF_UP);

            // Peak shaving revenue
            BigDecimal annualPeakShaving = BigDecimal.ZERO;
            if (enablePeakShaving && peakShavingSubsidy != null) {
                annualPeakShaving = effectiveCapacity.multiply(peakShavingSubsidy)
                    .multiply(new BigDecimal("365"))
                    .setScale(2, RoundingMode.HALF_UP);
            }

            // Operating cost = initialInvestment × maintenanceRatio × (1 + 0.02 × (year-1))
            BigDecimal inflationFactor = BigDecimal.ONE.add(
                new BigDecimal("0.02").multiply(new BigDecimal(year - 1))
            );
            BigDecimal annualCost = initialInvestment
                .multiply(config.getEssAnnualMaintenanceRatio())
                .multiply(inflationFactor)
                .setScale(2, RoundingMode.HALF_UP);

            BigDecimal netProfit = annualArbitrage.add(annualPeakShaving).subtract(annualCost)
                .setScale(2, RoundingMode.HALF_UP);
            cumulativeProfit = cumulativeProfit.add(netProfit).setScale(2, RoundingMode.HALF_UP);

            indicators.add(new YearlyEconomicIndicator(
                year, annualArbitrage, annualPeakShaving, annualCost, netProfit, cumulativeProfit
            ));
        }

        return indicators;
    }

    // ==================== V2G calculation methods ====================

    /**
     * Calculate total V2G discharge power, applying the derate factor.
     * Same pile selection logic as charging but multiplied by derate.
     *
     * @param derateFactor Discharge power ratio (e.g. 0.85 = 85% of charge power). If null, uses config default.
     */
    BigDecimal calculateTotalV2gDischargePower(int fastV2g, int slowV2g, int ultraFastV2g, int vehicleCount,
                                                BigDecimal derateFactor) {
        BigDecimal chargePower = calculateTotalChargingPower(fastV2g, slowV2g, ultraFastV2g, vehicleCount);
        BigDecimal factor = derateFactor != null ? derateFactor : config.getV2gDischargePowerDerateFactor();
        return chargePower.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Suggest pile configuration based on vehicle count using configurable ratios.
     * Returns int[3]: {fast, slow, ultraFast}
     */
    public int[] suggestPileConfiguration(int vehicleCount) {
        double[] ratios = config.getPileSuggestionRatios();
        int fast = (int) Math.ceil(vehicleCount * ratios[0]);
        int slow = (int) Math.ceil(vehicleCount * ratios[1]);
        int ultra = (int) Math.ceil(vehicleCount * ratios[2]);
        return new int[]{fast, slow, ultra};
    }

    /**
     * Calculate load curve with V2G support using per-range SOC tracking.
     *
     * Each chargeable range has its own minSoc target. V2G vehicles track SOC across ranges:
     * - If arrivalSoc > targetSoc: discharge headroom (arrivalSoc - targetSoc) at expensive slots
     * - If arrivalSoc < targetSoc: charge deficit at cheapest slots
     * - No global recharge step — SOC naturally cycles through ranges
     *
     * V1G vehicles use the existing global greedy algorithm (charge to max minSoc).
     */
    DailyLoadCurveResult calculateLoadCurveWithV2g(
        List<V2gVehicleConfigDTO.WeeklyScheduleEntry> weeklySchedule,
        List<TouPricePeriod> touPrices,
        int vehicleCount,
        BigDecimal batteryKwh,
        boolean enableTimeControl,
        BigDecimal v1gChargePowerKw,
        BigDecimal v2gChargePowerKw,
        BigDecimal v2gDischargePowerKw,
        int totalV2gPiles,
        List<String> steps
    ) {
        int slotsPerDay = 24 * 60 / config.getTimeSlotIntervalMinutes();
        int intervalMin = config.getTimeSlotIntervalMinutes();
        int v2gVehicleCount = Math.min(totalV2gPiles, vehicleCount);
        int v1gVehicleCount = vehicleCount - v2gVehicleCount;

        // Collect effective minSoc for V1G (max across all ranges)
        int effectiveMinSocPercent = 80;
        List<Integer> rangeMinSocs = new ArrayList<>();
        if (weeklySchedule != null) {
            for (V2gVehicleConfigDTO.WeeklyScheduleEntry entry : weeklySchedule) {
                if (entry.isOperating() && entry.getChargeableRanges() != null) {
                    for (V2gVehicleConfigDTO.TimeRange range : entry.getChargeableRanges()) {
                        if (range.getMinSoc() != null && range.getMinSoc() > 0) {
                            rangeMinSocs.add(range.getMinSoc());
                        }
                    }
                }
            }
        }
        if (!rangeMinSocs.isEmpty()) {
            effectiveMinSocPercent = Collections.max(rangeMinSocs);
        }

        BigDecimal socRange = new BigDecimal(effectiveMinSocPercent)
            .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        BigDecimal intervalHours = new BigDecimal(intervalMin)
            .divide(new BigDecimal("60"), 4, RoundingMode.HALF_UP);

        // V1G energy demand (global, using max minSoc)
        BigDecimal v1gEnergyDemand = batteryKwh.multiply(socRange).multiply(new BigDecimal(v1gVehicleCount));
        BigDecimal v1gMaxEnergyPerSlot = v1gChargePowerKw.multiply(intervalHours);
        BigDecimal v2gMaxChargePerSlot = v2gChargePowerKw.multiply(intervalHours);
        BigDecimal v2gMaxDischargePerSlot = v2gDischargePowerKw.multiply(intervalHours);

        if (steps != null) {
            steps.add(String.format("Step 2b-V2G: V1G vehicles=%d, V2G vehicles=%d, V1G target SOC=%d%%",
                v1gVehicleCount, v2gVehicleCount, effectiveMinSocPercent));
            steps.add(String.format("Step 2c-V2G: V1G charge demand=%.2fkWh, V1G power=%.0fkW",
                v1gEnergyDemand.doubleValue(), v1gChargePowerKw.doubleValue()));
            steps.add(String.format("Step 2d-V2G: V2G charge power=%.0fkW, V2G discharge power=%.0fkW",
                v2gChargePowerKw.doubleValue(), v2gDischargePowerKw.doubleValue()));
        }

        String[] dayNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        Map<String, List<LoadCurvePoint>> dailyCurves = new LinkedHashMap<>();
        BigDecimal maxDailyArbitrage = BigDecimal.ZERO;
        BigDecimal weeklyArbitrageSum = BigDecimal.ZERO;

        if (!enableTimeControl) {
            // No time control: single range covering the full day, default 80% SOC
            Set<Integer> allSlots = new HashSet<>();
            for (int i = 0; i < slotsPerDay; i++) allSlots.add(i);
            List<RangeInfo> fullDayRange = new ArrayList<>();
            fullDayRange.add(new RangeInfo(0, slotsPerDay - 1, 80, "00:00", "23:45"));

            V2gDayCurveResult dayResult = calculateSingleDayCurveWithV2g(
                allSlots, fullDayRange, touPrices,
                v1gEnergyDemand, v1gMaxEnergyPerSlot,
                v2gVehicleCount, v2gChargePowerKw, v2gDischargePowerKw,
                v2gMaxChargePerSlot, v2gMaxDischargePerSlot,
                batteryKwh, intervalHours, slotsPerDay, steps, "全天");
            for (String dayName : dayNames) {
                dailyCurves.put(dayName, dayResult.curve);
            }
            maxDailyArbitrage = dayResult.dailyArbitrage;
            weeklyArbitrageSum = dayResult.dailyArbitrage.multiply(new BigDecimal("7"));
        } else {
            if (weeklySchedule != null && !weeklySchedule.isEmpty()) {
                for (int dayIdx = 0; dayIdx < weeklySchedule.size() && dayIdx < 7; dayIdx++) {
                    V2gVehicleConfigDTO.WeeklyScheduleEntry entry = weeklySchedule.get(dayIdx);
                    String dayName = dayNames[dayIdx];
                    if (!entry.isOperating()) continue;

                    // Build per-range info AND flat slot set for V1G
                    Set<Integer> daySlots = new HashSet<>();
                    List<RangeInfo> dayRanges = new ArrayList<>();
                    if (entry.getChargeableRanges() != null) {
                        for (V2gVehicleConfigDTO.TimeRange range : entry.getChargeableRanges()) {
                            if (range.getStart() != null && range.getEnd() != null
                                && !range.getStart().isEmpty() && !range.getEnd().isEmpty()) {
                                int fromSlot = timeToSlotIndex(range.getStart(), intervalMin);
                                int toSlot = timeToSlotIndex(range.getEnd(), intervalMin);
                                addSlotsInRange(daySlots, fromSlot, toSlot, slotsPerDay);
                                int minSoc = range.getMinSoc() != null && range.getMinSoc() > 0
                                    ? range.getMinSoc() : 80;
                                dayRanges.add(new RangeInfo(fromSlot, toSlot, minSoc,
                                    range.getStart(), range.getEnd()));
                            }
                        }
                    }

                    if (daySlots.isEmpty()) {
                        List<LoadCurvePoint> zeroCurve = new ArrayList<>();
                        for (int i = 0; i < slotsPerDay; i++) {
                            zeroCurve.add(new LoadCurvePoint(
                                slotIndexToTime(i, intervalMin),
                                BigDecimal.ZERO, BigDecimal.ZERO));
                        }
                        dailyCurves.put(dayName, zeroCurve);
                    } else {
                        // Sort ranges by start slot for temporal ordering
                        dayRanges.sort(Comparator.comparingInt(r -> r.startSlot));

                        V2gDayCurveResult dayResult = calculateSingleDayCurveWithV2g(
                            daySlots, dayRanges, touPrices,
                            v1gEnergyDemand, v1gMaxEnergyPerSlot,
                            v2gVehicleCount, v2gChargePowerKw, v2gDischargePowerKw,
                            v2gMaxChargePerSlot, v2gMaxDischargePerSlot,
                            batteryKwh, intervalHours, slotsPerDay, steps, dayName);
                        dailyCurves.put(dayName, dayResult.curve);
                        weeklyArbitrageSum = weeklyArbitrageSum.add(dayResult.dailyArbitrage);
                        if (dayResult.dailyArbitrage.compareTo(maxDailyArbitrage) > 0) {
                            maxDailyArbitrage = dayResult.dailyArbitrage;
                        }
                    }
                }
            }

            if (dailyCurves.isEmpty()) {
                List<LoadCurvePoint> zeroCurve = new ArrayList<>();
                for (int i = 0; i < slotsPerDay; i++) {
                    zeroCurve.add(new LoadCurvePoint(
                        slotIndexToTime(i, intervalMin),
                        BigDecimal.ZERO, BigDecimal.ZERO));
                }
                return new DailyLoadCurveResult(dailyCurves, zeroCurve, BigDecimal.ZERO, BigDecimal.ZERO);
            }
        }

        // Compute max envelope (both charge and discharge)
        List<LoadCurvePoint> maxEnvelope = new ArrayList<>();
        for (int i = 0; i < slotsPerDay; i++) {
            BigDecimal maxCharge = BigDecimal.ZERO;
            BigDecimal minDischarge = BigDecimal.ZERO; // most negative = max discharge
            for (List<LoadCurvePoint> dayCurve : dailyCurves.values()) {
                if (i < dayCurve.size()) {
                    LoadCurvePoint p = dayCurve.get(i);
                    if (p.getPowerKw().compareTo(maxCharge) > 0) maxCharge = p.getPowerKw();
                    BigDecimal discharge = p.getDischargePowerKw() != null ? p.getDischargePowerKw() : BigDecimal.ZERO;
                    if (discharge.compareTo(minDischarge) < 0) minDischarge = discharge; // more negative = larger discharge
                }
            }
            maxEnvelope.add(new LoadCurvePoint(
                slotIndexToTime(i, config.getTimeSlotIntervalMinutes()), maxCharge, minDischarge));
        }

        BigDecimal peakPowerKw = findPeakPower(maxEnvelope);
        BigDecimal dailyMaxEnergy = BigDecimal.ZERO;
        for (List<LoadCurvePoint> dayCurve : dailyCurves.values()) {
            BigDecimal dayEnergy = calculateDailyEnergy(dayCurve);
            if (dayEnergy.compareTo(dailyMaxEnergy) > 0) dailyMaxEnergy = dayEnergy;
        }

        return new DailyLoadCurveResult(dailyCurves, maxEnvelope, peakPowerKw, dailyMaxEnergy, maxDailyArbitrage, weeklyArbitrageSum);
    }

    /**
     * Calculate a single day's load curve with V2G per-range SOC tracking.
     *
     * Algorithm:
     * 1. V1G vehicles: global greedy cheapest-first across all chargeable slots (unchanged)
     * 2. V2G vehicles: per-range processing in temporal order
     *    - Steady state initial SOC = last range's minSoc
     *    - For each range: if arrivalSoc > targetSoc → discharge headroom at expensive slots
     *    - If arrivalSoc < targetSoc → charge at cheapest slots in the range
     *    - Arbitrage = total discharge revenue - total V2G charge cost
     */
    private V2gDayCurveResult calculateSingleDayCurveWithV2g(
        Set<Integer> chargeableSlotSet,
        List<RangeInfo> ranges,
        List<TouPricePeriod> touPrices,
        BigDecimal v1gEnergyDemand,
        BigDecimal v1gMaxEnergyPerSlot,
        int v2gVehicleCount,
        BigDecimal v2gChargePowerKw,
        BigDecimal v2gDischargePowerKw,
        BigDecimal v2gMaxChargePerSlot,
        BigDecimal v2gMaxDischargePerSlot,
        BigDecimal batteryKwh,
        BigDecimal intervalHours,
        int slotsPerDay,
        List<String> steps,
        String dayLabel
    ) {
        int intervalMin = config.getTimeSlotIntervalMinutes();
        BigDecimal BD_100 = new BigDecimal("100");

        // Build all slot data
        List<SlotData> allSlots = new ArrayList<>();
        for (int i = 0; i < slotsPerDay; i++) {
            String timeStr = slotIndexToTime(i, intervalMin);
            BigDecimal price = getTouPriceForSlot(timeStr, touPrices);
            boolean chargeable = chargeableSlotSet.contains(i);
            allSlots.add(new SlotData(i, timeStr, price, chargeable));
        }

        BigDecimal[] v1gPower = new BigDecimal[slotsPerDay];
        BigDecimal[] v2gChargePwr = new BigDecimal[slotsPerDay];
        BigDecimal[] v2gDischargePwr = new BigDecimal[slotsPerDay];
        // Energy arrays: track actual kWh per slot (separate from rated power)
        BigDecimal[] v1gEnergy = new BigDecimal[slotsPerDay];
        BigDecimal[] v2gChargeEnergy = new BigDecimal[slotsPerDay];
        BigDecimal[] v2gDischargeEnergy = new BigDecimal[slotsPerDay];
        Arrays.fill(v1gPower, BigDecimal.ZERO);
        Arrays.fill(v2gChargePwr, BigDecimal.ZERO);
        Arrays.fill(v2gDischargePwr, BigDecimal.ZERO);
        Arrays.fill(v1gEnergy, BigDecimal.ZERO);
        Arrays.fill(v2gChargeEnergy, BigDecimal.ZERO);
        Arrays.fill(v2gDischargeEnergy, BigDecimal.ZERO);

        // ---- V1G: unchanged global greedy ----
        List<SlotData> v1gCheapSlots = allSlots.stream()
            .filter(s -> s.chargeable)
            .sorted(Comparator.comparing(s -> s.price))
            .collect(Collectors.toList());

        // V1G rated power = maxEnergyPerSlot / intervalH (chargers always run at rated power)
        BigDecimal v1gRatedPower = v1gMaxEnergyPerSlot.compareTo(BigDecimal.ZERO) > 0
            ? v1gMaxEnergyPerSlot.divide(intervalHours, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        BigDecimal remaining = v1gEnergyDemand;
        for (SlotData slot : v1gCheapSlots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal energy = remaining.min(v1gMaxEnergyPerSlot);
            // Power = rated pile power (not energy/interval) — charger runs at full power for shorter time
            v1gPower[slot.index] = v1gRatedPower;
            v1gEnergy[slot.index] = energy;  // actual energy (may be < ratedPower × interval for last slot)
            remaining = remaining.subtract(energy);
        }

        // ---- V2G: per-range SOC tracking ----
        if (v2gVehicleCount > 0 && !ranges.isEmpty()) {
            // Steady state: initial SOC = last range's departure minSoc
            int v2gSocPercent = ranges.get(ranges.size() - 1).minSocPercent;
            BigDecimal totalDischargeRevenue = BigDecimal.ZERO;
            BigDecimal totalChargeCost = BigDecimal.ZERO;

            if (steps != null) {
                steps.add(String.format("  [%s] V2G per-range: %d ranges, initial SOC=%d%% (steady state from last range)",
                    dayLabel, ranges.size(), v2gSocPercent));
            }

            for (RangeInfo range : ranges) {
                int arrivalSoc = v2gSocPercent;
                int targetSoc = range.minSocPercent;

                // Build slot list for this range
                Set<Integer> rangeSlotSet = new HashSet<>();
                addSlotsInRange(rangeSlotSet, range.startSlot, range.endSlot, slotsPerDay);
                List<SlotData> rangeSlots = allSlots.stream()
                    .filter(s -> rangeSlotSet.contains(s.index))
                    .collect(Collectors.toList());

                if (arrivalSoc > targetSoc) {
                    // Discharge headroom: (arrivalSoc - targetSoc)% × batteryKwh × vehicles
                    BigDecimal dischargeEnergy = batteryKwh
                        .multiply(new BigDecimal(arrivalSoc - targetSoc))
                        .divide(BD_100, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(v2gVehicleCount));

                    // Discharge at most expensive slots in this range
                    List<SlotData> expensiveSlots = rangeSlots.stream()
                        .sorted(Comparator.comparing((SlotData s) -> s.price).reversed())
                        .collect(Collectors.toList());

                    remaining = dischargeEnergy;
                    BigDecimal rangeRevenue = BigDecimal.ZERO;
                    int dischargeSlotsUsed = 0;

                    for (SlotData slot : expensiveSlots) {
                        if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                        BigDecimal energy = remaining.min(v2gMaxDischargePerSlot);
                        // Power = rated discharge power (negative), not energy/interval
                        BigDecimal slotPower = v2gDischargePowerKw.negate();
                        v2gDischargePwr[slot.index] = v2gDischargePwr[slot.index].add(slotPower);
                        v2gDischargeEnergy[slot.index] = v2gDischargeEnergy[slot.index].subtract(energy); // negative kWh
                        remaining = remaining.subtract(energy);
                        rangeRevenue = rangeRevenue.add(energy.multiply(slot.price));
                        dischargeSlotsUsed++;
                    }

                    totalDischargeRevenue = totalDischargeRevenue.add(rangeRevenue);

                    if (steps != null) {
                        steps.add(String.format("  [%s] Range %s~%s: V2G discharge %.2fkWh in %d slots, revenue=%.4f元 (SOC %d%%→%d%%)",
                            dayLabel, range.startTime, range.endTime,
                            dischargeEnergy.subtract(remaining).doubleValue(), dischargeSlotsUsed,
                            rangeRevenue.doubleValue(), arrivalSoc, targetSoc));
                    }

                } else if (arrivalSoc < targetSoc) {
                    // V2G vehicles need to charge in this range
                    BigDecimal chargeNeeded = batteryKwh
                        .multiply(new BigDecimal(targetSoc - arrivalSoc))
                        .divide(BD_100, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(v2gVehicleCount));

                    // Charge at cheapest slots in this range
                    List<SlotData> cheapSlots = rangeSlots.stream()
                        .sorted(Comparator.comparing(s -> s.price))
                        .collect(Collectors.toList());

                    remaining = chargeNeeded;
                    BigDecimal rangeCost = BigDecimal.ZERO;

                    for (SlotData slot : cheapSlots) {
                        if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                        BigDecimal energy = remaining.min(v2gMaxChargePerSlot);
                        // Power = rated V2G charge power (not energy/interval)
                        v2gChargePwr[slot.index] = v2gChargePwr[slot.index].add(v2gChargePowerKw);
                        v2gChargeEnergy[slot.index] = v2gChargeEnergy[slot.index].add(energy); // actual kWh
                        remaining = remaining.subtract(energy);
                        rangeCost = rangeCost.add(energy.multiply(slot.price));
                    }

                    totalChargeCost = totalChargeCost.add(rangeCost);

                    if (steps != null) {
                        steps.add(String.format("  [%s] Range %s~%s: V2G charge %.2fkWh, cost=%.4f元 (SOC %d%%→%d%%)",
                            dayLabel, range.startTime, range.endTime,
                            chargeNeeded.doubleValue(), rangeCost.doubleValue(), arrivalSoc, targetSoc));
                    }
                } else {
                    if (steps != null) {
                        steps.add(String.format("  [%s] Range %s~%s: V2G idle (SOC %d%% = target %d%%)",
                            dayLabel, range.startTime, range.endTime, arrivalSoc, targetSoc));
                    }
                }

                v2gSocPercent = targetSoc;
            }

            BigDecimal dailyArbitrage = totalDischargeRevenue.subtract(totalChargeCost)
                .setScale(2, RoundingMode.HALF_UP);

            if (steps != null) {
                steps.add(String.format("  [%s] V2G daily summary: revenue=%.4f元 - charge cost=%.4f元 = arbitrage %.2f元",
                    dayLabel, totalDischargeRevenue.doubleValue(), totalChargeCost.doubleValue(),
                    dailyArbitrage.doubleValue()));
            }

            // Build curve: power=rated, energy=actual kWh
            List<LoadCurvePoint> curve = new ArrayList<>();
            for (int i = 0; i < slotsPerDay; i++) {
                BigDecimal totalChargePower = v1gPower[i].add(v2gChargePwr[i]);
                BigDecimal totalChargeEnergy = v1gEnergy[i].add(v2gChargeEnergy[i]);
                curve.add(new LoadCurvePoint(
                    slotIndexToTime(i, intervalMin), totalChargePower, v2gDischargePwr[i],
                    totalChargeEnergy, v2gDischargeEnergy[i]));
            }
            return new V2gDayCurveResult(curve, dailyArbitrage, dailyArbitrage.compareTo(BigDecimal.ZERO) > 0);
        }

        // V2G vehicle count = 0 or no ranges: V1G-only curve
        List<LoadCurvePoint> curve = new ArrayList<>();
        for (int i = 0; i < slotsPerDay; i++) {
            curve.add(new LoadCurvePoint(
                slotIndexToTime(i, intervalMin), v1gPower[i], BigDecimal.ZERO,
                v1gEnergy[i], BigDecimal.ZERO));
        }
        return new V2gDayCurveResult(curve, BigDecimal.ZERO, false);
    }

    /** Find peak discharge power across all points (now stored as negative; return absolute value) */
    BigDecimal findPeakDischargePower(List<LoadCurvePoint> curve) {
        BigDecimal minVal = BigDecimal.ZERO; // most negative
        for (LoadCurvePoint p : curve) {
            BigDecimal d = p.getDischargePowerKw() != null ? p.getDischargePowerKw() : BigDecimal.ZERO;
            if (d.compareTo(minVal) < 0) minVal = d;
        }
        return minVal.abs();
    }

    /** Calculate max daily discharge energy across all days (discharge values are negative) */
    BigDecimal calculateDailyDischargeEnergy(Map<String, List<LoadCurvePoint>> dailyCurves) {
        BigDecimal intervalHours = new BigDecimal(config.getTimeSlotIntervalMinutes())
            .divide(new BigDecimal("60"), 4, RoundingMode.HALF_UP);
        BigDecimal maxEnergy = BigDecimal.ZERO;
        for (List<LoadCurvePoint> dayCurve : dailyCurves.values()) {
            BigDecimal dayEnergy = BigDecimal.ZERO;
            for (LoadCurvePoint p : dayCurve) {
                // Use actual discharge energy if available (dischargeEnergyKwh is negative)
                if (p.getDischargeEnergyKwh() != null && p.getDischargeEnergyKwh().compareTo(BigDecimal.ZERO) < 0) {
                    dayEnergy = dayEnergy.add(p.getDischargeEnergyKwh().abs());
                } else {
                    BigDecimal d = p.getDischargePowerKw() != null ? p.getDischargePowerKw() : BigDecimal.ZERO;
                    dayEnergy = dayEnergy.add(d.abs().multiply(intervalHours));
                }
            }
            if (dayEnergy.compareTo(maxEnergy) > 0) maxEnergy = dayEnergy;
        }
        return maxEnergy.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Standalone V2G calculation — all parameters from request body.
     */
    public V2gCalculationResultDTO calculateV2g(V2gCalculationRequestDTO request) {
        List<String> steps = new ArrayList<>();

        int vehicleCount = request.getVehicleCount() != null ? request.getVehicleCount() : 0;
        BigDecimal batteryKwh = request.getBatteryCapacityKwh() != null ? request.getBatteryCapacityKwh() : BigDecimal.ZERO;
        boolean enableTimeControl = request.getEnableTimeControl() != null ? request.getEnableTimeControl() : true;

        int fast = request.getFastChargers() != null ? request.getFastChargers() : 0;
        int slow = request.getSlowChargers() != null ? request.getSlowChargers() : 0;
        int ultra = request.getUltraFastChargers() != null ? request.getUltraFastChargers() : 0;
        int fastV2g = request.getFastChargersV2g() != null ? request.getFastChargersV2g() : 0;
        int slowV2g = request.getSlowChargersV2g() != null ? request.getSlowChargersV2g() : 0;
        int ultraV2g = request.getUltraFastChargersV2g() != null ? request.getUltraFastChargersV2g() : 0;
        int totalV2gPiles = fastV2g + slowV2g + ultraV2g;

        // Parse TOU prices from request
        List<TouPricePeriod> touPrices = new ArrayList<>();
        if (request.getTouPrices() != null) {
            for (V2gCalculationRequestDTO.TouPriceEntry entry : request.getTouPrices()) {
                List<int[]> ranges = new ArrayList<>();
                if (entry.getTimeRanges() != null) {
                    for (V2gCalculationRequestDTO.TimeRangeEntry tr : entry.getTimeRanges()) {
                        if (tr.getStart() != null && tr.getEnd() != null) {
                            ranges.add(new int[]{timeToMinutes(tr.getStart()), timeToMinutes(tr.getEnd())});
                        }
                    }
                }
                touPrices.add(new TouPricePeriod(entry.getPeriodType(), ranges, entry.getPrice()));
            }
        }

        // Suggest pile configuration
        int[] suggested = suggestPileConfiguration(vehicleCount);
        steps.add(String.format("Pile suggestion: fast=%d, slow=%d, ultra=%d (for %d vehicles)",
            suggested[0], suggested[1], suggested[2], vehicleCount));

        // Resolve discharge power ratio from request or config
        BigDecimal derateFactor = request.getDischargePowerRatio() != null
            ? request.getDischargePowerRatio()
            : config.getV2gDischargePowerDerateFactor();

        // Calculate V1G and V2G power
        BigDecimal totalChargePower = calculateTotalChargingPower(fast, slow, ultra, vehicleCount);
        steps.add(String.format("Total charging power: %.0fkW, discharge power ratio: %.0f%%",
            totalChargePower.doubleValue(), derateFactor.multiply(new BigDecimal("100")).doubleValue()));

        DailyLoadCurveResult curveResult;

        if (totalV2gPiles > 0) {
            BigDecimal v2gDischargePower = calculateTotalV2gDischargePower(fastV2g, slowV2g, ultraV2g, vehicleCount, derateFactor);
            BigDecimal v2gChargePower = calculateTotalChargingPower(fastV2g, slowV2g, ultraV2g, vehicleCount);
            BigDecimal v1gChargePower = calculateTotalChargingPower(
                fast - fastV2g, slow - slowV2g, ultra - ultraV2g,
                Math.max(0, vehicleCount - totalV2gPiles));

            steps.add(String.format("V2G enabled: V1G charge=%.0fkW, V2G charge=%.0fkW, V2G discharge=%.0fkW (derate=%.0f%%)",
                v1gChargePower.doubleValue(), v2gChargePower.doubleValue(), v2gDischargePower.doubleValue(),
                derateFactor.multiply(new BigDecimal("100")).doubleValue()));

            curveResult = calculateLoadCurveWithV2g(
                request.getWeeklySchedule(), touPrices, vehicleCount, batteryKwh, enableTimeControl,
                v1gChargePower, v2gChargePower, v2gDischargePower, totalV2gPiles, steps);
        } else {
            curveResult = calculateLoadCurve(
                request.getWeeklySchedule(), touPrices, vehicleCount, batteryKwh, enableTimeControl,
                totalChargePower, steps);
        }

        BigDecimal weeklyArbitrage = curveResult.weeklyArbitrageSum.setScale(2, RoundingMode.HALF_UP);

        // Peak discharge power = rated V2G pile discharge capability (not curve-derived)
        // The curve shows actual per-slot usage (energy-limited), but peak power is the pile's rated capacity.
        BigDecimal peakDischarge = totalV2gPiles > 0
            ? calculateTotalV2gDischargePower(fastV2g, slowV2g, ultraV2g, vehicleCount, derateFactor)
            : BigDecimal.ZERO;
        BigDecimal dailyDischargeEnergy = calculateDailyDischargeEnergy(curveResult.dailyCurves);

        steps.add(String.format("Peak discharge power (rated) = %.0fkW (pile capability × derate)", peakDischarge.doubleValue()));
        steps.add(String.format("Weekly arbitrage = %.2f元, Yearly = %.2f元",
            weeklyArbitrage.doubleValue(),
            weeklyArbitrage.multiply(new BigDecimal("52")).doubleValue()));

        return V2gCalculationResultDTO.builder()
            .suggestedFastChargers(suggested[0])
            .suggestedSlowChargers(suggested[1])
            .suggestedUltraFastChargers(suggested[2])
            .dailyLoadCurves(curveResult.dailyCurves)
            .maxEnvelopeCurve(curveResult.maxEnvelopeCurve)
            .peakChargingPowerKw(curveResult.peakPowerKw)
            .peakDischargePowerKw(peakDischarge)
            .dailyMaxChargingEnergyKwh(curveResult.dailyMaxEnergyKwh)
            .dailyMaxDischargeEnergyKwh(dailyDischargeEnergy)
            .weeklyArbitrageRevenue(weeklyArbitrage)
            .yearlyArbitrageRevenue(weeklyArbitrage.multiply(new BigDecimal("52")).setScale(2, RoundingMode.HALF_UP))
            .dischargePowerRatio(derateFactor)
            .pAllLoadMax(curveResult.peakPowerKw)
            .calculationSteps(steps)
            .build();
    }

    /**
     * Project-based V2G calculation — loads all params from DB.
     */
    public V2gCalculationResultDTO calculateV2gForProject(Long projectId) {
        V2gVehicleConfig v2gEntity = v2gConfigRepository.findByProjectId(projectId)
            .orElseThrow(() -> new IllegalArgumentException("V2G vehicle config is required."));
        List<ProjectElectricityPrice> priceEntities = priceRepository.findByProjectId(projectId);
        if (priceEntities.isEmpty()) {
            throw new IllegalArgumentException("TOU electricity prices are required.");
        }
        List<V2gVehicleConfigDTO.WeeklyScheduleEntry> weeklySchedule = parseWeeklySchedule(v2gEntity);

        // Build request from DB data
        V2gCalculationRequestDTO request = V2gCalculationRequestDTO.builder()
            .vehicleCount(v2gEntity.getVehicleCount())
            .batteryCapacityKwh(v2gEntity.getBatteryCapacityKwh())
            .enableTimeControl(v2gEntity.getEnableTimeControl())
            .weeklySchedule(weeklySchedule)
            .fastChargers(v2gEntity.getFastChargers())
            .slowChargers(v2gEntity.getSlowChargers())
            .ultraFastChargers(v2gEntity.getUltraFastChargers())
            .fastChargersV2g(v2gEntity.getFastChargersV2g())
            .slowChargersV2g(v2gEntity.getSlowChargersV2g())
            .ultraFastChargersV2g(v2gEntity.getUltraFastChargersV2g())
            .build();

        // Convert TOU prices
        List<V2gCalculationRequestDTO.TouPriceEntry> touEntries = new ArrayList<>();
        for (ProjectElectricityPrice pe : priceEntities) {
            List<V2gCalculationRequestDTO.TimeRangeEntry> ranges = new ArrayList<>();
            if (pe.getTimeRanges() != null) {
                try {
                    List<Map<String, String>> rawRanges = objectMapper.readValue(
                        pe.getTimeRanges(), new TypeReference<List<Map<String, String>>>() {});
                    for (Map<String, String> r : rawRanges) {
                        ranges.add(new V2gCalculationRequestDTO.TimeRangeEntry(r.get("start"), r.get("end")));
                    }
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse time ranges: {}", e.getMessage());
                }
            }
            touEntries.add(new V2gCalculationRequestDTO.TouPriceEntry(pe.getPeriodType(), ranges, pe.getPrice()));
        }
        request.setTouPrices(touEntries);

        return calculateV2g(request);
    }

    // ==================== Internal helpers ====================

    /** Aggregated result of per-day load curve calculations */
    static class DailyLoadCurveResult {
        /** Per-day curves: key = day name (e.g. "周一"), value = 96 LoadCurvePoints */
        Map<String, List<LoadCurvePoint>> dailyCurves;
        /** Max envelope curve: for each of 96 slots, the max power across all days */
        List<LoadCurvePoint> maxEnvelopeCurve;
        /** Peak power across all days (kW) */
        BigDecimal peakPowerKw;
        /** Maximum daily energy consumption across all days (kWh) */
        BigDecimal dailyMaxEnergyKwh;
        /** Max daily V2G arbitrage revenue across all days (0 if V1G-only) */
        BigDecimal maxDailyArbitrage;
        /** Weekly sum of all days' arbitrage (0 if V1G-only) */
        BigDecimal weeklyArbitrageSum;

        DailyLoadCurveResult(Map<String, List<LoadCurvePoint>> dailyCurves,
                             List<LoadCurvePoint> maxEnvelopeCurve,
                             BigDecimal peakPowerKw,
                             BigDecimal dailyMaxEnergyKwh) {
            this(dailyCurves, maxEnvelopeCurve, peakPowerKw, dailyMaxEnergyKwh, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        DailyLoadCurveResult(Map<String, List<LoadCurvePoint>> dailyCurves,
                             List<LoadCurvePoint> maxEnvelopeCurve,
                             BigDecimal peakPowerKw,
                             BigDecimal dailyMaxEnergyKwh,
                             BigDecimal maxDailyArbitrage,
                             BigDecimal weeklyArbitrageSum) {
            this.dailyCurves = dailyCurves;
            this.maxEnvelopeCurve = maxEnvelopeCurve;
            this.peakPowerKw = peakPowerKw;
            this.dailyMaxEnergyKwh = dailyMaxEnergyKwh;
            this.maxDailyArbitrage = maxDailyArbitrage;
            this.weeklyArbitrageSum = weeklyArbitrageSum;
        }
    }

    /** Result of a single day's V2G curve calculation */
    static class V2gDayCurveResult {
        List<LoadCurvePoint> curve;
        BigDecimal dailyArbitrage;
        boolean dischargeProfitable;

        V2gDayCurveResult(List<LoadCurvePoint> curve, BigDecimal dailyArbitrage, boolean dischargeProfitable) {
            this.curve = curve;
            this.dailyArbitrage = dailyArbitrage;
            this.dischargeProfitable = dischargeProfitable;
        }
    }

    /** Per-range info for V2G SOC tracking */
    static class RangeInfo {
        int startSlot;
        int endSlot;
        int minSocPercent;
        String startTime;
        String endTime;

        RangeInfo(int startSlot, int endSlot, int minSocPercent, String startTime, String endTime) {
            this.startSlot = startSlot;
            this.endSlot = endSlot;
            this.minSocPercent = minSocPercent;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    /** Internal data class for a TOU price period */
    static class TouPricePeriod {
        String periodType;
        List<int[]> timeRanges; // each int[2] = {startMinute, endMinute}
        BigDecimal price;

        TouPricePeriod(String periodType, List<int[]> timeRanges, BigDecimal price) {
            this.periodType = periodType;
            this.timeRanges = timeRanges;
            this.price = price;
        }
    }

    /** Internal data class for a time slot during greedy scheduling */
    static class SlotData {
        int index;
        String time;
        BigDecimal price;
        boolean chargeable;

        SlotData(int index, String time, BigDecimal price, boolean chargeable) {
            this.index = index;
            this.time = time;
            this.price = price;
            this.chargeable = chargeable;
        }
    }

    /** Add all slot indices in a time range to the set (handles wrap-around midnight) */
    private void addSlotsInRange(Set<Integer> slotSet, int fromSlot, int toSlot, int totalSlots) {
        if (fromSlot <= toSlot) {
            for (int i = fromSlot; i <= toSlot && i < totalSlots; i++) {
                slotSet.add(i);
            }
        } else {
            // Wraps around midnight
            for (int i = fromSlot; i < totalSlots; i++) {
                slotSet.add(i);
            }
            for (int i = 0; i <= toSlot && i < totalSlots; i++) {
                slotSet.add(i);
            }
        }
    }

    private List<V2gVehicleConfigDTO.WeeklyScheduleEntry> parseWeeklySchedule(V2gVehicleConfig entity) {
        if (entity.getWeeklySchedule() == null) return Collections.emptyList();
        try {
            return objectMapper.readValue(
                entity.getWeeklySchedule(),
                new TypeReference<List<V2gVehicleConfigDTO.WeeklyScheduleEntry>>() {}
            );
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse weekly schedule: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<TouPricePeriod> parseTouPrices(List<ProjectElectricityPrice> entities) {
        List<TouPricePeriod> result = new ArrayList<>();
        for (ProjectElectricityPrice entity : entities) {
            List<int[]> ranges = new ArrayList<>();
            if (entity.getTimeRanges() != null) {
                try {
                    List<Map<String, String>> rawRanges = objectMapper.readValue(
                        entity.getTimeRanges(),
                        new TypeReference<List<Map<String, String>>>() {}
                    );
                    for (Map<String, String> r : rawRanges) {
                        String start = r.get("start");
                        String end = r.get("end");
                        if (start != null && end != null) {
                            ranges.add(new int[]{ timeToMinutes(start), timeToMinutes(end) });
                        }
                    }
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse time ranges for price {}: {}", entity.getId(), e.getMessage());
                }
            }
            result.add(new TouPricePeriod(entity.getPeriodType(), ranges, entity.getPrice()));
        }
        return result;
    }

    /** Convert "HH:mm" to total minutes since midnight */
    private int timeToMinutes(String hhmm) {
        if (hhmm == null || hhmm.isEmpty()) return 0;
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + (parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
    }

    /** Convert "HH:mm" to slot index */
    private int timeToSlotIndex(String hhmm, int intervalMinutes) {
        return timeToMinutes(hhmm) / intervalMinutes;
    }

    /** Convert slot index to "HH:mm" string */
    private String slotIndexToTime(int slotIndex, int intervalMinutes) {
        int totalMinutes = slotIndex * intervalMinutes;
        int h = totalMinutes / 60;
        int m = totalMinutes % 60;
        return String.format("%02d:%02d", h, m);
    }

    /** Get TOU price for a given time slot; returns the matching period's price or a default */
    private BigDecimal getTouPriceForSlot(String timeStr, List<TouPricePeriod> touPrices) {
        int minutes = timeToMinutes(timeStr);
        for (TouPricePeriod period : touPrices) {
            for (int[] range : period.timeRanges) {
                if (range[0] <= range[1]) {
                    if (minutes >= range[0] && minutes < range[1]) {
                        return period.price;
                    }
                } else {
                    // Wraps around midnight
                    if (minutes >= range[0] || minutes < range[1]) {
                        return period.price;
                    }
                }
            }
        }
        // Default: return the average price if no period matches
        if (!touPrices.isEmpty()) {
            BigDecimal sum = touPrices.stream().map(p -> p.price).reduce(BigDecimal.ZERO, BigDecimal::add);
            return sum.divide(new BigDecimal(touPrices.size()), 4, RoundingMode.HALF_UP);
        }
        return new BigDecimal("0.5");
    }
}
