import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'keel',
  tagline: 'KMP Native Network I/O Engine',
  favicon: 'img/favicon.ico',

  future: {
    v4: true,
  },

  url: process.env.SITE_URL ?? 'https://fukusaka.github.io',
  baseUrl: process.env.BASE_URL ?? '/keel/',

  organizationName: 'fukusaka',
  projectName: 'keel',

  onBrokenLinks: 'throw',

  // Even if you don't use internationalization, you can use this field to set
  // useful metadata like html lang. For example, if your site is Chinese, you
  // may want to replace "en" with "zh-Hans".
  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/fukusaka/keel/tree/main/website/',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    colorMode: {
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'keel',
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Docs',
        },
        {
          href: 'https://github.com/fukusaka/keel',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {label: 'Getting Started', to: '/docs/intro'},
            {label: 'Architecture', to: '/docs/architecture/overview'},
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'GitHub',
              href: 'https://github.com/fukusaka/keel',
            },
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} fukusaka. Licensed under Apache 2.0.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['kotlin', 'groovy'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
