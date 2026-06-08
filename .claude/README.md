# AI-DLC Toolkit — Spring Boot Microservices

A lightweight, governed workflow for using Claude Code as an engineering accelerator.
The human owns all decisions; Claude proposes, drafts, and verifies.

---

## Agents

### analyst
Reads the target service and produces a structured risk report: inter-service
call graph, resilience gaps, security findings, test coverage holes.
**Use it** at the start of any engagement to agree scope before touching code.

### engineer
Implements one scoped change at a time — test first, show the diff, wait for
approval, then apply. Runs the build and reports the result.
**Use it** for writing tests, wiring build plugins (JaCoCo, SpotBugs, OWASP),
and creating CI workflows. Never applies a production change without explicit "okay".

### reviewer
Reviews a diff or PR for correctness bugs, security issues, and unnecessary
complexity. Posts findings as inline comments or a summary report.
**Use it** after every non-trivial change before merging, to get a second opinion
with no shared context from the implementation conversation.

---

## Skills

### analyze-service
Walks the service's entity model, repository queries, Feign clients, messaging
listeners, and resilience config. Outputs a prioritised list of risks with
file/line references.
**Use it** to answer "what are the riskiest parts of this service?" before
writing any tests or making any changes.

### test-first
Given a behaviour to assert, writes the test, runs it, confirms it is green (or
red for the right reason), then stops. No production code is touched unless the
test exposes a real bug — and only then with a recorded ADR.
**Use it** whenever a new test is needed. The rule is: test in hand before diff
in editor.

---

## How to reuse on another service

1. **Copy `CLAUDE.md`** into the target service root. Update the scope section
   (service name, ports, Feign clients) and the Definition of Done gates.

2. **Run the analyst agent first.** Ask it to map the service and produce a risk
   report. Agree with your team which risks to address before writing a line of code.

3. **Use the engineer agent test-first.** For each risk: write the test, run it,
   confirm behaviour, stop. The engineer shows every diff before applying it —
   your "okay" is the gate.

4. **Run the reviewer agent on every PR.** It has no memory of the implementation
   conversation, so its review is independent. Use `--fix` to apply low-risk
   cleanups automatically or `--comment` to post findings to the PR.

5. **Wire the CI workflow.** Copy `.github/workflows/companyms-ci.yml`, update the
   `paths:` filter and working directory to match the new service, and add the
   `NVD_API_KEY` secret. The same gates apply: tests + coverage, SpotBugs report,
   OWASP dependency-check, Gitleaks secret scan.

The pattern scales to any Maven-based Spring Boot service in this repo.
