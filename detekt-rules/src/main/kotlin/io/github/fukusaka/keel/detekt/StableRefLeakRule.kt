package io.github.fukusaka.keel.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Detects `StableRef.create()` calls without a corresponding `dispose()`
 * in the same function scope.
 *
 * StableRef pins a Kotlin object so it can be passed to C callbacks.
 * Forgetting to call `dispose()` prevents the object from being GC'd.
 *
 * Typical pattern: `StableRef.create(this)` in `start()`, with `dispose()`
 * called in the C callback after retrieving the reference. The rule checks
 * the enclosing function for a `dispose()` call; callback-based disposal
 * (recognized by `asCPointer`, `staticCFunction`, or `keel_*` calls)
 * suppresses the warning automatically.
 */
class StableRefLeakRule(config: Config) : Rule(config) {

    override val issue = Issue(
        "StableRefLeak",
        Severity.Defect,
        "StableRef.create() without dispose() in scope",
        Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression?.text ?: return
        if (callee != "create") return

        // Check if this is StableRef.create(...) by examining the
        // parent dot-qualified expression receiver.
        // Fallback: in lint mode (no type resolution), check parent text.
        val dotParent = expression.parent as? KtDotQualifiedExpression
        if (dotParent != null) {
            val receiver = dotParent.receiverExpression.text
            if (receiver != "StableRef") return
        } else {
            // lint mode fallback: parent text should contain "StableRef"
            val parentText = expression.parent?.text ?: return
            if ("StableRef" !in parentText) return
        }

        // Check enclosing function for dispose(), asCPointer,
        // staticCFunction, or keel_* cinterop calls
        val enclosingFunction = expression.getParentOfType<KtNamedFunction>(strict = true) ?: return
        val allCalls = enclosingFunction.collectDescendantsOfType<KtCallExpression>()
        val hasDispose = allCalls.any { call ->
            call.calleeExpression?.text == "dispose"
        }
        // C interop callback pattern: StableRef is passed to C via
        // asCPointer(). The C callback calls dispose() — not visible
        // in Kotlin AST.
        val hasCallbackPattern = allCalls.any { call ->
            val name = call.calleeExpression?.text ?: ""
            name == "staticCFunction" || name == "asCPointer" || name.startsWith("keel_")
        }

        if (!hasDispose && !hasCallbackPattern) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "StableRef.create() without dispose() in this function. " +
                        "If dispose() is called in a C callback, use " +
                        "@Suppress(\"StableRefLeak\").",
                ),
            )
        }
    }
}
