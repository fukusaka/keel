#!/usr/bin/env python3
"""Merge Dokka HTML outputs from macOS and Linux builds.

Usage:
    merge-dokka.py <macos-dir> <linux-dir> <output-dir>

Both input directories should contain Dokka HTML output (from dokkaGeneratePublicationHtml).
The output directory will contain a merged result with all modules from both platforms,
sorted navigation, and a "← keel docs" bar on every page.

If only one platform is available, pass the same directory for both arguments
or use "--macos-only" / "--linux-only" flags.

Examples:
    # Full merge (macOS + Linux)
    merge-dokka.py build/dokka/html /tmp/dokka-linux website/static/api

    # macOS only
    merge-dokka.py --macos-only build/dokka/html website/static/api

    # Linux only
    merge-dokka.py --linux-only /tmp/dokka-linux website/static/api
"""

import argparse
import os
import re
import shutil
import sys


DOCS_BAR = '''\
<div id="keel-docs-bar" style="background:#1b1b1d;border-bottom:1px solid #2e2e31;\
padding:6px 16px;font-family:-apple-system,BlinkMacSystemFont,sans-serif;font-size:13px;\
display:flex;align-items:center;gap:12px">
<a id="keel-docs-link" href="#" style="color:#4dabf7;text-decoration:none">\u2190 keel docs</a>
<span style="color:#606060">|</span>
<span style="color:#909090">API Reference</span>
<script>document.getElementById('keel-docs-link').href=(function(){\
var p=location.pathname,i=p.indexOf('/api/');\
return i>=0?p.substring(0,i)+'/':'/'})();</script>
</div>'''

LINUX_ONLY_MODULES = {'engine-epoll', 'engine-io-uring'}
MACOS_ONLY_MODULES = {'engine-kqueue', 'engine-nwconnection'}

# Custom CSS to align Dokka's appearance with Docusaurus theme.
# Overrides Dokka CSS variables with Docusaurus-compatible values.
CUSTOM_CSS = '''\
/* Docusaurus-aligned theme overrides */
:root {
  --background-color: #ffffff;
  --default-font-color: #1c1e21;
  --border-color: rgba(0, 0, 0, 0.1);
  --navigation-highlight-color: rgba(0, 0, 0, 0.05);
  --default-font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Oxygen, Ubuntu, Cantarell, "Helvetica Neue", Arial, sans-serif;
  --active-section-color: #2e8555;
  --sidemenu-section-active-color: #2e8555;
  --hover-link-color: #2e8555;
}
.theme-dark:root,
html.theme-dark {
  --background-color: #1b1b1d;
  --default-font-color: #e3e3e3;
  --border-color: rgba(255, 255, 255, 0.1);
  --navigation-highlight-color: rgba(255, 255, 255, 0.05);
  --active-section-color: #25c2a0;
  --sidemenu-section-active-color: #25c2a0;
  --hover-link-color: #25c2a0;
}
/* Navbar: match Docusaurus */
.navigation.theme-dark {
  background: #242526;
}
/* Reduce top nav height to match Docusaurus */
:root { --top-navigation-height: 60px; }
/* Code colors: light = GitHub theme (matching Docusaurus prismThemes.github) */
:root {
  --color-cd-keyword: #d73a49;
  --color-cd-builtin: #032f62;
  --color-cd-function: #6f42c1;
  --color-cd-number: #005cc5;
  --color-cd-operator: #d73a49;
  --color-cd-punctuation: #6a737d;
}
/* Code colors: dark = Dracula theme (matching Docusaurus prismThemes.dracula) */
.theme-dark:root,
html.theme-dark {
  --color-cd-keyword-alternative: #ff79c6;
  --color-cd-builtin-alternative: #f1fa8c;
  --color-cd-function-alternative: #50fa7b;
  --color-cd-number-alternative: #bd93f9;
  --color-cd-operator-alternative: #ff79c6;
}
/* Inline code background */
:root { --code-background: rgba(27, 31, 35, 0.05); }
.theme-dark:root,
html.theme-dark { --code-background: rgba(255, 255, 255, 0.1); }
'''


