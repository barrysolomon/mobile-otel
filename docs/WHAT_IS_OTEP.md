# What is an OTEP?

**OTEP = OpenTelemetry Enhancement Proposal**

---

## 📝 Overview

An OTEP is a formal design document used in the OpenTelemetry project to propose new features, changes, or enhancements. Think of it as a "Request for Comments" (RFC) for OpenTelemetry.

---

## 🎯 Purpose

OTEPs serve several important purposes:

1. **Propose New Features**: Major additions to OpenTelemetry components
2. **Design Discussion**: Get community feedback before implementing
3. **Document Decisions**: Permanent record of why things work the way they do
4. **Ensure Consistency**: Make sure features work across all languages/platforms
5. **Build Consensus**: Get agreement from maintainers and community

---

## 📋 OTEP Structure

A typical OTEP includes:

### 1. Metadata
```yaml
# OTEP Number: 0123
# Title: Mobile Buffering Pattern
# Author: Your Name
# Status: Draft / Under Review / Approved / Rejected
```

### 2. Problem Statement
**What problem are we solving?**

Example:
> Mobile applications need to buffer telemetry data locally due to intermittent
> network connectivity and bandwidth constraints. Current OpenTelemetry SDKs
> don't provide a standardized way to handle offline scenarios or selective
> data transmission based on event importance.

### 3. Proposed Solution
**High-level approach to solve the problem**

Example:
> Implement a two-tier ring buffer pattern:
> - Tier 1: RAM buffer (fast, bounded, volatile)
> - Tier 2: Disk buffer (persistent, larger, survives crashes)
>
> Events flow from RAM to disk on overflow, with size and TTL limits.

### 4. Design Details
**Technical specifications**

- Architecture diagrams
- API specifications
- Data structures
- Algorithms
- Configuration options
- Performance characteristics

### 5. Trade-offs and Alternatives
**What other approaches were considered and why this one is best**

Example:
> **Alternative 1: Single memory buffer**
> - Pro: Simpler implementation
> - Con: Loses data on crash
> - Con: Can't handle large amounts of buffered data
>
> **Alternative 2: Only disk buffer**
> - Pro: Never loses data
> - Con: Slower for high-frequency events
> - Con: Wears out flash storage faster
>
> **Chosen: Two-tier buffer**
> - Pro: Fast for common case (RAM)
> - Pro: Reliable for important data (disk)
> - Con: More complex implementation

### 6. Compatibility
**Impact on existing implementations**

- Breaking changes?
- Migration path for users
- Backwards compatibility

### 7. Open Questions
**Things to discuss with the community**

Example:
> - Should the RAM buffer size be configurable?
> - What should happen when disk is full?
> - Should we support custom eviction policies?

### 8. Reference Implementation
**Working code that demonstrates the proposal**

Link to:
- Source code
- Tests
- Documentation
- Examples

---

## 🔄 OTEP Process

### Step 1: Write the OTEP (1-2 weeks)
```
1. Fork opentelemetry-specification repo
2. Copy oteps/0000-template.md to oteps/NNNN-your-feature.md
3. Fill in all sections
4. Add diagrams and examples
5. Proofread and polish
```

### Step 2: Submit for Review (1 day)
```
1. Create PR to opentelemetry-specification repo
2. Fill out PR template
3. Tag relevant maintainers
4. Post in Slack #otel-specification channel
```

### Step 3: Community Discussion (2-4 weeks)
```
1. Respond to GitHub comments
2. Update OTEP based on feedback
3. Present at SIG meetings
   - Android SIG (for mobile features)
   - Collector SIG (for collector features)
4. Address concerns and questions
5. Iterate on design
```

### Step 4: Approval (varies)
```
1. Get maintainer approvals (usually 2-3 required)
2. Merge OTEP
3. OTEP becomes official design
```

### Step 5: Implementation (ongoing)
```
1. Implement the design
2. Submit implementation PRs
3. Reference the OTEP in PRs
4. Implementation gets merged
```

---

## 📊 OTEP Status Lifecycle

```
Draft → Under Review → Approved → Implemented
   ↓
Withdrawn / Rejected (if not accepted)
```

