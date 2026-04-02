/*
 * Shortens package names in Dokka navigation by removing the common prefix.
 *
 * "io.github.fukusaka.keel.core" → "core"
 * "io.github.fukusaka.keel.engine.kqueue" → "engine.kqueue"
 *
 * Runs after Dokka's navigation-loader.js populates the sidebar.
 */
(function () {
    var PREFIX = 'io.github.fukusaka.keel.';

    function shorten() {
        var elements = document.querySelectorAll(
            '.sideMenuPart span.overview, ' +   // Dokka HTML sidebar
            '.breadcrumbs a, ' +                 // Breadcrumbs
            '.cover h1'                          // Package heading
        );
        for (var i = 0; i < elements.length; i++) {
            var el = elements[i];
            var text = el.textContent;
            if (text && text.indexOf(PREFIX) === 0) {
                el.textContent = text.substring(PREFIX.length);
            }
        }
    }

    // Navigation is loaded asynchronously; observe DOM changes.
    if (typeof MutationObserver !== 'undefined') {
        var observer = new MutationObserver(function () {
            shorten();
        });
        observer.observe(document.documentElement, {
            childList: true,
            subtree: true
        });
    }

    // Also run on DOMContentLoaded as fallback.
    document.addEventListener('DOMContentLoaded', function () {
        setTimeout(shorten, 500);
    });
})();
