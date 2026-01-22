# gRPC Security for OpenTelemetry Android

**Last Updated**: January 21, 2026

This document explains how gRPC security works when sending telemetry data from the Android app to the OpenTelemetry Collector.

---

## Overview

When using gRPC to send telemetry data, security is handled through **three main layers**:

1. **Transport Security** (TLS/SSL encryption)
2. **Authentication** (Bearer tokens in metadata)
3. **Authorization** (Server-side validation)

---

## Layer 1: Transport Security (TLS/SSL)

### What is TLS?

**Transport Layer Security (TLS)** encrypts all data sent over gRPC, preventing:
- Man-in-the-middle attacks
- Eavesdropping on telemetry data
- Data tampering in transit

### How It Works

```
┌─────────────┐                    ┌─────────────────────┐
│  Android    │   TLS Handshake    │  OTEL Collector     │
│  App        │◄──────────────────►│  (gRPC Server)      │
│             │                    │                     │
│             │   Encrypted Data   │                     │
│             │═══════════════════►│                     │
│             │   (AES-256 cipher) │                     │
└─────────────┘                    └─────────────────────┘
```

**Steps**:
1. App initiates connection to `https://ingress.dash0.com:4317`
2. Server presents SSL/TLS certificate
3. App validates certificate (checks if it's trusted)
4. Secure encrypted channel established
5. All gRPC messages sent over this encrypted channel

### Implementation in Android

The OpenTelemetry SDK handles TLS automatically when you use HTTPS:

```kotlin
val config = MobileConfig(
    collectorEndpoint = "https://ingress.us-west-2.aws.dash0.com:4317"
    //                  ^^^^^ HTTPS = TLS enabled
)
```

**Under the Hood** (OpenTelemetry SDK):
```kotlin
// The OTLP exporter creates a secure gRPC channel
val channel = ManagedChannelBuilder
    .forAddress("ingress.us-west-2.aws.dash0.com", 4317)
    .useTransportSecurity()  // ← Enables TLS
    .build()
```

### Certificate Validation

By default, Android validates certificates using the **system trust store**:
- Certificates signed by known Certificate Authorities (CAs) are trusted
- Self-signed certificates are rejected
- Expired certificates are rejected

**Trusted CAs on Android**:
- Let's Encrypt
- DigiCert
- GlobalSign
- AWS Certificate Manager
- etc.

For Dash0 and most cloud providers, certificates are signed by trusted CAs, so validation happens automatically.

---

## Layer 2: Authentication (Bearer Tokens)

### How Bearer Tokens Work with gRPC

Unlike HTTP headers, gRPC uses **metadata** to send authentication information.

**HTTP vs gRPC**:
```
HTTP:
  GET /v1/logs
  Authorization: Bearer auth_token_here
  Content-Type: application/json

gRPC:
  RPC Call: Export(LogsServiceRequest)
  Metadata: authorization = "Bearer auth_token_here"
  Metadata: content-type = "application/grpc"
```

### Implementation

When you configure the auth token in Settings:

```kotlin
ConfigManager.saveAuthToken(context, "auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh")
ConfigManager.saveDataset(context, "pi5-k3s")
```

ConfigManager builds a headers map:

```kotlin
val headers = mutableMapOf<String, String>()
headers["Authorization"] = "Bearer auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh"
headers["Dash0-Dataset"] = "pi5-k3s"

val config = MobileConfig(
    collectorEndpoint = "https://ingress.dash0.com:4317",
    headers = headers  // ← Passed to OTLP exporter
)
```

The OpenTelemetry SDK converts these headers to **gRPC metadata**:

```kotlin
// Inside OTLP gRPC Exporter (simplified)
val metadata = Metadata()
config.headers?.forEach { (key, value) ->
    metadata.put(
        Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER),
        value
    )
}

// Attach metadata to every gRPC call
val stub = LogsServiceGrpc.newStub(channel)
    .withCallCredentials(new CallCredentials() {
        override fun applyRequestMetadata(...) {
            applier.apply(metadata)  // ← Headers attached here
        }
    })
```

### What Happens on the Server

The gRPC server (OTEL Collector or Dash0) receives the metadata:

```go
// Server-side (Go example)
func (s *Server) Export(ctx context.Context, req *LogsServiceRequest) (*LogsServiceResponse, error) {
    md, ok := metadata.FromIncomingContext(ctx)
    if !ok {
        return nil, status.Error(codes.Unauthenticated, "no metadata")
    }

    authHeader := md.Get("authorization")
    if len(authHeader) == 0 {
        return nil, status.Error(codes.Unauthenticated, "no auth token")
    }

    token := strings.TrimPrefix(authHeader[0], "Bearer ")
    if !validateToken(token) {
        return nil, status.Error(codes.PermissionDenied, "invalid token")
    }

    // Token valid, process logs
    return &LogsServiceResponse{}, nil
}
```

**Error Codes**:
- `UNAUTHENTICATED` (16) - No token or invalid format
- `PERMISSION_DENIED` (7) - Token validation failed
- `OK` (0) - Success

---

## Layer 3: Authorization (Server-Side)

### Token Validation

The server validates the Bearer token by:

1. **Checking Token Format**
   ```
   Bearer auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh
   ```

2. **Database Lookup**
   - Query token database
   - Check if token is active (not revoked)
   - Check if token is expired

3. **Permission Check**
   - What datasets can this token access?
   - What operations are allowed (read/write)?
   - Rate limits?

4. **Logging & Audit**
   - Log all authenticated requests
   - Track usage per token

### Multi-Tenancy (Dataset Routing)

The `Dash0-Dataset` header routes telemetry to the correct tenant:

```kotlin
headers["Dash0-Dataset"] = "production-mobile"
```

Server-side logic:
```go
dataset := md.Get("dash0-dataset")
if len(dataset) == 0 {
    dataset = "default"
}

// Check if token has access to this dataset
if !hasAccess(token, dataset) {
    return nil, status.Error(codes.PermissionDenied, "no access to dataset")
}

// Route logs to correct storage
storeLogsInDataset(logs, dataset)
```

---

## Complete Security Flow

### End-to-End Example

**1. App Configuration** (User enters in Settings):
```
Protocol: gRPC
Endpoint: https://ingress.us-west-2.aws.dash0.com:4317
Auth Token: auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh
Dataset: production-mobile
```

**2. MobileConfig Creation**:
```kotlin
val config = MobileConfig(
    collectorEndpoint = "https://ingress.us-west-2.aws.dash0.com:4317",
    headers = mapOf(
        "Authorization" to "Bearer auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh",
        "Dash0-Dataset" to "production-mobile"
    )
)
```

**3. OTLP Exporter Initialization**:
```kotlin
// OpenTelemetry SDK creates secure gRPC channel
val channel = ManagedChannelBuilder
    .forAddress("ingress.us-west-2.aws.dash0.com", 4317)
    .useTransportSecurity()  // TLS enabled
    .build()

val exporter = OtlpGrpcLogRecordExporter.builder()
    .setChannel(channel)
    .addHeader("authorization", "Bearer auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh")
    .addHeader("dash0-dataset", "production-mobile")
    .build()
```

**4. TLS Handshake** (when first log is exported):
```
App → Server: ClientHello (supported ciphers, TLS versions)
App ← Server: ServerHello + Certificate
App → Server: Verify certificate against system trust store
App → Server: Key exchange (establish symmetric encryption key)
App ← Server: Finished
```

**5. gRPC Call** (export logs):
```
App → Server: [ENCRYPTED with AES-256]
  RPC: opentelemetry.proto.collector.logs.v1.LogsService/Export
  Metadata:
    authorization: Bearer auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh
    dash0-dataset: production-mobile
  Body: LogsServiceRequest (Protobuf-encoded)

App ← Server: [ENCRYPTED with AES-256]
  Status: OK (0)
  Body: LogsServiceResponse
```

**6. Server Processing**:
```
1. Decrypt TLS
2. Extract metadata (auth token, dataset)
3. Validate token in database
4. Check token has access to "production-mobile" dataset
5. Process logs
6. Store in correct dataset
7. Return success response
```

---

## Security Best Practices

### ✅ What the App Does Right

1. **Always Use HTTPS/TLS**
   ```kotlin
   collectorEndpoint = "https://ingress.dash0.com:4317"  // ✓ Secure
   // NOT: "http://ingress.dash0.com:4317"              // ✗ Insecure
   ```

2. **Token Stored Securely**
   - SharedPreferences with MODE_PRIVATE (app-only access)
   - Not logged or exposed in debug output

3. **Token Masked in UI**
   ```xml
   <EditText
       android:id="@+id/editAuthToken"
       android:inputType="textPassword"  <!-- ✓ Masked -->
   ```

4. **Certificate Validation**
   - Automatic validation using Android system trust store
   - Rejects self-signed or expired certificates

### 🔒 Additional Security (Production Recommendations)

#### 1. Certificate Pinning

**What**: Only accept specific certificates (not just any CA-signed cert)

**Why**: Prevents compromised CAs from issuing fraudulent certificates

**Implementation**:
```kotlin
import okhttp3.CertificatePinner

val certificatePinner = CertificatePinner.Builder()
    .add("ingress.us-west-2.aws.dash0.com", "sha256/AAAAAAAAAA...")
    .add("ingress.us-west-2.aws.dash0.com", "sha256/BBBBBBBBBB...")  // Backup cert
    .build()

// Configure OkHttp (used by gRPC)
val httpClient = OkHttpClient.Builder()
    .certificatePinner(certificatePinner)
    .build()
```

**How to Get Certificate Pins**:
```bash
# Get certificate
openssl s_client -connect ingress.us-west-2.aws.dash0.com:4317 < /dev/null \
  | openssl x509 -outform DER \
  > cert.der

# Get SHA-256 hash
openssl x509 -in cert.der -inform DER -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary \
  | openssl base64
```

#### 2. Encrypted Token Storage

**Current** (MODE_PRIVATE SharedPreferences):
```kotlin
// Good for demo, not ideal for production
getSharedPreferences("otel_config", MODE_PRIVATE)
```

**Production** (EncryptedSharedPreferences):
```kotlin
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val prefs = EncryptedSharedPreferences.create(
    context,
    "otel_config_secure",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

// Now tokens are encrypted at rest
prefs.edit().putString("auth_token", token).apply()
```

**Dependencies**:
```gradle
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

#### 3. Token Rotation

**Strategy**:
```kotlin
// Check token expiry
fun isTokenExpired(context: Context): Boolean {
    val expiresAt = getTokenExpiry(context)
    return System.currentTimeMillis() > expiresAt
}

// Refresh token before expiry
suspend fun refreshTokenIfNeeded(context: Context) {
    if (isTokenExpired(context)) {
        val newToken = fetchNewTokenFromServer()
        ConfigManager.saveAuthToken(context, newToken)
    }
}
```

#### 4. Mutual TLS (mTLS)

**What**: Client also presents a certificate (not just server)

**When**: High-security environments requiring client authentication

**Implementation**:
```kotlin
val keyStore = KeyStore.getInstance("PKCS12")
keyStore.load(context.assets.open("client-cert.p12"), password)

val sslContext = SSLContext.getInstance("TLS")
sslContext.init(
    keyManagerFactory.keyManagers,
    trustManagerFactory.trustManagers,
    null
)

// Use with gRPC channel
val channel = OkHttpTransportBuilder
    .forAddress("collector.example.com", 4317)
    .sslSocketFactory(sslContext.socketFactory)
    .build()
```

---

## Debugging gRPC Security Issues

### Issue 1: Certificate Verification Failed

**Error**:
```
javax.net.ssl.SSLHandshakeException:
  java.security.cert.CertPathValidatorException:
    Trust anchor for certification path not found
```

**Cause**: Self-signed certificate or CA not in Android trust store

**Solutions**:
1. **Use proper CA-signed certificate** (recommended)
2. **Add custom CA to trust store** (testing only):
   ```xml
   <!-- res/xml/network_security_config.xml -->
   <network-security-config>
       <domain-config>
           <domain includeSubdomains="true">test-collector.local</domain>
           <trust-anchors>
               <certificates src="@raw/test_ca"/>
           </trust-anchors>
       </domain-config>
   </network-security-config>
   ```

   ```xml
   <!-- AndroidManifest.xml -->
   <application
       android:networkSecurityConfig="@xml/network_security_config">
   ```

### Issue 2: Authentication Failed

**Error in logs**:
```
RetryableExporter: Export failed with status: PERMISSION_DENIED
```

**Cause**: Invalid or expired token

**Solutions**:
1. Verify token is correct (check for typos)
2. Test token with curl:
   ```bash
   grpcurl \
     -H "authorization: Bearer YOUR_TOKEN" \
     ingress.dash0.com:4317 \
     list
   ```
3. Check token expiry
4. Regenerate token in Dash0 dashboard

### Issue 3: Connection Refused

**Error**:
```
io.grpc.StatusRuntimeException: UNAVAILABLE:
  io exception: Connection refused
```

**Causes**:
- Wrong port (use 4317 for gRPC, not 4318)
- Firewall blocking gRPC traffic
- Server not running

**Debug**:
```bash
# Test connectivity
telnet ingress.dash0.com 4317

# Test TLS
openssl s_client -connect ingress.dash0.com:4317

# Test gRPC
grpcurl ingress.dash0.com:4317 list
```

---

## Comparison: gRPC vs HTTP Security

| Feature | gRPC | HTTP |
|---------|------|------|
| **Transport** | TLS 1.2+ | TLS 1.2+ |
| **Authentication** | Metadata (authorization) | Headers (Authorization) |
| **Encoding** | Binary Protobuf | JSON or Protobuf |
| **Performance** | Faster (binary) | Slower (text) |
| **Certificate Pinning** | Supported | Supported |
| **Mutual TLS** | Supported | Supported |
| **Firewall Friendly** | Sometimes blocked | Usually works |

**Security Equivalence**: Both gRPC and HTTP use the same TLS encryption and Bearer token authentication. The **security level is identical** - the difference is only in how metadata is transmitted (gRPC metadata vs HTTP headers).

---

## Summary

### How gRPC Security Works

1. **TLS Encryption**
   - All data encrypted using AES-256
   - Certificate validation using Android trust store
   - Protects against eavesdropping and MITM attacks

2. **Bearer Token Authentication**
   - Token sent in gRPC metadata (not visible in plaintext)
   - Server validates token on every request
   - Token stored securely on device (SharedPreferences MODE_PRIVATE)

3. **Authorization**
   - Server checks token permissions
   - Dataset routing based on Dash0-Dataset metadata
   - Rate limiting and audit logging

### Security Checklist

- ✅ Always use HTTPS endpoint (TLS enabled)
- ✅ Store tokens in MODE_PRIVATE SharedPreferences
- ✅ Mask token input fields (inputType="textPassword")
- ✅ Validate certificates (automatic with system trust store)
- ✅ Use Bearer token authentication
- ⏭️ Consider certificate pinning (production)
- ⏭️ Consider encrypted storage (production)
- ⏭️ Implement token rotation (production)
- ⏭️ Consider mutual TLS (high-security environments)

---

**The same Bearer token works for both gRPC and HTTP** - the app automatically formats it correctly (gRPC metadata vs HTTP header) based on your protocol selection.

**Bottom line**: gRPC security is robust, production-ready, and equivalent to HTTPS REST API security. The OpenTelemetry SDK handles all the complexity automatically!

---

**Created**: January 21, 2026
**Status**: ✅ Complete explanation
**Related**: [AUTHENTICATION_SETUP.md](AUTHENTICATION_SETUP.md), [PROTOCOL_SELECTION_FEATURE.md](PROTOCOL_SELECTION_FEATURE.md)
