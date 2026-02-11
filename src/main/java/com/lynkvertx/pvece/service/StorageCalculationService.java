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

        // Calculate per-day load curves
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
        Arrays.fill(slotPower, BigDecimal.ZERO);

        for (SlotData slot : chargeableSlots) {
            if (remainingEnergy.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal energyThisSlot = remainingEnergy.min(maxEnergyPerSlot);
            BigDecimal powerThisSlot = energyThisSlot.divide(intervalHours, 2, RoundingMode.HALF_UP);
            slotPower[slot.index] = powerThisSlot;
            remainingEnergy = remainingEnergy.subtract(energyThisSlot);
        }

        // Build the load curve
        List<LoadCurvePoint> curve = new ArrayList<>();
        for (int i = 0; i < slotsPerDay; i++) {
            curve.add(new LoadCurvePoint(
                slotIndexToTime(i, config.getTimeSlotIntervalMinutes()),
                slotPower[i]
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
            total = total.add(point.getPowerKw().multiply(intervalHours));
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

        DailyLoadCurveResult(Map<String, List<LoadCurvePoint>> dailyCurves,
                             List<LoadCurvePoint> maxEnvelopeCurve,
                             BigDecimal peakPowerKw,
                             BigDecimal dailyMaxEnergyKwh) {
            this.dailyCurves = dailyCurves;
            this.maxEnvelopeCurve = maxEnvelopeCurve;
            this.peakPowerKw = peakPowerKw;
            this.dailyMaxEnergyKwh = dailyMaxEnergyKwh;
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
