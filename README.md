# Basic Call Recorder

[English](README.md) | [中文](README.zh-CN.md)

> **Note**: This repo is a fork of [chenxiaolong/BCR](https://github.com/chenxiaolong/BCR), modified
> from its source to add an "in-call screen recording button", so recording can be started/paused
> quickly even when the lock screen makes it hard to pull down the notification shade.
> Added "Settings backup and restore" feature for quickly importing settings after flashing or switching devices.
> Last modified: 2026-09.
> Unrelated to the original author — please report issues on [this repo's Issues page](https://github.com/aksb/BCR/issues).
> If this mod is useful to you, feel free to leave a tip via the QR code at the bottom of the page (for this mod only, unrelated to the original author).

<img src="app/images/mod-incoming-call.jpg" alt="Incoming call recording button" width="200" /> <img src="app/images/mod-active-call.jpg" alt="Active call recording button" width="200" />

This mod was written entirely by AI and has not been reviewed line-by-line by a human. It has only been confirmed working on the author's own Redmi K40S and Redmi K30 5G, both flashed with PixelExperience Android 13. Compatibility and stability on other devices, Android versions, or environments are not guaranteed.
Flashing modules carries very high risk and can cause boot loops, freezes, a bricked system, or even hardware damage. Please back up all important data before attempting to flash this.
If you don't have experience recovering a bricked device or don't know how to self-rescue when the system breaks, please do not attempt to flash this.
By choosing to flash this module, you confirm that you fully understand and voluntarily accept all of the risks above, and that you are solely responsible for any resulting data loss, device damage, or other consequences.

---

<img src="app/images/icon.svg" alt="app icon" width="72" />

BCR is a simple Android call recording app for rooted devices or devices running custom firmware. Once enabled, it stays out of the way and automatically records incoming and outgoing calls in the background.

<img src="app/images/light.png" alt="light mode screenshot" width="200" /> <img src="app/images/dark.png" alt="dark mode screenshot" width="200" />

## Features

* Supports Android 9 and newer
* Supports output in various formats:
  * OGG/Opus - Lossy, smaller files, default on Android 10+
  * M4A/AAC - Lossy, smaller files, default on Android 9
  * FLAC - Lossless, larger files
  * WAV/PCM - Lossless, largest files, least CPU usage
  * AMR-WB/AMR-NB - Lossy, smallest files, mono-only
* Supports stereo recording (separate uplink and downlink channels)
  * NOTE: This is only known to work on Pixel devices running newer versions of Android. Other devices may have unexpected behavior, such as lack of separation between uplink and downlink or even no audio at all. Try recording test calls before relying on this feature.
* Supports Android's Storage Access Framework (can record to SD cards, USB devices, etc.)
* Direct boot aware (records calls prior to first unlock after a reboot)
* Auto-record rules
* Quick settings toggle
* No persistent notification unless a recording is in progress
* No network access permission
* Supports both Magisk and KernelSU

## Non-features

As the name alludes, BCR intends to be a basic as possible. The project will have succeeded at its goal if the only updates it ever needs are for compatibility with new Android versions. Thus, many potentially useful features will never be implemented, such as:

* Support for old Android versions (support is dropped as soon as maintenance becomes cumbersome)
* Workarounds for [OEM-specific battery optimization and app killing behavior](https://dontkillmyapp.com/)
* Workarounds for devices that don't support the [`VOICE_CALL` audio source](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#VOICE_CALL) (eg. using microphone + speakerphone)
* Support for stock, unrooted firmware

## License

This project is released under the same GPL-3.0-only license as the original project. See [`LICENSE`](./LICENSE) in this repo for the full license text.

---

## Donate (this fork only)

This fork adds the incoming-call recording button shown above. If it's useful to you, you're welcome to leave a tip via the QR code below. This tip has nothing to do with the original BCR author.

<img src="app/images/quick-response-code.jpg" alt="Donation QR code" width="200" />

---

To download this fork (with the incoming-call recording button), go to [this repo's Releases page](https://github.com/aksb/BCR/releases).

**About signing**: Packages published from this repo are signed with a fixed, self-signed debug certificate. It only exists to keep in-place upgrades working between releases from this repo — it has nothing to do with the original author's official signing key. If you're using files from this repo, you don't need to (and shouldn't) verify them using the original author's signature-verification instructions; those apply only to the original author's official builds.

For everything else — general install steps, permissions, how recording works, filename templates, building from source, etc. — see the [original author's page](https://github.com/chenxiaolong/BCR) (note: the "download" and "verifying signatures" sections there apply to the original author's official packages, not to this repo).
