# C libcurl client benchmark. Build: `make` (needs libcurl + curl-config, which
# ships with the system curl on macOS/Linux). Produces ./client.
#
# Latency percentiles use the vendored HdrHistogram_c (see vendor/README.md) so
# they match the JVM / Rust / Go / Swift drivers. HDR_CFLAGS points the vendored
# source at its bundled malloc shim and lets <hdr/hdr_histogram.h> resolve.
CC ?= cc
CURL_CFLAGS := $(shell curl-config --cflags 2>/dev/null)
CURL_LIBS := $(shell curl-config --libs 2>/dev/null || echo -lcurl)
CFLAGS ?= -O2 -std=c11 -Wall -Wextra
HDR_CFLAGS := -Ivendor -DHDR_MALLOC_INCLUDE='"hdr_malloc.h"'

client: client.c vendor/hdr_histogram.o
	$(CC) $(CFLAGS) $(CURL_CFLAGS) $(HDR_CFLAGS) -pthread -o client client.c vendor/hdr_histogram.o $(CURL_LIBS) -lm

# Vendored third-party source: compile without -Wall -Wextra (not our code).
vendor/hdr_histogram.o: vendor/hdr_histogram.c
	$(CC) -O2 -std=c11 $(HDR_CFLAGS) -c -o $@ $<

clean:
	rm -f client vendor/hdr_histogram.o

.PHONY: clean
