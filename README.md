# KaraokeHome

KaraokeHome is an early-stage Android karaoke project intended to support a phone remote and an Android TV playback screen, with local song-library browsing and karaoke playback.

## Project status

This project is a work in progress. The default `main` branch currently contains an early prototype. A separate repair branch may contain experimental playback work; treat it as unfinished until the build and real-device behavior have been verified.

Planned capabilities include:

- Full-screen MP4 karaoke playback using the video's own picture and audio.
- MIDI/KAR playback with embedded lyrics and optional background video.
- One searchable song list and queue for supported media types.
- Phone remote for searching and reserving songs while TV playback continues.
- Local Wi-Fi operation without requiring an internet connection.
- Separate, purpose-built interfaces for phone and Android TV.

## Media folder and scanning

The intended user-managed folder is `KaraokeHome/`, with `songs/`, `bgvs/`, `fonts/`, `soundfonts/`, `assets/`, and app-generated metadata under `data/`. Put MP4 videos, MIDI files, KAR files, and ZIP archives containing supported songs in `songs/`. The scanner should index supported MIDI/KAR/MP4 entries inside ZIP archives without needlessly extracting duplicate files.

Read [Asset folder layout and scanning](docs/ASSET_LAYOUT.md) before changing storage or scanner behavior. Android generally cannot create an arbitrary public folder at installation time; setup should create it on first launch where permitted or guide the user through selecting a folder and granting access.

## Contributing

We welcome bug reports, documentation improvements, testing, and pull requests. Start with the [Contributor orientation](docs/CONTRIBUTOR_GUIDE.md) and [CONTRIBUTING.md](CONTRIBUTING.md). Please be specific about device type, Android version, media format, expected behavior, and actual behavior. Do not upload copyrighted karaoke media or personal song libraries.

## Build

The project uses Gradle, Kotlin, the Android Gradle Plugin, and Java 17. Pushes to `main` trigger the GitHub Actions Android build workflow. After a successful run, open the workflow run and download the `KaraokeHome-debug-apk` artifact.

## License

Original project source code and documentation are licensed under Apache-2.0; see [LICENSE](LICENSE). Third-party assets may have separate terms. Read [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before redistributing assets.

## Current known limitations

Do not assume MIDI/KAR lyric timing or word-by-word highlighting is implemented, or that MP4/BGV playback works correctly on every device. Those features require verification and contributions.
