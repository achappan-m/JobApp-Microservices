# ADR 0002 — Rating event for a missing company: warn-and-ack

**Date:** 2026-06-08
**Status:** Accepted — amended 2026-06-08

## Context

When `reviewms` publishes a rating event (`review.created`, `review.updated`,
`review.deleted`), `companyms` handles it in `ReviewEventListener` and calls
`CompanyServiceImpl.updateCompanyRatingOn{Create,Update,Delete}`. Each of those
methods delegates to `CompanyRepository.updateRatingAtomically()`, a single-shot
JPQL `UPDATE` that returns the number of rows affected.

Currently the return value is discarded at all three call sites:

- `CompanyServiceImpl.java:104` — `updateCompanyRatingOnCreate`
- `CompanyServiceImpl.java:118` — `updateCompanyRatingOnUpdate`
- `CompanyServiceImpl.java:138` — `updateCompanyRatingOnDelete`

If the target company has already been deleted, the `WHERE c.id = :companyId`
clause matches nothing, 0 rows are updated, and the event is silently acked.
No log, no metric, no signal of any kind. The drift is invisible in production.

This scenario is not hypothetical: `companyms` publishes a `company.deleted`
event when a company is removed, and `reviewms` acts on it asynchronously. In
the window between the company delete and `reviewms` deleting the reviews, any
in-flight review events will reference a company that no longer exists.

## Decision

Capture the `int` return value of `updateRatingAtomically()` at all three call
sites. If the value is `0`, log a `WARN` message that includes the company ID
and the event type, then return normally so the message is acknowledged.

Treat a 0-row update as a **stale event from a deleted company** — an expected
race condition in an eventually-consistent system, not an error requiring
intervention.

## Consequences

- Silent data drift is replaced with an observable warning in the application
  log. Operators can grep or alert on it.
- The message is acked, so it does not accumulate in the queue or cause
  requeue storms.
- No production behaviour changes for the normal path (company exists, 1 row
  updated).
- A test asserting the warn-and-ack path is required before this change is
  applied.

## Rejected alternative: throw and let the broker retry

Throwing an exception from the listener method would cause Spring AMQP to nack
the message and requeue it. For a genuinely transient failure (e.g., a DB
hiccup) this would be correct. For a stale event, it would requeue forever
because the company will never reappear.

`companyms` currently has **no dead-letter queue** configured in
`RabbitMQConfiguration`. Without a DLQ, a persistent nack creates an infinite
retry loop that saturates the queue and starves other messages.

This alternative should be revisited once a DLQ is configured (see Risk 5 in
the analysis). At that point the right behaviour would be: throw on transient
DB errors (which retry and eventually DLQ), return normally on confirmed stale
events (which ack). Distinguishing the two cases requires catching specific
exception types, which is left as a future improvement.

---

## Update — limitation found in PR review

**Date:** 2026-06-08

### What the first fix missed

The `updated == 0` guard only catches the case where the company row is absent
entirely. It does not protect against a second, equally real problem: RabbitMQ
delivers messages **at-least-once**. The same `review.deleted` event can be
delivered more than once.

On the second delivery of a delete event, the company row still exists. The
`WHERE c.id = :companyId` clause matches, `updated` returns `1`, and the warn
guard does not fire. But the JPQL unconditionally applies `deltaCount = -1`:

- `reviewCount` goes from `0` to `-1`
- `ratingSum` goes negative
- `averageRating` is computed as a negative number

The data is silently corrupted. No log, no metric, no signal.

The original commented-out read-modify-write code in `CompanyServiceImpl`
(lines 129–145) had an explicit `if (count <= 1)` branch that clamped both
`reviewCount` and `averageRating` to zero, making the delete path idempotent
for the last-review case. The refactor to the atomic JPQL removed that floor
without replacing it.

### Decision

Add a floor guard directly in the `updateRatingAtomically` JPQL so that
`reviewCount` and `ratingSum` can never be decremented below zero:

```sql
SET c.ratingSum = CASE WHEN (c.ratingSum + :deltaSum) < 0 THEN 0
                       ELSE c.ratingSum + :deltaSum END,
    c.reviewCount = CASE WHEN (c.reviewCount + :deltaCount) < 0 THEN 0
                         ELSE c.reviewCount + :deltaCount END,
    c.averageRating = ...
```

This makes the delete path idempotent against duplicate delivery: a second
delete event for a company with `reviewCount = 0` leaves the counts at zero
rather than driving them negative. The guard lives in the JPQL, close to the
data, so it applies regardless of which code path calls the method.

A test must be written and must go red before this JPQL change is applied.

### Rejected alternative: full message deduplication

Full idempotency via an outbox or message-ID table would prevent any event from
being processed twice, covering all event types, not just deletes. This is the
correct long-term solution for an at-least-once broker. It is deferred because
it requires a new database table, schema migration, and changes across all three
listener methods — a larger design change that goes beyond the scope of this
exercise. It is recorded here as a future improvement.
