# Design Note: Resilience and Observability in companyms

**Date:** 2026-06-09
**Status:** Current

This note records the design rationale behind three resilience and observability
decisions strengthened during the AI-DLC exercise. It is architectural context,
not a code walkthrough. See the linked ADRs for the detailed decisions and
rejected alternatives.

---

## 1. Feign fallback contract: silent degradation on reads, loud failure on deletes

`companyms` calls two downstream services — `jobms` and `reviewms` — via Feign
clients wrapped with Resilience4j circuit breaker, retry, and rate limiter.

**Rationale.** Read paths (fetching jobs and reviews to build a `CompanyResponse`)
degrade silently: the fallback returns an empty list, and the company data is
still returned to the caller. A partial response is more useful than a 5xx.
Delete paths throw a `RuntimeException` from the fallback. Silently skipping a
cascade delete would leave orphaned job and review records with no signal — the
worse failure mode.

**Trade-off.** Silent degradation on reads means callers cannot distinguish
"no jobs exist" from "jobms is down." This is acceptable for a read aggregation
but would not be acceptable if the caller needed to act on absence. The contract
is documented but not surfaced to the API response.

→ See [ADR 0001](../adr/0001-feign-fallback-contract.md)

---

## 2. Rating-event observability: warn-and-ack for a missing company

When a review event arrives but the target company no longer exists, the JPQL
`UPDATE` matches zero rows. The original code discarded this return value,
making the drift invisible in production.

**Rationale.** A zero-row update on a delete event is an expected race: the
company was removed between the review event being published and being consumed.
In an eventually-consistent system this is not an error, but it must be
observable. The fix logs a `WARN` and acks the message so it does not requeue.

**Trade-off.** The message is acked even on a genuine transient failure (e.g., a
database hiccup that briefly made the company invisible). The correct long-term
behaviour — ack stale events, nack transient failures — requires a dead-letter
queue and distinguishing exception types at the listener. Both are deferred (see
Future Considerations below).

→ See [ADR 0002](../adr/0002-rating-event-missing-company.md)

---

## 3. Idempotency floor-clamp for RabbitMQ at-least-once delivery

RabbitMQ guarantees at-least-once delivery. A `review.deleted` event can arrive
more than once. On the second delivery the company row exists, the `WARN` guard
does not fire, and without a floor the JPQL would drive `reviewCount` and
`ratingSum` below zero — silent data corruption.

**Rationale.** The floor is applied inside the JPQL `UPDATE` itself, as close to
the data as possible, so it holds regardless of which code path triggers the
update. It is not the caller's responsibility to check preconditions before
calling; the invariant is enforced at the query level.

**Trade-off.** The clamp is silent: it prevents corruption but does not log when
it fires. An operator watching metrics cannot tell whether a duplicate delivery
was received and clamped, or whether no duplicate arrived. This is a known
limitation. Adding an explicit `WARN` when the clamp activates (detectable by
checking whether the pre-update values were already at zero) is deferred.

Full idempotency — preventing any event from being processed twice regardless of
type — requires a message-ID deduplication table. That is the correct long-term
solution and is deferred (see Future Considerations).

→ See [ADR 0002 — floor-clamp update](../adr/0002-rating-event-missing-company.md#update--limitation-found-in-pr-review)

---

## Future Considerations

These items were identified during the exercise and deliberately deferred. They
are design decisions, not implementation backlog.

**Dead-letter queue configuration.** Without a DLQ, any nack from a listener
creates an infinite retry loop. Configuring a DLQ is a prerequisite for
distinguishing transient failures from stale events in the warn-and-ack path
(see §2 above).

**Full message deduplication.** A persistent store of processed message IDs
(outbox pattern or a deduplication table) would make all listener paths
idempotent against duplicate delivery, not just the floor-clamped delete path.
This requires a schema migration and changes across all three listener methods.

**Job-orphan cascade on company delete.** When a company is deleted, `companyms`
publishes a `company.deleted` event and `reviewms` removes its reviews. `jobms`
currently has no listener for this event. Jobs referencing a deleted company
remain in the database as orphans. The cascade behaviour — delete, nullify, or
tombstone — is a business decision not yet made.

**Clamp observability.** The floor-clamp in `updateRatingAtomically` corrects
silently. A future improvement would log a `WARN` when the clamp activates,
making duplicate delivery visible in the application log alongside the
existing zero-row warn.
