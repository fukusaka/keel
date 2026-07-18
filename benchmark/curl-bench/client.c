/*
 * C libcurl client benchmark — the universal native baseline for the keel HTTP
 * client bench. libcurl is what Ktor's Curl engine and countless tools wrap, so
 * it is the lowest-common-denominator native reference. Each worker thread owns
 * one CURL easy handle, which keeps its connection alive across requests (per
 * handle connection cache) — so N threads hold N reused keep-alive connections.
 *
 * Accepts the same CLI flags as the JVM harness and prints the same result
 * line, so bench-client.sh can drive it like any other client type:
 *   <name><endpoint>|<rps>|<p50ms>|<p99ms>|<p99.9ms>|<maxms>|<b/op>|<errors>
 * bytes/op is n/a for native clients; percentiles are exact (sorted samples).
 */
#include <curl/curl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static const char *NAME = "libcurl";

typedef struct {
    char **targets;
    int ntargets;
    int pinned;
    int64_t deadline_ns;
} shared_t;

typedef struct {
    int tid;
    const shared_t *sh;
    int64_t *lat;
    size_t nlat, caplat;
    int64_t completed, errors;
} worker_t;

static atomic_ullong g_pick;

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static size_t discard_cb(char *ptr, size_t size, size_t nmemb, void *userdata) {
    (void)ptr;
    (void)userdata;
    return size * nmemb;
}

static void *worker_fn(void *arg) {
    worker_t *w = (worker_t *)arg;
    CURL *h = curl_easy_init();
    curl_easy_setopt(h, CURLOPT_WRITEFUNCTION, discard_cb);
    const char *pinned = w->sh->pinned ? w->sh->targets[w->tid % w->sh->ntargets] : NULL;
    while (now_ns() < w->sh->deadline_ns) {
        const char *url = pinned;
        if (!url) {
            unsigned long long i = atomic_fetch_add(&g_pick, 1);
            url = w->sh->targets[i % (unsigned)w->sh->ntargets];
        }
        curl_easy_setopt(h, CURLOPT_URL, url);
        int64_t t0 = now_ns();
        CURLcode rc = curl_easy_perform(h);
        if (rc == CURLE_OK) {
            int64_t lat = now_ns() - t0;
            if (lat < 1) lat = 1;
            if (w->nlat == w->caplat) {
                w->caplat = w->caplat ? w->caplat * 2 : 1024;
                w->lat = realloc(w->lat, w->caplat * sizeof(int64_t));
            }
            w->lat[w->nlat++] = lat;
            w->completed++;
        } else {
            w->errors++;
        }
    }
    curl_easy_cleanup(h);
    return NULL;
}

static int cmp_i64(const void *a, const void *b) {
    int64_t x = *(const int64_t *)a, y = *(const int64_t *)b;
    return (x > y) - (x < y);
}

typedef struct {
    double rps;
    int64_t *lat;
    size_t nlat;
    int64_t errors;
} phase_t;

static phase_t run_phase(char **targets, int ntargets, int pinned, int conns, int secs) {
    shared_t sh;
    sh.targets = targets;
    sh.ntargets = ntargets;
    sh.pinned = pinned;
    sh.deadline_ns = now_ns() + (int64_t)secs * 1000000000LL;

    worker_t *ws = calloc(conns, sizeof(worker_t));
    pthread_t *th = calloc(conns, sizeof(pthread_t));
    int64_t start = now_ns();
    for (int i = 0; i < conns; i++) {
        ws[i].tid = i;
        ws[i].sh = &sh;
        pthread_create(&th[i], NULL, worker_fn, &ws[i]);
    }
    for (int i = 0; i < conns; i++) pthread_join(th[i], NULL);
    double elapsed = (double)(now_ns() - start) / 1e9;
    if (elapsed <= 0) elapsed = 1e-9;

    size_t total = 0;
    int64_t errors = 0, completed = 0;
    for (int i = 0; i < conns; i++) {
        total += ws[i].nlat;
        errors += ws[i].errors;
        completed += ws[i].completed;
    }
    int64_t *all = malloc((total ? total : 1) * sizeof(int64_t));
    size_t off = 0;
    for (int i = 0; i < conns; i++) {
        if (ws[i].nlat) memcpy(all + off, ws[i].lat, ws[i].nlat * sizeof(int64_t));
        off += ws[i].nlat;
        free(ws[i].lat);
    }
    qsort(all, total, sizeof(int64_t), cmp_i64);
    free(ws);
    free(th);

    phase_t p;
    p.rps = (double)completed / elapsed;
    p.lat = all;
    p.nlat = total;
    p.errors = errors;
    return p;
}

