#!/usr/bin/env bash
# Install Mbed TLS 4.x from source.
# Used by CI (GitHub Actions) where no apt package is available.
#
# Cache support: set MBEDTLS_CACHE_DIR to a directory path.
# On cache hit, copies pre-built files to /usr/local (fast).
# On cache miss, builds from source and populates the cache.
set -euo pipefail

MBEDTLS_VERSION=4.1.0
MBEDTLS_SHA256=377a09cf8eb81b5fb2707045e5522d5489d3309fed5006c9874e60558fc81d10
INSTALL_PREFIX=/usr/local

# Skip if already installed at the expected version.
if pkg-config --exists mbedtls 2>/dev/null; then
    installed=$(pkg-config --modversion mbedtls 2>/dev/null || true)
    if [ "$installed" = "$MBEDTLS_VERSION" ]; then
        echo "Mbed TLS $MBEDTLS_VERSION already installed — skipping."
        exit 0
    fi
fi

CACHE_DIR="${MBEDTLS_CACHE_DIR:-}"

# Cache hit: copy pre-built files to /usr/local.
if [ -n "$CACHE_DIR" ] && [ -f "${CACHE_DIR}/version.txt" ]; then
    cached_version=$(cat "${CACHE_DIR}/version.txt")
    if [ "$cached_version" = "$MBEDTLS_VERSION" ]; then
        echo "Restoring Mbed TLS $MBEDTLS_VERSION from cache..."
        sudo cp -a "${CACHE_DIR}/install/"* "$INSTALL_PREFIX/"
        sudo ldconfig
        echo "Mbed TLS $MBEDTLS_VERSION restored from cache."
        exit 0
    fi
fi

TARBALL="mbedtls-${MBEDTLS_VERSION}.tar.bz2"
URL="https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-${MBEDTLS_VERSION}/${TARBALL}"

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

echo "Downloading Mbed TLS ${MBEDTLS_VERSION}..."
curl -fsSL -o "${WORKDIR}/${TARBALL}" "$URL"

echo "Verifying SHA256..."
echo "${MBEDTLS_SHA256}  ${WORKDIR}/${TARBALL}" | sha256sum -c -

echo "Extracting..."
tar xjf "${WORKDIR}/${TARBALL}" -C "$WORKDIR"

echo "Building..."
STAGING="${WORKDIR}/staging"
cmake -S "${WORKDIR}/mbedtls-${MBEDTLS_VERSION}" -B "${WORKDIR}/build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$INSTALL_PREFIX" \
    -DUSE_SHARED_MBEDTLS_LIBRARY=On \
    -DENABLE_TESTING=Off \
    -DENABLE_PROGRAMS=Off
cmake --build "${WORKDIR}/build" -j "$(nproc)"

echo "Installing..."
# Install to staging first (for cache), then to /usr/local.
DESTDIR="$STAGING" cmake --install "${WORKDIR}/build"
sudo cp -a "${STAGING}${INSTALL_PREFIX}/"* "$INSTALL_PREFIX/"
sudo ldconfig

# Populate cache if MBEDTLS_CACHE_DIR is set.
if [ -n "$CACHE_DIR" ]; then
    echo "Populating cache at ${CACHE_DIR}..."
    mkdir -p "${CACHE_DIR}/install"
    cp -a "${STAGING}${INSTALL_PREFIX}/"* "${CACHE_DIR}/install/"
    echo "$MBEDTLS_VERSION" > "${CACHE_DIR}/version.txt"
fi

echo "Mbed TLS ${MBEDTLS_VERSION} installed successfully."
