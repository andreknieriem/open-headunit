# Bundled H.264 and HEVC decoders

The checked-in `armeabi-v7a` and `arm64-v8a` libraries and public headers are built
together from **FFmpeg 9.0.2** (libavcodec 63.1.102, libavutil 61.1.102, libswscale 10.1.102).
The source is unmodified. Only the software **H.264/AVC and HEVC/H.265 decoders
and parsers** are enabled. libswscale supplies pixel conversion for the JNI
renderer.

## Codec selection

The bundled backend supports H.264 and H.265 when software decoding is enabled,
Bundled FFmpeg is selected, and the requested native decoder is available.
Availability is checked separately for each codec. The optional backend requires
Android 5.0 or newer; Android 4.x skips loading it and retains the platform decoder.

The projection session uses the codec actually advertised in ServiceDiscovery,
not the mutable settings preference. Structurally valid parameter sets can
correct that choice at initial decoder creation. Ordinary slices and decoder
timeouts cannot justify switching H.264 to H.265 or vice versa. Restarts retain
the selected codec; repeated cold failures stop retries rather than feeding the
same stream to an opposite-codec decoder.

Android Auto may deliver AVC SPS/PPS separately from picture data. The AVC
decoder uses `AV_CODEC_FLAG2_CHUNKS` so these configuration packets update
decoder state without being rejected as a missing picture. Decode errors are
still reported; they are not swallowed to accommodate configuration packets.
Parameter sets are collected across the full access unit on both backends,
including while MediaCodec is running or a surface is absent. The cache preserves
each syntax ID, replaces updates to that ID, and replays VPS/SPS/PPS in dependency
order into a fresh decoder. Identical sets reuse stored bytes. Encoded picture
bytes are passed through unchanged.
A picture retained during native retirement also retains its configuration revision.
If newer parameter sets arrive, the entire older access unit is discarded before replay,
so neither the session cache nor the native decoder receives its stale in-band configuration.
The current valid surface owner requests a fresh keyframe through the existing recovery path.
Byte-identical configuration preserves replay; even a new independent syntax ID invalidates
conservatively rather than guessing which configurations the retained picture depends on.

The message type selects the CSD or timestamped-DATA payload offset; start-code
lookalikes at another offset do not change the envelope.
A fragment-loss verdict remains attached to its assembly until LAST or replacement;
surviving middle fragments cannot make an incomplete picture eligible for decoding.

Transport recovery is acknowledged only after a non-corrupt keyframe is decoded
and accepted by the output sink. The repair callback runs outside the decoder
monitor. Repeated consumer stalls can switch either decoder backend to Surface
output; selecting bundled software decoding does not disable that recovery.
SPS dimensions only seed initial configuration. Active dimensions come from the
MediaCodec output format or accepted FFmpeg frames on either output path, not
from an inactive SPS received later. YUV fallback copying handles padded rows,
and new EGL contexts recreate texture storage even when frame sizes are unchanged.


## Source authentication

- Release: <https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz>
- Detached signature: <https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz.asc>
- Signing key: <https://ffmpeg.org/ffmpeg-devel.asc>
- Pinned release-key fingerprint: `FCF986EA15E6E293A5644F10B4322F04D67658D8`
- Archive SHA-256: `8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e`

GnuPG verified a good signature with that exact fingerprint before extraction.
An isolated keyring reports undefined owner trust; the fingerprint, rather than
a locally trusted key name, is the authentication anchor. The official release
and key information is published at <https://ffmpeg.org/download.html>.

## ABI and packaging contract

- Android `armeabi-v7a`: ARMv7-A, softfp ABI and VFPv3-D16 baseline.
  NEON implementations are enabled and selected through runtime CPU detection;
  ordinary C code and the JNI bridge do not require NEON.
- Android `arm64-v8a`: ARMv8-A with runtime CPU feature detection.
- FFmpeg uses Android NDK **r27b / 27.1.12297006**, Linux x86-64 host, Android
  **API 21** target. This does not change the application's Android 4.x support.
  No FFmpeg library is supplied for x86 or x86_64.
- Shared libraries, PIC, pthreads; no external codec libraries, hardware
  accelerators, network support, programs, encoders, muxers or demuxers.
- Every bundled FFmpeg ELF `LOAD` segment has `0x4000` (16 KB) alignment.
  JNI wrapper and final APK alignment require separate integration verification.
