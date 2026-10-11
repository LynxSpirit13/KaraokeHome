# KaraokeHome

A phone-friendly Android karaoke project for Android phones and Android TV.

## Current prototype

- Dark, high-contrast KaraokeHome screen.
- File picker to select a local media file.
- Simple in-memory singing queue.
- Android TV launcher category.
- GitHub Actions workflow to build a debug APK and save it as a workflow artifact.

## Planned features

- MP4 karaoke video playback as the main full-screen content.
- KAR/MIDI playback with optional background video.
- Song library scanning from TV storage and USB drives.
- Search, queue management, and song reservations.
- Phone remote with lyrics and playback status.
- QR-code pairing and local Wi-Fi control without internet.

## Project status

Early prototype. The current screen can select a file and add its displayed name to a temporary queue; **media playback, automatic library scanning, persistent queue storage, and phone-to-TV networking are not implemented yet**.

## Build

Every push to `main` triggers the GitHub Actions workflow at `.github/workflows/android.yml`. After a successful run, open the workflow run and download the `KaraokeHome-debug-apk` artifact.

The project uses Gradle, Android Gradle Plugin, Kotlin, and Java 17.

## License

License to be decided.
