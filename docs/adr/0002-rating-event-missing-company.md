# ADR 0002 — Rating event for a missing company: warn-and-ack

**Date:** 2026-06-08
**Status:** Accepted

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
