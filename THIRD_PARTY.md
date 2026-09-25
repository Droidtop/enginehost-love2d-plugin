# Third-party components

This branch (`plugin-core`) carries Enginehost's LÖVE wrapper only. LÖVE for
Android itself is merged in per release line (`plugin/11.5`), which starts at
the upstream release tag. `enginehost/LICENSES.md` documents the notices that
ship inside the built bundle and is authoritative for what is distributed;
this table indexes it.

| Component | Version | Licence | Source | Where in tree |
|---|---|---|---|---|
| LÖVE | the submodule revision each `plugin/<line>` pins (11.5) | zlib | https://github.com/love2d/love | `love/src/jni/love`, on the line branches |
| LÖVE for Android | the upstream tag each line starts at | zlib | https://github.com/love2d/love-android | the line branches |
| SDL2, LuaJIT, FreeType, Ogg/Vorbis/Theora, libmodplug, Oboe | as vendored by LÖVE for Android | each its own (zlib, MIT, FreeType, BSD, public domain, Apache-2.0) | see `license.txt` on a line branch | `love/src/jni/`, on the line branches |
| mpg123, OpenAL Soft | as vendored by LÖVE for Android | LGPL-2.1-or-later | see `license.txt` on a line branch | `love/src/jni/`, shipped as their own shared libraries |
