# KaraokeHome KTV workflow

Use the supplied SongHub screenshot as the visual reference.

## Core behavior
- Enter a song number or search by title/artist.
- Play now without extra navigation, or reserve while the current song continues.
- Automatically start the next reserved song when playback finishes.
- Keep MP4, standalone MIDI/KAR, and ZIP-contained MIDI/KAR songs in one searchable library and queue.
- Play MP4 full-screen with original audio and no forced MIDI/background overlay.
- Keep phone remote and TV queue synchronized over local Wi-Fi without requiring internet after setup.

## ZIP archive support
- Index .mid, .midi, and .kar entries inside ZIP files without extracting the entire archive.
- Extract only the selected entry to temporary storage for playback.
- Never modify source archives; skip corrupt entries safely.

## Testing requirement
A successful build is not proof of functionality. Test song search, numeric selection, MP4 playback, reserve-without-interruption, automatic next-song playback, and ZIP-contained MIDI/KAR playback on a real Android device before marking each feature complete.