**Draft**: Being written, not yet submitted
**Under Review**: PR submitted, community discussing
**Approved**: Accepted by maintainers, ready to implement
**Implemented**: Code merged into official repos
**Withdrawn**: Author decided not to pursue
**Rejected**: Community/maintainers decided against it

---

## 🌟 Example OTEPs

### OTEP 0001: OpenTelemetry Telemetry Schema
- **Problem**: No standard way to version telemetry schemas
- **Solution**: Defined schema file format and versioning
- **Status**: Approved and implemented
- **Link**: https://github.com/open-telemetry/opentelemetry-specification/blob/main/oteps/0001-telemetry-without-manual-instrumentation.md

### OTEP 0099: OTLP File Exporter
- **Problem**: Need to export OTLP data to files for batch processing
- **Solution**: Standardized file format for OTLP
- **Status**: Approved and implemented
- **Link**: https://github.com/open-telemetry/opentelemetry-specification/blob/main/oteps/0099-otlp-file-exporter.md

### OTEP 0131: Exemplars for Metrics
- **Problem**: Hard to correlate metrics with traces
- **Solution**: Add exemplar support to metrics
- **Status**: Approved and implemented
- **Link**: https://github.com/open-telemetry/opentelemetry-specification/blob/main/oteps/0131-exemplars.md

---

## 📝 Our OTEPs to Write

### OTEP 1: Mobile Buffering Pattern

**Problem:**
Mobile apps need offline support and crash recovery, but current OTEL SDKs only buffer in memory.

**Solution:**
Two-tier ring buffer (RAM + disk) with bounded sizes and TTL.

**Why OTEP Needed:**
- New pattern for OpenTelemetry
- Applies to all mobile platforms (Android, iOS, React Native)
- Needs standardization for consistency
- Other projects may want to adopt

**Content Outline:**
```markdown
# OTEP NNNN: Mobile Buffering Pattern

## Problem Statement
- Mobile connectivity challenges
- Crash recovery requirements
- Bandwidth constraints
- Current limitations in OTEL SDKs

## Proposed Solution
- Two-tier ring buffer architecture
- RAM buffer (fast, volatile)
- Disk buffer (persistent, bounded)
- Overflow policy (FIFO)

## Design Details
- RAM buffer: ConcurrentQueue, 5000 events default
- Disk buffer: SQLite, 50MB default, 24h TTL
- Size enforcement: Delete oldest when full
- Thread safety guarantees

## API Design
[Code examples]

## Performance Characteristics
- RAM write: ~1ms
- Disk write: ~50ms
- RAM read: ~1ms
- Disk read: ~10ms

## Trade-offs
[Alternatives considered]

## Reference Implementation
[Link to our Android library]
```

---

### OTEP 2: Conditional Export for Mobile

**Problem:**
Mobile bandwidth is expensive. Always exporting 100% of telemetry wastes money and battery.

**Solution:**
Policy-based DSL for conditional/selective export based on event attributes.

**Why OTEP Needed:**
- Novel approach for OTEL
- Useful beyond mobile (edge devices, IoT)
- Needs standardization for DSL syntax
- Collector integration pattern

**Content Outline:**
```markdown
# OTEP NNNN: Conditional Export for Mobile

## Problem Statement
- Bandwidth costs on mobile
- Battery life concerns
- Not all events equally important
- Need intelligent sampling

## Proposed Solution
- Policy DSL for match conditions
- Conditional export actions
- Collector-side policy enforcement
- Mobile SDK integration

## DSL Specification
[Syntax definition]

## Supported Operators
- equals, gt, lt, gte, lte
- contains, regex
- and, or (logical)

## Actions
- flush_window: Export time window
- sample_rate: Adjust sampling
- annotate: Add metadata

## Collector Integration
- New processor: mobilepolicyprocessor
- Policy configuration
- Annotation propagation

## Use Cases
- Error-triggered flushing
- Performance issue detection
- Network error escalation
- Critical event capture

## Reference Implementation
[Link to our processor]
```

---

## 🎯 Why We Need OTEPs

### For Mobile Buffering Pattern:

