package io.github.fukusaka.keel.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Detects `Arena()` allocations without a corresponding `arena.clear()`
 * in a close() method.
 *
 * Arena is a Kotlin/Native cinterop construct that manages native memory.
 * Forgetting to call `clear()` leaks all memory allocated through the Arena.
 *
 * The rule checks: if a class has a property initialized with `Arena()`,
 * its `close()` method must contain a call to `clear()`.
 */
class ArenaLeakRule(config: Config) : Rule(config) {

    override val issue = Issue(
        "ArenaLeak",
        Severity.Defect,
        "Arena() allocated without clear() in close()",
        Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text ?: return
        if (callee != "Arena") return

        // Check if the enclosing class has a close() method with clear()
        val enclosingClass = expression.getParentOfType<KtClass>(strict = true) ?: return
        val closeMethods = enclosingClass.collectDescendantsOfType<KtNamedFunction>()
            .filter { it.name == "close" }

        val hasClearInClose = closeMethods.any { closeMethod ->
            closeMethod.collectDescendantsOfType<KtCallExpression>().any { call ->
                call.calleeExpression?.text == "clear"
            }
        }

        if (!hasClearInClose) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "Arena() allocated but no clear() found in close() method. " +
                        "Call arena.clear() in close() to free native memory.",
                ),
            )
        }
    }
}
