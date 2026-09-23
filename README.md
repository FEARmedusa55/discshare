# DiscShare (Fabric 26.2, client-side)

Rename a music disc in an anvil with a song tag, put it in a jukebox, and everyone with the mod hears that song. The server doesn't need the mod. Players without the mod just hear the normal disc.

## Disc name tags

| Name the disc...     | Plays                                          |
|----------------------|------------------------------------------------|
| `Song [yt:dQw4w9WgXcQ]` | the YouTube video with that 11-character ID |
| `Song [sc:artist/track]` | a SoundCloud track                         |
| `Song [#a1b2c3]`     | a short code made on the backend page (any link or an uploaded file) |

You can put anything before or after the tag. Anvil names max out at 50 characters.

## 1. Build the mod

1. Install **JDK 25** (e.g. Temurin 25).
2. Download the Fabric example mod for 26.2 (github.com/FabricMC/fabric-example-mod, branch `26.2`, Code, then Download ZIP).
   Copy `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` from it into this folder.
3. In this folder run `gradlew build` (Windows) or `./gradlew build`.
4. The mod is `build/libs/discshare-0.1.0.jar`. Put it in `.minecraft/mods` along with Fabric API.

Testing: `gradlew runClient` launches a dev game.

## 2. Run the backend (on your server)

Needs Python 3.10+, ffmpeg, and yt-dlp.

```
pip install -r backend/requirements.txt
python backend/server.py
```

It listens on port 8080 (open that port on your firewall). Optional env vars are listed at the top of `server.py`. `DISCSHARE_UPLOAD_TOKEN` sets a password for making short codes.

Visit `http://your-server:8080/` to make `[#code]` discs from links or files.

## 3. Point the mod at the backend

Start the game once, then edit `.minecraft/config/discshare.json`:

```json
{ "backendUrl": "http://your-server-ip:8080" }
```

## How jukeboxes get matched to discs

1. **You inserted it:** certain. Your client also tells the backend.
2. **Another player was holding a tagged disc next to the jukebox a moment ago:** a good guess.
3. **The backend knows** (a modded player inserted it earlier): covers walking up mid-song and syncs your position in the song.

Hoppers and redstone aren't supported.

## Roadmap

- [ ] Custom disc textures (backend upload, 128x128 max)
- [ ] `/discshare` client command (reload config, clear cache)
- [ ] Show the song title in the "Now Playing" text
