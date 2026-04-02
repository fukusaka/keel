/*
 * Shortens package names in Dokka 2.x navigation by removing the common prefix.
 *
 * "io.github.fukusaka.keel.core" → "core"
 * "io.github.fukusaka.keel.engine.kqueue" → "engine.kqueue"
 *
 * Dokka 2.x renders package names as multiple <span> + <wbr> elements inside
 * .toc--link anchors. This script replaces the innerHTML of matching links
 * with the shortened package name.
 */
(function () {
    var PREFIX = 'io.github.fukusaka.keel.';

    function shorten() {
        // Navigation sidebar: package-level .toc--link (nesting level 1)
        var parts = document.querySelectorAll('.toc--part[data-nesting-level="1"]');
        for (var i = 0; i < parts.length; i++) {
            var link = parts[i].querySelector(':scope > a.toc--link');
            if (!link) continue;
            var text = link.textContent;
            if (text && text.indexOf(PREFIX) === 0) {
                var short = text.substring(PREFIX.length);
                link.textContent = short;
            }
        }

        // Breadcrumbs
        var breadcrumbs = document.querySelectorAll('.breadcrumbs a');
        for (var j = 0; j < breadcrumbs.length; j++) {
            var el = breadcrumbs[j];
            var t = el.textContent;
            if (t && t.indexOf(PREFIX) === 0) {
                el.textContent = t.substring(PREFIX.length);
            }
        }

        // Package heading on content pages
        var headings = document.querySelectorAll('.cover h1');
        for (var k = 0; k < headings.length; k++) {
            var h = headings[k];
            var ht = h.textContent;
            if (ht && ht.indexOf(PREFIX) === 0) {
                h.textContent = ht.substring(PREFIX.length);
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
