# Sony a6000 — Wireless Live Monitor, Card Gallery & RAW Download

**Turn a Sony a6000 into a proper wireless monitor**, with live view that keeps
running while you record video, plus in‑app access to the camera's SD card so you
can browse shots and pull JPEG **or RAW/ARW** straight to your phone.

This replaces my earlier "keep live view alive while recording" workaround. That
trick only re‑enabled the physical **MOVIE** button. This setup is a real app:
**SonyLiveMonitor** (Android + iOS) talking to a patched Smart Remote Control on
the camera.

> ⚠️ **Not an official Sony method.** You are replacing an in‑camera app using
> reverse‑engineered tools (PMCA‑RE / OpenMemories). **Back up your original
> Smart Remote Control APK first.** Do this at your own risk.

---

## What you get

- **Wireless live‑view monitor** on your phone (low latency; validated ~25 fps on
  a real a6000).
- **Live view stays active while recording video** — press MOVIE on the camera (or
  start recording from the app) and keep monitoring on the phone.
- **On‑screen tools**: exposure meter (histogram + EV + clipping), framing grids,
  touch focus, mirror preview, ISO / shutter / aperture / EV / WB / drive
  controls, powered zoom. Controls are grouped into Camera, View and App tabs.
- **Camera card gallery** (the new part): browse everything on the SD card —
  including shots taken with the physical shutter — with a full‑screen viewer.
- **Download JPEG or RAW/ARW** to the phone. Multi‑select, progress dialog, and a
  choosable destination folder. Downloads are always an explicit action.

Two pieces are involved:

| Piece | Where it runs | What it is |
|---|---|---|
| **Patched Smart Remote Control** | On the camera | Smart Remote with `avContent` enabled, so the card gallery/RAW API works |
| **SonyLiveMonitor** | On your phone | Native Android app / SwiftUI iOS app that connects over the camera's Wi‑Fi |

---

## Requirements

- Sony a6000 (ILCE‑6000) with **Smart Remote Control / Mando a dist. inteligente**
  installed.
- A computer (Windows / macOS / Linux) and a **USB data cable** (not charge‑only).
- **PMCA‑RE** — installs apps on compatible Sony cameras.
- **OpenMemories: Tweak** — used to enable ADB so you can back up your original APK.
- **Android Platform Tools / ADB**.
- The two APKs from the Releases page (links below).

### Downloads

- **SonyLiveMonitor (phone app, Android):** `SonyLiveMonitor-vX.Y.apk`
  → https://github.com/otonielpv/SonyLiveMonitor/releases/latest
- **Patched Smart Remote (camera app):** `SonyLiveMonitor-a6000-avcontent.apk`
  → https://github.com/otonielpv/SonyLiveMonitor/releases/latest
- **iOS:** build from source in Xcode, or sideload the unsigned IPA from Releases
  with AltStore. See the repo README.

### Reference links

- PMCA‑RE: https://github.com/ma1co/Sony-PMCA-RE
- OpenMemories: Tweak: https://github.com/ma1co/OpenMemories-Tweak
- Android Platform Tools: https://developer.android.com/tools/releases/platform-tools

---

## Part A — Patch the camera

### 1. Install PMCA‑RE

Download from https://github.com/ma1co/Sony-PMCA-RE/releases and extract it, e.g.
`C:\sony-a6000\pmca`. On Windows you'll get ready‑to‑run executables like
`pmca-gui-v0.18-win.exe` and `pmca-console-v0.18-win.exe`.

On macOS/Linux, run from source:

```
git clone https://github.com/ma1co/Sony-PMCA-RE.git
cd Sony-PMCA-RE
python3 -m pip install -r requirements.txt
python3 pmca-console.py info
```

### 2. Camera USB setup

On the camera set **MENU → Setup → USB Connection → Mass Storage**. Turn the camera
off, connect it with a real USB data cable, turn it on. Close any Sony / photo /
cloud software that might grab the camera (Imaging Edge, PlayMemories, Photos,
Dropbox, Google Drive). Keep the battery charged and an SD card inserted.

### 3. Install OpenMemories: Tweak

In `pmca-gui`: **Get camera info** → confirm `ILCE-6000` → **Install app** tab →
select **OpenMemories: Tweak** → **Install selected app**. Don't disconnect during
install. Or from console:

```
pmca-console-v0.18-win.exe install -i      # Windows, then pick OpenMemories: Tweak
python3 pmca-console.py install -i         # macOS/Linux from source
```

### 4. Enable ADB and back up your original app

On the camera: **Applications → OpenMemories: Tweak → Developer** → enable
**Enable Wi‑Fi** and **Enable ADB**. Note the camera IP. Also set
**MENU → Setup → Power Save Start Time → 30 min** so it doesn't sleep mid‑process.

```
adb connect CAMERA_IP:5555
adb devices                                    # expect: 192.168.x.x:5555 device
adb shell pm path com.sony.imaging.app.srctrl  # e.g. /data/app/com.sony.imaging.app.srctrl-1.apk
adb pull /data/app/com.sony.imaging.app.srctrl-1.apk "C:\sony-a6000-backup\SmartRemote_original_a6000.apk"
```

