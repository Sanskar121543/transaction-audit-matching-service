package io.github.sanskar121543.reconciliation.generator.scoring;

import io.github.sanskar121543.reconciliation.generator.Scenario;
import io.github.sanskar121543.reconciliation.generator.Truth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compares the service's matches and breaks with the generator's ground truth. A match is correct only if
 * it pairs exactly the right two records; a break only if its type and both sides are right.
 */
public final class Scorer {

    private Scorer() {
    }

    private record Pair(String ledgerExternalId, int statementRow) {
    }

    private record BreakKey(String type, String ledgerExternalId, Integer statementRow) {
    }

    public static Score score(Truth truth, Predictions predicted) {
        Set<Pair> truePairs = new HashSet<>();
        truth.matches().forEach(m -> truePairs.add(new Pair(m.ledgerExternalId(), m.statementRow())));
        Map<Pair, String> predictedRules = new HashMap<>();
        predicted.matches().forEach(m -> predictedRules.put(new Pair(m.ledgerExternalId(), m.statementRow()), m.rule()));
        long correctMatches = predictedRules.keySet().stream().filter(truePairs::contains).count();

        Set<BreakKey> trueBreaks = new HashSet<>();
        truth.breaks().forEach(b -> trueBreaks.add(new BreakKey(b.type(), b.ledgerExternalId(), b.statementRow())));
        Set<BreakKey> predictedBreaks = new HashSet<>();
        predicted.breaks().forEach(b -> predictedBreaks.add(new BreakKey(b.type(), b.ledgerExternalId(), b.statementRow())));
        long correctBreaks = predictedBreaks.stream().filter(trueBreaks::contains).count();

        Map<String, Counts> byType = new TreeMap<>();
        Set<String> types = new HashSet<>();
        trueBreaks.forEach(b -> types.add(b.type()));
        predictedBreaks.forEach(b -> types.add(b.type()));
        for (String type : types) {
            long p = predictedBreaks.stream().filter(b -> b.type().equals(type)).count();
            long e = trueBreaks.stream().filter(b -> b.type().equals(type)).count();
            long c = predictedBreaks.stream().filter(b -> b.type().equals(type) && trueBreaks.contains(b)).count();
            byType.put(type, new Counts(p, e, c));
        }

        Map<String, long[]> scenarios = new LinkedHashMap<>();
        for (Scenario scenario : Scenario.values()) {
            scenarios.put(scenario.name(), new long[2]);
        }
        long withExpectedRule = 0;
        long byExpectedRule = 0;
        for (Truth.Match m : truth.matches()) {
            Pair pair = new Pair(m.ledgerExternalId(), m.statementRow());
            boolean found = predictedRules.containsKey(pair);
            tally(scenarios, m.scenario(), found);
            if (found && m.expectedRule() != null) {
                withExpectedRule++;
                if (Objects.equals(predictedRules.get(pair), m.expectedRule())) {
                    byExpectedRule++;
                }
            }
        }
        for (Truth.Break b : truth.breaks()) {
            tally(scenarios, b.scenario(),
                    predictedBreaks.contains(new BreakKey(b.type(), b.ledgerExternalId(), b.statementRow())));
        }
        Map<String, Score.ScenarioResult> scenarioResults = new LinkedHashMap<>();
        scenarios.forEach((name, counts) -> scenarioResults.put(name, new Score.ScenarioResult(counts[0], counts[1])));

        return new Score(new Counts(predictedRules.size(), truePairs.size(), correctMatches),
                new Counts(predictedBreaks.size(), trueBreaks.size(), correctBreaks), byType, scenarioResults,
                new Score.RuleAgreement(withExpectedRule, byExpectedRule));
    }

    private static void tally(Map<String, long[]> scenarios, Scenario scenario, boolean correct) {
        long[] counts = scenarios.get(scenario.name());
        counts[0]++;
        if (correct) {
            counts[1]++;
        }
    }
}
