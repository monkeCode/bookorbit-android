# Signing and CI

## Production identity

The production signing key is separate from the development/debug key. It was
created on November 27, 2026; no APK or stable release was published as part of setup.

| Field | Value |
| --- | --- |
| Application ID | `io.github.monkecode.bookorbitclient` |
| Key algorithm | RSA, 4096 bits |
| Key alias | `bookorbit-release` |
| Keystore format | PKCS12 |
| Certificate SHA-256 | `f666b6b5ff75399e34a07c7dcd373e58ca5b7dc9c33701e1e07222a8ac5c6908` |

The [public certificate](signing/release-certificate.pem) can be shared. Its digest
identifies the APK signing certificate, not the APK file contents or a phone key.
The private key and passwords must never be committed or included in an APK.

### Storage and backup

GitHub Actions holds these secrets only in the **stable-release** environment:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

The public `RELEASE_CERTIFICATE_SHA256` environment variable pins the expected
certificate. The environment permits only the `main` branch, requires approval
from `monkeCode`, and disables administrator bypass of that approval gate. The
owner can approve a release they initiated; this is not independent two-person review.

The local backup is outside the repository:

```text
/home/gera/.cache/bookorbit-client/signing/production/release.keystore
/home/gera/.cache/bookorbit-client/signing/production/credentials.json
```

The directory is owner-only (`0700`); the keystore and credentials are `0600`.
Keep an additional encrypted offline backup of both files. GitHub Secrets cannot
be downloaded as a backup. Do not overwrite or regenerate this identity for a
routine release: installed APKs normally require the same signer for updates.

### Server trust and upgrades

For protected BookOrbit AI routes, use the exact production package/certificate
pair above in the [server trust setup](../README.md#allow-this-client-on-your-own-server).
The README's minimal debug policy patch does not automatically allow production APKs.
Verify the actual APK certificate before changing a server policy; device-key
registration and the other server checks still apply. No production enrollment
or server deployment was performed when this key was created.

Development APKs use `io.github.monkecode.bookorbitclient.debug` and the existing
debug certificate. The production package is a separate installation, not an
in-place update of a debug APK. Both full/fdroid production variants use the same
production identity and replace one another.

## PR checks

Every PR reports a single required **PR checks** result:

- Documentation/screenshots only: local documentation links, changed image signatures
  and CI-policy tests. No Android SDK, Gradle or APK build.
- Code, workflows, build inputs or unknown file types: the same quick checks plus
  lint, unit tests and both Android debug APK builds.
- The final check fails if classification/docs fail or a required Android job fails,
  is cancelled or unexpectedly skips. Only a classified documentation-only PR may
  pass with Android skipped.

New commits cancel older runs of the same PR. Android builds have no push-to-main
trigger. The routing allowlist is in `tools/ci/pr_checks.py`; changes to that file
or workflows themselves require Android checks. Historical documentation snapshots
retain their old relative links and are excluded from the maintained-link check.
External URLs are not fetched by documentation CI.

Ordinary PR builds use temporary runner signing keys and cannot update installations
signed by the persistent development or production certificate. No signing secrets
are inherited by the PR/reusable build workflow.

## Releases

**Debug Prerelease** is a separate manual workflow on `main`. It verifies merged-PR
provenance and runs the full Android checks/build, even for a documentation commit.
A separate `debug-release` job waits for owner approval, signs without running
repository build scripts, and publishes the APKs with checksums. This environment
holds only the persistent development key, never the production key.

The stable **Android Release APK** workflow uses **stable-release**. It is manual
or called by the opt-in stable-release workflow, requires an existing `vX.Y.Z`
release/tag whose exact commit came through a merged main PR, and builds both
signed APKs plus the full AAB after environment approval. Play publishing remains
separate and is not enabled by creating the signing key.

Review the source commit and workflow before approving any release environment.
An approved malicious workflow from `main` could export secrets. Environment gates
are not an external signing service or hardware-backed key isolation, and repository
administrators can change the protection settings themselves.
