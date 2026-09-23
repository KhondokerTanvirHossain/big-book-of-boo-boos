# Contributing to Big Book

Thanks for looking. This page is everything you need to go from "I have an hour" to a merged PR.

## 1. Before you start

Read, in this order, about 20 minutes total:

1. [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) — what it does.
2. [docs/BIGBOOK.md](docs/BIGBOOK.md) — the rules. Especially **Principles**: reuse first, `lite` stays a 10-minute install, match Medplum's contract not its defects.
3. The issue you're taking, and the `BB-R-` requirement it names in [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md).

If the issue names an ADR under `docs/adr/`, read that too. ADRs are decisions already made; a PR that contradicts one won't merge, but a PR that proposes a new ADR for a real problem will be read carefully.

## 2. Pick an issue

- Everything is in the [`v0.1 lite` milestone](../../milestone/1). Each issue names what blocks it.
- **Build order** (set 2026-09-21; issue numbers are not the order): #2 → #3 → #4 → #5 → #6 → #8 → #7 → #9 → #12 → #15 → #14 → #10 → #17 → #11 and #13 → #16 → #18 → #22 → #19. `Blocked by` lines agree with it; if you find one that does not, say so rather than working around it.
- [`good first issue`](../../labels/good%20first%20issue) — wire-only work: configuring HAPI or Keycloak, writing a guide, a compose overlay. No architecture risk.
- [`help wanted`](../../labels/help%20wanted) — open and unassigned.
- `glue` — Big Book code. Read the ADR first.
- `wire` — configuration of an upstream component plus a test.

Comment "I'll take this" on the issue. A maintainer assigns it. If nothing happens in three days, ping again — that's on us.

