# Release notes

## v0.4

**New**

- **Focus peaking on Android and iOS** — edge highlighting is calculated from
  the live-view frames entirely on the phone, so it also works with vintage
  manual-focus lenses and requires no further camera-app patch. The control
  offers red, yellow and white overlays plus low, medium and high sensitivity.
- Peaking settings are remembered independently on each device. Processing is
  performed on a reduced-resolution mask and rate-limited to preserve the
  low-latency, latest-frame rendering strategy.

**Fixed**

- **Photo viewer pan on Android and iOS** — dragging a zoomed image now tracks
  the finger at the expected speed and stops at the actual image edges instead
  of feeling damped or allowing the photo to drift off-screen.
- Android now uses an image matrix for pinch zoom, keeping the point beneath
  the fingers stable and avoiding gesture jitter on devices such as the Oppo A5.

**Notes**

- Focus peaking runs entirely in the phone app. It works with the stock Smart
  Remote camera app and does not require reinstalling or updating the patched
  camera APK.

**Downloads**

- `SonyLiveMonitor-v0.4.apk` — Android phone app.
- `SonyLiveMonitor-a6000-avcontent-ONLY-FOR-CAMERA.apk` — unchanged patched
  camera app for card browsing/RAW support (install on the **camera** with
  PMCA-RE, not on the phone).
- `SonyLiveMonitor-v0.4-unsigned.ipa` — iOS, sideload with AltStore.

See the [install guide](https://github.com/otonielpv/SonyLiveMonitor/blob/main/docs/a6000-live-monitor-guide.md).

---

## v0.3

**New**

- **Link health monitor in the HUD** — a second HUD line tells you whether low
  fps are caused by the WiFi link or the camera, colour-coded (green / amber /
  red). On Android it shows the WiFi RSSI (dBm) and link speed; on iOS (where
  Apple blocks RSSI) it shows a link-health estimate from frame gaps and drops.
- **Camera diagnostics** — a *Diagnostics* button produces a shareable report of
  what the connected camera supports, on both Android and iOS. Handy for testers
  on unverified models.
- **Camera capability detection** — the app reads the camera's available API
  and hides controls a body genuinely doesn't support, so it behaves better
  across different Sony models (a6000, a6300, …).

**Fixed**

- **a6000 regression** where zoom, touch focus and the *Camera card* button
  could disappear: the a6000 reports an incomplete API list, so those methods
  are now always assumed available (each already fails safely on its own).

**Notes**

- The *Camera card* button appears on all cameras, but browsing the SD card and
  downloading RAW only work if the patched camera app
  (`SonyLiveMonitor-a6000-avcontent.apk`, installed on the camera via PMCA-RE)
  is in use. Zoom and touch focus work with the stock Smart Remote too.

**Downloads**

- `SonyLiveMonitor-v0.3.apk` — Android phone app.
- `SonyLiveMonitor-a6000-avcontent-ONLY-FOR-CAMERA.apk` — patched camera app
  (install on the **camera** with PMCA-RE, not on the phone).
- `SonyLiveMonitor-v0.3-unsigned.ipa` — iOS, sideload with AltStore.

See the [install guide](https://github.com/otonielpv/SonyLiveMonitor/blob/main/docs/a6000-live-monitor-guide.md).

---

## v0.2

**New**

- **Camera card gallery** — browse the camera's SD card from the app (including
  shots taken with the physical shutter), with a full-screen viewer (pinch to
  zoom, swipe between photos).
- **JPEG / RAW download** to the phone — single or multi-select, with a centered
  progress dialog that blocks the screen until it finishes.
- **iOS parity** — the whole gallery + download flow ported to the iOS app.

**Requires** the patched camera app (`avContent` enabled) for the gallery/RAW
features.

---

## v0.1

- Initial release: low-latency wireless live-view monitor for the Sony a6000
  (Android + iOS), with HUD, grids, exposure meter, camera controls, touch
  focus, powered zoom and a draggable floating shutter.
