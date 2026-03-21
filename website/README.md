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
│   │   ├── overview.md       # Layer architecture & design philosophy (public design.md)
│   │   ├── engine-guide.md   # Engine selection guide
│   │   └── buffer.md         # NativeBuf / BufferAllocator
│   ├── codecs/
│   │   ├── http.md           # :codec-http usage
│   │   └── websocket.md      # :codec-websocket usage
│   └── api/                  # Dokka output integration
└── src/pages/index.tsx       # Landing page
```

Tutorials and How-to guides will be added after Phase 5b (when async IoEngine is ready).

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