**Verify-first blocks.** Some issues (#5, #7, #8, #9, #10, #12, #16) start with a list of things to confirm about HAPI or Keycloak before writing anything. Do those first and post the answers on the issue. If an answer is "no", stop and say so — it changes the design, and that's a conversation, not a workaround.

## 3. Set up

Prerequisites: **Docker Compose ≥ 2.23.1** and **JDK 21**. `lite` publishes only Big Book's port; the Keycloak admin console is reachable on an operator-only address (`KC_HOSTNAME_ADMIN`, `http://127.0.0.1:9080` in compose). The build tool is **Gradle** (Kotlin DSL), through the wrapper; don't install Gradle yourself.

```
git clone https://github.com/KhondokerTanvirHossain/big-book-of-boo-boos
cd big-book-of-boo-boos
./gradlew build                                      # compiles, runs the tests (they start Postgres and Keycloak in Docker)
BIGBOOK_ADMIN_EMAIL=you@example.org docker compose --env-file deploy/versions.env \
  -f deploy/compose/lite.yml -f deploy/compose/build.yml up -d --build
```

The last command is `lite` with the server image built from your checkout (`build.yml`) instead of pulled. When all three containers are `healthy`, `curl http://localhost:8080/fhir/R4/metadata` answers. `deploy/ci/lite-boot.sh` is the timed boot CI runs; [docs/guides/install.md](docs/guides/install.md) is what users see.

Why Gradle (decided in issue #2): `deploy/versions.env` is the single file where upstream versions are pinned, and the build has to read its HAPI and Spring Boot versions from it. Gradle does that in a few lines of `settings.gradle.kts`; Maven resolves dependency versions before any plugin could load such a file. HAPI's own build is Maven, so its dependency management is imported as a platform (`hapi-fhir-bom`), not inherited.

**A guard is not merged until it has been shown to fail.** An ArchUnit rule, a scanner check, a CI assertion: plant a violation, watch the build go red, remove it, and **state in the PR which violation you planted**. This is not ceremony. `deploy/ci/check-deploy.sh` was proven this way, and in #7 an ArchUnit rule that forbade `catch (Exception)` in the policy path turned out to have matched nothing since the day it was written — it passed with a planted violation sitting in front of it, because a `catch` is not a method call. A rule nobody has seen fail is an assumption with a test's name on it.

**Negative security tests must send raw bytes, not templated URLs.** `TestRestTemplate.exchange(String, …)` treats its first argument as a URI *template* and re-encodes `%2f`, `%2e` and friends, so an allow-list test written that way sends something other than what it reads and proves nothing (issue #5). Pass a `java.net.URI`, as `LiteStackTest.call` does. And assert the objective — that the request never reached the protected component — not the status code, which varies by which layer rejects it.

**Green tests are not enough for a dependency change.** The tests run on Gradle's classpath; the container runs the boot jar, which orders jars by name. Two artifacts that ship the same classes under different coordinates (issue #3: `org.jboss:jandex` 2.x from RESTEasy against Hibernate's `io.smallrye:jandex` 3.x) pass every test and crash the container. Run `deploy/ci/lite-boot.sh` after touching dependencies; CI does.

Upstream versions live in `deploy/versions.env` and nowhere else; don't bump them in a feature PR. Spring Boot follows the Boot line HAPI is built against, so those two move together.

## 4. Do the work

- Branch from `main`: `issue-<n>-short-name`.
- **Small PRs.** One issue per PR. If an issue turns out to be two, say so and split it.
- **Every public behaviour has a test.** Wire issues: an integration test against the `lite` stack. Glue issues: unit tests plus the issue's exit test.
- **The line-count rule.** Big Book's custom code is capped at 5–10k lines total. Every glue PR states its net line change in the description. Issue #7 has a hard limit of 1.6k; others report. **If an issue's actual exceeds its [ARCHITECTURE §3](docs/ARCHITECTURE.md#3-module-map) share (per issue: [§3.1](docs/ARCHITECTURE.md#31-per-issue-glue-estimate)), the PR stops for a decision.** Lines for work already inside the v0.1 estimate are actuals against that share and need no matching removal; the "sign-off line reached" note in `docs/BIGBOOK.md` is about *new scope*, which does need one. If you find yourself writing something HAPI or Keycloak already does, stop — the answer is configuration, not code.
- **Types that make illegal states unrepresentable are not counted against the tripwire** — same footing as tests. A `CompiledPolicy` record, a sealed `Criteria` hierarchy, an ArchUnit rule that stops a layer being imported: these exist so a mistake cannot be written down, and counting them would push work towards the looser, cheaper shape. State them in the PR separately from the glue figure so the numbers stay comparable. **The test is whether removing the line would make an invalid state representable.** If it would, the line is excluded; if it decides *who gets access*, it is counted wherever it lives — a policy default expressed as a factory method is semantics, not an invariant (ruled 2026-09-22).
- **Docs in the same PR.** If your change touches a requirement, an ADR or a guide, the doc change ships with the code. `docs/` is not a separate task.
- **Don't invent.** Where Medplum has a header, extension URL, status code or JSON shape for something, use it verbatim. Where Medplum has a bug, don't reproduce it — record the divergence in `docs/guides/medplum-parity.md`.

## 5. Open the PR

The template asks for: the issue (`Closes #n`), which acceptance criteria are met and how, net glue lines, and any docs touched. Fill it in; reviewers check against the issue's acceptance criteria, not against taste.

CI must be green. CI boots `lite`, runs the tests, and (later) the performance floor (issue #22).

## 6. Review

A maintainer reviews within a few days. Expect questions about scope more than style: "does this need to be code?" is the most common one. Once acceptance criteria are met and CI is green, it merges.

## 7. Things that will get a PR closed

- Adds a container to `lite`.
- Adds a feature that isn't in a `BB-R-` requirement. Open an issue proposing the requirement first.
- Reimplements something HAPI, Keycloak or Postgres already does.
- Adds behaviour specific to any one consuming application. Those live in their own repos.
- Bumps an upstream version outside the versions file.

## 8. Roles

- **Maintainer** (currently Tanvir): merges, cuts releases, owns `docs/`.
- **Contributors**: everyone else. Regular contributors get write access after a few merged PRs.
- Product and architecture decisions are made in the open — ADRs in `docs/adr/`, scope in `docs/REQUIREMENTS.md`. If you disagree with one, open an issue with the alternative and its cost.

## 9. Conduct

[Contributor Covenant](CODE_OF_CONDUCT.md). Short version: be direct, be kind, argue about the code.

## 10. Questions

GitHub Discussions for "how does X work"; an issue with the `question` label if it's about a specific requirement or ADR.
