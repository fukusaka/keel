package io.github.fukusaka.keel.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Provides keel-specific detekt rules for resource leak detection.
 *
 * Rules detect common patterns where POSIX/cinterop resources
 * (NativeBuf, Arena, StableRef) are allocated without corresponding
 * release in try-finally.
 */
class KeelRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "keel"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            NativeBufLeakRule(config),
            ArenaLeakRule(config),
            StableRefLeakRule(config),
        ),
    )
}
