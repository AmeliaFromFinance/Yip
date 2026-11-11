# Yip

An unofficial Twitch client for Android with 7TV, BetterTTV and FrankerFaceZ built in.

Watch live streams, VODs and clips, chat with every third-party emote, and set almost everything up the way you like it.

> Yip is not affiliated with Twitch or Amazon. It uses Twitch's official APIs where they exist and Twitch's private web API where they don't.

## Features

### Watching

- Live streams, VODs and clips, with VOD chapters and resume
- Quality picker with separate defaults for Wi-Fi and mobile data, remembered per channel
- Low-latency, balanced or stable buffering, with the live delay shown, jump to live and auto catch-up
- Rewind a live stream through its VOD, then Go live to return
- VOD seek bar with scrub thumbnails and muted parts marked, with an option to skip them
- Speed control on VODs and clips, down to 0.25x on clips
- Background audio, audio-only mode, picture-in-picture, mini-player and notification controls
- Swipe for brightness and volume, double-tap to seek, pinch to fill
- Landscape chat panel beside or over the video; side-by-side layout on tablets and foldables
- Multi-view: up to four streams, each with its own chat
- Sleep timer (fixed time or end of stream) and bedtime mode (black theme, dimmer player, auto sleep timer)
- Optional volume levelling and per-channel volume, mute and audio-only
- Pauses when headphones, Bluetooth or car audio disconnect
- Stats overlay: delay, resolution, bitrate, network speed, buffer, dropped frames
- Android Auto: live favourites and follows, audio-only, with voice search

### Clips, downloads and library

- Make clips: pick the length and title on live streams, or the exact range with a preview on VODs
- Share streams, VODs (at the current time) and clips
- Download VODs and clips to watch offline
- Library: watch history, Watch later and downloads
- Continue watching for unfinished VODs; watched videos and clips are dimmed and marked
- Top clips this week from the channels you follow

### Chat

- 7TV, BTTV and FFZ emotes with live 7TV updates, personal emotes and zero-width overlays
- 7TV name paints (including animated) and badges; badges from BTTV, FFZ, FFZ:AP, Chatterino, Homies and Moltorino
- Recent messages on open, replies and threads, mentions, sub and raid notices
- Channel points redemptions shown for everyone
- First-time chatters and the streamer highlighted
- Emote picker (Favourites, Recent, Twitch, 3rd Party) with most-used emotes and locked tiers greyed out
- Emote and @name suggestions, slash commands with hints, quick replies
- Filters: hide bots, block words or /patterns/, hide users on this device
- Highlights and pings for mentions, keywords and chosen users
- Link previews for Twitch and YouTube links
- Pronouns, shared chat labels, private nicknames and notes, older messages on user cards
- Translate a message on-device (Android 12+)
- Search a chat by word or `from:name`; repeated messages collapse into a count
- Poll, prediction and Hype Train cards, raid prompt and pinned message above chat
- Inbox for mentions and whispers
- Chat fonts including Atkinson Hyperlegible and OpenDyslexic, letter spacing, high contrast
- Chat synced to the stream delay; VOD chat replay
- Moderation: timeouts, bans, deletes, AutoMod, pinned messages and one-tap quick buttons

### Browsing

- Following with pinned favourites, Browse, categories, search and channel pages (videos, clips, schedule)
- Browse filters for language, viewer count, uptime and reruns
- Pin categories to the top of Browse; hide channels and categories
- Long-press a stream for a muted preview and quick actions
- Manage follows: sort by last live, bulk unfollow
- Stream manager for your own channel: title, category, tags, markers, ads, unban requests, viewer list
- Incognito mode: nothing saved to history or recent searches

### Notifications

- Go-live notifications for everyone or just favourites, with muting and quiet hours
- Category alerts when someone you follow streams a chosen category
- Unread count on the app icon for mentions and whispers, where the launcher supports it
- Home screen widget and Quick Settings tile showing who's live

### App

- Multiple accounts, switched from your avatar on Following
- Themes: system, light, dark and OLED black, with eight accents including Material You
- Searchable settings with import, export and transfer to another device by QR code or file
- Data usage per day and per channel; data saver and battery saver modes
- Accessibility: screen reader labels, high-contrast chat, controls that stay up with TalkBack
- Updates itself from GitHub releases (checksum-verified), with release notes after each update

### Opt-in

All off by default.

- Ad handling: swaps to an ad-free copy of the stream during ad breaks, or uses proxy servers you add
- twitch.tv connection, for things Twitch only allows from its own apps:
  - Channel points: balance, auto-claim and redeeming rewards
  - Following and unfollowing channels and categories
  - Voting in polls and predictions
  - Pinning chat messages and clipping VODs

## Install

You need Android 10 or newer.

