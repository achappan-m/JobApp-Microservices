# ADR 0001 — Feign fallback contract: silent degradation on reads, loud failure on deletes

**Date:** 2026-06-09
**Status:** Accepted

## Context

`companyms` calls two downstream services synchronously via OpenFeign:

- `JobClient` — `getJobsByCompany`, `deleteJobsByCompany`
- `ReviewClient` — `getReviewsByCompany`, `deleteReviewsByCompany`

Every call is wrapped with Resilience4j `@CircuitBreaker`, `@Retry`, and
`@RateLimiter` (instances `jobBreaker` / `reviewBreaker`). When all retry
attempts are exhausted or the circuit is open, Resilience4j invokes the declared
fallback method. We needed to define what each fallback should do.

`CompanyNotFoundException`, `FeignException$NotFound`, and
`IllegalArgumentException` are configured as ignored exceptions: they bypass
the circuit breaker and retry entirely and propagate directly to the caller.

## Decision

Apply an asymmetric fallback contract based on the consequence of failure:

**Read paths — silent degradation.**
`getJobFallback` and `getReviewFallback` return `List.of()`. The company detail
page renders without the jobs or reviews section rather than returning a 5xx to
the user. A partial response is more useful than a hard failure for a read
aggregation.

**Delete paths — loud failure.**
`deleteJobFallback` and `deleteReviewFallback` log an ERROR and throw
`RuntimeException`. Silently swallowing a failed delete would leave orphaned job
or review records with no signal and no retry, which is the worse failure mode.

## Consequences

- **Caller impact on reads:** a caller cannot distinguish "no records exist"
  from "downstream is unavailable." This is acceptable for a display aggregation
  but would not be acceptable if the caller needed to act on absence.

- **Caller impact on deletes:** the `DELETE /companies/{id}` endpoint returns
  a 5xx when either downstream is unavailable. The company row is not deleted.
  This is intentional: it is safer to refuse the delete than to leave
  inconsistent data.

- **Known inconsistency:** `getJobFallback` logs an ERROR before returning the
  empty list; `getReviewFallback` returns silently with no log. Both return the
  same empty list, but the job fallback is observable in logs and the review
  fallback is not. This should be unified — either both log or neither does.

- **Test lock:** the asymmetric contract is asserted in
  `JobClientServiceImplTest` and `ReviewClientServiceImplTest`. Any change to
  fallback behaviour must update those tests first.
