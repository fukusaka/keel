-- StaticHeaderTable BigQuery confirmation — request headers, H1-only.
-- Replicates the QPACK static table methodology (>5% per-name value-frequency
-- threshold, https://github.com/quicwg/base-drafts/wiki/QPACK-Static-Table)
-- against the HTTP Archive 2024-06-01 crawl, restricted to HTTP/1.1 traffic,
-- to empirically verify StaticHeaderTable's H1 Title-Case extension (c)
-- provisional preset (StaticHeaderTable.kt).
--
-- Scope: desktop client, root-page requests, `type = "html"` (document
-- request headers — request-side entries in preset (c), e.g. `Accept`,
-- are sent on the document request itself).
WITH filtered AS (
  SELECT request_headers
  FROM `httparchive.crawl.requests`
  WHERE date = "2024-06-01"
    AND client = "desktop"
    AND is_root_page = true
    AND type = "html"
    AND JSON_VALUE(summary, "$.respHttpVersion") = "http/1.1"
),
unnested AS (
  SELECT h.name AS name, h.value AS value
  FROM filtered, UNNEST(request_headers) AS h
),
per_name_totals AS (
  SELECT name, COUNT(*) AS name_total
  FROM unnested
  GROUP BY name
),
per_value_counts AS (
  SELECT name, value, COUNT(*) AS value_count
  FROM unnested
  GROUP BY name, value
)
SELECT
  v.name,
  v.value,
  v.value_count,
  t.name_total,
  ROUND(v.value_count / t.name_total * 100, 2) AS pct
FROM per_value_counts v
JOIN per_name_totals t USING (name)
WHERE v.value_count / t.name_total > 0.05
ORDER BY name, pct DESC
