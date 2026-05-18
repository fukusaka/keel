import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docsSidebar: [
    'intro',
    {
      type: 'category',
      label: 'Architecture',
      items: [
        'architecture/overview',
        'architecture/engine-guide',
        'architecture/buffer',
        'architecture/pipeline',
        'architecture/coroutine',
        'architecture/tls',
      ],
    },
    {
      type: 'category',
      label: 'Codecs',
      items: [
        'codecs/http',
        'codecs/websocket',
      ],
    },
    {
      type: 'category',
      label: 'Server',
      items: [
        'server/http-server',
      ],
    },
  ],
};

export default sidebars;
