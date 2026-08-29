package io.github.fukusaka.keel.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Provides keel-specific detekt rules.
 *
 * The leak rules detect common patterns where POSIX/cinterop resources
 * (IoBuf, Arena, StableRef) are allocated without corresponding
 * release in try-finally. [SecondAfterTestRule] guards a test-fixture
 * invariant instead: a source set that centralises teardown in one
 * inherited `@AfterTest` check bans siblings that would race it.
 */
class KeelRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "keel"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            IoBufLeakRule(config),
            ArenaLeakRule(config),
            StableRefLeakRule(config),
            SecondAfterTestRule(config),
        ),
    )
}
