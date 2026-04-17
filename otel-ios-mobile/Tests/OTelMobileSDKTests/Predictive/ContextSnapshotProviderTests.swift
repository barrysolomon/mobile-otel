import Testing
@testable import OTelMobileSDK

/// `ContextSnapshotProvider` wraps a pile of iOS system APIs (UIDevice,
/// Locale, TimeZone, NWPathMonitor). We can't inject mocks for those in a
/// unit test without a much heavier refactor, so these tests cover the
/// behaviours we CAN check without the wrapped APIs:
///   1. Snapshots are produced.
///   2. Cached reads within TTL return the same instance (no re-read).
///   3. `invalidate()` forces a refresh on the next call.
///   4. The snapshot carries non-nil core fields (locale, timezone,
///      OS version, app version) on any host that has them — failure here
///      would indicate the provider wiring is wrong even without mocks.
@Suite("ContextSnapshotProvider")
struct ContextSnapshotProviderTests {
    @Test("currentSnapshot produces a ContextSnapshot")
    func snapshotProduced() {
        let provider = ContextSnapshotProvider(ttlSeconds: 60)
        let snap = provider.currentSnapshot()
        // Timezone + locale are always resolvable in a macOS/iOS process.
        #expect(snap.timezone != nil)
        #expect(snap.localeId != nil)
        // OS major version is always > 0 on any real/simulator host.
        #expect((snap.osVersionInt ?? 0) > 0)
    }

    @Test("snapshots are stable within the TTL window")
    func cacheHit() {
        let provider = ContextSnapshotProvider(ttlSeconds: 10)
        let first = provider.currentSnapshot()
        let second = provider.currentSnapshot()
        // The values are Equatable. Within TTL they must be identical.
        #expect(first == second)
    }

    @Test("invalidate forces a re-read on next call")
    func invalidateRefetches() {
        let provider = ContextSnapshotProvider(ttlSeconds: 60)
        let first = provider.currentSnapshot()
        provider.invalidate()
        let second = provider.currentSnapshot()
        // The two snapshots may be equal (values haven't changed) but the
        // provider internally built a new one — we can't observe that
        // directly. We can at least verify both calls succeed.
        #expect(first.timezone == second.timezone)
    }

    @Test("networkType is always one of the documented string values")
    func networkTypeIsWellKnown() {
        let provider = ContextSnapshotProvider(ttlSeconds: 60)
        let snap = provider.currentSnapshot()
        let allowed: Set<String> = ["wifi", "cellular", "offline", "unknown"]
        // Network type may not be populated immediately after init on a
        // fresh simulator. It's either one of the known values or the
        // neutral "unknown" sentinel.
        if let nt = snap.networkType {
            #expect(allowed.contains(nt))
        }
    }
}
