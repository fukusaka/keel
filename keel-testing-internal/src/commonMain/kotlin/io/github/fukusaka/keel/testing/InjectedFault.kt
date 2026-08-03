package io.github.fukusaka.keel.testing

/**
 * The failure a test deliberately injects, so it can be told apart from a real one.
 *
 * Fault-injection sites reached for `RuntimeException` because any exception would
 * travel the path under test. But an assertion that a `RuntimeException` came out
 * the far end is satisfied by *any* `RuntimeException` — including one thrown by
 * the production code for its own reasons, which is exactly the case a
 * fault-propagation test must not mistake for success. A dedicated type lets the
 * assertion say "the fault I injected arrived", which is what these tests mean.
 *
 * Where the exception object is reachable, the assertion uses it — 4 call sites
 * assert `assertFailsWith<InjectedFault>`. Where it is not, the tests observe the
 * message through a recording handler, and the type still removes the ambiguity at
 * the injection site.
 *
 * It is also what detekt's `TooGenericExceptionThrown` is asking for: not a
 * different generic exception, but one that names its error case.
 *
 * [message] stays the test's own string, since several of these tests assert on it.
 */
public class InjectedFault(message: String) : RuntimeException(message)
