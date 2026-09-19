<p align="center"><img src="assets/overport.svg" alt="overport logo"></p>

# OVRPort app

OVRPort is a Compose Multiplatform application and command-line tool for
patching legally obtained Android applications that target Quest headsets so
they can use supported OpenXR headsets.

This repository is a downstream fork of
[`ovrport/app`](https://github.com/ovrport/app). It preserves the upstream
patcher and its supported-platform tiers while adding safer multi-platform
packaging and an **opt-in, experimental** direct VrApi-to-OpenXR compatibility
path. Builds from this fork are named from the repository that produced them,
so they are not presented as official upstream releases.

## Build channels

| Channel | Tag | Contents |
| --- | --- | --- |
| Stable | `v<major>.<minor>.<patch>` | The normal OVRPort desktop, Android and CLI packages. The experimental VrApi adapter is not built or packaged. |
| Experimental | `v<major>.<minor>.<patch>-<prerelease>` (for example, `v1.2.3-vrapi.1`) | The same applications with the project-built VrApi compatibility resource enabled. |

The GitHub Actions release workflow is configured but has not yet been run on
GitHub. When run manually it can select either channel; a matching tag selects
stable or experimental automatically. Tagged runs are configured to create a
**draft** GitHub release for maintainer inspection and signing before manual
publication. Release names and artifacts include this repository's name,
version and channel.

The stable packaging work only corrects how platform installers and artifacts
are assembled. It does not enable VrApi translation, replace a game's
`libvrapi.so`, or otherwise change the normal patch set. The adapter is present
only when Gradle is invoked with `-PwithVrApi=true`.

## Experimental direct VrApi compatibility

Experimental builds add the non-recommended `Replace VrApi with experimental OpenXR compatibility layer` patch. It
replaces an existing arm64-v8a `libvrapi.so` in a selected APK with this
project's OpenXR-backed compatibility library. The patch is opt-in in both the
build and the user interface. It fails rather than partially modifying an APK
when its required arm64 resource is absent or a participating ABI is
unsupported, and it cannot be combined with the patch that removes VrApi.

The initial engineering target is the arm64 Vulkan build of **The Climb 2**.
Local launches initialize the VrApi adapter through AXRB/OpenXR, create the
Vulkan device, enter an OpenXR session and allocate the game's color and
full-rate compatibility textures. The mandatory foveation-texture query now
succeeds with variable-rate shading disabled. Startup reaches frame submission,
where the adapter rejects the game's fixed-to-view projection flag (`0x4`).
The game subsequently terminates with `SIGTRAP`. **The Climb 2 is not currently
playable through this adapter.** No presented game frame or live-headset
interaction has been verified.

The adapter currently supports only `arm64-v8a`, Vulkan, primary-stereo
projection layers and a limited set of projection flags and blend modes. It
does not support GLES, 32-bit ABIs or non-projection VrApi layers. Its tracking
and controller paths have not been exercised in a live headset. Projection
matrices currently use the conventional GL-depth form expected to be converted
by the target's CryEngine Vulkan renderer; this is not a general Vulkan-depth
or cross-engine projection implementation. Rendering, tracking, controllers
and gameplay remain unverified, and no real-headset test has passed.

For local AXRB startup diagnosis, adding `r_variable_rate_shading = 0` to
`/storage/emulated/0/Android/data/com.crytek.climb2/files/user.cfg` gets past the
initial device-extension check. Preserve any existing settings in that file.
This is **not a complete compatibility fix**: fixed-to-view projection remains
unsupported. The patch does not modify game configuration automatically or
advertise unsupported Vulkan extensions.

The disabled-foveation path supplies a real, immutable `1x1` `RG8_UNORM`
texture, initialized to full density on every array layer and shared across
the swapchain's buffer indices. Initialization must complete before the image
is returned. Hardware foveation is still reported as unsupported; this ordinary
sampled texture is **not** a fragment-density attachment or an implementation
of Vulkan shading-rate extensions. No foveation backend is required for this
full-rate resource compatibility path.

The current Vulkan interop path assumes the application's synchronization
queue belongs to the first graphics-capable queue family and uses queue index
0 for the OpenXR graphics binding. It submits its copies on the queue provided
by VrApi rather than rejecting distinct emulator handles for the same queue.
Other queue-family arrangements remain unsupported and unverified.

The compatibility library is built from source in this repository and uses the
OpenXR loader already handled by OVRPort's existing library packaging. This
repository does not redistribute Meta's proprietary VrApi library or SDK.
Users must supply applications they are legally entitled to use.

Local release-readiness checks have verified that the adapter exports all 34
VrApi imports used by the inspected target, and the CLI test suite passes.
The desktop test task currently reports `NO-SOURCE`; it is not a tested suite.
Local Windows installer, Android APK and CLI builds succeed, and channel
checks verify the experimental payload and native notices stay out of stable
packages. Experimental APK/JAR resources are compared byte-for-byte with the
native build output. These are packaging and ABI checks, not
evidence of successful rendering or headset compatibility.

## Downloads

The configured workflow is intended to produce these versioned artifacts when
it is run:

- Windows `.exe` installer
- Linux `.deb` package
- macOS `.dmg` image
- Android release APK (unsigned unless a maintainer supplies signing outside
  this repository)
- runnable CLI shadow JAR

After the configured workflow has run, use this repository's GitHub
**Releases** page for tagged draft/released builds or the **Actions** run for
branch and pull-request artifacts. Check the channel in both the artifact
filename and release title before downloading.

## Building from source

### Requirements

- JDK 17
- Android SDK with platform/API 36 and the normal Android build tools
- Gradle through the included wrapper
- For experimental VrApi builds only: Python 3 and Android NDK
  `27.3.13750724`

Set `ANDROID_NDK_HOME` to that NDK, or install it under an Android SDK exposed
through `ANDROID_HOME`/`ANDROID_SDK_ROOT`. The native builder can locate either
layout.

`gradle.properties` is the source of the numeric application version. Desktop
installer formats require a numeric `major.minor.patch` version; prerelease
text belongs in the Git tag and artifact metadata, not in `appVersion`.

### Stable/default build

The default profile does not build or package the experimental adapter:

```bash
./gradlew :overportcli:shadowJar :composeApp:assembleRelease \
  :composeApp:packageReleaseDistributionForCurrentOS
```

On Windows, use `gradlew.bat` instead of `./gradlew`. The desktop task emits the
installer appropriate to the current host: EXE on Windows, DEB on Linux and DMG
on macOS.

### Experimental opt-in build

With NDK `27.3.13750724` installed, the exact opt-in profile is:

```bash
./gradlew :overportcli:shadowJar :composeApp:assembleRelease \
  :composeApp:packageReleaseDistributionForCurrentOS \
  -PwithVrApi=true
```

That profile builds the native resource automatically. To build it explicitly
or reuse one payload across packaging jobs:

```bash
python native/vrapi/build.py \
  --ndk "$ANDROID_NDK_HOME" \
  --output "$PWD/build/vrapi-resource"

./gradlew :overportcli:shadowJar :composeApp:assembleRelease \
  :composeApp:packageReleaseDistributionForCurrentOS \
  -PwithVrApi=true \
  -PvrApiResourceRoot="$PWD/build/vrapi-resource"
```

The generated resource root contains
`vrapi/arm64-v8a/libvrapi.bin` and the dependency notices under
`vrapi/licenses/`. The payload is an Android arm64 shared library stored with a
`.bin` resource suffix; it is not an upstream or proprietary VrApi binary.
Omitting `withVrApi` (or setting it to `false`) keeps both the payload and its
native notices out of the normal distribution.

### Experimental CLI opt-in

The experimental patch is not part of the recommended patch set. Use an
experimental CLI JAR and name it explicitly:

```bash
java -jar <experimental-cli.jar> patches
java -jar <experimental-cli.jar> patch \
  --input=/path/to/application.apk \
  --patches=patch_vrapi_openxr
```

`--patches` replaces the default recommended selection. To retain other
desired patches, pass their names in the same quoted, semicolon-separated
value, for example
`--patches='patch_vrapi_openxr;another_patch'`. Keep the original APK and
review the output before installing it.

## Release process and signing

Tags must use `v<major>.<minor>.<patch>[-prerelease]`. A suffix automatically
selects the experimental channel; a tag without a suffix always selects stable
and therefore cannot package the experimental native resource. The workflow
has not yet been run on GitHub. When run, it is configured to execute the test
suites once before building platform packages, require every expected artifact
to exist, and upload only those packaged artifacts to the draft release.

Before publishing a draft, a maintainer must:

1. verify that the tag's numeric version matches `appVersion` in
   `gradle.properties`;
2. configure and apply Android release signing (the CI APK is intentionally
   unsigned);
3. apply any required Windows code signing and Apple signing/notarization using
   protected repository or environment secrets;
4. test the installers and, for an experimental release, test patched output on
   real target hardware; and
5. review the generated notes and explicitly publish the draft in GitHub.

No signing keys or publishing credentials are committed to the repository.

## Upstream supported platforms

These tiers describe upstream OVRPort patching support, not validation of the
experimental direct VrApi adapter.

### Tier 1

- Pico 4 Ultra

### Tier 2

- Pico 4 / 4 Pro
- Pico Neo 3
- Oculus Quest 2

### Tier 3

- Oculus Quest (1)
- Meta Quest 3 / 3S
- YVR/PFDMR headsets

Other OpenXR-compatible headsets may work but require additional testing.

## Reporting issues

Attach `adb logcat` output when reporting a problem. For game compatibility
issues, first enable verbose OVRPort logging:

```text
adb shell setprop debug.sys.ovrport.verboselogging 1
```

State the build channel, artifact version, source and target ABI, headset,
OpenXR runtime and the selected patches. Do not report an experimental result
as stable behavior.

## Legal, licensing and attribution

Think of this application like an emulator or compatibility tool: you must
legally obtain the application files you process. Do not use it to distribute
copyrighted games, proprietary SDKs or vendor libraries.

OVRPort app is licensed under the GNU General Public License v3; see
[`LICENSE`](LICENSE). The experimental native adapter's pinned OpenXR-SDK
headers are offered under `Apache-2.0 OR MIT`; this distribution uses the MIT
option. Statically linked Android NDK runtime portions are covered by the
Apache License 2.0 with LLVM exception and the applicable legacy MIT,
University of Illinois/NCSA and BSD notices. Exact attributions and license
texts are in [`native/vrapi/licenses`](native/vrapi/licenses/) and are copied
into every generated experimental resource root.

This project is neither affiliated with nor endorsed by Meta.