- Android's upstream configure branch sets unversioned SONAMEs and install names;
  no source patch or binary SONAME rewrite is used.

Observed installed ELF dependencies:

| ABI | Library / SONAME | DT_NEEDED |
| --- | --- | --- |
| `arm64-v8a` | `libavcodec.so` | `libavutil.so`, `libc.so` |
| `armeabi-v7a` | `libavcodec.so` | `libavutil.so`, `libm.so`, `libc.so` |
| Both | `libavutil.so` | `libm.so`, `libc.so` |
| Both | `libswscale.so` | `libavutil.so`, `libm.so`, `libc.so` |

The libraries are packaged in `app/src/main/jniLibs/<abi>/`. Both builds produce
identical public headers, including generated `libavutil/avconfig.h`; the shared
header tree is in `app/src/main/cpp/ffmpeg/include/`. Replace it as a unit when
upgrading; old headers such as `libavcodec/avfft.h` must not survive the cutover.

## Rebuild recipe

Run from the repository root on Linux with curl, GnuPG, xz/tar, GNU Make, a host
C compiler, diff, rsync, and the specified Android NDK available. Use a fresh work
directory. This recipe records the exact configure flags used for this bundle;
the installation prefix and toolchain paths are embedded in FFmpeg's build
configuration string, so changing paths can change binary hashes.

```sh
set -eu
REPO="$PWD"
WORK=/tmp/ohu-issue-1188/ffmpeg-upgrade
NDK="$HOME/.cache/open-headunit-review/android-sdk/ndk/27.1.12297006"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
mkdir -p "$WORK"
cd "$WORK"
curl --fail --location --output ffmpeg-9.0.2.tar.xz \
  https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz
curl --fail --location --output ffmpeg-9.0.2.tar.xz.asc \
  https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz.asc
curl --fail --location --output ffmpeg-devel.asc \
  https://ffmpeg.org/ffmpeg-devel.asc
mkdir -m 700 gnupg
gpg --homedir "$WORK/gnupg" --import ffmpeg-devel.asc
gpg --homedir "$WORK/gnupg" --status-fd 1 \
  --verify ffmpeg-9.0.2.tar.xz.asc ffmpeg-9.0.2.tar.xz
# Stop unless VALIDSIG contains the pinned fingerprint above.
printf '%s  %s\n' \
  8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e \
  ffmpeg-9.0.2.tar.xz | sha256sum --check -
for ABI in arm64-v8a armeabi-v7a; do
  case "$ABI" in
    arm64-v8a)
      SOURCE=ffmpeg-9.0.2
      INSTALL=install-arm64
      ARCH=aarch64
      CPU=armv8-a
      TARGET=aarch64-linux-android21
      CFLAGS='-O3 -fPIC'
      set --
      ;;
    armeabi-v7a)
      SOURCE=armv7-source
      INSTALL=install-armv7
      ARCH=arm
      CPU=armv7-a
      TARGET=armv7a-linux-androideabi21
      CFLAGS='-O3 -fPIC -mfloat-abi=softfp -mfpu=vfpv3-d16'
      set -- --enable-neon --enable-runtime-cpudetect
      ;;
  esac
  mkdir -p "$WORK/$SOURCE"
  tar -xJf "$WORK/ffmpeg-9.0.2.tar.xz" --strip-components=1 -C "$WORK/$SOURCE"
  cd "$WORK/$SOURCE"
  ./configure \
    --prefix="$WORK/$INSTALL" \
    --target-os=android --arch="$ARCH" --cpu="$CPU" \
    --enable-cross-compile \
    --cc="$TOOLCHAIN/bin/$TARGET-clang" \
    --cxx="$TOOLCHAIN/bin/$TARGET-clang++" \
    --ar="$TOOLCHAIN/bin/llvm-ar" \
    --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
    --nm="$TOOLCHAIN/bin/llvm-nm" \
    --strip="$TOOLCHAIN/bin/llvm-strip" \
    --sysroot="$TOOLCHAIN/sysroot" \
    --disable-everything --disable-programs --disable-doc --disable-debug \
    --disable-checkasm --disable-network --disable-autodetect \
    --disable-avdevice --disable-avfilter --disable-avformat --disable-swresample \
    --disable-encoders --disable-muxers --disable-demuxers \
    --enable-avcodec --enable-avutil --enable-swscale \
    --enable-decoder=h264,hevc --enable-parser=h264,hevc \
    --enable-shared --disable-static --enable-pic "$@" \
    --extra-cflags="$CFLAGS" \
    --extra-ldflags='-Wl,-z,max-page-size=16384'
  make -j8
  make install
  mkdir -p "$REPO/app/src/main/jniLibs/$ABI"
  cp "$WORK/$INSTALL/lib/libavcodec.so" \
     "$WORK/$INSTALL/lib/libavutil.so" \
     "$WORK/$INSTALL/lib/libswscale.so" \
     "$REPO/app/src/main/jniLibs/$ABI/"
done
diff -qr "$WORK/install-arm64/include" "$WORK/install-armv7/include"
rsync -a --delete "$WORK/install-arm64/include/" \
  "$REPO/app/src/main/cpp/ffmpeg/include/"
```

