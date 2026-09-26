# Enginehost LÖVE plugin

This repository packages the official LÖVE for Android runtime as an Enginehost
engine bundle, so LÖVE games run in place from wherever they are, with no copy
and no per-game APK.

## Branch model

`main` follows upstream LÖVE for Android. `plugin-core` is the Enginehost
changeset alone: the wrapper activity, the build workflow and the bundle
metadata. Release lines (`plugin/11.5`) start at an upstream release tag,
merge `plugin-core`, and carry the few seams the wrapper needs in the engine's
own files (below). Every push to a line builds, signs and publishes the bundle
on the unstable channel; promotion to testing or stable is a manual dispatch.

## What the wrapper does

`EngineHostGameActivity` takes Enginehost's runtime contract and hands LÖVE the
game: the game folder, or the `execFile` inside it (a `.love`, or a fused
Windows executable, which is `love.exe` with the game's zip appended and which
PhysFS mounts by the zip it ends in).

The seams on a line branch, all of them in the engine's Android glue:

- `GameActivity.setEnginehostGame`, which sets the game path the native side
  asks for, instead of reading it from an intent's data URI.
- `Filesystem::setIdentity`'s Android branch reads `ENGINEHOST_LOVE_SAVE_PARENT`
  (LÖVE itself is the `love/src/jni/love` submodule, which this repository
  does not own, so this seam is `enginehost/patches/love/*.patch`, applied to
  the pinned revision by the workflow).
  LÖVE saves under the system's application-data folder, `%APPDATA%/LOVE/<identity>`
  for a game `love.exe` runs and `%APPDATA%/<identity>` for a fused one.
  Enginehost does not change where a game saves; it only makes that system
  folder mean the save folder it hands the runtime, so saves land where the
  desktop runtime would put them, under the person's chosen save root.
- Gradle: arm64-v8a and x86_64 (every Enginehost bundle carries both), the
  resource table at package id 0x80 (Enginehost's own is 0x7f), no minification
  (the wrapper is reached by name from the manifest Enginehost writes), and the
  plugin's own application id.

## Transport and sandboxing

`runtimeTransport` is `android-activity`, not `plugin`. LÖVE for Android is
built on SDL2's `GameActivity`/`SDLActivity`: SDL owns the window, the input
loop and the GL surface as an `Activity` the OS itself starts and resumes, and
`android:isolatedProcess="true"` is a `<service>` attribute the manifest schema
has no equivalent of for `<activity>` (Enginehost `docs/engine-sandbox.md`,
"Layer 2"). An Activity-transport plugin cannot move into an isolated service
without the host-side rewrite that doc's roadmap step 6 describes -- replacing
"the plugin's own Activity is the window" with a host-owned Activity plus an
isolated service the plugin's View/engine loop runs inside -- which is not
built yet for any plugin. Until it is, this plugin runs unisolated, in
`:runtime`, with the same all-files and network access the host process has:
Enginehost's `LaunchActivity` shows its "Run unsandboxed" prompt on every
single launch, remembering nothing between runs. This is acceptable by the
owner's 2026-09-25 decision (`docs/engine-sandbox.md`): a plugin that cannot
declare `isolatable` is never silently trusted, it is asked every time.

## Controller

LÖVE reads the pad itself through SDL's game controller (`love.gamepad`), so
Enginehost's map for `love2d` is LÖVE's own `GamepadButton` and `GamepadAxis`
names, with its bypass on by default. With bypass off, the wrapper reports every
pad event to LÖVE as the button or axis it is bound to.

Upstream: https://github.com/love2d/love-android, https://github.com/love2d/love.
Enginehost: https://github.com/Droidtop/enginehost.
