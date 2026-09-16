# CHANGES-FORK

## Provenance

- Upstream repository: https://github.com/gabriel-sisjr/react-native-background-location
- Upstream package: `@gabriel-sisjr/react-native-background-location`
- Upstream version vendored: `1.0.0` (git tag `v1.0.0`)
- Upstream commit SHA vendored: `670317a740a184bd788fa2e9beafde95ccba2b45`
- License: MIT (see `LICENSE` in this directory, copied byte-identical from upstream)

> Portions of this software are copyright (c) their respective owners as
> noted above. This fork is distributed under the same MIT license as the
> upstream project; see `LICENSE`.

## What was vendored

Only the files needed to build and test the library were copied into
`modules/rn-background-location-sync/`:

- `LICENSE` (byte-identical to upstream)
- `src/` (including `__tests__/`)
- `android/`
- `ios/`
- `BackgroundLocation.podspec`
- `package.json`
- `.gitignore`
- `tsconfig.json`, `tsconfig.build.json`, `babel.config.js`

Excluded (not needed to build/run this fork): `.git/`, `node_modules/`,
`website/` (Docusaurus docs site), `example/` (upstream's example app),
`.github/` (CI workflows), `.yarn/`/`.yarnrc.yml`/`.nvmrc` (upstream's Yarn
toolchain), `README.md`, `CHANGELOG.md`, `BREAKING_CHANGES.md`,
`CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `SPONSOR.md`, `labels.json`,
`lychee.toml`, `sync-labels.sh`, `turbo.json`, `lefthook.yml`,
`eslint.config.mjs`, `context7.json`, `.editorconfig`, `.gitattributes`.

The pristine copy (before any renaming/trimming) is committed separately
from the modifications below, so `git log -p` on the first fork commit is
a faithful diff against upstream.

## Modifications

renamed package to @cashify/rn-background-location-sync; no functional changes yet

Specifically, in this fork:

1. **`package.json`**
   - `name`: `@gabriel-sisjr/react-native-background-location` -> `@cashify/rn-background-location-sync`
   - `version`: reset to `0.1.0` for the fork's own version line
   - `codegenConfig` **left unchanged** (`name: BackgroundLocationSpec`,
     `android.javaPackageName: com.backgroundlocation`) - this is a
     deliberate ruling: native namespaces/codegen name stay stable even
     though the npm package was renamed.
   - Removed scripts that only serve upstream's release/docs/example
     workflows: `example`, `lint`, `clean`, `release`, `docs:dev`,
     `docs:build`, `docs:serve`, `docs:clear`. Kept `test`, `typecheck`,
     and the builder-bob `prepare` (this fork's Metro/App consumption
     relies on the built `lib/` output - see below).
   - Removed `workspaces` (`example`, `website` - both excluded), 
     `packageManager` (`yarn@3.6.1` - we use npm), `publishConfig`
     (we never publish this fork to a registry), and `resolutions`
     (`webpack` pin, only relevant to the excluded docs site).
   - Removed devDependencies that only supported the dropped
     scripts/tooling: `@commitlint/config-conventional`, `@eslint/*`,
     `@evilmartians/lefthook`, `@react-native-community/cli`,
     `@react-native/eslint-config`, `@release-it/conventional-changelog`,
     `commitlint`, `del-cli`, `eslint`, `eslint-config-prettier`,
     `eslint-plugin-prettier`, `prettier`, `release-it`, `turbo`.
     Kept the devDependencies needed for `test`/`typecheck`/`prepare`:
     `@react-native/babel-preset`, `@testing-library/react-native`,
     `@types/jest`, `@types/react`, `jest`, `react`, `react-native`,
     `react-native-builder-bob`, `react-test-renderer`, `typescript`
     (all left at the exact versions upstream pinned - this fork's own
     `npm install`/`npm test`/`npm run typecheck` run against a private
     `node_modules` inside this directory, independent of the app's
     React Native version).
   - Dropped the `commitlint`, `release-it`, and `prettier` config
     blocks (no longer used once the corresponding scripts/deps were
     removed).
   - `jest.modulePathIgnorePatterns`: dropped the `example/node_modules`
     and `website/` entries (those directories no longer exist in this
     fork); kept the `lib/` ignore.
   - Everything else (`main`, `types`, `exports`, `files`, `keywords`,
     `repository`, `author`, `license`, `sideEffects`, `bugs`,
     `homepage`, `peerDependencies`, `jest` preset/testMatch/coverage
     config, `react-native-builder-bob` build config,
     `create-react-native-library` metadata) is unchanged from upstream.

2. **No other changes to any file under `src/`, `android/`, or `ios/`**
   beyond the fixes/additions listed under "Functional changes" below
   (added in later tasks; everything else remains byte-identical to the
   vendored upstream commit). One cosmetic note: a doc-comment example in
   `src/types/notifications.ts` still references the old import path
   (`@gabriel-sisjr/react-native-background-location`) - left as-is
   since it's a comment, not code, and touching it would be a gratuitous
   source diff against upstream for a bring-up task.

3. **Consuming the fork without a registry publish.** Since `main`/
   `exports.default` point at `./lib/module/index.js` (builder-bob's
   output) and Metro's default resolver conditions
   (`unstable_conditionNames: ["react-native"]`) don't match the
   `source` export condition, the app needs `lib/` to exist. We run
   `npm run prepare` (`bob build`) inside this directory to produce it.
   `lib/` is `.gitignore`d (inherited from upstream's `.gitignore`, which
   already excludes it as "generated by bob") and is **not** committed -
   see `docs/upstream-storage.md`'s sibling report
   (`.superpowers/sdd/field-agent-poc-plan/task-2-report.md`) for the
   exact build/verification steps, and the app-wiring commit for how
   Metro is told to watch this directory.

## Test fixes due to rename

None. All upstream Jest tests import the library's own modules via
relative paths (e.g. `../NativeBackgroundLocation`) or mock
`react-native` directly; none snapshot or assert on the package name, so
no test needed changes after the rename.

## Functional changes

### Boot-restart of tracking (Android)

**Gap:** `BootCompletedReceiver.kt` only re-registered geofences after
device reboot (`GeofencingClient` registrations are cleared on reboot).
It did not resume location tracking, even though
`TrackingStateEntity`/`tracking_state` (see `docs/upstream-storage.md`)
already persists everything needed to resume (`isActive`, `tripId`,
tracking options). This is this fork's first functional divergence from
upstream.

**Fix** (`android/src/main/java/com/backgroundlocation/BootCompletedReceiver.kt`):
`onReceive` now also reads the persisted tracking state via
`LocationStorage.getTrackingStateAsync()` and, if `isActive == true` with
a non-null `tripId`, calls `RecoveryWorker.scheduleRecovery(context)` to
resume tracking - the same WorkManager recovery path
`BackgroundLocationModule.onHostResume()` already uses on API 31+, reused
here rather than starting `LocationService` directly. Rationale: a
BOOT_COMPLETED receiver runs with no foreground activity, and Android
15+'s foreground-service background-launch restrictions further limit
which FGS types (including `location`) may be started directly from a
BOOT_COMPLETED receiver. `RecoveryWorker` already implements the safe
path for this exact situation (checks `LocationService.isRunning` and the
stop token, re-validates permissions, then calls `setForeground()` before
starting `LocationService`), so no second service-start mechanism was
invented. Geofence restoration is unchanged and still runs unconditionally
on every boot. See `docs/phase2-audit.md` for the full audit and the
Android-15 reasoning.

The extracted `restoreAfterBoot(context)` (`internal suspend fun`) is
what's unit tested; `onReceive`'s `goAsync()`/`PendingResult` machinery
around it is unchanged and isn't itself exercised under this module's
plain-JUnit-plus-Android-stub-jar unit test setup (same reasoning as
`RecoveryWorkerTest` testing `doWork()` directly rather than the
WorkManager scheduling around it).

New tests: `android/src/test/java/com/backgroundlocation/BootCompletedReceiverTest.kt`
(4 tests - resumes tracking when active, no-ops when inactive/no row,
does not crash when the database throws, geofence restoration still runs
in all cases).

**Follow-up hardening** (`android/src/main/java/com/backgroundlocation/RecoveryWorker.kt`,
added in review follow-up on the same task): a review caught that the
"reuse `RecoveryWorker.scheduleRecovery()`" reasoning above doesn't fully
transfer to boot time - `scheduleRecovery()` enqueues a plain
(non-expedited) WorkManager job, which isn't guaranteed to inherit an
FGS-start exemption the way it does when called from
`BackgroundLocationModule.onHostResume()` (an Activity resume). If
`setForeground()` is rejected (`ForegroundServiceStartNotAllowedException`,
an `IllegalStateException` on API 31+), `RecoveryWorker.doWork()` now
catches that specific failure and returns `Result.retry()` immediately,
instead of falling through to the pre-existing generic catch, which gives
up after 3 attempts and clears `tracking_state` - so a persistent
rejection retries via WorkManager backoff rather than silently disabling
tracking. See `docs/phase2-audit.md`'s "Residual uncertainty in this
reasoning" section for the full analysis. New test:
`RecoveryWorkerTest.kt`, `` `retries without clearing tracking state when
foreground service start is rejected` ``.

**Expedited boot enqueue** (`RecoveryWorker.kt`, final-review follow-up):
the boot-triggered recovery request is now **expedited**
(`setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`),
which is the direct root-cause fix for the FGS-start concern above -
expedited work carries a temporary `setForeground()`-start allowance,
which a plain background-enqueued job does not. The request-building was
factored into `RecoveryWorker.buildRecoveryWorkRequest()` so the expedited
flag is unit-testable without a live `WorkManager`. Expedited
`CoroutineWorker`s must also override `getForegroundInfo()` (API < 31
promotes all expedited work to a foreground service using its return value
before `doWork()` runs), so that override was added too. This became
verifiable because `androidx.work:work-testing` is now present in the
module (added for `SyncWorkerTest`); an earlier version of the audit doc
had deferred this fix as untestable, which no longer held. New tests:
`RecoveryWorkerTest.kt`, `` `scheduleRecovery's work request is expedited
so a boot-time enqueue can legally call setForeground` `` (asserts
`WorkSpec.expedited` + out-of-quota policy), and
`RecoveryWorkerForegroundInfoTest.kt` (Robolectric, covers
`getForegroundInfo()`).

### Sync bookkeeping added to Room schema (v2 migration)

**Goal:** Phase 3a lays the storage groundwork for a future upload layer
(next task) without building any uploader code itself: `locations` rows
need to track their own upload lifecycle (not-yet-uploaded / uploading /
uploaded), a per-row idempotency key, and bounded retention so the table
doesn't grow forever if a device stays offline for a long time.

**Schema change** (`android/src/main/java/com/backgroundlocation/database/LocationEntity.kt`):
four columns added to `locations`, all with Kotlin defaults applied at
`LocationStorage.saveLocation`'s single `LocationEntity(...)` construction
point, so that call site needn't know anything about sync bookkeeping:

- `syncState: String` (NOT NULL, default `PENDING`) - one of
  `com.backgroundlocation.sync.SyncState.{PENDING,SYNCING,SYNCED}`. Kept
  as plain string constants (not a Kotlin `enum class`) because `@Query`
  values must be compile-time-constant SQL literals, so DAO queries below
  hardcode `'PENDING'`/`'SYNCING'`/`'SYNCED'` directly rather than
  referencing the enum.
- `attempts: Int` (NOT NULL, default `0`) - upload attempt count.
- `lastAttemptAt: Long?` (nullable) - epoch millis of the most recent
  attempt.
- `clientId: String` (NOT NULL, `UUID.randomUUID().toString()` per row) -
  a stable client-generated id, for the future uploader to dedupe/upsert
  idempotently server-side.
- New index on `syncState` (`getPendingBatch` filters on it).

**Migration** (`android/src/main/java/com/backgroundlocation/database/Migrations.kt`):
`Migrations.MIGRATION_1_2` bumps `LocationDatabase` to version 2 and is
registered via `.addMigrations(...)` - **the pre-existing
`fallbackToDestructiveMigration()` was removed**, since `locations` can
now hold not-yet-uploaded data that must survive an app update; every
future schema bump must add its own migration. The four columns are added
via `ALTER TABLE ... ADD COLUMN`; `clientId` can't get a real UUID via a
SQL `DEFAULT` clause because SQLite forbids non-constant defaults
(`randomblob()` etc.) on `ADD COLUMN`, so it's added with a placeholder
`DEFAULT ''` and backfilled in a separate `UPDATE ... SET clientId =
lower(hex(randomblob(16))) WHERE clientId = ''` (evaluated once per row,
unlike the ADD COLUMN default) - not RFC-4122 UUID-shaped, but the
requirement for pre-existing rows is only "unique-ish", not
spec-compliant. The `@ColumnInfo(defaultValue = ...)` annotations on the
new entity fields are kept in sync with these literal SQL defaults so
Room's schema validation (via `MigrationTestHelper`) passes.

**DAO additions** (`android/src/main/java/com/backgroundlocation/database/LocationDao.kt`):
`getPendingBatch(limit)`, `markSyncing(ids)`,
`markSynced(ids, attemptedAt)`, `revertSyncingToPending(ids)`,
`resetAllSyncingToPending()`, `incrementAttempts(ids, attemptedAt)`,
`countPending()`/`countPendingFlow()`, and retention:
`deleteSyncedOlderThan(cutoffMillis)` plus `trimQueueToMax(maxRows)` (a
`@Transaction` default DAO method composed from three smaller queries -
`countAll`, `deleteOldestSynced`, `deleteOldestPending` - deleting the
oldest `SYNCED` rows first and only falling back to the oldest `PENDING`
rows, a deliberate data-loss guard, once every `SYNCED` row is exhausted
and the table is still over cap).

Two deliberate deviations from a literal reading of the task brief, both
because the alternative wasn't otherwise implementable/testable:
- `markSynced` takes an `attemptedAt: Long` parameter (mirroring
  `incrementAttempts`) so `lastAttemptAt` is set to a caller-supplied,
  deterministic timestamp rather than something computed from SQLite's
  wall clock inside the query.
- `resetAllSyncingToPending()` is defined but **not called anywhere in
  this task** - "crash recovery at startup" only makes sense once the
  uploader (which owns the definition of "startup" for this purpose)
  exists next task; wiring it up now would be scope creep into uploader
  territory.

**Retention constants** (`android/src/main/java/com/backgroundlocation/sync/SyncDefaults.kt`,
new package): `MAX_QUEUE_ROWS = 10_000`, `RETENTION_DAYS = 7`
(`RETENTION_MILLIS` derived). Hardcoded for this phase - no config
plumbing yet, deliberately, per the task brief.

**Retention wiring** (`android/src/main/java/com/backgroundlocation/LocationStorage.kt`):
`init {}` launches a one-shot `runRetentionCleanup()` coroutine (deletes
old `SYNCED` rows, then trims to `MAX_QUEUE_ROWS`) alongside the
pre-existing batch-flush timer. Runs once per `LocationStorage` instance -
i.e. once per `LocationService` start - rather than after every insert:
retention only matters over long timescales, so running it on every 5s
batch flush would be needless DB churn for no benefit. No new scheduling
infrastructure (WorkManager, etc.) was introduced.

**Tests** (TDD - see `task-5-report.md` for RED/GREEN evidence):
- `android/src/test/java/com/backgroundlocation/database/LocationDaoSyncTest.kt`
  (15 tests) - Robolectric + `LocationDatabase.getInMemoryInstance(...)`:
  insert defaults (PENDING/0/null/UUIDv4, and distinct per row),
  `getPendingBatch` ordering/limit/state-filtering, the
  `markSyncing`/`markSynced`/`revertSyncingToPending`/
  `resetAllSyncingToPending`/`incrementAttempts` transitions,
  `countPending(Flow)`, and both retention queries including trim's
  SYNCED-first/PENDING-fallback priority.
- `android/src/test/java/com/backgroundlocation/database/LocationDatabaseMigrationTest.kt`
  (2 tests) - real migration coverage via Room's `MigrationTestHelper`
  under Robolectric (not skipped): seeds v1-shaped rows, runs
  `MIGRATION_1_2`, and validates the result against the exported v2
  schema (`runMigrationsAndValidate(..., validateDroppedTables = true)`),
  asserting new-column defaults, per-row-unique backfilled `clientId`s,
  the new index, and that pre-existing v1 columns/data are untouched.

**Test infra added** (`android/build.gradle`): this module's existing
Kotlin test stack (JUnit + mockk + kotlinx-coroutines-test + turbine) had
no Robolectric/Room-testing support before this task. Added
`org.robolectric:robolectric:4.16.1`, `androidx.test:core:1.6.1`, and
`androidx.room:room-testing:2.6.1` (matching the module's existing Room
version) as `testImplementation`. Also wired the KSP-exported schema
directory (`android/schemas`, already present for `exportSchema = true`)
into the `test` sourceSet's `assets`, and set
`testOptions.unitTests.includeAndroidResources = true` - required for
`MigrationTestHelper` to read the schema JSON from the unit test
classpath (`assets.srcDirs` alone is only honored for instrumented/androidTest
builds, not plain JVM unit tests). New Robolectric tests are pinned to
`@Config(sdk = [34], manifest = Config.NONE)` regardless of this module's
`compileSdk`/`targetSdk` (36), to pin a well-established Robolectric
shadow API level rather than depend on bleeding-edge SDK support in the
Robolectric/android-all pairing.

Verified: all pre-existing 30 Kotlin tests plus the 17 new ones above
(47 total) pass; `:app:assembleDebug` and the module's own `npm test`
(491 tests)/`npm run typecheck` are unaffected (no JS/spec changes in
this task) - see `task-5-report.md` for command output.

### Native bulk-upload sync layer (LocationSyncManager) - Phase 3b

**Goal:** drain the `locations` PENDING queue (schema v2, previous task) to a
configured HTTP endpoint. Pure native - no TurboModule/JS surface here (next
task builds one on top of this layer's public API); driven entirely by
persisted config/state.

**New package** (`android/src/main/java/com/backgroundlocation/sync/`):

- `SyncConfigStore` - plain (unencrypted) SharedPreferences: `syncUrl`,
  `batchSize` (default 500), `maxQueueRows`/`maxAgeDays` (defaults seeded
  from `SyncDefaults`'s constants), `extraHeaders` (JSON-encoded
  `Map<String,String>`), and `authBlocked`. Now the **single runtime
  source of truth** for retention config: `LocationStorage.runRetentionCleanup()`
  reads `maxQueueRows`/`maxAgeMillis` from here instead of from
  `SyncDefaults` directly (whose constants remain only as this store's
  seed defaults - `SyncDefaults.kt`'s kdoc was updated to say so).
- `TokenStore` (interface) + `EncryptedTokenStore` (real impl) - the bearer
  token, in an `androidx.security:security-crypto` `EncryptedSharedPreferences`
  file (AES256-GCM Android Keystore master key), namespaced to this fork.
  See "EncryptedSharedPreferences vs. Robolectric" below for why the
  interface exists.
- `SyncResult`/`SyncStatus` - small result/status data classes (see exact
  fields in `task-6-report.md`, which is the authoritative source for the
  next task's TurboModule).
- `SyncEnvelope.kt` - `SyncLocationPayload`/`SyncRequestBody` (the wire
  JSON shape) plus `LocationEntity.toSyncPayload()`, the `LocationEntity` ->
  wire mapping (plan placeholder A3): `lat`/`lng` from
  `latitude`/`longitude`, `recordedAt` as ISO-8601 UTC
  (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, matching `GeofenceManager`'s existing
  formatter), `provider` defaulting to `"unknown"` and `isMocked`
  defaulting to `false` when the stored value is null, and
  `accuracy`/`speed`/`bearing`/`altitude` left nullable so Moshi's
  reflective adapter *omits* a null field from the JSON entirely rather
  than writing `"key":null` - asserted directly in
  `LocationSyncManagerTest` against the raw request body (key absence, not
  just a null decoded value).
- `SyncNotificationHelper` - posts the 401 auth-block notification,
  mirroring `GeofenceNotificationHelper`'s channel-creation pattern.
- `LocationSyncManager` - the uploader itself (see below).
- `LocationSyncManagerHolder` - context-injected singleton, mirroring
  `GeofenceManagerHolder` exactly (same `getInstance`/`setInstance`/`clearInstance`
  shape), giving `SyncWorker`, `LocationService`'s connectivity callback,
  and `LocationStorage`'s post-insert trigger a shared instance without a
  React Native module reference.
- `SyncWorker` (`CoroutineWorker`) - the 15-minute safety-net trigger (see
  "Triggers" below).

**`LocationSyncManager` design:**

- *Single-flight*: `syncNow(reason)` guards one in-flight `Deferred<SyncResult>`
  behind a `Mutex` - a concurrent second call joins the same run (same
  `SyncResult`) rather than starting a second HTTP pass.
- *Batch loop*: reads up to `batchSize` PENDING rows (oldest first) ->
  `markSyncing` -> POST -> on 2xx `markSynced` and loop again, until the
  queue drains. `clientId` (from schema v2) is the per-row idempotency key
  sent to the server and is never regenerated - a reverted-then-retried
  batch resends the identical `clientId`s, so a partially-processed batch
  can be safely retried/deduped server-side.
- *Failure*: `IOException` / any 5xx / any 4xx≠401 -> `revertSyncingToPending`
  + `incrementAttempts`, loop stops, and a process-lifetime backoff gate
  (`nextAttemptAt`, schedule `[30s, 1m, 5m, 15m, 15m...]`, indexed by a
  consecutive-failure counter that a success resets) blocks further
  attempts until it elapses.
- *401*: `revertSyncingToPending` + `incrementAttempts`, persists
  `SyncConfigStore.authBlocked = true`, posts the notification via
  `SyncNotificationHelper`, and stops - every subsequent `syncNow` is then
  a no-op (`error = "AUTH_BLOCKED"`) until `clearAuthBlocked()` or
  `setToken(nonBlankToken)` clears the flag (`setToken` is the recommended
  single call for the next task's TurboModule: it persists the token *and*
  clears the block in one call; `clearAuthBlocked()` is exposed separately
  for a token-less "retry" path).
- *No `syncUrl` configured*: no-op (`error = "NO_SYNC_URL"`), logged, no
  crash.
- *Crash recovery*: `resetAllSyncingToPending()` (added but deliberately
  unwired in the previous task) now runs once, guarded by an
  `AtomicBoolean`, at the start of the *first* `runSyncLoop` - i.e. on
  first actual use rather than at construction/`init{}` time, so it's
  guaranteed to complete before the very first `getPendingBatch` read
  (an `init{}`-launched fire-and-forget coroutine could otherwise race a
  very-early `syncNow` call).
- HTTP: OkHttp 4.12.0, ~30s connect/read/write timeouts. JSON: Moshi 1.15.1
  + `moshi-kotlin` (`KotlinJsonAdapterFactory`, reflection-based - no KSP
  codegen, per the brief).

**Triggers:**

a. **Location-insert** (`LocationStorage.flushBuffer()`): after a
   successful `insertAll`, fires
   `LocationSyncManagerHolder.getInstance(appContext).triggerSyncIfDue("location-insert")`.
   `LocationStorage` now stores its `context` (previously only used it to
   look up the database) to make this call.
b. **Connectivity** (`LocationService`): a `ConnectivityManager.NetworkCallback`
   registered in `onCreate()`/unregistered in `onDestroy()` fires
   `triggerSyncIfDue("connectivity")` on `onAvailable`.
c. **Safety-net worker** (`SyncWorker`, `CoroutineWorker`): unique periodic
   work, 15-minute interval (WorkManager's minimum), network-constrained,
   enqueued (`ExistingPeriodicWorkPolicy.KEEP`) from `LocationService.onCreate()`.
   `doWork()` calls `syncNow("worker")`, then - the self-cancel logic lives
   entirely inside the worker, per the brief - cancels its own unique work
   *only if* the queue is empty **and** `LocationService.isRunning` is
   false; while tracking is active it stays enqueued regardless of pending
   count. `LocationService.onDestroy()` does **not** separately cancel it -
   that decision is deliberately left entirely to the worker's own next
   scheduled run.

`triggerSyncIfDue(reason)` (used by a and b) is a cheap early-out: it
skips (without even entering the single-flight path) when
`ConnectivityManager` reports no active/internet-capable network, else
fires `syncNow(reason)` on the manager's own background scope. The
authoritative backoff/no-op gates all live inside `syncNow`/`runSyncLoop`
itself, so this is purely an optimization (e.g. so every single location
insert while offline doesn't spawn a coroutine for nothing) - `syncNow`
called directly (trigger c, or a test) gets identical protection.

**`LocationService` wiring:** `onCreate()` now also calls
`LocationSyncManagerHolder.getInstance(this)` (ensure the manager exists),
`SyncWorker.enqueuePeriodic(this)`, and registers the connectivity
callback; `onDestroy()` unregisters it. No other `LocationService`
behavior changed.

**New dependencies** (`android/build.gradle`): `com.squareup.okhttp3:okhttp:4.12.0`,
`com.squareup.moshi:moshi:1.15.1` + `moshi-kotlin:1.15.1`,
`androidx.security:security-crypto:1.1.0-alpha06` (`implementation`);
`com.squareup.okhttp3:mockwebserver:4.12.0` and
`androidx.work:work-testing:2.9.0` (`testImplementation`).

**Manifest** (`android/src/main/AndroidManifest.xml`): added `INTERNET` and
`ACCESS_NETWORK_STATE` permissions (the app's own manifest already
declared `INTERNET`, but the library that actually needs both should
declare them itself).

**EncryptedSharedPreferences vs. Robolectric:** confirmed empirically (not
just anticipated) that `androidx.security-crypto` does not work under
plain Robolectric - `MasterKey.Builder(...).build()` throws
`KeyStoreException: AndroidKeyStore not found` (caused by
`NoSuchAlgorithmException`), since Robolectric's plain-JVM `java.security`
providers don't include a real `AndroidKeyStore` (there's no secure
hardware/TEE to back it in a unit test JVM). Per the task brief's
allowance, this is handled as: `TokenStore` is an interface;
`LocationSyncManagerTest` (which exercises `LocationSyncManager`'s
upload/backoff/auth-block logic, not `TokenStore`'s persistence) uses an
in-memory `FakeTokenStore` test double; and the real `EncryptedTokenStore`
still ships unchanged and is exercised for real construction plus an
assertion that using it surfaces exactly this known, documented
environment limitation (`EncryptedTokenStoreTest`) - not silently skipped.
On a real device/emulator the identical code path succeeds.

**Tests** (TDD - see `task-6-report.md` for RED/GREEN evidence, including
the post-review fix round below): 22 new Kotlin tests -
`android/src/test/java/com/backgroundlocation/sync/LocationSyncManagerTest.kt`
(14 - happy-path batch draining with full envelope/header assertions
against a real `MockWebServer`, exact ISO-8601 formatting, null-field
omission, 500 revert/backoff/retry with `clientId` stability across the
retry, a raw-`IOException` failure, 401 auth-block + notification (via a
Robolectric `ShadowNotificationManager`) + no-op-until-cleared, `setToken`
clearing the block, single-flight, no-`syncUrl` no-op, crash recovery,
crash-recovery retry after an initial failure, a non-`IOException`
`Throwable` from a malformed `syncUrl`, single-flight surviving a
cancelled awaiter, and a gated no-op not clobbering `lastResult`),
`SyncConfigStoreTest.kt` (4, incl. `batchSize` coercion),
`EncryptedTokenStoreTest.kt` (3, see above), and `SyncWorkerTest.kt` (1 -
a real `SyncWorker` via `androidx.work:work-testing`'s
`TestListenableWorkerBuilder` against a real, programmatically-initialized
`WorkManager`, asserting the self-cancel branch; the complementary "stays
enqueued while tracking is active" branch is implemented as designed but
not independently unit tested - see that file's kdoc for why).

**Post-review fix round** (same task, before landing): a review caught
one critical and two important correctness gaps plus three cheap adjacent
fixes, all in `LocationSyncManager.kt`/`SyncConfigStore.kt`:
- The batch step only caught `IOException` around `postBatch` - a
  non-`IOException` `Throwable` reachable from JS-supplied config (e.g.
  `IllegalArgumentException` from `Request.Builder().url(syncUrl)` on a
  scheme-less URL, or from an invalid extra-header name/value) escaped
  uncaught, stranding the batch SYNCING forever (invisible to
  `countPending`/`trimQueueToMax`, and causing `SyncWorker` to wrongly
  self-cancel on `pending == 0`) with no backoff armed and `isSyncing`
  latched `true` forever. Fixed: catch `Throwable` around the batch step
  (rethrowing `CancellationException` untouched) with the same
  revert+increment+backoff+`finishResult` handling as the old
  `IOException` catch; `runSyncLoop`'s status-flow reset moved into a
  `finally`.
- Single-flight ownership belonged to the *awaiter*, not the run: a
  cancelled awaiting caller's own `finally` could still complete a fast,
  non-suspending `Mutex` acquisition and clear `inFlight` while the
  underlying run kept executing in the manager's own scope, letting a
  fresh `syncNow` start a second concurrent HTTP pass over the same rows.
  Fixed by moving the `inFlight = null` clear into the *producing*
  coroutine's own `finally` (which runs to completion regardless of any
  awaiter's cancellation, since it's rooted in the manager's own
  long-lived scope, not the caller's).
- `ensureRecovered()` latched its "done" flag *before* confirming
  `resetAllSyncingToPending()` succeeded, so a single failure at startup
  permanently gave up on crash recovery for the rest of the process.
  Fixed to latch only on success.
- Adjacent minors: `SyncConfigStore.batchSize` now coerces a stored
  zero/negative value to at least 1 (previously a silent, permanent
  no-op, since `getPendingBatch(0)` always returns empty); a gated no-op
  run (no `syncUrl` / auth-blocked / backoff-active) no longer overwrites
  `SyncStatus.lastResult` with itself, so a status UI keeps showing the
  last *real* attempt's outcome; and this section's own new-test count
  (previously miscounted as "27 new" against an itemization that actually
  summed to 17) is corrected above.

**Final-review follow-up (adjacent hardening):** `SyncConfigStore.batchSize`
now coerces to the range `[1, 999]` (the upper bound was added on top of
the earlier `>= 1` floor): a stored value above 999 would exceed SQLite's
default host-parameter limit (`SQLITE_MAX_VARIABLE_NUMBER`, 999 on the
SQLite ~3.9 builds down to `minSdk` 24) the first time a batch's ids are
bound as `IN (:ids)` params, throwing instead of uploading. Covered by a
new upper-bound case in `SyncConfigStoreTest.kt`. The `SyncResult` kdoc was
also corrected (the auth-blocked no-op is `failed = true`, not `false`),
and the TS `SyncResult.httpCode`/`error` types are now `number | null` /
`string | null` to match the native side always writing an explicit `null`
key (a JS-consumer accuracy fix only; the Spec's `forceSync` return shape
was never Codegen-validated — see the "Codegen note" in `src/types/sync.ts`).

Verified: all pre-existing 47 Kotlin tests plus the 22 new ones above (69
total) pass; `:app:assembleDebug` and the module's own JS
`npm test`/`npm run typecheck` are unaffected (no JS/spec changes in this
task) - see `task-6-report.md` for exact command output.

### TurboModule sync API + JS surface (`useSyncStatus`) - Phase 3c/3d

**Goal:** expose the previous task's native `LocationSyncManager` through
the TurboModule spec, add a matching JS surface (functions + a hook), and
wire the POC app's UI to it. Android only; iOS remains unimplemented for
this method set (see "iOS" below).

**Spec** (`src/NativeBackgroundLocation.ts`):

- New inline codegen types: `SyncConfigSpec` (`syncUrl`, optional
  `batchSize`/`maxQueueRows`/`maxAgeDays`, and `headersJson` - a
  JSON-serialized `Record<string,string>`, since Codegen can't express
  maps, mirroring `TrackingOptionsSpec.notificationOptions`). `SyncResult`
  (flat - `uploaded`, `failed`, optional `httpCode`/`error`,
  `pendingAfter`, `timestamp`) lives in `src/types/sync.ts` and is used
  directly in the Spec (no separate `*Spec` twin needed - unlike
  `TrackingOptionsSpec`, every field is already a Codegen-flat primitive),
  the same pattern `Coords`/`TrackingStatus` already use.
- Four new `Spec` methods: `configureSync(config: SyncConfigSpec):
  Promise<void>`, `setAuthToken(token: string): Promise<void>`,
  `getPendingCount(): Promise<number>`, `forceSync(): Promise<SyncResult>`.
- New event (no Spec type - events aren't Codegen-checked, same as
  `onLocationUpdate`/`onGeofenceTransition`): `onSyncStatusChanged`, payload
  = `SyncResult`'s fields flattened, plus `pending` (a friendlier alias of
  `pendingAfter` for a live-status consumer) and `authBlocked` (the
  manager's current auth-block state - included beyond the plan's literal
  "SyncResultSpec + pending" wording because it can change, e.g. cleared by
  `setAuthToken`, without a new sync attempt ever running, which would
  otherwise leave a status UI showing a stale blocked/error state).

**Kotlin** (`BackgroundLocationModule.kt`):

- All four methods delegate to `LocationSyncManagerHolder.getInstance(reactApplicationContext)`
  - the same process-lifetime singleton the location-insert/connectivity/
  worker triggers already share - never a module-owned instance.
  `configureSync` writes fields onto a freshly-constructed
  `SyncConfigStore` (cheap/uncached by design, same `SharedPreferences`
  file the manager's own `SyncConfigStore` reads); `setAuthToken` calls
  `LocationSyncManager.setToken` (not a raw `TokenStore`) per Task 6's
  recommendation, dispatched on `Dispatchers.IO` since `EncryptedTokenStore`
  lazily touches the Android Keystore; `getPendingCount`/`forceSync` call
  the manager's `pendingCount()`/`syncNow("js-force-sync")` on `moduleScope`.
- `onSyncStatusChanged` is relayed by collecting the manager's
  `statusFlow` on `moduleScope` (started/stopped alongside the existing
  location/geofence/notification-action collectors in
  `initialize()`/`invalidate()`/`onHostResume()`/`onHostDestroy()`), guarded
  by `hasActiveReactInstance()` and a try/catch, exactly mirroring
  `handleLocationUpdate`'s existing pattern. No-ops while
  `SyncStatus.lastResult` is still null (before the first sync attempt
  completes this process) - a known, documented limitation of relying on
  `lastResult` for the payload, not a bug (see the method's kdoc).
- **Binding carry-forward from Task 6's review, restated as a code comment
  on `moduleScope` itself**: this module's own coroutine scope
  (`moduleScope`, cancelled in `invalidate()`) must never be confused with
  - or used to cancel - `LocationSyncManager`'s internal scope. The manager
  is a process-lifetime singleton whose sync runs must keep executing
  across TurboModule instance teardown; cancelling a run mid-POST would
  strand its batch in SYNCING permanently, and the run's `finally` clears
  its single-flight guard via a suspending `Mutex.withLock` that can wedge
  under cancellation. This module never constructs its own
  `LocationSyncManager` and never reaches into the holder's instance to
  cancel anything - `moduleScope.cancel()` only tears down this module's
  own promise/event-relay coroutines.

**JS surface** (fork):

- `src/hooks/useSyncStatus.ts`: seeds `pending` via `getPendingCount()` on
  mount; listens for `onSyncStatusChanged` and updates `pending`,
  `lastResult`, `lastError` (`lastResult?.error` convenience accessor), and
  `authBlocked`; exposes a manual `refresh()` (re-fetches `pending`).
  `lastResult`/`lastError`/`authBlocked` have no seed call (the spec has no
  "get current status" query method) - they start at `null`/`null`/`false`
  until the first event arrives.
- `src/index.tsx`: `configureSync`, `setAuthToken`, `getPendingCount`,
  `forceSync` - thin wrappers following the geofencing exports' convention
  (`assertNativeModuleAvailable()` then a direct native call), not the
  older tracking exports' console-warn-and-fallback convention (a silent
  no-op on a misconfigured/unavailable sync layer is worse than a thrown
  error - it would look like sync is running when it never started).
  `configureSync` JSON-serializes `headers` into `headersJson`, mirroring
  `configureGeofenceNotifications`'s inline `JSON.stringify`.
- Types: `SyncConfig`/`SyncResult`/`SyncStatusEvent` in `src/types/sync.ts`;
  `UseSyncStatusResult` in `src/types/hooks.ts` (alongside the other hook
  result types); all re-exported from `src/index.tsx`.
- Tests (TDD - RED captured by stashing just the production files, keeping
  the new test files + extended `setup-minimal.ts` mock in place; see
  `task-7-report.md`): `src/__tests__/sync/api.test.ts` (12, the four
  exported functions), `src/__tests__/hooks/useSyncStatus.test.ts` (7,
  seed/update/refresh/unavailable-module) - 19 new tests, 510 total for the
  module (491 pre-existing + 19 new).

**iOS (at the time of Phase 3c/3d):** was left unimplemented — the four sync
methods existed only in the shared TS spec, so an iOS build would not have
compiled against this fork. **This is now resolved by Phase 5 (below).**

**App wiring** (`app/`): added `react-native-keychain@^10.0.0` (a
TurboModule/New-Architecture-ready release, confirmed via its
`codegenConfig` and a green `:app:assembleDebug` + Metro bundle smoke after
adding it). `app/src/auth/token.ts` (`saveToken`/`loadTokenIntoNative`,
keychain + `setAuthToken`) and `app/src/tracking/sync-config.ts`
(`SYNC_URL` dev default + `configureSyncOnAppStart`) are new; `App.tsx`
calls both once on mount (best-effort - errors are logged, not
UI-blocking) and wires `useSyncStatus()` plus a Force Sync button and a
masked dev token input into `SyncPanel` (replacing Task 3's
"sync layer not built yet" stub). Full details in `task-7-report.md`.

### Real backend contract (Cashify sales-tracker) - replaces placeholder A3

The placeholder A3 wire contract the sync layer was first built against was
replaced with the real Cashify sales-tracker bulk-upload contract once it was
provided. This is **Cashify-specific** and is NOT an upstreaming candidate.

- **Endpoint/body** (`sync/SyncEnvelope.kt`): envelope key `locations` →
  `logs`; per-fix fields are now `{punchType:"RECORD", lat, long, recordedAt,
  locationType}`. `long` (not `lng`) is longitude; `recordedAt` is epoch millis
  as a number (was ISO-8601 string) - `LocationEntity.timestamp` is already in
  that unit, so it is sent verbatim. `punchType` is always `"RECORD"`;
  `locationType` maps the fix's real Android provider (`toLocationType`:
  gps→GPS, network→NETWORK, fused→FUSED, passive→PASSIVE, uppercase
  passthrough otherwise, GPS when absent). The A3 fields `clientId`,
  `accuracy`, `speed`, `bearing`, `altitude`, `isMocked` are **no longer on the
  wire**. `clientId` is still generated and kept on the local row for queue
  bookkeeping/idempotency; agent identity comes from the SSO token server-side.
  `isFromMockProvider` is still captured locally in Room but has no field in
  this contract (a production server wanting the fraud signal would need one).
- **Auth** (`LocationSyncManager.postBatch`): the single `Authorization: Bearer`
  header was replaced by the API's two headers - `x-authorization: Bearer
  <token>` (the client/role token, from `setAuthToken`) and `x-sso-token:
  <token>` (the raw user SSO JWT, no "Bearer" prefix, from the new
  `setSsoToken`). Both are read from the encrypted `TokenStore` so background
  uploads work with JS dead; a 401 on either still triggers the auth-block.
- **New second credential** (`TokenStore`/`EncryptedTokenStore`/
  `LocationSyncManager.setSsoToken`, spec `setSsoToken`, JS `setSsoToken`,
  `useSyncStatus` unchanged): the SSO token is a second key in the same
  encrypted prefs file. The app grew a second dev token field + keychain
  service (`app/src/auth/token.ts`: `saveAuthToken`/`saveSsoToken`/
  `loadTokensIntoNative`).
- **Static headers**: `x-app-version`, `x-app-module: sales-tracker`,
  `x-app-installer: react-native`, `cache-control: no-store` are passed from
  the app via `configureSync({ headers })` (`SYNC_HEADERS` in
  `app/src/tracking/sync-config.ts`), not hardcoded natively.
- **App default `SYNC_URL`** now points at the staging API
  (`https://sales-tracker.api.stage.cashify.in/sales-tracker/location-log/bulk`);
  the mock server (`tools/mock-server/`) and `DEBUG.md` were updated to the
  same `logs`/two-header shape (dedup keyed on `lat,long,recordedAt` since
  there is no `clientId` on the wire).

### iOS sync layer (Phase 5) - Swift/ObjC mirror of the Android sync layer

Brings iOS to parity with Android's sales-tracker sync. **Cashify-specific** (not an
upstreaming candidate). Verified by `xcodebuild` (simulator, Debug) building the app end-to-end
with zero errors; there is no iOS unit-test target in this fork, so correctness was established by
an adversarial code review against the shipped Android reference plus the build.

- **Core Data** (`ios/Database/BackgroundLocationModel.xcdatamodeld/BackgroundLocationModel_v3.xcdatamodel`):
  added `syncState` (default `PENDING`), `attempts`, `lastAttemptAt`, `clientId` (+ a `bySyncState`
  fetch index) to `LocationEntity`. Added **in place to the current v3 model** (not a new version):
  iOS had never shipped or run in this project, so no Core Data store exists on any device to
  migrate — unlike Android, which used a real v1→v2 migration because it had already run on the
  user's device. (If a device had ever run an older v3 build, this would be a store-hash mismatch;
  none has.)
- **`ios/LocationStorage.swift`**: sets `syncState`/`attempts`/`clientId` at insert; throttles
  inserts to ~110s (`SyncDefaults.insertThrottleSeconds` — iOS has no timer-based updates, so
  continuous `distanceFilter: 0` updates are thinned to match the ~2-min contract); an
  `onLocationsPersisted` hook fired after a flush; and the sync DAO surface: `fetchPendingBatch`,
  `markSyncing`/`markSynced`/`revertSyncingToPending` (state-guarded), `incrementAttempts`,
  `resetAllSyncingToPending`, `countPending`, `runRetention` (SYNCED-first trim to 10k rows / 7 days).
- **New Swift (`ios/Sync/`)**: `SyncConfigStore` (a fork-namespaced `UserDefaults` suite),
  `TokenStore` (Keychain, both `x-authorization` and `x-sso-token`, `kSecAttrAccessibleAfterFirstUnlock`
  so background uploads can read them), `SyncModels` (SyncState/SyncResult/`syncLocationType`),
  `SyncNotificationHelper` (auth-blocked local notification), `BackgroundTaskScheduler`, and
  `LocationSyncManager` — the `URLSession` bulk uploader: single-flight serial queue with a
  semaphore-blocked request per batch, the exact sales-tracker envelope (`logs`/`long`/epoch-ms
  `recordedAt`/`locationType`/`punchType`, lat/long parsed String→Double), `x-authorization` +
  `x-sso-token` + static headers, mark-SYNCED-only-on-2xx, backoff 30s/1m/5m/15m, 401 →
  persisted `authBlocked` + notification + stop, crash recovery (`resetAllSyncingToPending` once
  per process), retention (also run once at bootstrap so the cap holds even without a clean drain),
  `NWPathMonitor` connectivity trigger, and a `BGTaskScheduler` safety-net task (~15 min) with an
  expiration handler.
- **Bridge** (`ios/BackgroundLocation.mm`): implemented `configureSync`/`setAuthToken`/`setSsoToken`/
  `getPendingCount`/`forceSync` (conforming to the codegen `NativeBackgroundLocationSpec`, which is
  what makes iOS build again) + the `onSyncStatusChanged` event. A JS-independent `bootstrap()`
  wires the insert hook, connectivity monitor, and background task from **both** the native
  tracking-start and SLC-recovery paths (`ios/LocationManagerWrapper.swift`), so sync works even
  when iOS relaunches the app in the background without JS.
- **App** (`app/`): `Info.plist` gained `NSLocationAlwaysAndWhenInUseUsageDescription`, real usage
  strings, `UIBackgroundModes` (`location`/`fetch`/`processing`), and
  `BGTaskSchedulerPermittedIdentifiers`; `AppDelegate.swift` registers the background task at launch
  (the one app-side hook the fork's BGTask needs — iOS requires registration before launch
  completes); the iOS deployment target was bumped **15.1 → 16.0** (the fork's podspec requires 16).
- **JS/UI**: unchanged — the sync API and `useSyncStatus` were already cross-platform.
- **Known iOS limitation:** a terminated, stationary iPhone cannot sync on a timer (OS design);
  resurrection is via significant-location change, which then triggers a queue-backfill sync.
  Device-side acceptance (background suspend, force-quit + SLC resurrection + backfill) needs a real
  iPhone — see `DEBUG.md`.

## Upstreaming candidates

Per the plan's Phase 6 ("evaluate which pieces... are worth a PR upstream so
the fork can shrink"): two pieces of this fork's functional work have no
Cashify-specific coupling and are plausible candidates for a PR back to
`gabriel-sisjr/react-native-background-location`.

1. **Boot-resume of tracking** (`BootCompletedReceiver` resuming
   `LocationService` via `RecoveryWorker` - commits `869393e`/`21c4e10`).
   This closes a genuine gap in upstream: its own `BootCompletedReceiver`
   already re-registers geofences after reboot but never resumed active
   location tracking, even though upstream's own persisted
   `tracking_state`/`TrackingStateEntity` already has everything needed to
   resume it. Nothing in the fix depends on this fork's later sync work -
   it's a self-contained bug fix built entirely on top of upstream's
   existing persistence model, and a reasonable candidate for a clean PR
   close to as-is. The PR description would need to explain the Android
   15+ `setForeground()`-rejection hardening's rationale (`docs/phase2-audit.md`
   has the full audit), since it isn't obvious without that context.

2. **Generic `syncUrl` bulk sync** (`LocationSyncManager` +
   `SyncConfigStore` + the wire envelope (`SyncEnvelope.kt`) + the four
   TurboModule methods - commits `8efe2eb`/`57f2cfd`/`50f9d48`/`d4e53cc`).
   The core mechanism - drain a `PENDING` queue to a *configured* HTTP
   endpoint with batching/backoff/auth-block/retention - has no
   Cashify-specific coupling: `syncUrl`, `batchSize`, retention, and extra
   headers are already configurable via `configureSync`, and the wire
   envelope is a reasonable generic default rather than anything tied to a
   specific backend contract. What would need work before a real upstream
   PR, though:
   - The wire envelope's exact field names (`lat`/`lng`/`clientId`/
     `recordedAt`/etc., `SyncLocationPayload`) are currently fixed, not
     pluggable - upstream would likely want this overridable rather than
     opinionated, since a given consumer's backend won't necessarily share
     this fork's exact contract.
   - Token storage (`EncryptedTokenStore`) is this fork's own
     `EncryptedSharedPreferences` file, independent of anything upstream
     currently does with credentials - upstream may have (or want) its own
     opinion here worth reconciling rather than just adding a second
     credential store next to whatever it already has.
   - iOS has no mirror of this yet (Phase 5, still pending) - a
     cross-platform library's PR would need both platforms landed, not
     just Android.

**Not** upstreaming candidates: `EncryptedTokenStore`'s exact
keystore/namespacing choices, the specific retention *values* (10,000 rows /
7 days - configurable, but POC-chosen defaults, not upstream-worthy
opinions), and this fork's own package rename all stay fork-specific either
way.

## License integrity

`git log --oneline -- modules/rn-background-location-sync/LICENSE` shows
exactly one commit (`5e3e82f`, the initial vendoring commit) touching
`LICENSE` - it has never been modified since vendoring, and its content
remains byte-identical to upstream's original MIT license (see "Provenance"
above).
