package io.github.fukusaka.keel.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Detects `NativeBuf(size)` allocations that are not protected by
 * try-finally with release()/close().
 *
 * **Limitations**: AST-level only (no type resolution). Cannot track
 * ownership transfer across function boundaries (e.g., `channel.write(buf)`
 * may internally release the buf). Use `@Suppress("NativeBufLeak")` for
 * intentional ownership transfers documented in KDoc.
 */
class NativeBufLeakRule(config: Config) : Rule(config) {

    override val issue = Issue(
        "NativeBufLeak",
        Severity.Defect,
        "NativeBuf allocated without try-finally release/close protection",
        Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text ?: return
        if (callee != "NativeBuf") return

        // Skip allocator implementations — they return NativeBuf to caller.
        // Also skip test functions (test helper methods that allocate buffers).
        // Skip allocator implementations — they return NativeBuf to caller.
        // Single-expression functions (fun foo() = NativeBuf(...)) have the
        // NativeBuf call as the body expression, so strict=false matches.
        val enclosingFunction = expression.getParentOfType<KtNamedFunction>(strict = false)
        val funcName = enclosingFunction?.name ?: ""
        if (funcName == "allocate" || funcName == "release") return
        // If no enclosing function found (e.g., property initializer), skip
        if (enclosingFunction == null) return
        val tryExpressions = enclosingFunction.collectDescendantsOfType<KtTryExpression>()

        val hasProtectedRelease = tryExpressions.any { tryExpr ->
            val finallyBlock = tryExpr.finallyBlock?.finalExpression ?: return@any false
            val finallyCalls = finallyBlock.collectDescendantsOfType<KtCallExpression>()
            finallyCalls.any { call ->
                val name = call.calleeExpression?.text
                name == "release" || name == "close"
            }
        }

        if (!hasProtectedRelease) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "NativeBuf allocated without try-finally release/close. " +
                        "Use try { ... } finally { buf.release() } or " +
                        "@Suppress(\"NativeBufLeak\") for intentional ownership transfer.",
                ),
            )
        }
    }
}
