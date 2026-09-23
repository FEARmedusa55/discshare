# DiscShare

Custom music discs for Minecraft 26.2 (Fabric) that everyone with the mod can hear, on **any** server. The server doesn't need anything installed.

## Using it

Rename a music disc in an anvil with a song tag, then put it in a jukebox:

| Name the disc | Plays |
|---|---|
| `Song [yt:VIDEO_ID]` | a YouTube video (the 11-character ID) |
| `Song [sc:artist/track]` | a SoundCloud track |
| `Song [#code]` | a code from the DiscShare website (any link or uploaded file) |

- **Disc texture:** click the button above the anvil and pick a PNG.
- **Portable player:** hold a tagged disc in your off hand and press **P**.
- **Volume:** `/discshare volume 0-100`

Players without the mod hear the normal disc.

## Project layout

| Folder | What it is |
|---|---|
| `src/` | The Fabric mod (client-side only) |
| `backend/` | Small Python server: textures, titles, codes, syncing. Run with `python backend/server.py` |
| `plugin/` | Optional Paper plugin for exact jukebox info (not built by default) |

## Building

Needs JDK 25. Run `gradlew build` (or double-click `build.bat` on Windows). The jar ends up in `build/libs`.

## What the mod downloads and sends

- **Downloads once** (only if not already installed): yt-dlp, ffmpeg and deno from their official GitHub releases, into `.minecraft/discshare-tools`. They turn song links into audio on your PC.
- **Talks to the backend** (`backendUrl` in `config/discshare.json`, default `https://disc.lumaria.us`): the server address you're on, your Minecraft player ID, and the discs you use. It's used for textures, titles, syncing, and the mod icon (turn the icon off with `showModIcons: false`).

## License

MIT. See `LICENSE`.

*Contains AI-generated code.*
