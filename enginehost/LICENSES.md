# Licences covering this bundle

The payload is the LÖVE for Android runtime APK built from this repository's
`plugin/<version>` branch, the native libraries it carries, and the Enginehost
wrapper class. Each component keeps its own licence; nothing here relicenses
anything.

| Component | Licence | Notice shipped in the payload |
| --- | --- | --- |
| LÖVE (`https://github.com/love2d/love`) | zlib | `LICENSE.love.txt` |
| LÖVE for Android (`https://github.com/love2d/love-android`) and the libraries it bundles (SDL2, LuaJIT, FreeType, libogg, libvorbis, libtheora, libmodplug, Oboe, ...) | zlib and each library's own licence | `LICENSE.love-android.txt` |
| mpg123, OpenAL Soft | LGPL-2.1-or-later | `LICENSE.love-android.txt` |
| Enginehost wrapper (`EngineHostGameActivity`) | zlib | `LICENSE.enginehost.txt` |

mpg123 and OpenAL Soft are LGPL and ship as their own shared libraries
(`libmpg123.so`, `libopenal.so`), so they can be replaced independently of the
rest of the runtime. The corresponding source for everything in this bundle
is the branch it was built from, in this repository, with LÖVE at the
submodule revision that branch pins.
