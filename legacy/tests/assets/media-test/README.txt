Synthetic local media fixtures for the separate emulator instrumentation APK only.
silence48k-mono.aac: one second of silent AAC-LC, 48 kHz mono, ADTS headers.
Generated with bundled imageio-ffmpeg 7.1:
  ffmpeg -f lavfi -i anullsrc=r=48000:cl=mono -t 1 -c:a aac -b:a 64k -f adts silence48k-mono.aac
The test strips each ADTS header to reproduce the RTP payload supplied by CarPlay.
These assets are excluded from the production APK.
