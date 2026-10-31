# DiPlay 0.42.0 auto-ALSA compatibility beta

## Release status

This is a compatibility test release, not a stable release. The latest stable wired baseline remains 0.40.0. No separate rollback APK is attached; earlier versions remain available from the GitHub Releases history.

The APK and the matching `DiPlay_API17_0.42.0_AutoALSA_Source.zip` source snapshot are attached to this GitHub release. This build increments the install version to 0.42.0 / versionCode 133. Its signing certificate matches 0.41.0, so it can be installed over that release; the 0.40.0 stable release remains unchanged.

## What changed

Earlier builds opened the fixed capture node `/dev/snd/pcmC1D0c`. This release discovers capture-capable PCM nodes from `/proc/asound/pcm` and `/dev/snd`, tries the previously verified MX9A0 node first, then probes other discovered nodes and common S16_LE rate/channel combinations. It records the last path that produced a measurable signal for the same local ALSA inventory and tries it first on later sessions. If a path returns three consecutive all-zero one-second windows, the client tries the next candidate.

The client does not write ALSA mixer controls. If no direct ALSA capture stream can be opened, it records the reason and uses Android `AudioRecord` as a fallback. A node that opens but produces no signal after all alternatives have been tried is kept as the selected path and reported for diagnosis; the app does not claim that opening a node proves that the vehicle microphone is routed to it.

## Diagnostic data

The ordinary diagnostic upload includes:

- Android audio board/model fields already used for compatibility grouping.
- `/proc/asound/cards`, `/proc/asound/pcm`, and the visible `/dev/snd` node names and read/write availability.
- Candidate paths, open success/failure, requested and negotiated rate/channel, selected path, switches, and explicit `AudioRecord` fallback reasons.
- One-second microphone signal measurements such as peak and mean absolute sample value. These are numeric measurements only; the app does not store or upload raw microphone audio.

The server validates and stores these ALSA fields in the normal compatibility report with the existing 24-hour retention. The app shows the expanded upload disclosure once after installing this version.

The environment report only enumerates system files and nodes. It does not open a capture stream, allocate an audio device, or change mixer controls. The purpose is to distinguish missing nodes, permission denial, unsupported PCM formats, and an opened-but-silent capture path in one report.

## Test scope and limits

The existing real-car record is limited to a BYD Song DM 2017 with an MX9A0 head unit, Android 4.2.2 / API 17, and an iPhone 15 Pro Max running iOS 26.2.1. The 0.40.0 wired behavior was confirmed on that combination. The 0.42.0 dynamic path selection has not yet been verified in a vehicle, and no other car/head-unit model has been tested.

Dynamic node discovery reduces assumptions about card/device numbering. It cannot bypass a system that denies ordinary apps access to `/dev/snd`, expose a microphone that the kernel/HAL does not publish, or guarantee that an opened node is physically connected to the vehicle microphone. The system `AudioRecord` fallback may follow the head unit's normal audio policy.

## Suggested single vehicle test

1. Install 0.42.0 and connect the phone as usual.
2. Make one short test call. Speak for several seconds and separately note whether the vehicle microphone reaches the other party and whether incoming audio is audible on the car speakers.
3. Submit the normal compatibility/environment report after the call. The report will include the candidate scan and measured capture path without attaching a recording.

Do not test while driving. Stop the projection before changing or uninstalling versions.
