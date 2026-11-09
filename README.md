# Yip

An unofficial Twitch client for Android with 7TV, BetterTTV and FrankerFaceZ built in.

Watch live streams, VODs and clips, chat with every third-party emote, see 7TV name paints and badges, and set almost everything up the way you like it.

> Yip is not affiliated with Twitch or Amazon. It uses Twitch's official APIs where they exist and Twitch's private web API where they don't.

## Features

**Watching**
- Live, VODs (with chapters and resume), clips
- Download VODs and clips to watch offline
- Library: full watch history and downloads; Continue watching skips finished videos
- Quality picker, separate defaults for Wi-Fi and mobile data, remember per channel
- Low-latency, balanced or stable buffering, with live latency shown on the player
- Stats overlay: delay, resolution, bitrate, network speed, buffer and dropped frames
- Background audio, audio-only mode, picture-in-picture, mini-player
- Sleep timer: fixed times or when the stream ends
- Clip the last 30 seconds and share the link
- Notification controls: play, pause and mute without opening the app
- Swipe for brightness and volume, double-tap to seek, pinch to fill
- Landscape chat as an overlay, side panel or hidden
- Multi-view: up to four streams with chat for each, grid or focus layout. Start it from any stream, tap one to hear it

**Chat**
- 7TV, BTTV and FFZ emotes with live 7TV updates, personal emotes and zero-width overlays
- 7TV name paints (incl. animated) and badges, FFZ custom mod/VIP badges
- Badges from BTTV, FFZ, FFZ:AP, Chatterino, Chatterino Homies (incl. custom) and Moltorino
- Recent messages on open, replies, mentions, first-message tags, sub/raid/redemption notices
- Filters sheet in chat: hide bots in one tap, block words or /patterns/, hide users
- Highlights for keywords and users
- Emote card: long-press an emote to see it large, its source and creator, and favourite it
- Pronouns next to names, from pronouns.alejo.io
- Inbox: every mention from chats you opened, plus whispers (read and reply)
- Search a chat by word or `from:name`
- Private nicknames and notes on chatters
- Raid prompt: jump to the channel being raided in one tap
- Poll, prediction and Hype Train cards above chat (view only)
- Pings for mentions, keywords and highlighted users when that chat isn't on screen
- Emote picker (Favourites, Recent, Twitch, 3rd Party), emote and @name suggestions, Tab completion on keyboards
- Moderation: timeouts, bans, deletes and slash commands; AutoMod holds in your own chat and channels you moderate
- VOD chat replay, synced to playback

**Everything else**
- Following with pinned favourites at the top, browse, categories, search, channel pages (videos, clips, schedule)
- Hide channels or categories from browse, filter by language
- Long-press a stream for quick actions: pin, multi-view, hide
- Go-live notifications for everyone or just pinned favourites, with muting and quiet hours
- Multiple accounts
- Stream manager for your own channel: title, category, tags, markers, ads, unban requests, who's in chat
- Updates itself from GitHub releases (checksum-verified)
- Themes: system, light, dark, OLED black, eight accents including Material You
- Home screen widget and Quick Settings tile showing who's live
- Tablet and foldable layout: player and chat side by side
- Accessibility: high-contrast chat, screen reader labels, controls stay up with TalkBack
- Searchable settings with import and export
- Move settings to another device by QR code or file
- "What's new" after each update

**Opt-in and experimental** (all off by default)
- Ad handling: swaps to an ad-free stream copy during ad breaks, or uses proxy servers you add yourself
- Channel points balance and auto-claim, one-tap follow (needs the optional twitch.tv connection)

## Building

Requirements: JDK 21 and the Android SDK (API 37).