def extract_module_rows(html):
    """Extract per-module HTML blocks from Dokka's All-modules index.html."""
    pattern = (
        r'(<a data-name="[^"]+" anchor-label="([^"]+)"[^>]*></a>'
        r'\s*<div class="table-row table-row_multimodule">.*?</div>\s*</div>\s*</div>)'
    )
    return {name: block for block, name in re.findall(pattern, html, re.DOTALL)}


def extract_nav_modules(html):
    """Extract per-module navigation sections from navigation.html."""
    parts = re.split(r'(?=<div class="toc--part" id="[^"]*-nav-submenu" )', html)
    modules = {}
    for part in parts:
        match = re.match(r'<div class="toc--part" id="([^"]*)-nav-submenu"', part)
        if match:
            modules[match.group(1)] = part.rstrip()
    return modules


def merge_index(macos_html, linux_html, output_path):
    """Merge index.html from both platforms with all modules in sorted order."""
    all_modules = {}
    if linux_html:
        all_modules.update(extract_module_rows(linux_html))
    if macos_html:
        all_modules.update(extract_module_rows(macos_html))

    sorted_blocks = '\n'.join(all_modules[n] for n in sorted(all_modules))

    # Use whichever HTML has the most modules as the base template
    base_html = linux_html or macos_html
    all_blocks_pattern = (
        r'(<a data-name="[^"]+" anchor-label="[^"]+".*?</a>'
        r'\s*<div class="table-row table-row_multimodule">.*?</div>\s*</div>\s*</div>\s*)+'
    )
    match = re.search(all_blocks_pattern, base_html, re.DOTALL)
    if match:
        merged = base_html[:match.start()] + sorted_blocks + '\n' + base_html[match.end():]
    else:
        merged = base_html

    with open(output_path, 'w') as f:
        f.write(merged)
    print(f'  index.html: {len(all_modules)} modules')


def merge_navigation(macos_html, linux_html, output_path):
    """Merge navigation.html from both platforms in sorted order."""
    all_modules = {}
    if linux_html:
        all_modules.update(extract_nav_modules(linux_html))
    if macos_html:
        all_modules.update(extract_nav_modules(macos_html))

    sorted_nav = '\n'.join(all_modules[n] for n in sorted(all_modules))
    with open(output_path, 'w') as f:
        f.write('<div class="sideMenu">\n' + sorted_nav + '\n</div>\n')
    print(f'  navigation.html: {len(all_modules)} modules')


def inject_custom_css(output_dir):
    """Write custom CSS and inject a link into every HTML file."""
    css_path = os.path.join(output_dir, 'styles', 'keel-custom.css')
    with open(css_path, 'w') as f:
        f.write(CUSTOM_CSS)

    css_link = '<link href="{prefix}styles/keel-custom.css" rel="Stylesheet">'
    count = 0
    for root, _dirs, files in os.walk(output_dir):
        for f in files:
            if not f.endswith('.html'):
                continue
            path = os.path.join(root, f)
            with open(path) as fh:
                html = fh.read()
            if 'keel-custom.css' in html:
                continue
            # Determine path prefix from pathToRoot variable
            match = re.search(r'var pathToRoot = "([^"]*)"', html)
            prefix = match.group(1) if match else ''
            link = css_link.format(prefix=prefix)
            # Insert after the last Dokka stylesheet link
            html = html.replace(
                '<link href="{}ui-kit/ui-kit.min.css"'.format(prefix),
                '{}\n<link href="{}ui-kit/ui-kit.min.css"'.format(link, prefix),
                1,
            )
            with open(path, 'w') as fh:
                fh.write(html)
            count += 1
    print(f'  custom CSS: injected in {count} files')


def replace_footer_copyright(output_dir):
    """Replace Dokka's default copyright with project-specific one."""
    import datetime
    year = datetime.date.today().year
    old = f'\u00a9 {year} Copyright'
    new = (
        f'Copyright \u00a9 {year} fukusaka \u00b7 '
        f'<a href="https://www.apache.org/licenses/LICENSE-2.0" '
        f'class="footer--link footer--link_external">Apache 2.0</a>'
    )
    count = 0
    for root, _dirs, files in os.walk(output_dir):
        for f in files:
            if not f.endswith('.html'):
                continue
            path = os.path.join(root, f)
            with open(path) as fh:
                html = fh.read()
            if old in html:
                html = html.replace(old, new)
                with open(path, 'w') as fh:
                    fh.write(html)
                count += 1
    print(f'  footer copyright: updated in {count} files')


