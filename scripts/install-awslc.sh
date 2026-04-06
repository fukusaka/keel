#!/usr/bin/env bash
# Install AWS-LC from source.
# Used by CI (GitHub Actions) and luna.local where no apt package is available.
#
# Cache support: set AWSLC_CACHE_DIR to a directory path.
# On cache hit, copies pre-built files to /usr/local (fast).
# On cache miss, builds from source and populates the cache.
#
# Requires: cmake, gcc/g++, go, make
set -euo pipefail

AWSLC_VERSION=1.71.0
AWSLC_SHA256=31b1eed775294825f084c0d4e09df53e1cf036fb98a202a8c2c342543828a985
INSTALL_PREFIX=/usr/local

# Expected libraries after installation.
EXPECTED_LIBS=(libssl.so libcrypto.so)

check_installed() {
    for lib in "${EXPECTED_LIBS[@]}"; do
        [ -f "${INSTALL_PREFIX}/lib/${lib}" ] || return 1
    done
    return 0
}

# Skip if already installed at the expected version.
if [ -f "${INSTALL_PREFIX}/lib/libcrypto.so" ]; then
    # AWS-LC embeds version in the library; check via strings or marker file.
    if [ -f "${INSTALL_PREFIX}/share/awslc-version.txt" ]; then
        installed=$(cat "${INSTALL_PREFIX}/share/awslc-version.txt")
        if [ "$installed" = "$AWSLC_VERSION" ] && check_installed; then
            echo "AWS-LC $AWSLC_VERSION already installed — skipping."
            exit 0
        fi
    fi
fi

CACHE_DIR="${AWSLC_CACHE_DIR:-}"

# Validate and restore from cache.
if [ -n "$CACHE_DIR" ] && [ -f "${CACHE_DIR}/version.txt" ]; then
    cached_version=$(cat "${CACHE_DIR}/version.txt")
    cache_valid=true
    for lib in "${EXPECTED_LIBS[@]}"; do
        if [ ! -f "${CACHE_DIR}/install/lib/${lib}" ]; then
            cache_valid=false
            break
        fi
    done
    if [ "$cached_version" = "$AWSLC_VERSION" ] && [ "$cache_valid" = true ]; then
        echo "Restoring AWS-LC $AWSLC_VERSION from cache..."
        sudo cp -a "${CACHE_DIR}/install/"* "$INSTALL_PREFIX/"
        sudo ldconfig
        echo "AWS-LC $AWSLC_VERSION restored from cache."
        exit 0
    else
        echo "Cache invalid or version mismatch (cached: ${cached_version}, expected: ${AWSLC_VERSION}) — rebuilding."
        rm -rf "$CACHE_DIR"
    fi
fi

TARBALL="aws-lc-${AWSLC_VERSION}.tar.gz"
URL="https://github.com/aws/aws-lc/archive/refs/tags/v${AWSLC_VERSION}.tar.gz"

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

echo "Downloading AWS-LC ${AWSLC_VERSION}..."
curl -fsSL -o "${WORKDIR}/${TARBALL}" "$URL"

echo "Verifying SHA256..."
echo "${AWSLC_SHA256}  ${WORKDIR}/${TARBALL}" | sha256sum -c -

echo "Extracting..."
tar xzf "${WORKDIR}/${TARBALL}" -C "$WORKDIR"

echo "Building..."
cmake -S "${WORKDIR}/aws-lc-${AWSLC_VERSION}" -B "${WORKDIR}/build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$INSTALL_PREFIX" \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_TESTING=OFF
cmake --build "${WORKDIR}/build" -j "$(nproc)"

echo "Installing to ${INSTALL_PREFIX}..."
sudo cmake --install "${WORKDIR}/build"
sudo ldconfig

# Write version marker for skip-if-installed check.
sudo mkdir -p "${INSTALL_PREFIX}/share"
echo "$AWSLC_VERSION" | sudo tee "${INSTALL_PREFIX}/share/awslc-version.txt" > /dev/null

# Populate cache if AWSLC_CACHE_DIR is set.
if [ -n "$CACHE_DIR" ]; then
    echo "Populating cache at ${CACHE_DIR}..."
    STAGING="${WORKDIR}/staging"
    DESTDIR="$STAGING" cmake --install "${WORKDIR}/build" 2>/dev/null || true
    mkdir -p "${CACHE_DIR}/install"
    cp -a "${STAGING}${INSTALL_PREFIX}/"* "${CACHE_DIR}/install/"
    echo "$AWSLC_VERSION" > "${CACHE_DIR}/version.txt"
fi

echo "AWS-LC ${AWSLC_VERSION} installed successfully."
