# Repeatable visual check

Input/physics conflict regression (auto-exits after assertions):

```powershell
.\gradlew.bat runClient -PcrouchTest --no-daemon
```

This checks crouch-jump landing standing, rejecting buffered airborne crouch,
fresh grounded presses, sprint/crouch precedence and sprint resumption, headroom,
slabs, Q/E mutual exclusion, release-to-other-side behavior, camera/model signs,
and rapid alternating input recovery. All changes stay in the disposable project
`run/saves/New World` world. PASS/COMPLETE entries are in `run/logs/latest.log`.

Run from the repository root after closing any earlier development client:

```powershell
.\gradlew.bat runClient -PvisualTest --no-daemon
```

This opt-in source set is added only to the development run, and never to the published main JAR or sources JAR. The harness checks that its game directory is the repository's `run` directory, then Quick Play opens the existing `run/saves/New World` test world. It builds a fixed grid stage there, equips an iron sword, locks the player orientation, and performs 28 first-person/front/back/side-view phases, including looking up/down while leaning. Do not point this test at a personal world. The Gradle flag deliberately ignores `gameDir` overrides.

It exercises the actual key mappings, including the shared Q/E click path, rather than directly triggering animations. Opening a screen, losing the test sword, or leaving an unconsumed vanilla inventory/drop click fails the sequence and preserves a failure screenshot.

Results are written under `run/visual-test`:

- `screenshots/*.png`: fixed names for each settled phase and early lean transitions.
- `sequence.log`: phase inputs, camera type, player pose, camera/eye positions, camera roll, sword presence, save results, and final `COMPLETE` or `FAILED` status.

The sequence waits five seconds for world setup, then spends 2.5 seconds per phase. On completion it releases all test inputs and returns to the player camera, leaving the client open for inspection. Screenshots still require human visual review; a completed sequence establishes only that the test ran without its runtime/input assertions failing.


GD656/GWO peek regression (isolated run, auto-exits):

```powershell
.\gradlew.bat runClient -PpeekTest
```

This uses `run-peek-smoke/saves/New World`, copied from the disposable project test
world, and refuses other directories. It verifies client/server position and AABB
invariance, actual Q/E, root and leg transforms, first/third-person camera, reset and
wall clipping. With the optional GWO Fix-0.5 JAR in this test instance it also checks
native first/third-person render hooks, ADS and a real server bullet from the peeked
eye. Synthetic gun definitions avoid protected graphical assets; the harness does
not claim to verify those gun models. Results are under `run-peek-smoke/peek-smoke`.
