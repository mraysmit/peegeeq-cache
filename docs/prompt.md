## Architecture Analysis Reference

Imported reference note:

- this document was copied from sibling PeeGeeQ architecture notes
- the modules, interfaces, handlers, and dependency graph below refer to the sibling `peegeeq` repository unless matching code is added here
- in `peegee-cache`, use this as architecture-reference material, not as proof that the described layering already exists in this repo

**Status:** Imported sibling-repo architecture snapshot (December 22, 2025)

### Summary

The sibling PeeGeeQ architecture was described as fully aligned with clean separation of concerns, with the layer violations below already resolved there.

### What Was Reported As Correct In The Sibling Repo

1. **peegeeq-api defines the public interfaces:**
   - `QueueFactory` - factory for creating producers/consumers
   - `MessageProducer<T>` - for sending messages
   - `MessageConsumer<T>` - for receiving messages
   - `ConsumerGroup<T>` - for consumer group management
   - `EventStore<T>` - for bi-temporal event storage
   - `DatabaseSetupService` - for database setup operations
   - ✅ **`SubscriptionService`** - for subscription management (added)
   - ✅ **`SubscriptionInfo`** - DTO for subscription data (added)
   - ✅ **`SubscriptionState`** - enum for subscription states (added)

2. **Implementation modules implement the API interfaces:**
   - `peegeeq-native`: `PgNativeQueueFactory implements QueueFactory`
   - `peegeeq-outbox`: `OutboxFactory implements QueueFactory`
   - `peegeeq-bitemporal`: `PgBiTemporalEventStore implements EventStore`
   - ✅ **`SubscriptionManager implements SubscriptionService`** (peegeeq-db)

3. **REST layer correctly uses API interfaces:**
   - ✅ `ConsumerGroupHandler` uses `SubscriptionService`, `SubscriptionInfo` from peegeeq-api
   - ✅ `SubscriptionManagerFactory` uses `SubscriptionService` from peegeeq-api
   - ✅ `RestDatabaseSetupService` delegates to injected `DatabaseSetupService` (deprecated, use `peegeeq-runtime`)
   - ✅ Implementation modules are **NOT** compile dependencies in peegeeq-rest
   - ✅ `peegeeq-rest` depends on `peegeeq-runtime` for composition

### Layer Violations Reported As Resolved In The Sibling Repo

**Previous violations (now fixed):**

| File | Previous Violation | ✅ Resolution |
|------|-------------------|---------------|
| `ConsumerGroupHandler.java` | Imported from peegeeq-db | Now uses `SubscriptionService`, `SubscriptionInfo` from peegeeq-api |
| `SubscriptionManagerFactory.java` | Imported from peegeeq-db | Now uses `SubscriptionService` from peegeeq-api |
| `RestDatabaseSetupService.java` | Imported from peegeeq-db | Deprecated - delegates to injected `DatabaseSetupService` |

### API Interfaces Reported As Implemented In The Sibling Repo

All required interfaces were reported as present in `peegeeq-api` there:

1. ✅ **`SubscriptionService`** interface - Subscription lifecycle management
2. ✅ **`SubscriptionInfo`** record - Immutable subscription data DTO
3. ✅ **`SubscriptionState`** enum - Subscription lifecycle states (ACTIVE, PAUSED, CANCELLED, DEAD)
4. ✅ **`DatabaseSetupService`** interface - Database setup operations (already existed)

### Architecture Verification Snapshot

**Dependency Flow Reported There:**
```
peegeeq-rest → peegeeq-api (interfaces only)
             → peegeeq-runtime (composition layer)

peegeeq-runtime → peegeeq-db, peegeeq-native, peegeeq-outbox, peegeeq-bitemporal

peegeeq-db → peegeeq-api (implements interfaces)
peegeeq-native → peegeeq-api (implements interfaces)
peegeeq-outbox → peegeeq-api (implements interfaces)
peegeeq-bitemporal → peegeeq-api (implements interfaces)
```

**Key Points Reported There:**
- ✅ peegeeq-rest has **NO** compile dependencies on implementation modules
- ✅ peegeeq-rest uses **ONLY** peegeeq-api interfaces
- ✅ peegeeq-runtime provides composition and wiring
- ✅ All implementation modules implement peegeeq-api interfaces

### 📚 Related Documentation

- See `peegeeq-integration-tests/docs/PEEGEEQ_CALL_PROPAGATION_DESIGN.md` for runtime composition details
- See `peegeeq-rest/pom.xml` for dependency configuration
- See `peegeeq-api/src/main/java/dev/mars/peegeeq/api/subscription/` for subscription interfaces

---

**Conclusion:** This file is a sibling-repo architecture snapshot. For `peegee-cache`, treat it as reference context only.
