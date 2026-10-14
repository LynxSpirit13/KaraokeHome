# Contributing to KaraokeHome

Thanks for helping improve KaraokeHome! Contributions of bug reports, code, documentation, and testing are welcome.

## Start here

Before changing code, read:
- [Contributor orientation and intended architecture](docs/CONTRIBUTOR_GUIDE.md)
- [Asset folder layout and scanning rules](docs/ASSET_LAYOUT.md)
- [Third-party asset notices](THIRD_PARTY_NOTICES.md)

These documents describe the intended design. KaraokeHome is early-stage, so confirm behavior in the current source and tests rather than assuming every planned feature is implemented.

## Before opening an issue

- Search existing issues and pull requests first.
- Include your Android version and whether you tested on a phone, Android TV, emulator, or both.
- For playback bugs, include the media type (MP4, MIDI, KAR, ZIP, BGV, or SoundFont), what you expected, what happened, and relevant logs if available.
- For storage bugs, describe how the folder was selected, whether access was granted, and the folder type—not private file contents.
- Never attach copyrighted karaoke media, personal song libraries, passwords, API tokens, or other private information.

## Pull requests

1. Fork the repository and create a focused branch.
2. Explain the problem and the intended behavior in the PR description.
3. Keep changes small where possible and include tests or reproducible verification steps.
4. Confirm the Android build passes, or clearly state what could not be tested.
5. Do not claim a fix was verified on a TV or physical device unless it was actually tested there.
6. Update the contributor and asset-scanning docs if behavior, supported formats, or folder rules change.

## Current high-priority areas

- First-launch storage setup and reliable library scanning.
- Reliable full-screen MP4 playback with original audio.
- MIDI/KAR playback and extraction of embedded lyrics.
- Timed lyric highlighting synchronized with MIDI events.
- Optional background-video playback for MIDI/KAR without obscuring lyrics.
- Phone-to-TV pairing and local Wi-Fi song reservations.
- Clear separation between phone-remote UI and TV karaoke UI.

## Licensing

By submitting a contribution, you agree that your contribution is provided under the project's Apache-2.0 license, unless the contribution explicitly states otherwise. Please only submit work you have the right to contribute. Third-party fonts, SoundFonts, samples, videos, and other assets may have separate terms; verify those terms before adding or redistributing them.
