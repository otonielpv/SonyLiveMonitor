# Release notes

## v0.3

**New**

- **WiFi signal monitor in the HUD** — a second HUD line shows the camera
  network's RSSI (dBm) and link speed (Mbps), colour-coded (green / amber /
  red). Now you can tell whether low fps come from a weak WiFi signal or from
  the camera itself.
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