Installed library SHA-256 values for the checked-in build:

```text
278051fa47d375450389f0e178b99475a18c53b6619e70bcabc632aecc199cfc  armeabi-v7a/libavcodec.so
004dd6f6842c31f0aea0a775049773164e8a7b4bacab037702a2698284985d4c  armeabi-v7a/libavutil.so
ba168e175153d337f5a5a658942bf3cd2afa572c3d57882a69330699b9dc6161  armeabi-v7a/libswscale.so
ac26e98f1ac894cceea97de87944b59d5dc597e87cc798f7ce62e96c4c28be2c  arm64-v8a/libavcodec.so
d236dacdecc82f5b589939f4fb2c0606f48c11a914def4a5fd90c13c1e6d74f4  arm64-v8a/libavutil.so
0cb9ae74b267ea0c65be58ed33e4be5fc5b45614904ab512dcdad6447843d64c  arm64-v8a/libswscale.so
```

## Validation boundary

The production JNI implementation was exercised on Linux against a minimal
dual-codec host build of this FFmpeg source. Only the Android logging and native
window boundaries were substituted. H.264 clips at 480p, 720p and 1080p and an
H.265 clip at 1080p produced every expected frame through both YUV callbacks and
RGBA rendering, including fresh-decoder restarts, nonzero input offsets and
padded output rows. Frame pixels matched an independent FFmpeg 7.0.2 decode;
RGBA comparison used scalar swscale on both sides to avoid SIMD rounding
differences.

Configuration-only packets were tested before playback and midstream, with
parameter sets both combined and sent individually. The no-FFmpeg native build
also rejected both codec IDs, preserving optional-backend availability.
The Kotlin-to-JNI path also exercised parameter sets arriving during a surface
gap, after a long SEI or many NALs, and with multiple active IDs. Recreation
without repeated headers produced the same decoded pixels. Output-side repair
excluded rejected YUV frames and failed Surface submissions. First-creation
failure injection verified the cold retry limit without assuming an earlier
successful decoder instance.
Mixed-resolution SPS inputs also exercised actual output-size transitions through
YUV and RGBA, and a throwing output sink entered the existing recovery path.
The production GL renderer was separately exercised against host EGL/GLES with
padded-plane fallback copying, context recreation, texture allocation and pixel
readback. Android windowing, SurfaceTexture and scheduling were substituted in
that renderer check; it is not a head-unit driver verification.
Both ARM ABIs' library and JNI wrapper ELF `LOAD` segments have 16 KB alignment.

The ARM32 libraries and JNI wrapper extracted from the APK were also executed
with QEMU's Cortex-A7 model and Android 8.1 Bionic. The JNI environment and window
boundary were supplied by a YUV-only harness, not an Android app or display.
AVC and HEVC pixels matched the independent reference with both runtime-selected
NEON and forced scalar decoding, including separate configuration packets,
nonzero input offsets and decoder recreation.

These checks do not establish head-unit performance or end-to-end Android
projection correctness. Device verification still needs to cover H.264/H.265
projection with both YUV and Surface renderers, unavailable-backend fallback,
and the existing Android 4.x platform path. Final APK ZIP alignment must be
checked after packaging.

License: the configured build reports `LGPL version 2.1 or later`, with no GPL,
nonfree or external codec components enabled. Retain `FFMPEG_LICENSE.md` and
`COPYING.LGPLv2.1` alongside the headers and the upstream source notices.
