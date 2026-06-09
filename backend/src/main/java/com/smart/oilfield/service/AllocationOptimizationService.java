package com.smart.oilfield.service;

import com.smart.oilfield.dto.AllocationSuggestionDTO;
import com.smart.oilfield.entity.*;
import com.smart.oilfield.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.optim.MaxIter;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.linear.*;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AllocationOptimizationService {

    @Autowired
    private WellRepository wellRepository;

    @Autowired
    private WaterInjectionDataRepository injectionDataRepository;

    @Autowired
    private ProductionDataRepository productionDataRepository;

    @Autowired
    private InjectionProductionRelationRepository relationRepository;

    @Autowired
    private AllocationSuggestionRepository suggestionRepository;

    @Value("${allocation.days-interval:7}")
    private Integer daysInterval;

    @Value("${allocation.model-version:1.0.0}")
    private String modelVersion;

    private static final int HISTORY_DAYS = 30;
    private static final double MAX_WATER_INCREASE_RATE = 0.2;
    private static final double MAX_WATER_DECREASE_RATE = 0.3;

    @Scheduled(cron = "${allocation.schedule:0 0 2 * * ?}")
    public void scheduledAllocationOptimization() {
        LocalDate today = LocalDate.now();
        LocalDate lastRun = today.minusDays(daysInterval);
        
        boolean hasRecentRun = !suggestionRepository.findFromDate(lastRun).isEmpty();
        if (hasRecentRun) {
            log.info("Skipping allocation optimization - recent run exists");
            return;
        }

        log.info("Starting allocation optimization...");
        List<String> blocks = wellRepository.findAll().stream()
                .map(Well::getBlockName)
                .distinct()
                .collect(Collectors.toList());

        for (String block : blocks) {
            try {
                optimizeBlockAllocation(block, today);
            } catch (Exception e) {
                log.error("Failed to optimize allocation for block: {}", block, e);
            }
        }
        log.info("Allocation optimization completed");
    }

    public List<AllocationSuggestion> optimizeBlockAllocation(String blockName, LocalDate date) {
        log.info("Optimizing allocation for block: {}", blockName);

        List<Well> injectionWells = wellRepository
                .findByWellTypeAndBlockName("INJECTION", blockName)
                .stream()
                .filter(w -> "ACTIVE".equals(w.getStatus()))
                .collect(Collectors.toList());

        List<Well> productionWells = wellRepository
                .findByWellTypeAndBlockName("PRODUCTION", blockName)
                .stream()
                .filter(w -> "ACTIVE".equals(w.getStatus()))
                .collect(Collectors.toList());

        if (injectionWells.isEmpty() || productionWells.isEmpty()) {
            log.warn("No active wells in block: {}", blockName);
            return Collections.emptyList();
        }

        Map<String, Double> currentInjection = getCurrentInjectionVolumes(injectionWells);
        Map<String, double[]> waterFloodParams = calculateWaterFloodParameters(blockName);
        Map<String, List<InjectionProductionRelation>> relations = getInjectionRelations(injectionWells);

        double[] optimalVolumes = solveLinearProgram(
                injectionWells,
                productionWells,
                currentInjection,
                waterFloodParams,
                relations
        );

        List<AllocationSuggestion> suggestions = new ArrayList<>();
        for (int i = 0; i < injectionWells.size(); i++) {
            Well well = injectionWells.get(i);
            double current = currentInjection.getOrDefault(well.getWellId(), 0.0);
            double suggested = optimalVolumes[i];
            double adjustment = suggested - current;

            AllocationSuggestion suggestion = new AllocationSuggestion();
            suggestion.setWellId(well.getWellId());
            suggestion.setSuggestionDate(date);
            suggestion.setCurrentWaterVolume(current);
            suggestion.setSuggestedWaterVolume(Math.round(suggested * 100.0) / 100.0);
            suggestion.setAdjustmentAmount(Math.round(adjustment * 100.0) / 100.0);

            if (adjustment > 1.0) {
                suggestion.setAdjustmentDirection("INCREASE");
            } else if (adjustment < -1.0) {
                suggestion.setAdjustmentDirection("DECREASE");
            } else {
                suggestion.setAdjustmentDirection("KEEP");
            }

            suggestion.setReason(generateReason(well, current, suggested, relations.get(well.getWellId())));
            suggestion.setModelVersion(modelVersion);

            suggestions.add(suggestionRepository.save(suggestion));
        }

        log.info("Generated {} allocation suggestions for block: {}", suggestions.size(), blockName);
        return suggestions;
    }

    private Map<String, Double> getCurrentInjectionVolumes(List<Well> injectionWells) {
        Map<String, Double> volumes = new HashMap<>();
        for (Well well : injectionWells) {
            WaterInjectionData latest = injectionDataRepository.findLatestByWellId(well.getWellId());
            volumes.put(well.getWellId(), latest != null ? latest.getWaterVolume() : 100.0);
        }
        return volumes;
    }

    private Map<String, double[]> calculateWaterFloodParameters(String blockName) {
        Map<String, double[]> params = new HashMap<>();
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(HISTORY_DAYS);

        List<Well> productionWells = wellRepository.findByWellTypeAndBlockName("PRODUCTION", blockName);
        for (Well well : productionWells) {
            List<ProductionData> data = productionDataRepository
                    .findByWellIdAndReportDateBetweenOrderByReportDate(well.getWellId(), startDate, endDate);

            if (data.size() < 7) {
                params.put(well.getWellId(), new double[]{0.015, 0.0, 0.0});
                continue;
            }

            double[] regression = performWaterFloodRegression(data);
            params.put(well.getWellId(), regression);
        }
        return params;
    }

    private double[] performWaterFloodRegression(List<ProductionData> data) {
        int n = data.size();
        double[] x = new double[n];
        double[] y = new double[n];

        double cumulativeOil = 0;
        double cumulativeWater = 0;

        for (int i = 0; i < n; i++) {
            ProductionData d = data.get(i);
            cumulativeOil += d.getOilVolume();
            cumulativeWater += (d.getLiquidVolume() - d.getOilVolume());
            x[i] = cumulativeOil;
            y[i] = Math.log10(Math.max(cumulativeWater, 1.0));
        }

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
        }

        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        double intercept = (sumY - slope * sumX) / n;
        double waterCutRate = calculateWaterCutRiseRate(data);

        return new double[]{slope, intercept, waterCutRate};
    }

    private double calculateWaterCutRiseRate(List<ProductionData> data) {
        if (data.size() < 2) return 0.001;

        double firstWaterCut = data.get(0).getWaterCut();
        double lastWaterCut = data.get(data.size() - 1).getWaterCut();
        int days = data.size();

        return Math.max((lastWaterCut - firstWaterCut) / days / 100.0, 0.001);
    }

    private Map<String, List<InjectionProductionRelation>> getInjectionRelations(List<Well> injectionWells) {
        Map<String, List<InjectionProductionRelation>> relations = new HashMap<>();
        for (Well well : injectionWells) {
            relations.put(well.getWellId(),
                    relationRepository.findByInjectionWellId(well.getWellId()));
        }
        return relations;
    }

    private double[] solveLinearProgram(
            List<Well> injectionWells,
            List<Well> productionWells,
            Map<String, Double> currentInjection,
            Map<String, double[]> waterFloodParams,
            Map<String, List<InjectionProductionRelation>> relations) {

        int n = injectionWells.size();
        log.info("Solving linear program for {} injection wells", n);

        if (n <= 50) {
            return solveWithSimplex(injectionWells, currentInjection, waterFloodParams, relations);
        } else {
            return solveWithDualDecomposition(injectionWells, currentInjection, waterFloodParams, relations);
        }
    }

    private double[] solveWithSimplex(
            List<Well> injectionWells,
            Map<String, Double> currentInjection,
            Map<String, double[]> waterFloodParams,
            Map<String, List<InjectionProductionRelation>> relations) {

        int n = injectionWells.size();
        double[] coefficients = buildCoefficients(injectionWells, waterFloodParams, relations);

        LinearObjectiveFunction objective = new LinearObjectiveFunction(coefficients, 0);
        List<LinearConstraint> constraints = buildConstraints(injectionWells, currentInjection);

        try {
            long start = System.currentTimeMillis();
            SimplexSolver solver = new SimplexSolver();
            PointValuePair solution = solver.optimize(
                    new MaxIter(500),
                    objective,
                    new LinearConstraintSet(constraints),
                    GoalType.MAXIMIZE,
                    new NonNegativeConstraint(true)
            );

            log.info("Simplex solved {} variables in {}ms, objective: {}",
                    n, System.currentTimeMillis() - start, solution.getValue());
            return solution.getPoint();

        } catch (Exception e) {
            log.error("Simplex failed, using current values", e);
            return getCurrentValues(injectionWells, currentInjection);
        }
    }

    private double[] solveWithDualDecomposition(
            List<Well> injectionWells,
            Map<String, Double> currentInjection,
            Map<String, double[]> waterFloodParams,
            Map<String, List<InjectionProductionRelation>> relations) {

        int n = injectionWells.size();
        long start = System.currentTimeMillis();

        Map<String, Integer> wellIndexMap = new HashMap<>();
        for (int i = 0; i < n; i++) {
            wellIndexMap.put(injectionWells.get(i).getWellId(), i);
        }

        Map<String, List<Integer>> subproblems = buildSubproblems(injectionWells, relations, wellIndexMap);
        log.info("Decomposed into {} subproblems", subproblems.size());

        double[] result = getCurrentValues(injectionWells, currentInjection);
        double[] dualVariables = new double[subproblems.size()];
        double stepSize = 0.01;
        int maxIterations = 100;
        double convergenceThreshold = 1e-6;

        double totalTarget = currentInjection.values().stream().mapToDouble(Double::doubleValue).sum();
        double[] subproblemWeights = new double[subproblems.size()];
        int idx = 0;
        for (List<Integer> subproblem : subproblems.values()) {
            double subTotal = 0;
            for (int wellIdx : subproblem) {
                subTotal += currentInjection.getOrDefault(injectionWells.get(wellIdx).getWellId(), 0.0);
            }
            subproblemWeights[idx] = subTotal / totalTarget;
            idx++;
        }

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            double[] newResult = new double[n];
            System.arraycopy(result, 0, newResult, 0, n);

            double primalResidual = 0;
            int subIdx = 0;
            for (Map.Entry<String, List<Integer>> entry : subproblems.entrySet()) {
                List<Integer> subproblemIndices = entry.getValue();
                double subTarget = totalTarget * subproblemWeights[subIdx] + dualVariables[subIdx];

                double[] subSolution = solveSubproblem(
                        injectionWells, subproblemIndices, currentInjection,
                        waterFloodParams, relations, subTarget);

                for (int i = 0; i < subproblemIndices.size(); i++) {
                    int wellIdx = subproblemIndices.get(i);
                    double diff = subSolution[i] - result[wellIdx];
                    primalResidual += Math.abs(diff);
                    newResult[wellIdx] = subSolution[i];
                }
                subIdx++;
            }

            double totalInjection = 0;
            for (double v : newResult) {
                totalInjection += v;
            }
            double balanceResidual = Math.abs(totalInjection - totalTarget) / totalTarget;

            subIdx = 0;
            for (List<Integer> subproblem : subproblems.values()) {
                double subTotal = 0;
                for (int wellIdx : subproblem) {
                    subTotal += newResult[wellIdx];
                }
                double subTarget = totalTarget * subproblemWeights[subIdx];
                dualVariables[subIdx] += stepSize * (subTotal - subTarget);
                subIdx++;
            }

            double dualResidual = 0;
            for (double d : dualVariables) {
                dualResidual += Math.abs(d);
            }

            if (primalResidual < convergenceThreshold * n &&
                balanceResidual < convergenceThreshold &&
                iteration > 5) {
                log.info("Dual decomposition converged at iteration {}, primal={}, balance={}",
                        iteration, primalResidual, balanceResidual);
                result = newResult;
                break;
            }

            result = newResult;
            stepSize = Math.max(stepSize * 0.99, 1e-4);
        }

        double totalInjection = 0;
        for (double v : result) {
            totalInjection += v;
        }
        double scalingFactor = totalTarget / totalInjection;
        for (int i = 0; i < n; i++) {
            double current = currentInjection.getOrDefault(injectionWells.get(i).getWellId(), 0.0);
            double maxIncrease = current * (1 + MAX_WATER_INCREASE_RATE);
            double maxDecrease = current * (1 - MAX_WATER_DECREASE_RATE);

            result[i] = result[i] * scalingFactor;
            result[i] = Math.min(result[i], maxIncrease);
            result[i] = Math.max(result[i], Math.max(maxDecrease, 10.0));
        }

        log.info("Dual decomposition solved {} variables in {}ms",
                n, System.currentTimeMillis() - start);
        return result;
    }

    private Map<String, List<Integer>> buildSubproblems(
            List<Well> injectionWells,
            Map<String, List<InjectionProductionRelation>> relations,
            Map<String, Integer> wellIndexMap) {

        Map<String, List<Integer>> subproblems = new HashMap<>();
        Map<String, String> wellToCommunity = new HashMap<>();
        int nextCommunityId = 0;

        for (Well injWell : injectionWells) {
            String wellId = injWell.getWellId();
            List<InjectionProductionRelation> wellRelations = relations.get(wellId);

            if (wellRelations == null || wellRelations.isEmpty()) {
                String community = "comm_" + (nextCommunityId++);
                wellToCommunity.put(wellId, community);
                subproblems.computeIfAbsent(community, k -> new ArrayList<>())
                        .add(wellIndexMap.get(wellId));
                continue;
            }

            Set<String> connectedCommunities = new HashSet<>();
            for (InjectionProductionRelation rel : wellRelations) {
                String prodWellId = rel.getProductionWellId();
                for (Well otherWell : injectionWells) {
                    String otherWellId = otherWell.getWellId();
                    if (otherWellId.equals(wellId)) continue;
                    List<InjectionProductionRelation> otherRelations = relations.get(otherWellId);
                    if (otherRelations != null) {
                        boolean connected = otherRelations.stream()
                                .anyMatch(r -> prodWellId.equals(r.getProductionWellId()));
                        if (connected && wellToCommunity.containsKey(otherWellId)) {
                            connectedCommunities.add(wellToCommunity.get(otherWellId));
                        }
                    }
                }
            }

            String targetCommunity;
            if (connectedCommunities.isEmpty()) {
                targetCommunity = "comm_" + (nextCommunityId++);
            } else {
                targetCommunity = connectedCommunities.iterator().next();
                for (String otherCommunity : connectedCommunities) {
                    if (!otherCommunity.equals(targetCommunity)) {
                        List<Integer> wellsToMove = subproblems.remove(otherCommunity);
                        if (wellsToMove != null) {
                            subproblems.get(targetCommunity).addAll(wellsToMove);
                            for (int wellIdx : wellsToMove) {
                                String movedWellId = injectionWells.get(wellIdx).getWellId();
                                wellToCommunity.put(movedWellId, targetCommunity);
                            }
                        }
                    }
                }
            }

            wellToCommunity.put(wellId, targetCommunity);
            subproblems.computeIfAbsent(targetCommunity, k -> new ArrayList<>())
                    .add(wellIndexMap.get(wellId));
        }

        int maxSubproblemSize = 40;
        Map<String, List<Integer>> finalSubproblems = new HashMap<>();
        int splitCounter = 0;
        for (Map.Entry<String, List<Integer>> entry : subproblems.entrySet()) {
            List<Integer> wells = entry.getValue();
            if (wells.size() <= maxSubproblemSize) {
                finalSubproblems.put(entry.getKey(), wells);
            } else {
                for (int i = 0; i < wells.size(); i += maxSubproblemSize) {
                    int end = Math.min(i + maxSubproblemSize, wells.size());
                    String splitKey = entry.getKey() + "_split_" + (splitCounter++);
                    finalSubproblems.put(splitKey, new ArrayList<>(wells.subList(i, end)));
                }
            }
        }

        return finalSubproblems;
    }

    private double[] solveSubproblem(
            List<Well> allInjectionWells,
            List<Integer> subproblemIndices,
            Map<String, Double> currentInjection,
            Map<String, double[]> waterFloodParams,
            Map<String, List<InjectionProductionRelation>> relations,
            double subTarget) {

        int m = subproblemIndices.size();
        double[] coefficients = new double[m];

        for (int i = 0; i < m; i++) {
            Well injWell = allInjectionWells.get(subproblemIndices.get(i));
            List<InjectionProductionRelation> wellRelations = relations.get(injWell.getWellId());

            double oilGainCoefficient = 0;
            double waterCutPenalty = 0;

            for (InjectionProductionRelation rel : wellRelations) {
                double[] params = waterFloodParams.get(rel.getProductionWellId());
                if (params != null) {
                    double effectiveness = rel.getEffectivenessDegree() != null ?
                            rel.getEffectivenessDegree() / 100.0 : 0.5;
                    oilGainCoefficient += params[0] * effectiveness * 1000;
                    waterCutPenalty += params[2] * effectiveness * 500;
                }
            }

            coefficients[i] = oilGainCoefficient - waterCutPenalty;
        }

        LinearObjectiveFunction objective = new LinearObjectiveFunction(coefficients, 0);
        List<LinearConstraint> constraints = new ArrayList<>();

        double[] equalityCoeff = new double[m];
        double subCurrentTotal = 0;
        for (int i = 0; i < m; i++) {
            equalityCoeff[i] = 1.0;
            subCurrentTotal += currentInjection.getOrDefault(
                    allInjectionWells.get(subproblemIndices.get(i)).getWellId(), 0.0);
        }

        double constraintTarget = subTarget > 0 ? subTarget : subCurrentTotal;
        constraints.add(new LinearConstraint(equalityCoeff, Relationship.EQ, constraintTarget));

        for (int i = 0; i < m; i++) {
            double current = currentInjection.getOrDefault(
                    allInjectionWells.get(subproblemIndices.get(i)).getWellId(), 0.0);
            double maxIncrease = current * (1 + MAX_WATER_INCREASE_RATE);
            double maxDecrease = current * (1 - MAX_WATER_DECREASE_RATE);

            double[] boundCoeff = new double[m];
            boundCoeff[i] = 1.0;
            constraints.add(new LinearConstraint(boundCoeff, Relationship.LEQ, maxIncrease));
            constraints.add(new LinearConstraint(boundCoeff, Relationship.GEQ, Math.max(maxDecrease, 10.0)));
        }

        try {
            SimplexSolver solver = new SimplexSolver();
            PointValuePair solution = solver.optimize(
                    new MaxIter(300),
                    objective,
                    new LinearConstraintSet(constraints),
                    GoalType.MAXIMIZE,
                    new NonNegativeConstraint(true)
            );
            return solution.getPoint();
        } catch (Exception e) {
            double[] result = new double[m];
            for (int i = 0; i < m; i++) {
                result[i] = currentInjection.getOrDefault(
                        allInjectionWells.get(subproblemIndices.get(i)).getWellId(), 0.0);
            }
            return result;
        }
    }

    private double[] buildCoefficients(
            List<Well> injectionWells,
            Map<String, double[]> waterFloodParams,
            Map<String, List<InjectionProductionRelation>> relations) {

        int n = injectionWells.size();
        double[] coefficients = new double[n];

        for (int i = 0; i < n; i++) {
            Well injWell = injectionWells.get(i);
            List<InjectionProductionRelation> wellRelations = relations.get(injWell.getWellId());

            double oilGainCoefficient = 0;
            double waterCutPenalty = 0;

            if (wellRelations != null) {
                for (InjectionProductionRelation rel : wellRelations) {
                    double[] params = waterFloodParams.get(rel.getProductionWellId());
                    if (params != null) {
                        double effectiveness = rel.getEffectivenessDegree() != null ?
                                rel.getEffectivenessDegree() / 100.0 : 0.5;
                        oilGainCoefficient += params[0] * effectiveness * 1000;
                        waterCutPenalty += params[2] * effectiveness * 500;
                    }
                }
            }

            coefficients[i] = oilGainCoefficient - waterCutPenalty;
        }

        return coefficients;
    }

    private List<LinearConstraint> buildConstraints(
            List<Well> injectionWells,
            Map<String, Double> currentInjection) {

        int n = injectionWells.size();
        List<LinearConstraint> constraints = new ArrayList<>(2 * n + 1);

        double[] equalityCoeff = new double[n];
        double totalCurrentInjection = 0;
        for (int i = 0; i < n; i++) {
            equalityCoeff[i] = 1.0;
            totalCurrentInjection += currentInjection.getOrDefault(injectionWells.get(i).getWellId(), 0.0);
        }
        constraints.add(new LinearConstraint(equalityCoeff, Relationship.EQ, totalCurrentInjection));

        for (int i = 0; i < n; i++) {
            double current = currentInjection.getOrDefault(injectionWells.get(i).getWellId(), 0.0);
            double maxIncrease = current * (1 + MAX_WATER_INCREASE_RATE);
            double maxDecrease = current * (1 - MAX_WATER_DECREASE_RATE);

            double[] upperBound = new double[n];
            upperBound[i] = 1.0;
            constraints.add(new LinearConstraint(upperBound, Relationship.LEQ, maxIncrease));

            double[] lowerBound = new double[n];
            lowerBound[i] = 1.0;
            constraints.add(new LinearConstraint(lowerBound, Relationship.GEQ, Math.max(maxDecrease, 10.0)));
        }

        return constraints;
    }

    private double[] getCurrentValues(List<Well> injectionWells, Map<String, Double> currentInjection) {
        int n = injectionWells.size();
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            result[i] = currentInjection.getOrDefault(injectionWells.get(i).getWellId(), 0.0);
        }
        return result;
    }

    private String generateReason(Well well, double current, double suggested,
                                   List<InjectionProductionRelation> relations) {
        double percentChange = ((suggested - current) / current) * 100;
        StringBuilder reason = new StringBuilder();

        if (Math.abs(percentChange) < 1) {
            reason.append("当前注水量合理，建议保持");
        } else if (percentChange > 0) {
            reason.append(String.format("建议增加注水量 %.1f%%", percentChange));
            if (relations != null && !relations.isEmpty()) {
                long highEffCount = relations.stream()
                        .filter(r -> "HIGH".equals(r.getEffectivenessType())).count();
                if (highEffCount > 0) {
                    reason.append(String.format("，该井连通 %d 口高效受效采油井", highEffCount));
                }
            }
        } else {
            reason.append(String.format("建议减少注水量 %.1f%%", -percentChange));
            if (relations != null && !relations.isEmpty()) {
                long lowEffCount = relations.stream()
                        .filter(r -> "LOW".equals(r.getEffectivenessType())).count();
                if (lowEffCount > 0) {
                    reason.append(String.format("，该井有 %d 口低效受效井，需控制注水", lowEffCount));
                }
            }
        }

        return reason.toString();
    }

    public List<AllocationSuggestionDTO> getLatestSuggestions() {
        List<AllocationSuggestion> suggestions = suggestionRepository.findLatestSuggestions();
        return convertToDTO(suggestions);
    }

    public List<AllocationSuggestionDTO> getSuggestionsByDate(LocalDate date) {
        List<AllocationSuggestion> suggestions = suggestionRepository.findBySuggestionDate(date);
        return convertToDTO(suggestions);
    }

    public List<AllocationSuggestionDTO> getSuggestionsByWell(String wellId) {
        List<AllocationSuggestion> suggestions = suggestionRepository
                .findByWellIdOrderBySuggestionDateDesc(wellId);
        return convertToDTO(suggestions);
    }

    private List<AllocationSuggestionDTO> convertToDTO(List<AllocationSuggestion> suggestions) {
        return suggestions.stream().map(s -> {
            AllocationSuggestionDTO dto = new AllocationSuggestionDTO();
            dto.setId(s.getId());
            dto.setWellId(s.getWellId());
            dto.setSuggestionDate(s.getSuggestionDate());
            dto.setCurrentWaterVolume(s.getCurrentWaterVolume());
            dto.setSuggestedWaterVolume(s.getSuggestedWaterVolume());
            dto.setAdjustmentDirection(s.getAdjustmentDirection());
            dto.setAdjustmentAmount(s.getAdjustmentAmount());
            dto.setReason(s.getReason());

            wellRepository.findById(s.getWellId()).ifPresent(well -> {
                dto.setWellName(well.getWellName());
                dto.setLongitude(well.getLongitude());
                dto.setLatitude(well.getLatitude());
            });

            return dto;
        }).collect(Collectors.toList());
    }

    public void runOptimizationNow() {
        log.info("Manual optimization triggered");
        List<String> blocks = wellRepository.findAll().stream()
                .map(Well::getBlockName)
                .distinct()
                .collect(Collectors.toList());

        LocalDate today = LocalDate.now();
        for (String block : blocks) {
            try {
                optimizeBlockAllocation(block, today);
            } catch (Exception e) {
                log.error("Failed to optimize allocation for block: {}", block, e);
            }
        }
    }
}
