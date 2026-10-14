# Asset folders and library scanning

This document is the source of truth for the user-managed media folder and the scanner. Keep implementation, tests, and user-facing help aligned with this layout.

## User-visible folder

KaraokeHome should create or guide the user to create a folder named `KaraokeHome` on first launch. The folder should be easy to find in a file manager and should not require internet access.

**Android storage note:** Android's scoped-storage rules mean an app cannot reliably create an arbitrary public folder during installation. Create the folder on first launch using an allowed storage location, or use Android's Storage Access Framework to let the user select a location and grant persistent access. Do not request broad storage access when a narrower supported option is sufficient. Show a clear setup message if folder access is not granted.

Recommended layout:

```text
KaraokeHome/
├── songs/        # MP4 karaoke videos, MIDI, KAR, ZIP song archives
├── bgvs/         # Optional background videos used during MIDI/KAR playback
├── fonts/        # Optional user-provided fonts
├── soundfonts/   # SoundFonts such as .sf2 used by MIDI playback
├── assets/       # Other optional user-provided resources
└── data/         # App-generated index and metadata; do not put source media here
```

The app may keep private caches and technical databases in its own app-private storage. The public `data/` folder is only for generated metadata that the user may reasonably want to inspect or back up; never make source media depend on this generated data.

## What belongs in `songs/`

Recognize these extensions case-insensitively:

- `.mp4`: karaoke video. Play the original video full-screen with its own audio; do not draw BGV overlays over it.
- `.mid` and `.midi`: MIDI songs.
- `.kar`: karaoke MIDI songs, including embedded lyric events where supported.
- `.zip`: archives to inspect for supported `.mp4`, `.mid`, `.midi`, and `.kar` entries.

A ZIP file is a container, not automatically a playable song. Index supported entries inside it without extracting duplicate copies into `songs/`. Keep an archive-entry identity (archive URI/path plus entry path) so two entries with the same filename are not confused. Ignore directories and unsupported file types. Protect the scanner against malformed archives, path traversal entries, excessive entry counts, and oversized uncompressed content. Never extract archive paths directly to disk without validating them.

Nested ZIP handling should be explicitly decided and tested; the initial implementation should scan one archive level only unless nested archives are deliberately supported.

## Scanning behavior

1. On first launch, create the folder tree where Android permits, or guide the user through selecting a folder using the Storage Access Framework. Explain the expected folder structure in setup.
2. Scan the selected `KaraokeHome/songs/` and supporting folders. Do not scan the entire device indiscriminately.
3. Include supported files from subfolders under `songs/` unless the product explicitly disables recursion. Avoid following symlinks or revisiting the same document URI.
4. Treat extensions case-insensitively. Use content/provider metadata and safe parsing rather than trusting a filename alone.
5. Build a unified song catalog for MP4, MIDI, KAR, and supported ZIP entries. Keep the original file/entry available for playback; don't transcode or rewrite users' files.
6. Preserve existing track numbers when present. Assign stable unused numbers to unnumbered songs, persist assignments in app metadata, and flag duplicate numbers rather than silently renumbering existing tracks.
7. Re-scan when the user requests it and when supported platform change notifications indicate that the library changed. Also provide a manual rescan action.
8. Keep scanning off the UI thread, report progress for large libraries, and handle unreadable or damaged files without crashing the app.
9. Keep the index rebuildable from the files. Missing or stale index data must not make the original media inaccessible.
10. On phones and Android TV, use the same library rules. The TV is the playback display; library management and settings should remain outside the clean karaoke playback screen.

## Supporting folders

- `bgvs/`: optional BGV files for MIDI/KAR playback. Match a BGV to a song only through a documented, deterministic matching rule; never apply BGV over MP4 playback.
- `fonts/`: optional fonts. Validate supported formats and use them only when the platform/library supports them.
- `soundfonts/`: `.sf2` files. Let the user or settings choose the active SoundFont; define a safe fallback when none is available.
- `assets/`: other optional files only when a specific feature consumes them. Avoid treating arbitrary files as executable or loading them without validation.

App-bundled assets shipped inside the APK are different from user-managed files in this folder. Document which location each asset type uses and define precedence if the same asset exists in both places.

## Contributor checklist

When changing the scanner, playback, or storage code:

- Update this document if the layout or supported formats change.
- Add tests for extension case, subfolders, duplicate filenames, ZIP entries, malformed archives, permission denial, rescanning, and stable track numbers as applicable.
- Do not commit users' personal song libraries or copyrighted karaoke media.
- Verify licenses and redistribution rights for bundled fonts, SoundFonts, and other third-party assets before publishing them.
- Report Android version, device type, storage location, and media type when filing a bug.
