# StaticHeaderTable BigQuery confirmation — results (2026-07-12)

Empirical verification of `StaticHeaderTable`'s H1 Title-Case extension category
(c) "production-frequent provisional preset" (`StaticHeaderTable.kt`, 17 entries,
originally selected by inspection of browser/framework defaults, pending this
empirical confirmation),
using the QPACK static-table methodology (>5% per-name value-frequency
threshold, https://github.com/quicwg/base-drafts/wiki/QPACK-Static-Table)
replayed against HTTP Archive's public BigQuery dataset, restricted to
HTTP/1.1 traffic.

## Query scope

- Dataset: `httparchive.crawl.requests` (2024-06-01 crawl)
- Client: desktop
- Pages: root pages only (`is_root_page = true`)
- Protocol: `JSON_VALUE(summary, "$.respHttpVersion") = "http/1.1"` (note: the
  live schema value is lowercase `http/1.1`, not `HTTP/1.1` — confirmed by
  sampling before running the full query)
- Request headers: `type = "html"` (document navigation requests — where
  request-side preset entries like `Accept` are sent) — 91.5 GB billed
- Response headers: `type IN ("html", "css", "script")` — the resource types
  where the response-side preset entries are set — 566.4 GB billed
- Total billed this run: **~751 GB** (within the 1 TB/month free tier)

SQL: [`request-headers-h1.sql`](request-headers-h1.sql),
[`response-headers-h1.sql`](response-headers-h1.sql). Re-running these against
the same crawl date reproduces the per-entry results tabulated below; the raw
query output (third-party crawl data, not keel's own) is not committed to the
repo.

**Scope caveat**: the response-header query is limited to `html`/`css`/`script`
resource types for cost reasons (an unfiltered `is_root_page=true` pass over
all resource types dry-ran at ~1 TB, alone exceeding the monthly free tier).
Entries whose natural home is a different resource type (`xhr`/`fetch`/`json`
API responses, non-navigation `Accept` headers) are **inconclusive** under
this scope, not confirmed-absent — see per-entry notes below.

## Per-entry results (17 preset (c) entries)

| # | Entry | Result | Empirical data |
|---|---|---|---|
| 1 | `Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8` | ❌ **stale** | This exact value does not appear in the >5% output at all. The dominant Title-Case `Accept` value is now `text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7` (**50.72%**, modern Chrome, includes AVIF/WebP/signed-exchange added since the preset was authored) |
| 2 | `Accept: application/json` | ⚠️ inconclusive | Not sent on `type=html` document navigations (this is an XHR/fetch-context header) — out of this query's scope |
| 3 | `Accept-Encoding: gzip` (bare) | ❌ **not observed >5%** | Real Title-Case `Accept-Encoding` values are `gzip, deflate, br, zstd` (**93.14%**) and `gzip, deflate` (**6.83%**); bare `gzip` alone doesn't cross 5% among desktop H1 document requests |
| 4 | `Cache-Control: private` | ❌ **below threshold (Title-Case exact)** | Only the mixed-case `Cache-control` name variant crosses 5% for value `private` (**18.46%**); the canonical Title-Case `Cache-Control` name does not appear in the >5% output at all |
| 5 | `Cache-Control: public` | ❌ **not observed** | No `Cache-Control`/`Cache-control` + `public` combination appears anywhere in the >5% output |
| 6 | `Content-Encoding: deflate` | ❌ **below threshold (Title-Case exact)** | Only the mixed-case `Content-encoding` name variant crosses 5% (**14.43%**); canonical Title-Case absent from output |
| 7 | `Content-Type: text/html` | ✅ **confirmed** | 6.4% |
| 8 | `Content-Type: text/html; charset=UTF-8` | ✅ **confirmed** | 5.24% (narrow margin) |
| 9 | `Content-Type: text/plain; charset=utf-8` | ⚠️ inconclusive | Not present in the `html`/`css`/`script` scope — likely belongs to a different resource type |
| 10 | `Content-Type: text/plain; charset=UTF-8` | ⚠️ inconclusive | Same as above |
| 11 | `Content-Type: application/json; charset=utf-8` | ⚠️ inconclusive | Same as above (json responses are typically classified as a different `type`) |
| 12 | `Content-Type: application/json; charset=UTF-8` | ⚠️ inconclusive | Same as above |
| 13 | `Content-Type: application/octet-stream` | ⚠️ inconclusive | Same as above |
| 14 | `Vary: Accept-Encoding` | ✅ **confirmed** | 78.7% |
| 15 | `X-Frame-Options: DENY` | ✅ **confirmed** | 7.4% (narrow margin) |
| 16 | `X-Frame-Options: SAMEORIGIN` | ✅ **confirmed** | 79.58% |
| 17 | `X-XSS-Protection: 1; mode=block` | ✅ **confirmed** | 78.45% |

**Summary**: 7/17 robustly confirmed, 4/17 fail the threshold in this scope
(1 stale value, 3 not observed at the canonical Title-Case name), 6/17
inconclusive (out of the html/css/script resource-type scope, would need a
follow-up query against `xhr`/`fetch`/`json` types to resolve).

## New candidates surfaced (>5%, not currently in the table)

| Header | Value | Frequency | Note |
|---|---|---|---|
| `Accept` | `text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7` | 50.72% | Should replace/supplement entry #1 — current modern Chrome default |
| `Accept` | `image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8` | 37.71% | Observed on `type=html` responses (redirect chains / iframe documents) |
| `Accept` | `*/*` | 8.66% | |
| `Accept-Encoding` | `gzip, deflate, br, zstd` | 93.14% | Should replace entry #3 |
| `Accept-Encoding` | `gzip, deflate` | 6.83% | |
| `Content-Type` | `application/javascript; charset=utf-8` | 5.67% | Not currently in the table at all (bare `application/javascript` is QPACK 45, already covered) |
| `Content-Type` | `text/javascript` | 5.36% | Not currently in the table |
| `Vary` | `Accept-Encoding,User-Agent` | 10.95% | Combined Vary value, not currently in the table |
| `X-XSS-Protection` | `0` | 9.85% | |
| `X-XSS-Protection` | `1` | 7.06% | |

Lowercase-name variants (`content-type`, `cache-control`, `content-encoding`,
`vary`, `x-frame-options`, `x-xss-protection`) also independently cross 5% as
separate name buckets in the raw CSV, but preset (c) is scoped to the
Title-Case H1 convention (categories (a)/(b) already cover the lowercase
HPACK/QPACK forms) — noted here for completeness, not treated as candidates
for this preset.

## Removal candidates (fail the >5% bar within this query's scope)

- `Accept-Encoding: gzip` (bare) — superseded by `gzip, deflate, br, zstd`
- `Cache-Control: private` (Title-Case exact)
- `Cache-Control: public` (Title-Case exact)
- `Content-Encoding: deflate` (Title-Case exact)

`Cache-Control`/`Content-Encoding` Title-Case may still be legitimately rare
in this specific 2024-06-01/desktop/html+css+script slice while being common
elsewhere (e.g. a different crawl date, or resource types outside this
query's scope). A structural intern-table entry has near-zero runtime cost
per unused slot (`StaticHeaderTable` is a process-wide singleton, ~1 KB total
for the whole 248-entry table), so keeping an unconfirmed entry costs little; removing a genuinely
common one costs a real interning opportunity. **Disposition: kept, not
removed** (bias toward caution) — only the bare `Accept-Encoding: gzip` entry
was replaced (superseded by the empirically dominant modern value), the
other three sub-threshold entries stay in the table unchanged.

## Applied to `StaticHeaderTable.kt` (2026-07-12)

- **Replaced (stale)**: `Accept` entry #1's value (modern Chrome default,
  50.72%); `Accept-Encoding: gzip` bare (superseded by `gzip, deflate, br,
  zstd`, 93.14%, not covered by the existing HPACK 16 / QPACK 31 entries)
- **Added (6 new candidates)**: `Accept: image/avif,image/webp,image/apng,
  image/svg+xml,image/*,*/*;q=0.8` (37.71%); `Content-Type: application/
  javascript; charset=utf-8` (5.67%); `Content-Type: text/javascript`
  (5.36%); `Vary: Accept-Encoding,User-Agent` (10.95%); `X-XSS-Protection: 0`
  (9.85%); `X-XSS-Protection: 1` (7.06%)
- **Kept unchanged (below threshold in this scope, but not disproven)**:
  `Cache-Control: private`/`public`, `Content-Encoding: deflate`, and the
  five inconclusive `Content-Type` charset variants (entries #9-13) — see
  the disposition note above
- Table grew from 242 to 248 entries; `StaticHeaderTableBucketDepthTest`
  reconfirms the max chain depth stays within the ≤ 12 soft cap