1. Register an application at [dev.twitch.tv/console](https://dev.twitch.tv/console/apps):
   - Client type: **Public**
   - OAuth redirect URL: `https://yip.sinist3r.lol/auth`
2. Create `local.properties` in the project root:
   ```properties
   sdk.dir=/path/to/Android/Sdk
   twitch.clientId=your_client_id
   yip.configPublicKey=base64_key_from_the_watcher
   ```
3. Build:
   ```bash
   ./gradlew assembleDebug        # debug APK
   ./gradlew testDebugUnitTest    # unit tests
   ./gradlew assembleRelease      # release APK (needs signing, below)
   ```

Without `yip.configPublicKey` the app ignores the remote config and uses its built-in defaults.

### Signing releases

Create `keystore.properties` in the project root (never commit it):

```properties
storeFile=/path/to/yip-release.jks
storePassword=...
keyAlias=yip
keyPassword=...
```

Or set `YIP_KEYSTORE_PATH`, `YIP_KEYSTORE_PASSWORD`, `YIP_KEY_ALIAS` and `YIP_KEY_PASSWORD`.

Then put the certificate's SHA-256 fingerprint into `watcher/deploy/web/yip.sinist3r.lol/.well-known/assetlinks.json` so browser sign-in can return to the app:

```bash
keytool -list -v -keystore yip-release.jks -alias yip | grep SHA256
```

## Server setup

Yip reads a small signed file that says which Twitch client ID and query versions to use. When Twitch changes something, the server updates the file and every copy of Yip picks it up within an hour, with no app update needed.

The watcher (`watcher/`) checks twitch.tv every 30 minutes with a headless browser, confirms the new values work, signs them with an Ed25519 key, and writes `playback.json`. The app only accepts files signed with that key.

### Install the watcher

On the server (Debian/Ubuntu shown; Arch notes below):

```bash
sudo apt install python3-venv
cd watcher/deploy
sudo ./install.sh /var/www/config.sinist3r.lol/yip/playback.json
```

This creates a `yipwatch` system user, installs everything under `/opt/yip-watcher`, generates the signing key, adds the cron job and runs it once. Copy the `yip.configPublicKey=...` line it prints into the app's `local.properties`.

On Arch, install the browser dependencies first: `sudo pacman -S nss atk at-spi2-core libcups libdrm libxkbcommon libxcomposite libxdamage libxrandr mesa pango alsa-lib`.

Useful commands:

```bash
# run now and show what changed
sudo -u yipwatch PLAYWRIGHT_BROWSERS_PATH=/opt/yip-watcher/browsers \
  /opt/yip-watcher/.venv/bin/python /opt/yip-watcher/yip_watcher.py run -v --force

# check the published file
/opt/yip-watcher/.venv/bin/python /opt/yip-watcher/yip_watcher.py verify \
  /var/www/config.sinist3r.lol/yip/playback.json --key /opt/yip-watcher/keys/config_ed25519.pem

# show a notice in every app's About screen
... yip_watcher.py run --message "Playback is being fixed, hang tight"

tail -f /var/log/yip-watcher/watcher.log
```

Keep `/opt/yip-watcher/keys/config_ed25519.pem` private and backed up. If it leaks, run `keygen --force` and ship an app update with the new public key.

### Apache

Two subdomains, both with HTTPS:

| Subdomain | Serves |
| --- | --- |
| `config.sinist3r.lol` | `/yip/playback.json` from the watcher |
| `yip.sinist3r.lol` | `/auth` (browser sign-in return page) and `/.well-known/assetlinks.json` |

```bash
sudo cp watcher/deploy/apache/*.conf /etc/apache2/sites-available/
sudo mkdir -p /var/www/yip.sinist3r.lol
sudo cp -r watcher/deploy/web/yip.sinist3r.lol/. /var/www/yip.sinist3r.lol/
sudo a2enmod ssl headers
sudo a2ensite config.sinist3r.lol yip.sinist3r.lol
sudo certbot --apache -d config.sinist3r.lol -d yip.sinist3r.lol
sudo systemctl reload apache2
```

If you change the script in `auth/index.html`, update its hash in the Content-Security-Policy line of `yip.sinist3r.lol.conf`.

## Project layout

| Module | What's in it |
| --- | --- |
| `app` | Activity, navigation, notifications, picture-in-picture |
| `core:model` | Data classes, including the full settings schema |
| `core:network` | Twitch Helix, GQL, OAuth, EventSub and IRC chat, 7TV (API v4, EventAPI), BTTV/FFZ, signed config |
| `core:data` | Repositories: accounts, browsing, playback, emotes, chat pipeline |
| `core:playback` | Media3 player, background service, playlist hook |
| `core:datastore` | Settings and encrypted account storage |
| `core:database` | Watch history, emote cache, notification state |
| `core:designsystem` | Theme, cards, chat renderer, 7TV paints |
| `feature:browse` | Following, browse, search, categories, channels |
| `feature:watch` | Player screen, chat, emote picker, multi-view |
| `feature:settings` | Settings, accounts, sign-in |
| `watcher` | Server script that keeps the playback config current |

## Security notes

- No client secret ships in the app. Sign-in uses Twitch's device code flow, or the browser with a CSRF state check.
- Tokens are encrypted with an Android Keystore key and excluded from backups.
- The remote config is ignored unless its Ed25519 signature checks out.
- HTTPS only; cleartext traffic is disabled.
- Logs never include tokens, and stay on the device.

## Licence

GPL-3.0. See [LICENSE](LICENSE).
