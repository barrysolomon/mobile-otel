# Gateway Persistence: SQLite vs JSON File

## Decision: SQLite

## Justification

### Requirements
* Store config versions (graph_json, dsl_json, metadata)
* Track device heartbeats
* Support versioning with rollback
* Query recent heartbeats by device
* Atomic config activation (only one active at a time)

### SQLite Advantages

**ACID Transactions**
* Atomic config activation (deactivate old + activate new)
* No race conditions during rollback
* Guaranteed consistency

**Efficient Queries**
* Indexed lookups for active config
* Time-range queries for heartbeats
* Device-specific heartbeat filtering

**Concurrent Access**
* Multiple readers
* WAL mode for better write concurrency
* Built-in locking mechanisms

**Schema Enforcement**
* Type safety (timestamps, integers, booleans)
* Foreign key constraints (future use)
* Indexes for performance

**Versioning Support**
* Native autoincrement for version numbers
* Transactional rollback implementation
* Audit trail built-in

### JSON File Drawbacks

**No Atomicity**
* Rollback = read full file + modify + write full file
* Race conditions between config publish and device reads
* Corruption risk on interrupted writes

**Poor Query Performance**
* Must read entire file for any query
* No indexes
* Linear scan for device heartbeats

**Concurrency Issues**
* File locking is OS-dependent
* No read-while-write support
* Manual coordination needed

**Schema Drift**
* No enforcement
* Easy to corrupt structure
* Manual validation required

### Trade-offs Accepted

**SQLite Limitations**
* Single-writer limitation (mitigated by single replica)
* File-based (but persistent volume handles this)
* Not distributed (acceptable for demo MVP)

**Not a Problem For Demo**
* Single replica deployment
* Low write volume (configs published infrequently)
* Read-heavy workload (devices polling config)

## Conclusion

SQLite provides the necessary guarantees for safe config management and efficient heartbeat tracking with minimal complexity. For a production system at scale, consider PostgreSQL or a distributed key-value store, but SQLite is optimal for this demo MVP.
