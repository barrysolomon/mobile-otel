// Tests for the O(1) bounded live-span store used by OTelMobileCallSink.
//
// OTelMobileCallSink itself is excluded from this SwiftPM test target (it
// depends on OTelMobileSDK), so we test the eviction/lookup logic directly on
// the generic store with a plain value type. This is the same data structure
// the call sink specializes to `Span`.

import Foundation
import Testing
@testable import Dash0MobileReactNative

@Suite("BoundedLiveSpanStore")
struct BoundedLiveSpanStoreTests {

    @Test("start/end pairing: put then removeValue returns the value")
    func putThenRemove() {
        let store = BoundedLiveSpanStore<String, Int>(capacity: 8)
        #expect(store.put("a", 1) == nil)
        #expect(store.count == 1)
        #expect(store.removeValue(forKey: "a") == 1)
        #expect(store.count == 0)
        // Removing again is a safe nil.
        #expect(store.removeValue(forKey: "a") == nil)
    }

    @Test("removeValue for absent key returns nil and leaves store intact")
    func removeAbsent() {
        let store = BoundedLiveSpanStore<String, Int>(capacity: 4)
        store.put("a", 1)
        #expect(store.removeValue(forKey: "missing") == nil)
        #expect(store.count == 1)
    }

    @Test("eviction at capacity drops the OLDEST entry (FIFO)")
    func evictsOldest() {
        let store = BoundedLiveSpanStore<String, Int>(capacity: 3)
        #expect(store.put("a", 1) == nil)
        #expect(store.put("b", 2) == nil)
        #expect(store.put("c", 3) == nil)
        // Fourth insert evicts the oldest ("a" → 1).
        #expect(store.put("d", 4) == 1)
        #expect(store.evictedCount == 1)
        #expect(store.count == 3)
        // "a" is gone; b, c, d remain.
        #expect(store.removeValue(forKey: "a") == nil)
        #expect(store.removeValue(forKey: "b") == 2)
        #expect(store.removeValue(forKey: "c") == 3)
        #expect(store.removeValue(forKey: "d") == 4)
    }

    @Test("eviction order stays correct after interleaved removes")
    func evictionAfterRemoves() {
        let store = BoundedLiveSpanStore<String, Int>(capacity: 3)
        store.put("a", 1)
        store.put("b", 2)
        store.put("c", 3)
        // Remove the current oldest explicitly.
        #expect(store.removeValue(forKey: "a") == 2 - 1) // 1
        // Now b is oldest. Insert two more; capacity is 3, count is 2 → first
        // insert fits, second evicts oldest (b).
        #expect(store.put("d", 4) == nil)   // count 3, no eviction
        #expect(store.put("e", 5) == 2)     // evicts b
        #expect(store.removeValue(forKey: "b") == nil)
        #expect(store.removeValue(forKey: "c") == 3)
        #expect(store.removeValue(forKey: "d") == 4)
        #expect(store.removeValue(forKey: "e") == 5)
    }

    @Test("re-putting an existing key updates value without eviction or reorder")
    func rePutUpdatesInPlace() {
        let store = BoundedLiveSpanStore<String, Int>(capacity: 2)
        store.put("a", 1)
        store.put("b", 2)
        // Overwrite "a" — no eviction, count unchanged, "a" keeps its (oldest)
        // position so the NEXT insert still evicts "a".
        #expect(store.put("a", 11) == nil)
        #expect(store.count == 2)
        #expect(store.put("c", 3) == 11)   // evicts a (still oldest), now with updated value
        #expect(store.removeValue(forKey: "a") == nil)
        #expect(store.removeValue(forKey: "b") == 2)
        #expect(store.removeValue(forKey: "c") == 3)
    }

    @Test("removeAll returns all values oldest→newest and empties the store")
    func removeAllDrains() {
        let store = BoundedLiveSpanStore<String, Int>(capacity: 8)
        store.put("a", 1)
        store.put("b", 2)
        store.put("c", 3)
        #expect(store.removeAll() == [1, 2, 3])
        #expect(store.count == 0)
        // Store is reusable after removeAll.
        #expect(store.put("x", 9) == nil)
        #expect(store.count == 1)
    }

    @Test("no leak: count never exceeds capacity under sustained inserts")
    func noLeakUnderChurn() {
        let cap = 100
        let store = BoundedLiveSpanStore<Int, Int>(capacity: cap)
        for i in 0..<10_000 {
            store.put(i, i)
            #expect(store.count <= cap)
        }
        #expect(store.count == cap)
        #expect(store.evictedCount == 10_000 - cap)
        // The surviving keys are the most-recent `cap` insertions.
        #expect(store.removeValue(forKey: 10_000 - cap) == 10_000 - cap)
        #expect(store.removeValue(forKey: 10_000 - cap - 1) == nil)
    }

    @Test("concurrent access via an external lock stays consistent")
    func concurrentWithExternalLock() async {
        // Mirrors how OTelMobileCallSink serializes access under spanLock.
        let box = LockedStore(capacity: 256)
        await withTaskGroup(of: Void.self) { group in
            for i in 0..<2000 {
                group.addTask {
                    box.put("k\(i % 300)", i)
                    _ = box.remove("k\(i % 300)")
                }
            }
        }
        // After all start/remove pairs, the store must not have grown beyond
        // capacity and must be internally consistent (count == reachable nodes,
        // enforced by removeAll succeeding without crash).
        #expect(box.count() <= 256)
        _ = box.removeAll()
        #expect(box.count() == 0)
    }

    /// Tiny lock wrapper so the concurrency test exercises the same
    /// serialize-under-a-lock pattern the production call sink uses.
    private final class LockedStore: @unchecked Sendable {
        private let lock = NSLock()
        private let store: BoundedLiveSpanStore<String, Int>
        init(capacity: Int) { store = BoundedLiveSpanStore(capacity: capacity) }
        func put(_ k: String, _ v: Int) { lock.lock(); store.put(k, v); lock.unlock() }
        func remove(_ k: String) -> Int? { lock.lock(); defer { lock.unlock() }; return store.removeValue(forKey: k) }
        func count() -> Int { lock.lock(); defer { lock.unlock() }; return store.count }
        func removeAll() -> [Int] { lock.lock(); defer { lock.unlock() }; return store.removeAll() }
    }
}
