package io.github.fukusaka.keel.detekt

import io.gitlab.arturbosch.detekt.api.AnnotationExcluder
import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.api.config
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.resolve.BindingContext

/**
 * Reports an `@AfterTest` function whose name is not in [allowedNames].
 *
 * For a source set whose fixture centralises teardown in one inherited
 * `@AfterTest` check. On Kotlin/Native the runner executes a class's own
 * `@AfterTest`s first in declaration order, then every inherited one in
 * function-name order, and stops at the first that throws — so a sibling
 * teardown whose name sorts before the check can take the check with it when
 * it throws. Measured before this rule existed: one throwing sibling left a
 * hundred and twenty-nine cases running with the fixture's leak check never
 * reached and not one leak reported. The check's own name buys nothing
 * against a sibling named after it, which is the likeliest name of all.
 *
 * So the source set bans the race instead of running it: the one check is
 * allowed by name, and anything else a case or fixture would tear down goes
 * through the fixture's registration, which composes without an order.
 *
 * The annotation is matched two ways, both without type resolution: by its
 * short name, and through [AnnotationExcluder]'s guessed fully-qualified
 * names, which is what catches an import alias — measured, an aliased
 * `@AfterTest` passed the first match and is caught by the second. The short
 * name also matches an `AfterTest` from any other package — over-reporting,
 * on purpose: in a scoped test source set a foreign annotation of that name
 * is noise worth a look, and the safe direction is to say so.
 *
 * Premises, none enforced here. A *typealias* for the annotation still slips
 * past — neither match sees through one without resolution. The allowlist
 * matches by name alone, so a function merely *named* like the check passes;
 * what keeps that from replacing the real check is the language, since the
 * check is final and an accidental override does not compile. The scoping to
 * a source set lives in the detekt config's `includes`, so a renamed or
 * relocated source set silently un-scopes the rule — a moved directory turns
 * every finding off, and nothing here says so. And the rule reaches only runs
 * that lint: a plain module test run compiles and executes a racing sibling
 * without ever consulting detekt, and a regenerated test baseline
 * grandfathers a violation that was present when it was written — measured.
 */
class SecondAfterTestRule(config: Config) : Rule(config) {

    override val issue = Issue(
        "SecondAfterTest",
        Severity.Defect,
        "An @AfterTest beside the fixture's inherited check races it on function-name order",
        Debt.TEN_MINS,
    )

    private val allowedNames: List<String> by config(emptyList())

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)

        val entries = function.annotationEntries
        if (entries.isEmpty()) return
        val annotated = entries.any { it.shortName?.asString() == AFTER_TEST } ||
            AnnotationExcluder(function.containingKtFile, listOf(AFTER_TEST_FQ), BindingContext.EMPTY)
                .shouldExclude(entries)
        if (!annotated) return
        if (function.name in allowedNames) return

        report(
            CodeSmell(
                issue,
                Entity.from(function),
                "@AfterTest `${function.name ?: "<anonymous>"}` races the fixture's inherited check " +
                    "on function-name order: the runner stops at the first teardown that throws, and " +
                    "inherited ones run sorted by name, so this one can silently take the check with " +
                    "it. Register the release from @BeforeTest (onRelease), or hand the resource to " +
                    "the fixture (owned()) instead.",
            ),
        )
    }

    private companion object {
        const val AFTER_TEST = "AfterTest"
        val AFTER_TEST_FQ = Regex("""kotlin\.test\.AfterTest""")
    }
}
