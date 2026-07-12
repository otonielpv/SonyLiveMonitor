# Reddit post — r/a6000

## Title

> Update to my free a6000 app: a custom camera app now unlocks video, SD card access and RAW download

(shorter alternatives:
> My a6000 monitor app now unlocks the SD card and RAW — via a custom camera app
> a6000 update: video, storage access and RAW download, thanks to a patched camera app)

## Body

A couple of weeks ago I shared **SonyLiveMonitor**, the free, open‑source
low‑latency monitor app I built for the a6000 (the ~25 fps / ~5 ms frame‑age one
that doesn't lag like Imaging Edge). Original post here: [link to your previous post].

That first version was just a viewfinder — it could show live view, and that was it.
The big update: I built a **custom app for the camera itself**, and *that* is what
makes everything else possible.

**These three features only work because of the camera app I made — the phone app
alone can't do any of them:**

- **Video** — live view keeps running while the camera records.
- **Access to the camera's storage** — browse everything on the SD card from your
  phone (including shots taken with the physical shutter), with a full‑screen viewer
  (pinch to zoom, swipe between photos).
- **RAW download** — pull JPEG *or* RAW/ARW to your phone, single or multi‑select,
  with a progress dialog. Android saves to `Download/SonyLiveMonitor`; iOS lands it
  in the Files app.

The way it works: the app on the camera is a **patched Smart Remote Control that
enables Sony's `avContent` API**. Stock Smart Remote keeps that locked, which is why
Imaging Edge and the plain app can't touch the card or record over the connection.
Unlock it on the camera side, and the phone app can finally do the storage, video
and RAW work. The two apps are built as one system.

**The honest catch:** installing the camera app means replacing an in‑camera app
with reverse‑engineered tools (PMCA‑RE / OpenMemories). **It's not an official Sony
method — back up your original Smart Remote APK first and do it at your own risk.**
I wrote a full step‑by‑step guide, and the patched camera APK is on the Releases
page too (correctly signed, so the camera actually accepts it).

**Get it:**

- Phone app + patched camera APK: github.com/otonielpv/SonyLiveMonitor/releases
- Install guide: github.com/otonielpv/SonyLiveMonitor/blob/main/docs/a6000-live-monitor-guide.md

Still only tested on the a6000, but it uses Sony's Camera Remote API so it may work
on other Sony bodies — if you try it on another camera (or a different a6000
firmware), let me know how it goes. Feedback welcome.

> **Note:** replace `[link to your previous post]` with the URL of your monitor‑app
> post before publishing.
