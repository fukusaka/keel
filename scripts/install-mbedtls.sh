#!/usr/bin/env bash
# Install Mbed TLS 4.x from source.
# Used by CI (GitHub Actions) where no apt package is available.
set -euo pipefail

MBEDTLS_VERSION=4.1.0
MBEDTLS_SHA256=377a09cf8eb81b5fb2707045e5522d5489d3309fed5006c9874e60558fc81d10

# Skip if already installed at the expected version.
if pkg-config --exists mbedtls 2>/dev/null; then
    installed=$(pkg-config --modversion mbedtls 2>/dev/null || true)
    if [ "$installed" = "$MBEDTLS_VERSION" ]; then
        echo "Mbed TLS $MBEDTLS_VERSION already installed — skipping."
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
cmake -S "${WORKDIR}/mbedtls-${MBEDTLS_VERSION}" -B "${WORKDIR}/build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DUSE_SHARED_MBEDTLS_LIBRARY=On \
    -DENABLE_TESTING=Off \
    -DENABLE_PROGRAMS=Off
cmake --build "${WORKDIR}/build" -j "$(nproc)"

echo "Installing..."
sudo cmake --install "${WORKDIR}/build"
sudo ldconfig

echo "Mbed TLS ${MBEDTLS_VERSION} installed successfully."