def insert_docs_bar(output_dir):
    """Insert the '← keel docs' bar into every HTML file."""
    count = 0
    for root, _dirs, files in os.walk(output_dir):
        for f in files:
            if not f.endswith('.html'):
                continue
            path = os.path.join(root, f)
            with open(path) as fh:
                html = fh.read()
            if 'keel-docs-bar' in html:
                continue
            html = html.replace('<body>', '<body>\n' + DOCS_BAR, 1)
            with open(path, 'w') as fh:
                fh.write(html)
            count += 1
    print(f'  docs bar: inserted in {count} files')


def read_file(path):
    """Read file contents or return None if not found."""
    try:
        with open(path) as f:
            return f.read()
    except FileNotFoundError:
        return None


def main():
    parser = argparse.ArgumentParser(description='Merge Dokka HTML outputs from macOS and Linux.')
    parser.add_argument('output_dir', help='Output directory for merged Dokka HTML')
    parser.add_argument('--macos-dir', help='macOS Dokka HTML directory')
    parser.add_argument('--linux-dir', help='Linux Dokka HTML directory')
    parser.add_argument('--macos-only', action='store_true', help='Only macOS output available')
    parser.add_argument('--linux-only', action='store_true', help='Only Linux output available')
    args = parser.parse_args()

    if args.macos_only and args.linux_only:
        parser.error('Cannot specify both --macos-only and --linux-only')

    macos_dir = args.macos_dir
    linux_dir = args.linux_dir

    if args.macos_only:
        if not macos_dir:
            parser.error('--macos-dir required with --macos-only')
        linux_dir = None
    elif args.linux_only:
        if not linux_dir:
            parser.error('--linux-dir required with --linux-only')
        macos_dir = None
    else:
        if not macos_dir or not linux_dir:
            parser.error('Both --macos-dir and --linux-dir required (or use --macos-only / --linux-only)')

    output_dir = args.output_dir

    # Determine base directory (prefer the one with more modules)
    base_dir = macos_dir or linux_dir
    other_dir = linux_dir if base_dir == macos_dir else macos_dir

    print(f'Base: {base_dir}')
    if other_dir:
        print(f'Merge: {other_dir}')
    print(f'Output: {output_dir}')

    # Copy base to output
    if os.path.exists(output_dir):
        shutil.rmtree(output_dir)
    shutil.copytree(base_dir, output_dir)

    # Copy platform-specific modules from the other directory
    if other_dir:
        platform_modules = LINUX_ONLY_MODULES if other_dir == linux_dir else MACOS_ONLY_MODULES
        for module in platform_modules:
            src = os.path.join(other_dir, module)
            dst = os.path.join(output_dir, module)
            if os.path.isdir(src):
                if os.path.exists(dst):
                    shutil.rmtree(dst)
                shutil.copytree(src, dst)
                print(f'  copied {module} from {os.path.basename(other_dir)}')

    # Merge index.html
    macos_index = read_file(os.path.join(macos_dir, 'index.html')) if macos_dir else None
    linux_index = read_file(os.path.join(linux_dir, 'index.html')) if linux_dir else None
    merge_index(macos_index, linux_index, os.path.join(output_dir, 'index.html'))

    # Merge navigation.html
    macos_nav = read_file(os.path.join(macos_dir, 'navigation.html')) if macos_dir else None
    linux_nav = read_file(os.path.join(linux_dir, 'navigation.html')) if linux_dir else None
    merge_navigation(macos_nav, linux_nav, os.path.join(output_dir, 'navigation.html'))

    # Inject custom CSS (Docusaurus-aligned theme)
    inject_custom_css(output_dir)

    # Replace footer copyright
    replace_footer_copyright(output_dir)

    # Insert docs bar
    insert_docs_bar(output_dir)

    print('Done.')


if __name__ == '__main__':
    main()
