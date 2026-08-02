package io.github.fukusaka.keel.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFinallySection
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Detects `Arena()` allocations that nothing will reliably `clear()`.
 *
 * Arena is a Kotlin/Native cinterop construct that owns native memory: everything
 * allocated through it lives until `clear()` runs. The rule asks who is responsible
 * for that call, which depends on how the arena is bound:
 *
 * - **Local `val`** — the owner is the enclosing function, and the arena must be
 *   handed straight to a `try` whose `finally` clears it. A `clear()` reachable only
 *   on the normal path leaks the whole arena when the body throws, which is exactly
 *   when a test is failing and the leak is hardest to attribute.
 * - **Class property** — the owner is the class, and some member must clear it. The
 *   method's *name* is not prescribed: `close()`, `dispose()` and an `@AfterTest`
 *   teardown are all legitimate.
 * - **Unbound** — `Arena().alloc(...)` keeps no reference, so the memory is
 *   unreachable and can never be freed.
 *
 * ## What this rule cannot see
 *
 * It runs without type resolution and matches syntax, so it is an approximation in
 * two directions worth stating plainly.
 *
 * It proves nothing about *reachability*. For a local arena it insists on one
 * shape — declaration, then the guarding `try` — rather than reasoning about paths,
 * because "does every path from here reach `clear()`" is dataflow and this is a
 * syntax matcher. Code that frees its arena in some other correct shape will be
 * reported; `@Suppress("ArenaLeak")` with a comment is the intended answer.
 *
 * It also only recognises `clear()` written on the arena by name, including through
 * `this.` and `?.`. An arena cleared via `with(arena) { clear() }` or
 * `arena.let { it.clear() }` reads as uncleared. Scope functions are deliberately
 * not chased: matching them by name without type resolution would credit any
 * receiver's `clear()`, which is the failure mode that lets a real leak through.
 *
 * ## Why ownership, and why shadowing matters
 *
 * An earlier version asked only whether the enclosing *class* had a `close()`
 * containing some `clear()`. That over- and under-reported at once: it flagged every
 * correctly scoped `try`/`finally` arena inside a class, skipped `Arena()` in a
 * top-level function outright (a missing enclosing class ended the check), and
 * counted a `close()` that cleared something else entirely.
 *
 * Resolving the owner fixes those but introduces a hazard of its own, so the search
 * for `clear()` excludes scopes that rebind the name. Without that, a class holding
 * an arena nobody frees passes as soon as any method happens to declare its own
 * local `arena` and clear it — a leak hidden by a name collision.
 */
class ArenaLeakRule(config: Config) : Rule(config) {

    override val issue = Issue(
        "ArenaLeak",
        Severity.Defect,
        "Arena() allocated without a clear() that will run",
        Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != ARENA) return

        val property = expression.owningProperty()
        if (property == null) {
            report(expression, "Arena() is not bound to a name, so nothing can clear() it later.")
            return
        }
        val name = property.name ?: return