1. Download the latest `Yip-x.y.z.apk` from [Releases](https://github.com/AmeliaFromFinance/Yip/releases/latest) or [yipchat.dev](https://yipchat.dev).
2. Open the file. If Android asks, allow your browser or Files app to install apps.
3. Tap **Install**. If Play Protect warns you, choose **Install anyway**. It does this for any app from outside the Play Store.
4. Open Yip and sign in with Twitch, either with a short code at twitch.tv/activate or through your browser.

After that, Yip updates itself. It checks GitHub for new releases, verifies the download's checksum and asks before installing.

To check a download yourself, keep the `.sha256` file from the release next to the APK and run:

```bash
sha256sum -c Yip-1.0.0.apk.sha256
```

## Build it yourself

Do these in order. Steps 1 to 3 are enough to run your own debug build.

### 1. Install the tools

JDK 21 and the Android SDK (API 37).

### 2. Register a Twitch app

At [dev.twitch.tv/console](https://dev.twitch.tv/console/apps), register an application with:

- Client type: **Public**
- OAuth redirect URL: `https://yipchat.dev/auth`

### 3. Add `local.properties` and build

Create `local.properties` in the project root:

```properties
sdk.dir=/path/to/Android/Sdk
twitch.clientId=your_client_id
yip.configPublicKey=base64_key_from_the_watcher
```

`yip.configPublicKey` comes from the watcher (see [Server setup](#server-setup)). Leave it out and the app uses its built-in defaults.

```bash
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests
```

The APK lands in `app/build/outputs/apk/debug/`.

### 4. Create a signing key

Release builds need your own key. Create it once:

```bash
keytool -genkeypair -v -keystore yip-release.jks -alias yip \
  -keyalg RSA -keysize 4096 -validity 10000
```

Back it up somewhere safe and never commit it. If you lose it, installed copies can't update and you can't register the app with Google.

Then create `keystore.properties` in the project root:

```properties
storeFile=/path/to/yip-release.jks
storePassword=...
keyAlias=yip
keyPassword=...
```

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`.

Put the key's SHA-256 fingerprint into `watcher/deploy/web/yipchat.dev/.well-known/assetlinks.json`, so browser sign-in can return to the app:

```bash
keytool -list -v -keystore yip-release.jks -alias yip | grep SHA256
```

### 5. Publish releases from GitHub

Add these under **Settings > Secrets and variables > Actions** in your repository:

| Secret | Value |
| --- | --- |
| `YIP_KEYSTORE_BASE64` | Output of `base64 -w0 yip-release.jks` |
| `YIP_KEYSTORE_PASSWORD` | The keystore password |
| `YIP_KEY_ALIAS` | `yip` |
| `YIP_KEY_PASSWORD` | The key password |
| `YIP_TWITCH_CLIENT_ID` | Your Twitch client ID |
| `YIP_CONFIG_PUBLIC_KEY` | The watcher's public key (optional) |

Then push a version tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The release workflow builds a signed APK, attaches it with its checksum to a GitHub release, and installed copies of Yip offer the update. It stops with an error if a required secret is missing.

## Server setup

Yip reads a small signed file that says which Twitch client ID and query versions to use. When Twitch changes something, the server updates the file and every copy of Yip picks it up within an hour, with no app update needed.

The watcher (`watcher/`) checks twitch.tv every 30 minutes with a headless browser, confirms the new values work, signs them with an Ed25519 key, and writes `playback.json`. The app only accepts files signed with that key.

### Install the watcher

On the server (Debian/Ubuntu shown; Arch notes below):

```bash
sudo apt install python3-venv
cd watcher/deploy
sudo ./install.sh /var/www/config.yipchat.dev/yip/playback.json
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
  /var/www/config.yipchat.dev/yip/playback.json --key /opt/yip-watcher/keys/config_ed25519.pem

# show a notice in every app's About screen
... yip_watcher.py run --message "Playback is being fixed, hang tight"

tail -f /var/log/yip-watcher/watcher.log
```

Keep `/opt/yip-watcher/keys/config_ed25519.pem` private and backed up. If it leaks, run `keygen --force` and ship an app update with the new public key.

### Apache

Two domains, both with HTTPS:

| Domain | Serves |
| --- | --- |
| `config.yipchat.dev` | `/yip/playback.json` from the watcher |
| `yipchat.dev` | The website, `/auth` (browser sign-in return page), `/import` and `/.well-known/assetlinks.json` |

Get the certificates first. The configs point at them, and Apache won't start if they're missing.

```bash
sudo apt install certbot python3-certbot-apache
sudo a2enmod ssl headers
sudo certbot certonly --apache -d yipchat.dev
sudo certbot certonly --apache -d config.yipchat.dev
```

Then add the pages and turn the sites on:

```bash
sudo mkdir -p /var/www/yipchat.dev
sudo cp -r watcher/deploy/web/yipchat.dev/. /var/www/yipchat.dev/
sudo cp watcher/deploy/apache/*.conf /etc/apache2/sites-available/
sudo a2ensite config.yipchat.dev yipchat.dev
sudo apache2ctl configtest
sudo systemctl reload apache2
```

The website is a separate project deployed into the same folder. Never deploy it with a sync that deletes files, or it removes `auth/`, `import/` and `.well-known/`.

If you change the script in `auth/index.html`, update its hash in the Content-Security-Policy line of `yipchat.dev.conf`.

## Project layout

| Module | What's in it |
| --- | --- |
| `app` | Activity, navigation, notifications, picture-in-picture, widget and tile |
| `core:model` | Data classes, including the full settings schema |
| `core:common` | Dispatchers, logging, formatting helpers |
| `core:network` | Twitch Helix, GQL, OAuth, EventSub, IRC and Hermes chat, 7TV (API v4, EventAPI), BTTV/FFZ, signed config |
| `core:data` | Repositories: accounts, browsing, playback, emotes, chat pipeline |
| `core:playback` | Media3 player, background service, Android Auto, downloads, playlist hook |
| `core:datastore` | Settings and encrypted account storage |
| `core:database` | Watch history, emote cache, notification state |
| `core:designsystem` | Theme, cards, chat renderer, 7TV paints |
| `feature:browse` | Following, browse, search, categories, channels |
| `feature:watch` | Player screen, chat, emote picker, multi-view, library |
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
