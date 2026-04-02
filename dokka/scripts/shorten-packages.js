/*
 * Shortens package names in Dokka 2.x navigation by removing the common prefix.
 *
 * "io.github.fukusaka.keel.core" → "core"
 * "io.github.fukusaka.keel.engine.kqueue" → "engine.kqueue"
 *
 * Dokka 2.x loads navigation asynchronously via fetch() in navigation-loader.js.
 * This script observes the #sideMenu container for DOM changes and applies
 * shortening after navigation is rendered.
 */
(function () {
    var PREFIX = 'io.github.fukusaka.keel.';

    function shorten() {
        var changed = false;

        // Navigation sidebar: package-level toc links (nesting level 1)
        var parts = document.querySelectorAll('.toc--part[data-nesting-level="1"] > a.toc--link');
        for (var i = 0; i < parts.length; i++) {
            var text = parts[i].textContent;
            if (text && text.indexOf(PREFIX) === 0) {
                parts[i].textContent = text.substring(PREFIX.length);
                changed = true;
            }
        }

        // Breadcrumbs
        var breadcrumbs = document.querySelectorAll('.breadcrumbs a');
        for (var j = 0; j < breadcrumbs.length; j++) {
            var bt = breadcrumbs[j].textContent;
            if (bt && bt.indexOf(PREFIX) === 0) {
                breadcrumbs[j].textContent = bt.substring(PREFIX.length);
                changed = true;
            }
        }

        // Package heading on content pages
        var headings = document.querySelectorAll('.cover h1');
        for (var k = 0; k < headings.length; k++) {
            var ht = headings[k].textContent;
            if (ht && ht.indexOf(PREFIX) === 0) {
                headings[k].textContent = ht.substring(PREFIX.length);
                changed = true;
            }
        }

        return changed;
    }

    // Wait for #sideMenu to be populated by navigation-loader.js.
    // The loader fetches navigation.html asynchronously and sets innerHTML.
    function waitForNavigation() {
        var sideMenu = document.getElementById('sideMenu');
        if (!sideMenu) {
            // sideMenu not in DOM yet — retry after delay.
            setTimeout(waitForNavigation, 100);
            return;
        }

        // Check if navigation is already loaded.
        if (sideMenu.querySelector('.toc--part')) {
            shorten();
        }

        // Observe for future changes (initial load or page transitions).
        var observer = new MutationObserver(function () {
            shorten();
        });
        observer.observe(sideMenu, { childList: true, subtree: true });
    }

    // Also shorten breadcrumbs and headings on current page (already in DOM).
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            shorten();
            waitForNavigation();
        });
    } else {
        shorten();
        waitForNavigation();
    }
})();