        if (property.isLocal) checkLocal(expression, property, name) else checkProperty(expression, property, name)
    }

    /**
     * The property this `Arena()` initialises, or null when the result is discarded.
     *
     * The call need not *be* the initializer: `Arena().also { … }`, a parenthesised
     * initializer and `by lazy { Arena() }` all bind the arena to the name just as
     * firmly, and treating them as unbound reported correct code.
     */
    private fun KtCallExpression.owningProperty(): KtProperty? {
        val property = getParentOfType<KtProperty>(strict = true) ?: return null
        val binding = property.initializer ?: property.delegateExpression ?: return null
        return property.takeIf { binding.covers(this) }
    }

    /**
     * A local arena is owned by the function that declares it, and the `clear()` has
     * to run on the throwing path too — so the guarding `try` must be what follows
     * the declaration, with nothing but further declarations in between.
     */
    private fun checkLocal(expression: KtCallExpression, property: KtProperty, name: String) {
        val scope = property.getParentOfType<KtDeclarationWithBody>(strict = true) ?: return
        val clears = scope.clearCallsOn(property, name)
        if (clears.isEmpty()) {
            report(expression, "Local Arena '$name' is never cleared. Call $name.clear() in a finally block.")
            return
        }
        val guard = property.guardingTry()
        if (guard == null || clears.none { it.isInFinallyOf(guard) }) {
            report(
                expression,
                "Local Arena '$name' is cleared, but not by a finally guarding it, so it leaks when the " +
                    "body throws. Put the allocation directly before the try whose finally calls $name.clear().",
            )
        }
    }

    /**
     * The `try` that guards this declaration: the next statement after it, looking
     * past sibling declarations, which is the shape every arena in this repository
     * already uses.
     */
    private fun KtProperty.guardingTry(): KtTryExpression? {
        val block = parent as? KtBlockExpression ?: return null
        return block.statements
            .asSequence()
            .dropWhile { it !== this }
            .drop(1)
            .dropWhile { it is KtProperty }
            .firstOrNull() as? KtTryExpression
    }

    private fun KtCallExpression.isInFinallyOf(tryExpression: KtTryExpression): Boolean =
        getParentOfType<KtFinallySection>(strict = true)?.let { it.parent === tryExpression } == true

    /**
     * A property arena is owned by its class, and any member may tear it down —
     * teardown methods are variously named `close`, `dispose`, or a test's
     * `@AfterTest` hook, so the rule looks for the call rather than for a name.
     */
    private fun checkProperty(expression: KtCallExpression, property: KtProperty, name: String) {
        val owner = property.getParentOfType<KtClassOrObject>(strict = true) ?: return
        if (owner.clearCallsOn(property, name).isEmpty()) {
            report(
                expression,
                "Arena property '$name' is never cleared. Call $name.clear() from a teardown method " +
                    "(close(), dispose() or an @AfterTest hook).",
            )
        }
    }

    /**
     * Every `<name>.clear()` inside this element that refers to *this* `name`.
     *
     * Calls sitting under a scope that rebinds the name are excluded: they clear the
     * inner binding, and counting them would let an unfreed field pass because some
     * unrelated method declared a local of the same name.
     */
    private fun KtElement.clearCallsOn(owner: KtProperty, name: String): List<KtCallExpression> =
        collectDescendantsOfType<KtCallExpression>().filter { call ->
            call.calleeExpression?.text == CLEAR &&
                call.receiverName() == name &&
                !call.isUnderRebindingOf(owner, name, boundary = this)
        }

    /** The receiver `clear()` is called on, with a leading `this.` dropped; `a.clear()` and `a?.clear()` both give `a`. */
    private fun KtCallExpression.receiverName(): String? =
        (parent as? KtQualifiedExpression)?.receiverExpression?.text?.removePrefix(THIS_PREFIX)

    /**
     * True when some scope between this call and [boundary] rebinds [name].
     *
     * [owner]'s own declaration is not a rebinding: for a local arena the declaring
     * block is inside the boundary by construction, and counting it would exclude
     * every clear of the very arena being checked.
     */
    private fun KtCallExpression.isUnderRebindingOf(owner: KtProperty, name: String, boundary: KtElement): Boolean =
        parents.takeWhile { it !== boundary }.any { scope ->
            when (scope) {
                is KtBlockExpression -> scope.statements.any { it is KtProperty && it.name == name && it !== owner }
                is KtFunction -> scope.valueParameters.any { it.name == name }
                else -> false
            }
        }

    /** True when [element] is this expression or sits inside it. */
    private fun KtExpression.covers(element: KtElement): Boolean =
        this === element || element.parents.any { it === this }

    private fun report(expression: KtCallExpression, message: String) {
        report(CodeSmell(issue, Entity.from(expression), message))
    }

    companion object {
        private const val ARENA = "Arena"
        private const val CLEAR = "clear"
        private const val THIS_PREFIX = "this."
    }
}
