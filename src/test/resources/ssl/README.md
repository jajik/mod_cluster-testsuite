# SSL Test Certificates

Pre-generated PKI chain for SSL/TLS tests. Certificates are valid 2022-2032 (10 years).

## What tests use

From `ca/intermediate/keystores/`:

| Keystore | Purpose |
|----------|---------|
| `node1.server.keystore.jks` | Server cert for worker1 |
| `node2.server.keystore.jks` | Server cert for worker2 |
| `localhost.server.keystore.jks` | Server cert for balancer |
| `ca-chain.keystore.jks` | Trust store (root + intermediate CA) |

All keystores use password `testpass` and JKS format.

## Regenerating certificates

Remove the existing `ca/` directory first, then use Docker/Podman for OS-independent generation:

```bash
rm -rf ca/
docker build . -t ssl-gen
docker run --rm -v $PWD/ca:/trustchain/ca:z ssl-gen bash generate-trustchain.sh
```

Or run manually if OpenSSL and keytool are available:

```bash
rm -rf ca/
./generate-trustchain.sh
```

See `generate-trustchain.sh` for the full generated directory structure.