**Verify the backup exists and is not 0 KB. Keep a copy somewhere safe.** This is
how you restore the stock app if anything goes wrong.

### 5. Install the patched Smart Remote

Download `SonyLiveMonitor-a6000-avcontent.apk` from the Releases page. Disconnect
ADB and switch back to USB Mass Storage before installing:

```
adb disconnect
adb kill-server
```

Then install with PMCA‑RE:

```
pmca-console-v0.18-win.exe install -f "C:\sony-a6000\SonyLiveMonitor-a6000-avcontent.apk"     # Windows
python3 pmca-console.py install -f /path/to/SonyLiveMonitor-a6000-avcontent.apk               # macOS/Linux
```

If PMCA‑RE refuses because Smart Remote is already installed (**do this only after
backing up**): on the camera go to **Applications → Application Management**,
uninstall **Smart Remote Control**, then run the install again. After it finishes,
restart the camera — the app should show the **SonyLiveMonitor** icon in the
Applications list.

---

## Part B — Connect the phone

### Android

1. On the camera open **Applications → SonyLiveMonitor** (the patched Smart Remote).
   It creates a `DIRECT-xxxx:ILCE-6000` Wi‑Fi hotspot.
2. Install `SonyLiveMonitor-vX.Y.apk` on your phone.
3. Open the app and tap **Connect** — it joins the camera Wi‑Fi and starts live view.
   It reconnects automatically.

### iOS

iOS can't join a Wi‑Fi network programmatically, so it's a one‑time manual step:
open the camera app, then on the iPhone go to **Settings → Wi‑Fi** and join the
`DIRECT-xxxx:ILCE-6000` network with the password shown on the camera. Return to
SonyLiveMonitor. Allow the **Local Network** permission the first time or the camera
is unreachable.

---

## Using it

- **Monitor + record:** live view stays up while the camera records. Start/stop with
  the on‑screen shutter (in movie mode) or the physical MOVIE button.
- **Mirror preview / selfie mode:** open the **View** tab and enable **Mirror** so
  left/right movement behaves like a mirror while filming yourself. This affects
  only the phone preview; the camera recording is never flipped. Touch focus is
  automatically corrected while the mirrored view is active.
- **Control tabs:** camera exposure and shooting controls are under **Camera**,
  monitoring aids are under **View**, and connection, gallery and diagnostics
  live under **App**. The last selected tab is remembered.
- **Camera card gallery:** tap **Camera card**. Thumbnails load in pages; tap one for
  the full‑screen viewer, pinch to zoom, and **swipe left/right** between shots.
- **Download JPEG / RAW:** select one or more photos, tap **Download**, choose JPEG,
  RAW, or JPEG+RAW. A centered progress dialog blocks the screen until it finishes.
- **Delete from the camera card:** select one or more photos and tap **Delete**.
  Android and iOS require a second confirmation because the operation cannot be
  undone. The button appears only when the camera reports API support.
  - If you pick **RAW** and some selected photos have no RAW, those are skipped — the
    ones that do have RAW are downloaded. You only get a warning if *none* of the
    selection has RAW.
  - **Android** saves to `Download/SonyLiveMonitor` (or a folder you pick).
    **iOS** saves to the **Files** app under *Sony Live Monitor* (or a folder you pick).

---

## Restore the original app

Reinstall the backup you pulled from your own camera:

```
pmca-console-v0.18-win.exe install -f "C:\sony-a6000-backup\SmartRemote_original_a6000.apk"   # Windows
python3 pmca-console.py install -f /path/to/SmartRemote_original_a6000.apk                     # macOS/Linux
```

If PMCA‑RE won't install over the patched app, uninstall it first via
**Applications → Application Management** on the camera, then install the backup.

---

## Troubleshooting

**`adb devices` is empty** — `adb kill-server && adb start-server && adb connect CAMERA_IP:5555`.
Make sure **Enable ADB** is on in OpenMemories, the camera hasn't gone to sleep, and
a firewall isn't blocking ADB.

**PMCA‑RE doesn't detect the camera** — use a real USB *data* cable, try another
port, set USB Connection to Mass Storage, and close any app that might grab the
camera.

**Install fails on the camera (`Communication error`)** — the camera's old Android
only accepts **v1 / SHA1** signed APKs. The APK on the Releases page is already
signed that way; if you rebuild it yourself, sign with `apksigner` using
`--v1-signing-enabled true --v2-signing-enabled false`.

**App shows no icon / wrong icon** — reinstall the current Releases APK; older builds
had the icon in the wrong resource bucket for the camera's 4:3 screen.

**Nothing works / want the stock app back** — restore your original APK backup
(above). That's what the backup is for.

---

*SonyLiveMonitor is an independent project and is not affiliated with or endorsed by
Sony. "Sony" and "a6000" are trademarks of their respective owner.*
