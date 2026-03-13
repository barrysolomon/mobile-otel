# Contributing to OpenTelemetry Mobile Extensions

We welcome contributions from the community!

## Code of Conduct

This project follows the [OpenTelemetry Code of Conduct](https://github.com/open-telemetry/community/blob/main/code-of-conduct.md).

## How to Contribute

### Reporting Issues

- Use GitHub Issues to report bugs or request features
- Search existing issues before creating a new one
- Provide detailed reproduction steps for bugs
- Include version information and environment details

### Development Setup

#### Android Library

```bash
# Clone repository (current location — will move to opentelemetry-android-contrib on upstream merge)
git clone https://github.com/barrysolomon/mobile-otel
cd mobile-otel

# Build the SDK (run from the demo-app wrapper which includes the SDK as a project dependency)
cd examples/demo-app
./gradlew :otel-android-mobile:build

# Run tests
./gradlew :otel-android-mobile:test

# Run on device
./gradlew :otel-android-mobile:connectedAndroidTest
```

#### Collector Processor

```bash
# Clone repository (current location — will move to opentelemetry-collector-contrib on upstream merge)
git clone https://github.com/barrysolomon/mobile-otel
cd mobile-otel/collector-processor/mobilepolicyprocessor

# Install dependencies
go mod download

# Build
go build ./...

# Run tests
go test ./...
```

### Making Changes

1. **Fork the repository**

2. **Create a feature branch**

   ```bash
   git checkout -b feature/my-feature
   ```

3. **Make your changes**
   - Write clean, readable code
   - Follow existing code style
   - Add tests for new functionality
   - Update documentation

4. **Test your changes**

   ```bash
   # Android
   ./gradlew test connectedAndroidTest

   # Go
   go test ./...
   go vet ./...
   ```

5. **Commit with clear messages**

   ```bash
   git commit -m "feat: add new feature X"
   ```

   Use conventional commits:
   - `feat:` New feature
   - `fix:` Bug fix
   - `docs:` Documentation changes
   - `test:` Test changes
   - `refactor:` Code refactoring
   - `chore:` Maintenance tasks

6. **Push and create Pull Request**

   ```bash
   git push origin feature/my-feature
   ```

### Pull Request Guidelines

- **Title**: Clear, concise description
- **Description**:
  - What does this PR do?
  - Why is this change needed?
  - How was it tested?
  - Any breaking changes?
- **Tests**: All tests must pass
- **Documentation**: Update docs for user-facing changes
- **Size**: Keep PRs focused and reasonably sized

### Code Style

#### Kotlin (Android)

Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html):

```kotlin
// Good
class MobileLoggerProvider private constructor(
    private val context: Context,
    private val config: MobileConfig
) {
    fun initialize() {
        // Implementation
    }
}

// Use descriptive names
val ramBufferSize = 5000

// Add KDoc for public APIs
/**
 * Provides OpenTelemetry loggers optimized for mobile applications.
 *
 * @param context Android application context
 * @param config Mobile-specific configuration
 */
```

#### Go (Collector)

Follow [Effective Go](https://golang.org/doc/effective_go.html):

```go
// Good
type mobilePolicyProcessor struct {
    config *Config
    logger *zap.Logger
}

// Use descriptive names
func (p *mobilePolicyProcessor) processLogs(ctx context.Context, ld plog.Logs) (plog.Logs, error) {
    // Implementation
}

// Add godoc for exported functions
// NewFactory creates a new processor factory.
func NewFactory() processor.Factory {
    // Implementation
}
```

### Testing Requirements

#### Unit Tests

All new code must include unit tests:

```kotlin
// Android
@Test
fun `test buffer overflow triggers disk flush`() {
    // Arrange
    val processor = MobileLogRecordProcessor.builder()
        .setRamBufferSize(10)
        .build()

    // Act
    repeat(15) {
        processor.onEmit(context, logRecord)
    }

    // Assert
    verify { diskBuffer.write(any()) }
}
```

```go
// Go
func TestPolicyMatching(t *testing.T) {
    // Arrange
    processor := newMobilePolicyProcessor(config, logger)

    // Act
    result := processor.matchesPolicy(logRecord, policy)

    // Assert
    require.True(t, result)
}
```

#### Integration Tests

For significant features, add integration tests:

```bash
# Android
./gradlew connectedAndroidTest

# Go
go test -tags=integration ./...
```

### Documentation

Update documentation for user-facing changes:

- **README.md**: Feature descriptions, examples
- **API docs**: KDoc (Kotlin) or godoc (Go)
- **Guides**: tutorials/, docs/
- **CHANGELOG.md**: Note your changes

### Performance Considerations

- Profile changes that affect hot paths
- Include benchmarks for performance-critical code
- Document performance characteristics

```go
// Go benchmark
func BenchmarkProcessLogs(b *testing.B) {
    processor := newMobilePolicyProcessor(config, logger)
    b.ResetTimer()

    for i := 0; i < b.N; i++ {
        processor.processLogs(ctx, logs)
    }
}
```

### Security

- Never commit secrets or credentials
- Be cautious with user data (PII)
- Follow OWASP guidelines
- Report security issues privately

### Licensing

- All contributions are licensed under Apache 2.0
- You must have rights to contribute your code
- Add license header to new files:

```go
// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
```

## Community

### Communication Channels

- **Slack**: [#otel-android](https://cloud-native.slack.com/archives/C01N7PP1THC)
- **Mailing List**: [cncf-opentelemetry-contributors](https://lists.cncf.io/g/cncf-opentelemetry-contributors)
- **SIG Meetings**: Check [OpenTelemetry calendar](https://github.com/open-telemetry/community#calendar)

### Getting Help

- **GitHub Discussions**: For questions and discussions
- **Slack**: For real-time help
- **Stack Overflow**: Tag with `opentelemetry`

## Recognition

Contributors are recognized in:

- CHANGELOG.md
- GitHub contributors page
- Release notes

Thank you for contributing to OpenTelemetry! 🎉
