-- StaticHeaderTable BigQuery confirmation — response headers, H1-only.
-- Same QPACK-methodology replay as request-headers-h1.sql, for the
-- response-side candidates in StaticHeaderTable's preset (c): Content-Type
-- charset spacing/case variants, X-Frame-Options, Cache-Control,
-- Content-Encoding, X-XSS-Protection, Vary.
--
-- Scope: desktop client, root-page requests, `type IN ("html", "css",
-- "script")` — the resource types where these response headers are most
-- commonly set (broader than the request-headers pass, since these are
-- response-side headers on any resource the document pulls in, not just
-- the document response itself).
WITH filtered AS (
  SELECT response_headers
  FROM `httparchive.crawl.requests`
  WHERE date = "2024-06-01"
    AND client = "desktop"
    AND is_root_page = true
    AND type IN ("html", "css", "script")
    AND JSON_VALUE(summary, "$.respHttpVersion") = "http/1.1"
),
unnested AS (
  SELECT h.name AS name, h.value AS value
  FROM filtered, UNNEST(response_headers) AS h
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
