# ReelLoop Prototype 0.1.9 – Stable Thread Fix

Das Layout wurde nicht verändert.

Behoben:
- Das Wiedergabe-Metronom folgt der tatsächlichen Media3-Playerposition.
- Player-Loop und Metronom können dadurch nicht mehr pro Wiederholung auseinanderdriften.
- Der Live-Klick wird für die Aufnahme-/Ausgabelatenz des Smartphones kompensiert.
- Der Einzähler nutzt vorab geladene SoundPool-Klickdateien statt ToneGenerator.
- Die präzise Uhr blockiert nicht mehr den Android-Main-Thread.
- TAKTE, TAKTART und TAP verwenden zuverlässige Compose-Click-Flächen.
- Der Tempo-Regler reagiert auf Tippen und horizontales Ziehen.
- Tempo und Taktzahl sind nur bei gestopptem Transport veränderbar.

Test:
1. Alte App überschreiben oder deinstallieren.
2. Spur 1 mit hörbarem Klick neu aufnehmen.
3. STOP, danach PLAY.
4. Metronom einschalten und mindestens 8–16 Loops laufen lassen.


## 0.1.9 Stable Thread Fix
- Removed Android 11-incompatible/busy-wait Java thread calls from MasterClock.
- ExoPlayer state and position are now read only from the main coroutine context.
- No layout or feature changes.

## 0.1.10 Sync and front-camera fix
- Metronome output latency is compensated before each beat instead of after it.
- Recorded clips remove the same latency offset from their preroll.
- Front-camera recordings now match the mirrored camera preview.