1. **Novel Pattern**: Two-tier buffering isn't standard in OTEL yet
2. **Cross-Platform**: Should work on Android, iOS, React Native
3. **Standardization**: API should be consistent across languages
4. **Community Input**: Get feedback on buffer sizes, eviction policies, etc.
5. **Documentation**: Permanent record of design decisions

### For Conditional Export:

1. **New Concept**: Policy-based export doesn't exist in OTEL
2. **DSL Design**: Need agreement on syntax and operators
3. **Collector Pattern**: New type of processor behavior
4. **Use Cases**: Validate with community that this solves real problems
5. **Security**: Get review on policy evaluation security

---

## 💡 Benefits of OTEP Process

### For Contributors:
- ✅ Get feedback before writing tons of code
- ✅ Build consensus early
- ✅ Understand requirements better
- ✅ Avoid wasted effort on wrong approach
- ✅ Get help from experienced maintainers

### For OpenTelemetry:
- ✅ Maintain quality standards
- ✅ Ensure consistent APIs
- ✅ Document design decisions
- ✅ Build community consensus
- ✅ Prevent fragmentation

### For Users:
- ✅ Features work consistently across platforms
- ✅ Well-documented designs
- ✅ Confidence in stability
- ✅ Clear roadmap for features

---

## 📚 Resources

**Official OTEP Resources:**
- OTEP Template: https://github.com/open-telemetry/opentelemetry-specification/blob/main/oteps/0000-template.md
- All OTEPs: https://github.com/open-telemetry/opentelemetry-specification/tree/main/oteps
- OTEP Process: https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/document-status.md
- Contributing Guide: https://github.com/open-telemetry/community/blob/main/guides/contributor/README.md

**Community Channels:**
- CNCF Slack: https://cloud-native.slack.com/
  - #otel-specification (OTEP discussion)
  - #otel-android (Android features)
  - #otel-collector (Collector features)
- GitHub Discussions: https://github.com/open-telemetry/opentelemetry-specification/discussions

**SIG Meetings:**
- Specification SIG: https://github.com/open-telemetry/community#specification-sig
- Android SIG: https://github.com/open-telemetry/community#android-sig
- Collector SIG: https://github.com/open-telemetry/community#collector-sig

---

## 🚀 Next Steps for Our Project

1. **Complete Implementation** (Phase 4)
   - Finish all tests
   - Verify everything works
   - Fix any bugs

2. **Write OTEPs** (Phase 5)
   - OTEP 1: Mobile Buffering Pattern
   - OTEP 2: Conditional Export

3. **Submit OTEPs** (Phase 6)
   - Create PRs
   - Present at SIG meetings
   - Respond to feedback
   - Get approval

4. **Submit Implementation PRs**
   - Reference approved OTEPs
   - Submit code
   - Code review
   - Merge!

---

## ❓ FAQ

**Q: Do I need an OTEP for every contribution?**
A: No. Only for major features or changes that affect the specification. Bug fixes and small improvements don't need OTEPs.

**Q: How long does OTEP approval take?**
A: Usually 2-4 weeks, but can vary. Depends on:
- Complexity of proposal
- Number of maintainers available
- Amount of discussion needed
- Whether it's controversial

**Q: What if my OTEP is rejected?**
A: You can:
- Revise based on feedback and resubmit
- Implement as a contrib/extension (not part of core)
- Work with maintainers to find a compromise approach

**Q: Can I implement before OTEP approval?**
A: Yes! Having a reference implementation helps the OTEP. But be prepared to change it based on feedback.

**Q: Do I need to be an expert to write an OTEP?**
A: No! The community is friendly and will help you improve it. Just start with the template and do your best.

---

## 📝 Summary

**OTEP = Formal proposal for OpenTelemetry enhancements**

**Process:**
1. Write design document
2. Submit PR for review
3. Community discussion
4. Maintainer approval
5. Implement the design

**Our OTEPs:**
1. Mobile Buffering Pattern (two-tier ring buffer)
2. Conditional Export (policy-based DSL)

**Timeline:**
- Writing: 1 week
- Review: 2-4 weeks
- Implementation PRs: After approval

**Why Important:**
- Gets community buy-in
- Ensures consistent design
- Documents decisions
- Makes contribution official

---

**Ready to write OTEPs in Phase 5!** 🚀
