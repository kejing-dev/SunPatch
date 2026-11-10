# Sun Patch / 窗边日照

A PICO OS 6 mixed-reality tool (PICO Spatial SDK 6.1.9, Kotlin). Mark your real window with two corners, tell it which
way the window faces and which city you are in, then drag the clock and the month: the patch of direct sun falls on
your real floor, desk and far wall at full size. "Sweep the day" leaves a map of how many hours each spot gets, and
markers (plant, desk, screen, pet bed…) tell you whether a spot gets enough sun.

Direct sun only: buildings, trees, eaves and curtains outside are not counted. A guide, not a promise.

- Package `io.embodit.sunpatch`, version 0.1.0, offline, Chinese + English.
- Build: see `AGENTS.md` (`./gradlew --no-daemon assembleDebug`).
- Design package: `../spatial-app-factory/apps/sun-patch/design-package.json`.