static double pct_ms(const int64_t *sorted, size_t n, double q) {
    if (n == 0) return 0.0;
    size_t idx = (size_t)(q * (double)(n - 1));
    return (double)sorted[idx] / 1e6;
}

int main(int argc, char **argv) {
    const char *target = NULL;
    const char *endpoint = "/hello";
    int conns = 50, warmup = 3, duration = 10, pinned = 0;
    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        if (strncmp(a, "--", 2) != 0) continue;
        const char *eq = strchr(a, '=');
        if (!eq) continue;
        size_t klen = (size_t)(eq - (a + 2));
        const char *v = eq + 1;
        if (!strncmp(a + 2, "client-target", klen) && klen == 13) target = v;
        else if (!strncmp(a + 2, "client-endpoint", klen) && klen == 15) endpoint = v;
        else if (!strncmp(a + 2, "client-connections", klen) && klen == 18) conns = atoi(v);
        else if (!strncmp(a + 2, "client-warmup", klen) && klen == 13) warmup = atoi(v);
        else if (!strncmp(a + 2, "client-duration", klen) && klen == 15) duration = atoi(v);
        else if (!strncmp(a + 2, "client-target-mode", klen) && klen == 18) pinned = !strcmp(v, "pinned");
    }
    if (!target) {
        fprintf(stderr, "missing --client-target\n");
        return 1;
    }

    /* Build full URLs (base + endpoint) from the comma-separated target list. */
    char **targets = NULL;
    int ntargets = 0;
    char *dup = strdup(target);
    for (char *tok = strtok(dup, ","); tok; tok = strtok(NULL, ",")) {
        while (*tok == ' ') tok++;
        size_t len = strlen(tok);
        while (len > 0 && (tok[len - 1] == '/' || tok[len - 1] == ' ')) tok[--len] = '\0';
        if (len == 0) continue;
        char *full = malloc(len + strlen(endpoint) + 1);
        strcpy(full, tok);
        strcat(full, endpoint);
        targets = realloc(targets, (ntargets + 1) * sizeof(char *));
        targets[ntargets++] = full;
    }
    free(dup);
    if (ntargets == 0) {
        fprintf(stderr, "no target parsed\n");
        return 1;
    }

    curl_global_init(CURL_GLOBAL_DEFAULT);
    fprintf(stderr, "client bench: type=%s targets=%d conns=%d warmup=%ds duration=%ds\n",
            NAME, ntargets, conns, warmup, duration);
    if (warmup > 0) {
        phase_t w = run_phase(targets, ntargets, pinned, conns, warmup);
        free(w.lat);
    }
    phase_t p = run_phase(targets, ntargets, pinned, conns, duration);
    double max_ms = p.nlat ? (double)p.lat[p.nlat - 1] / 1e6 : 0.0;
    printf("%s%s|%.0f|%.3f|%.3f|%.3f|%.3f|n/a|%lld\n",
           NAME, endpoint, p.rps,
           pct_ms(p.lat, p.nlat, 0.50), pct_ms(p.lat, p.nlat, 0.99),
           pct_ms(p.lat, p.nlat, 0.999), max_ms, (long long)p.errors);
    free(p.lat);
    for (int i = 0; i < ntargets; i++) free(targets[i]);
    free(targets);
    curl_global_cleanup();
    return 0;
}
