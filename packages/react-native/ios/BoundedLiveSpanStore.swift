// Bounded, insertion-ordered store of live spans keyed by spanId.
//
// The RN bridge holds a span object between `startSpan` and `endSpan` so it can
// apply end-time attributes and status. A misbehaving JS layer that starts spans
// but never ends them (navigation churn, leaked timers) would otherwise grow
// this map without bound. We cap it and evict the oldest entry when full.
//
// Design goals (all O(1)):
//   - lookup / remove by spanId           → dictionary
//   - evict the OLDEST inserted entry      → intrusive doubly-linked list
//
// A naive bounded list does an O(n) `firstIndex(where:)` scan to evict; this
// structure keeps a `head`/`tail` of a doubly-linked list of nodes plus a
// `[Key: Node]` index, so both lookup-by-id AND oldest-eviction are O(1).
//
// This type is deliberately generic and has **no** dependency on OTelMobileSDK
// or React, so it lives in the base RN package target and is unit-testable with
// plain `swift test`. `OTelMobileCallSink` specializes it to `Span`.
//
// NOT thread-safe on its own — the caller (`OTelMobileCallSink`) serializes all
// access under its existing `spanLock`, matching the prior `liveSpans` usage.
final class BoundedLiveSpanStore<Key: Hashable, Value> {

    private final class Node {
        let key: Key
        var value: Value
        var prev: Node?
        var next: Node?
        init(key: Key, value: Value) {
            self.key = key
            self.value = value
        }
    }

    private let capacity: Int
    private var index: [Key: Node] = [:]
    // head = oldest (evict from here), tail = newest (insert here). FIFO order.
    private var head: Node?
    private var tail: Node?

    /// Count of entries evicted because the store hit `capacity`. Exposed for
    /// tests and a future health gauge.
    private(set) var evictedCount: Int = 0

    init(capacity: Int) {
        precondition(capacity > 0, "capacity must be positive")
        self.capacity = capacity
        index.reserveCapacity(capacity)
    }

    var count: Int { index.count }

    /// Insert or replace the value for `key`. Returns the value that was
    /// evicted to make room (the oldest entry), or `nil` if nothing was evicted.
    /// O(1). Re-inserting an existing key updates its value in place and does
    /// NOT change its insertion position (it is not "refreshed" to newest) —
    /// startSpan for a duplicate id is a degenerate case we simply overwrite.
    @discardableResult
    func put(_ key: Key, _ value: Value) -> Value? {
        if let existing = index[key] {
            existing.value = value
            return nil
        }

        var evicted: Value?
        // Evict oldest if at capacity. `>=` because we are about to add one.
        if index.count >= capacity, let oldest = head {
            evicted = oldest.value
            removeNode(oldest)
            index[oldest.key] = nil
            evictedCount += 1
        }

        let node = Node(key: key, value: value)
        appendNode(node)
        index[key] = node
        return evicted
    }

    /// Remove and return the value for `key`, or `nil` if absent. O(1).
    @discardableResult
    func removeValue(forKey key: Key) -> Value? {
        guard let node = index[key] else { return nil }
        removeNode(node)
        index[key] = nil
        return node.value
    }

    /// Remove all entries. Returns the values in insertion (oldest→newest) order
    /// so the caller can end/clean them up if needed. O(n).
    @discardableResult
    func removeAll() -> [Value] {
        var values: [Value] = []
        values.reserveCapacity(index.count)
        var cur = head
        while let node = cur {
            values.append(node.value)
            cur = node.next
        }
        index.removeAll(keepingCapacity: true)
        head = nil
        tail = nil
        return values
    }

    // MARK: - Intrusive list ops (all O(1))

    private func appendNode(_ node: Node) {
        node.prev = tail
        node.next = nil
        if let t = tail {
            t.next = node
        } else {
            head = node
        }
        tail = node
    }

    private func removeNode(_ node: Node) {
        let p = node.prev
        let n = node.next
        if let p = p { p.next = n } else { head = n }
        if let n = n { n.prev = p } else { tail = p }
        node.prev = nil
        node.next = nil
    }
}
