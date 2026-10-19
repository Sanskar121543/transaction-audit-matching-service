package io.github.sanskar121543.reconciliation.generator.scoring;

import java.util.Map;

/**
 * @param matches          predicted match pairs against true pairs
 * @param breaks           predicted breaks (type + both sides) against true breaks
 * @param breaksByType     the same, per break type
 * @param scenarios        per generated scenario: how many of its truth entries the service got exactly right
 * @param expectedRuleUsed of the correct matches that have an expected rule, how many used that rule
 */
public record Score(Counts matches, Counts breaks, Map<String, Counts> breaksByType,
        Map<String, ScenarioResult> scenarios, RuleAgreement expectedRuleUsed) {

    public record ScenarioResult(long truthEntries, long correct) {
    }

    public record RuleAgreement(long correctMatchesWithExpectedRule, long madeByExpectedRule) {
    }
}
