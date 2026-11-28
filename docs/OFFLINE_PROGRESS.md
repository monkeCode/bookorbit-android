# Offline positions and narrated EPUB editions

## Position contract

Room schema 12 adds `progress_sync`; migration 11→12 preserves existing reader,
audio, download, annotation and queued-session tables. Coordinates are scoped to
the configured server database, account, book and file. Text CFI/page, audiobook
track/source time and EPUB SMIL fragment/source time remain independent.

Actual navigation checkpoints go to an app-owned serial local writer. Uploads
run separately and first fetch the server position. A shared baseline distinguishes
local-only changes from incompatible changes on both sides; neither maximum page
nor most recent wall clock decides the winner. The root conflict dialog groups
conflicting coordinates by book and offers phone, server or later. Session uploads
do not wait for that decision. Reconnection does not seek an open player.

Server choices invalidate old writer leases; reporting resumes only after the
consumer acknowledges applying the position. EPUB restore resolves the exact CFI;
invalid coordinates remain fenced rather than silently reporting a guessed page.
Initial rendering and narration-follow navigation are not reading-progress writes.
Audio queue loads carry a fresh generation token, including reloading the same
book; cached error checkpoints may only belong to that reporting queue.

## Compatibility and limits

- Text/narration use v3.10.0 `GET/PUT /reading-progress`, separate position kinds
  and the row's `editedAt` safeguard. This server implementation checks before
  saving; it is **not proven atomic compare-and-swap**. A client cannot guarantee
  linearizability between simultaneous writers without a server change.
- Modern audiobooks retain manifest asset identity, manifest revision and
  playback-state `baseRevision`. Missing/ambiguous track identity is not guessed.
- Legacy audio endpoints without conditional writes do not receive automatic
  fallback uploads. Unsupported text endpoints likewise leave checkpoints local.
  This is safety-first behavior, not full synchronization support on old servers.
- Existing dirty cache rows have no historical shared baseline. They are imported
  conservatively when their book is opened. Unopened legacy text rows cannot yet
  be safely grouped and sent. Known other-account mirrored caches are not imported;
  historical rows with no account attribution cannot be conclusively attributed.
- Storage failures retry and warn. Abrupt process death before the queued write
  becomes durable can still lose that write. Session checkpoints retain the existing
  60-second interval, 10-second minimum and 120-second idle-gap cap; no new heartbeat
  or changed idle accounting is implied.

## EPUB audio editions

The audio chooser discovers verified SMIL editions. Downloaded/cached EPUB resources
are reused; there is no new permanent audiobook download and no additional “read
and listen” mode. The existing narration service provides background playback,
audio-only screen, mini-player, source-clock seeking and speed control. “Listen from
here” continues to use exact fragment starts, never text/audio percentage mapping.

EPUB audio accrues LISTEN sessions, not an additional READ session. Native follow
highlighting does not move the saved text position. Server narration markers must
resolve to an unambiguous fragment and source clock in this edition. Unresolvable
markers block playback/reporting; explicit selection can establish a fresh start.
Streaming discovery accepts missing optional `contentVersion`: a validated SMIL
playlist, exact book/file IDs and available file metadata form a deterministic
snapshot identity. Changed fragments, source clocks and resources invalidate that
snapshot. Existing versioned identities are retained. Unchanged server IDs/metadata
still cannot guarantee immutable resource bytes; this is not a byte-level content hash.

The existing **Listen** action always opens the audio chooser; merely opening or
discovering editions does not start MP3 playback. No separate EPUB listening button
is added. The built-in reader streams an undownloaded EPUB instead of fetching the
entire archive; explicit downloads open locally. Reader settings contain only
**Built-in** and **BookOrbit**, including migration from both previous native modes.

## Validation boundaries

Tests use synthetic local books, in-memory/test-owned Room databases, loopback HTTP
and the isolated emulator. Production progress, production configuration and paid
AI inference are outside these checks. Emulator success does not establish audible
hardware playback or real-server multi-device conflict behavior.

### Local evidence (2026-11-27)

- Both `fdroidDebug` and `fullDebug`: assemble, lint and **659 JVM tests per
  flavor passed**. The fdroid instrumentation APK also assembled successfully.
- Node: 31 checks passed; Python tooling: 20 checks passed.
- Isolated emulator, three successful runs per class:

| Class | Local evidence under `.local/instrumentation/` |
| --- | --- |
| `ProgressSyncUiTest` (grouped choice, additive migration) | `20261127T183345749066Z` |
| `NativeAudioPlayerUiTest` | `20261127T183412918122Z` |
| `LocalNativeNarrationTest` | `20261127T183430497399Z` |
| `NativeNarrationPlayerTest` | `20261127T183437543906Z` |
| `NativeNarrationRangeTest` | `20261127T183443646765Z` |
| `CastQueueReplacementTest` | `20261127T183448534992Z` |
| `ProgressQueueTest` (identical-file reload generations) | `20261127T183556317760Z` |
| `ReaderPositionRestoreTest` (shipped EPUB bridge) | `20261127T183618073752Z` |

`AutoMediaSessionTest` was also attempted and **failed** at the voice-playback
timeout (`20261127T183456554347Z`). Logcat reports the sign-in requirement on this
signed-out emulator; the test assumes an authenticated app fixture. It is not a
passing authenticated Android Auto acceptance result, nor proof that the remaining
voice/resumption path is regression-free. No real account was used to bypass it.

### Reader-mode/audio-chooser follow-up (2026-11-28)

- Both flavors: assemble, lint and 665 JVM tests/flavor passed; fdroid test APK
  assembled. Node 31/31 and Python 20/20 passed.
- Unit coverage includes migration of both old native settings, streaming policy
  for undownloaded EPUBs, mixed MP3/EPUB discovery without optional content version
  or archive download, and metadata snapshot changes.
- Three emulator runs each: `AudioEditionDialogTest`
  (`20261128T150024385050Z`), `ReaderPositionRestoreTest`
  (`20261128T150034051059Z`), `NativeNarrationRangeTest`
  (`20261128T150044424028Z`), `NativeAudioPlayerUiTest`
  (`20261128T150049156063Z`). Dialog coverage verifies no MP3 callback on render
  and explicit EPUB selection. The user's actual book/server and installed APK
  were not inspected; these are synthetic regression checks, not device acceptance.
