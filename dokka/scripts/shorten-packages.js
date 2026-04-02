/*
 * Shortens package names in Dokka 2.x by removing the common prefix.
 *
 * "io.github.fukusaka.keel.core" → "core"
 * "io.github.fukusaka.keel.engine.kqueue" → "engine.kqueue"
 *
 * Dokka 2.x loads navigation asynchronously via fetch() in navigation-loader.js.
 * This script observes the #sideMenu container for DOM changes and applies
 * shortening after navigation is rendered.
 *
 * DOM structure (Dokka 2.x):
 *   #sideMenu > .sideMenu > .toc--part[data-nesting-level="0"] (module)
 *     > .toc--part[data-nesting-level="1"] (package)
 *       > .toc--row > a.toc--link (package name with <span> + <wbr>)
 *
 * Breadcrumbs: .breadcrumbs > span.current (package name as plain text)
 */
(function () {
    var PREFIX = 'io.github.fukusaka.keel.';

    function shorten() {
        // Navigation sidebar: package-level links
        var links = document.querySelectorAll(
            '.toc--part[data-nesting-level="1"] > .toc--row > a.toc--link'
        );
        for (var i = 0; i < links.length; i++) {
            var text = links[i].textContent;
            if (text && text.indexOf(PREFIX) === 0) {
                links[i].textContent = text.substring(PREFIX.length);
            }
        }

        // Navigation button aria-labels (for accessibility)
        var buttons = document.querySelectorAll(
            '.toc--part[data-nesting-level="1"] > .toc--row > button.toc--button'
        );
        for (var j = 0; j < buttons.length; j++) {
            var label = buttons[j].getAttribute('aria-label');
            if (label && label.indexOf(PREFIX) === 0) {
                buttons[j].setAttribute('aria-label', label.substring(PREFIX.length));
            }
        }

        // Breadcrumbs: current page name
        var breadcrumbs = document.querySelectorAll('.breadcrumbs .current');
        for (var k = 0; k < breadcrumbs.length; k++) {
            var bt = breadcrumbs[k].textContent;
            if (bt && bt.indexOf(PREFIX) === 0) {
                breadcrumbs[k].textContent = bt.substring(PREFIX.length);
            }
        }

        // Page title in <title> and heading
        var title = document.title;
        if (title && title.indexOf(PREFIX) === 0) {
            document.title = title.substring(PREFIX.length);
        }
    }

    function waitForNavigation() {
        var sideMenu = document.getElementById('sideMenu');
        if (!sideMenu) {
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
