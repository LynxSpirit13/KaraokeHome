# Contributor orientation

This guide explains how to approach KaraokeHome before changing code. The project is early-stage, so some behavior below describes the intended design, not a claim that it is already implemented. Check the current source and test on the target device before assuming a feature exists.

## Product in one minute

KaraokeHome is intended to be a hybrid karaoke system:

- **TV screen:** plays the selected song. Karaoke playback should stay visually clean; queue management, settings, and track-number administration belong outside the active playback screen.
- **Phone screen:** searches the library, reserves songs, and acts as a remote while the TV keeps playing.
- **Library:** one catalog and queue for MP4, MIDI, KAR, and supported entries inside ZIP archives.
- **Offline-first:** phone and TV should communicate over the local network without requiring internet access.
- **Playback rules:** MP4 uses its own video and audio full-screen. MIDI/KAR produces instrument audio, supports embedded lyrics when implemented, and may use an optional BGV. A BGV must not cover an MP4 video.

These are product goals. Treat unfinished or unverified features as work in progress.

## Read these in order

1. [README](../README.md) — project status, build notes, and scope.
2. [Asset folder layout and scanning](ASSET_LAYOUT.md) — where user files go and how they should be indexed.
3. [CONTRIBUTING](../CONTRIBUTING.md) — issue reports, pull requests, and verification expectations.
4. [Third-party notices](../THIRD_PARTY_NOTICES.md) — license/provenance cautions for bundled assets.

## The intended data flow

```text
First launch
    |
    v
Choose/create accessible KaraokeHome folder
    |
    v
Create/check songs, bgvs, fonts, soundfonts, assets folders
    |
    v
Scan files and supported ZIP entries
    |
    v
Build/rebuild a catalog with stable song identities
    |
    +------> Phone: search / reserve / remote controls
    |
    +------> TV: resolve selected item and play it
                     |
                     +-- MP4: original video + original audio
                     |
                     +-- MIDI/KAR: MIDI synthesis + lyrics when supported
                                   + optional BGV
```

This diagram describes the intended architecture, not a verified implementation map. When editing the actual app, trace the existing call path in source and update this guide if the design changes.

## Keep responsibilities separate

Prefer clear boundaries between these responsibilities, even if the current prototype has not yet separated them into dedicated classes:

- **Storage setup:** requests/selects permitted folder access and creates expected directories.
- **Scanner:** discovers candidate files and ZIP entries, handles errors, and emits metadata. It should not own UI or playback.
- **Catalog/index:** provides stable IDs and searchable metadata. It must be rebuildable from source files and must not be the only copy of a user's content.
- **Playback resolver:** maps a catalog item to a real file or archive entry.
- **Playback engine:** handles MP4 separately from MIDI/KAR; optional BGV is only relevant to MIDI/KAR.
- **Phone/TV UI:** displays controls appropriate to each screen and uses shared library/queue behavior rather than implementing independent scanners.
- **Network layer:** sends remote commands and song reservations over local Wi-Fi. Playback should continue if the phone disconnects.

Avoid introducing duplicate scans, UI-thread file I/O, hidden file moves, or media rewriting as a side effect of indexing.

## File identity and track numbers

Do not use a display title or bare filename as the only identifier. Two folders or ZIP archives can contain entries with the same name. A stable identity should distinguish ordinary files from archive entries and remain stable across rescans when the underlying item has not changed.

Track numbers are user-facing identifiers, not array positions. Preserve known assignments, allocate unused numbers for new items according to a documented rule, and surface collisions instead of silently changing existing assignments.

## Android storage and permissions

Do not assume Android allows an app to create a public shared folder during installation or read every path on the device. Use an Android-supported storage location or the Storage Access Framework, persist granted access when supported, and provide a useful setup/recovery message when permission is denied or revoked. Do not ask for broader access than the feature needs.

## ZIP scanning safety

ZIPs are containers. Index supported entries; don't extract them just to make scanning easier unless a concrete implementation requirement calls for it. Validate archive paths and protect against malformed archives, path traversal, huge entry counts, and extreme uncompressed sizes. Don't trust file extensions as proof that content is valid. Decide explicitly whether nested ZIPs are supported; initial behavior should be one level unless tests and product requirements say otherwise.

## Change checklist

Before submitting a scanner, storage, catalog, playback, or UI change:

- [ ] Identify which responsibility the change belongs to.
- [ ] Preserve the source media; don't transcode, rename, move, or delete user files during a scan.
- [ ] Handle missing folders, empty libraries, duplicate filenames, inaccessible files, and permission revocation.
- [ ] Keep scanning and media parsing off the UI thread.
- [ ] Add tests for supported formats and failure cases where feasible.
- [ ] Update [ASSET_LAYOUT.md](ASSET_LAYOUT.md) and this guide if behavior or folder rules change.
- [ ] Run the Gradle build and report the result honestly.
- [ ] Distinguish automated build success from real-device verification.
- [ ] Verify phone and Android TV behavior separately when the change affects either interface.
- [ ] Confirm licenses before adding fonts, SoundFonts, sample banks, videos, or other third-party assets.

## Useful bug report template

Include:
- Device type: phone / Android TV / emulator
- Android version:
- App commit or build:
- Storage setup: selected folder type and whether access was granted
- File type: MP4 / MIDI / KAR / ZIP / BGV / SoundFont
- Steps to reproduce:
- Expected behavior:
- Actual behavior:
- Logs or screenshots, with private information removed:

Never include copyrighted song files or a private music library in a public issue.
