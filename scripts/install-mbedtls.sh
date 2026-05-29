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

# Expected libraries after installation (used for both skip and cache validation).
EXPECTED_LIBS=(libmbedtls.so libmbedx509.so libmbedcrypto.so libtfpsacrypto.so)

# Check if all expected libraries exist at the install prefix.
check_installed() {
    for lib in "${EXPECTED_LIBS[@]}"; do
        [ -f "${INSTALL_PREFIX}/lib/${lib}" ] || return 1
    done
    return 0
}

# Force a rebuild even if the expected version is already installed.
# The version-based skip / cache-restore below only compares the version
# string, not the *build configuration* — so a host that already has this
# version but built with a different config (e.g. MBEDTLS_THREADING_C off)
# would otherwise be skipped. Set MBEDTLS_FORCE=1 to rebuild from source
# and overwrite the install regardless.
FORCE="${MBEDTLS_FORCE:-}"

# Skip if already installed at the expected version (unless forced).
if [ -z "$FORCE" ] && pkg-config --exists mbedtls 2>/dev/null; then
    installed=$(pkg-config --modversion mbedtls 2>/dev/null || true)
    if [ "$installed" = "$MBEDTLS_VERSION" ] && check_installed; then
        echo "Mbed TLS $MBEDTLS_VERSION already installed — skipping (set MBEDTLS_FORCE=1 to rebuild)."
        exit 0
    fi
fi

CACHE_DIR="${MBEDTLS_CACHE_DIR:-}"

# Validate and restore from cache (unless forced — a cache built before a
# config change would otherwise mask the rebuild).
if [ -z "$FORCE" ] && [ -n "$CACHE_DIR" ] && [ -f "${CACHE_DIR}/version.txt" ]; then
    cached_version=$(cat "${CACHE_DIR}/version.txt")
    # Validate that cache contains expected libraries before restoring.
    cache_valid=true
    for lib in "${EXPECTED_LIBS[@]}"; do
        if [ ! -f "${CACHE_DIR}/install/lib/${lib}" ]; then
            cache_valid=false
            break
        fi
    done
    if [ "$cached_version" = "$MBEDTLS_VERSION" ] && [ "$cache_valid" = true ]; then
        echo "Restoring Mbed TLS $MBEDTLS_VERSION from cache..."
        sudo cp -a "${CACHE_DIR}/install/"* "$INSTALL_PREFIX/"
        sudo ldconfig
        echo "Mbed TLS $MBEDTLS_VERSION restored from cache."
        exit 0
    else
        echo "Cache invalid or version mismatch (cached: ${cached_version}, expected: ${MBEDTLS_VERSION}) — rebuilding."
        rm -rf "$CACHE_DIR"
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

# Enable the threading abstraction layer (pthread). Mbed TLS defaults
# MBEDTLS_THREADING_C off, leaving the PSA Crypto global key store
# unguarded — concurrent mbedtls_ssl_setup / handshake / free across
# threads (keel's per-connection codecs on multiple EventLoop threads)
# then race in PSA and corrupt the heap (K53; deterministic on many-core
# hosts). Enabling threading makes PSA's shared state mutex-guarded, which
# keel's multi-threaded server use requires. config.py edits
# include/mbedtls/mbedtls_config.h in place before the cmake configure.
echo "Enabling MBEDTLS_THREADING_C + MBEDTLS_THREADING_PTHREAD..."
python3 "${WORKDIR}/mbedtls-${MBEDTLS_VERSION}/scripts/config.py" set MBEDTLS_THREADING_C
python3 "${WORKDIR}/mbedtls-${MBEDTLS_VERSION}/scripts/config.py" set MBEDTLS_THREADING_PTHREAD

echo "Building..."
cmake -S "${WORKDIR}/mbedtls-${MBEDTLS_VERSION}" -B "${WORKDIR}/build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$INSTALL_PREFIX" \
    -DUSE_SHARED_MBEDTLS_LIBRARY=On \
    -DUSE_STATIC_MBEDTLS_LIBRARY=Off \
    -DENABLE_TESTING=Off \
    -DENABLE_PROGRAMS=Off
cmake --build "${WORKDIR}/build" -j "$(nproc)"

echo "Installing to ${INSTALL_PREFIX}..."
sudo cmake --install "${WORKDIR}/build"
sudo ldconfig

# Populate cache if MBEDTLS_CACHE_DIR is set.
# Install a second copy to a staging dir owned by the current user.
if [ -n "$CACHE_DIR" ]; then
    echo "Populating cache at ${CACHE_DIR}..."
    STAGING="${WORKDIR}/staging"
    DESTDIR="$STAGING" cmake --install "${WORKDIR}/build" 2>/dev/null || true
    mkdir -p "${CACHE_DIR}/install"
    cp -a "${STAGING}${INSTALL_PREFIX}/"* "${CACHE_DIR}/install/"
    echo "$MBEDTLS_VERSION" > "${CACHE_DIR}/version.txt"
fi

echo "Mbed TLS ${MBEDTLS_VERSION} installed successfully."
