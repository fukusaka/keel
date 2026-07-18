# C libcurl client benchmark. Build: `make` (needs libcurl + curl-config, which
# ships with the system curl on macOS/Linux). Produces ./client.
CC ?= cc
CURL_CFLAGS := $(shell curl-config --cflags 2>/dev/null)
CURL_LIBS := $(shell curl-config --libs 2>/dev/null || echo -lcurl)
CFLAGS ?= -O2 -std=c11 -Wall -Wextra

client: client.c
	$(CC) $(CFLAGS) $(CURL_CFLAGS) -pthread -o client client.c $(CURL_LIBS)

clean:
	rm -f client

.PHONY: clean
