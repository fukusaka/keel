# Test Certificates

Self-signed certificate and private key for TLS echo testing only.

**Generated with:**
```bash
openssl req -x509 -newkey rsa:2048 \
  -keyout server.key -out server.crt \
  -days 365 -nodes -subj '/CN=localhost'
```

**Properties:**
- Subject: CN=localhost
- Key: RSA 2048-bit
- Validity: 365 days from generation
- Self-signed (no CA chain)

**Ephemeral**: These files are for cinterop prototype testing and will be
removed when TLS modules are refactored to use programmatic cert generation
or shared test fixtures.

**DO NOT use in production.** Self-signed certificates are insecure.
