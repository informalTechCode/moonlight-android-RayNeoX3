# Third-Party Notices

This project is based on Moonlight Android and includes third-party components under their respective licenses.

## Core Project License
- Moonlight Android source in this repository is licensed under GNU GPLv3.
- See: `LICENSE.txt`

## Bundled Native Components

### moonlight-common-c
- Location: `app/src/main/jni/moonlight-core/moonlight-common-c`
- License: GNU GPLv3
- See: `app/src/main/jni/moonlight-core/moonlight-common-c/LICENSE.txt`

### ENet (submodule under moonlight-common-c)
- Location: `app/src/main/jni/moonlight-core/moonlight-common-c/enet`
- License: ENet license (BSD-like)
- See: `app/src/main/jni/moonlight-core/moonlight-common-c/enet/LICENSE`

### Opus
- Location: `app/src/main/jni/moonlight-core/libopus`
- License: BSD-style (as documented in Opus headers)
- See: `app/src/main/jni/moonlight-core/libopus/include/opus.h`
- See: `app/src/main/jni/moonlight-core/libopus/include/opus_defines.h`
- See: `app/src/main/jni/moonlight-core/libopus/include/opus_types.h`
- See: `app/src/main/jni/moonlight-core/libopus/include/opus_multistream.h`
- See: `app/src/main/jni/moonlight-core/libopus/include/opus_projection.h`

### OpenSSL
- Location: `app/src/main/jni/moonlight-core/openssl`
- License: OpenSSL/Apache-style as documented in bundled headers
- See: `app/src/main/jni/moonlight-core/openssl/include/openssl/opensslv.h`
- Additional OpenSSL header files in that directory include license notices.

### Reed-Solomon implementation (within moonlight-common-c)
- Location: `app/src/main/jni/moonlight-core/moonlight-common-c/reedsolomon/rs.c`
- License: BSD-style terms in file header

### zlib-style licensed files (moonlight-core)
- Examples:
  - `app/src/main/jni/moonlight-core/minisdl.c`
  - `app/src/main/jni/moonlight-core/usb_ids.h`
  - `app/src/main/jni/moonlight-core/controller_list.h`
  - `app/src/main/jni/moonlight-core/controller_type.h`
- License terms are in file headers.

## Java/Kotlin/Android Dependencies
- Bouncy Castle (`bcprov-jdk18on`, `bcpkix-jdk18on`)
- OkHttp
- JCodec
- JmDNS
- ShieldControllerExtensions

Dependency declarations are in: `app/build.gradle`

For exact license terms of Maven dependencies, refer to each dependency's upstream project and published artifacts.

