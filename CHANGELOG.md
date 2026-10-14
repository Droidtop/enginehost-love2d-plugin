# Changelog

All notable changes to this plugin are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- Moved onto the plugin-api transport (`runtimeTransport: plugin`):
  `EngineHostGamePlugin` implements `EnginePlugin` directly instead of
  extending LOVE's `GameActivity`/`SDLActivity`, loading LOVE's native
  libraries and driving SDL's static lifecycle itself, with Enginehost
  owning the Activity and window instead of the engine. Matches the
  transport CatSystem2 and CMVS already use (owner decision, 2026-09-27:
  every official plugin uses one transport).

### Fixed

- Droidtop/tracker#483, crash on start with the joystick module on:
  `EngineHostGamePlugin` now acquires SDL's `HIDDeviceManager` the way
  `SDLActivity.startNative()` does. Only that constructor registers the JVM
  with SDL's HIDAPI, so `SDL_JoystickInit` reached `PLATFORM_hid_init` with a
  null `JavaVM` and crashed.
- Droidtop/tracker#483, abort with the joystick module off (`NoSuchMethodError`
  for `getImmersiveMode`): LOVE's native side calls `GameActivity` instance
  methods on SDL's context, which is the host Activity under the plugin-api
  transport. `love/src/common/android.cpp` now asks
  `GameActivity.getGameActivity()` (engine patch 0002) for the object, and
  `GameActivity` answers window, service, asset and application-info calls
  through the host Activity (line patch 0002). `getMetrics()` no longer
  returns null.
- `GameActivity.setEnginehostGame()` is the real JNI entry point for the
  game path; a rig run had hit a `NullPointerException` calling it wrong.
- `mSingleton` needs a real `GameActivity` instance, not a bare
  `SDLActivity`; a rig run had hit a `ClassCastException`.
- `runtimeTransport` is `plugin`, from plugin-api, not `plugin` in this
  repository's own copy of the constant.
