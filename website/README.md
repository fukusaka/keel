# Website

This website is built using [Docusaurus](https://docusaurus.io/), a modern static website generator.

Hosted on Cloudflare Pages: `keel-kt.pages.dev`

## Document Structure

```
website/
├── docusaurus.config.ts      # Site configuration
├── docs/
│   ├── intro.md              # Getting Started
│   ├── architecture/
│   │   ├── overview.md       # Layer architecture & design philosophy
│   │   ├── engine-guide.md   # Engine selection guide
│   │   ├── buffer.md         # IoBuf / BufferAllocator
│   │   ├── pipeline.md       # Pipeline mode
│   │   ├── coroutine.md      # Coroutine mode
│   │   └── tls.md            # TLS
│   ├── codecs/
│   │   ├── http.md           # keel-codec-http usage
│   │   └── websocket.md      # keel-codec-websocket usage
│   ├── server/
│   │   └── http-server.md    # HTTP server DSL (keel-server-http)
│   ├── ja/                   # Japanese translations (mirrors the EN doc tree)
│   └── api/                  # Dokka output integration
└── src/pages/index.tsx       # Landing page
```

## Documentation Tools

| Tool | Purpose |
|---|---|
| **Docusaurus** | Landing page / Tutorial / How-to / Architecture (MDX + Mermaid) |
| **Dokka** | KDoc → HTML (API reference), integrated into Docusaurus |

## Installation

```bash
yarn
```

## Local Development

```bash
yarn start
```

This command starts a local development server and opens up a browser window. Most changes are reflected live without having to restart the server.

## Build

```bash
yarn build
```

This command generates static content into the `build` directory and can be served using any static contents hosting service.
