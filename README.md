# mc-dlss

As the name suggests, DLSS to Minecraft Java Edition!
With the power provided by Streamline SDK, 
NVIDIA DLSS for Minecraft Java 26.2 on the Vulkan backend: Super Resolution (including DLAA),
Frame Generation, and Reflex. Fabric mod. Windows, RTX GPU.

![Stress-pass framerate with DLSS off vs on](docs/stress-test.png)

Sodium is the compatibility test case. Other mods should work as long as they do not replace
Minecraft's renderer. This does nothing useful in a CPU-limited world that already runs at
hundreds of frames — the screenshot is the GPU-bound case.

Use the Vulkan graphics backend. Video Settings → DLSS Settings…, or Sodium's options page.

| Key | |
| --- | --- |
| F6 | Super Resolution on/off |
| F7 | Quality mode (DLAA → Quality → Balanced → Performance → Ultra Performance) |
| F8 | SR model preset (K / L / M) |
| F9 | GPU stress pass (the screenshot) |
| F10 | Frame Generation on/off |
| F12 | Frame Generation multiplier (2x, then 3x/4x if the GPU allows it) |

Frame Generation needs a 40-series or newer. 3x/4x needs a 50-series. Super Resolution runs on
any RTX GPU.

## Install

Minecraft 26.2, Fabric Loader, [Fabric API](https://modrinth.com/mod/fabric-api), and
[Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin). Drop the mod jar in
`mods/`. Sodium is optional.

The jar already contains the Streamline runtime. No separate NVIDIA SDK install.

## Build

Windows only. Install these first:

1. [JDK 25](https://adoptium.net/temurin/releases/?version=25). Set `JAVA_HOME` to its root.
2. [Visual Studio 2022 Build Tools](https://visualstudio.microsoft.com/downloads/). In Visual
   Studio Installer, select **Desktop development with C++** and its Windows SDK component.
3. [LunarG Vulkan SDK 1.4.357.0](https://vulkan.lunarg.com/sdk/home#windows). Install the SDK,
   including `glslc`; its installer normally sets `VULKAN_SDK`.

No system-wide Visual Studio IDE is required. Build Tools supplies `cl.exe`, linker, libraries,
and `VsDevCmd.bat` used by Gradle.

Gradle downloads the pinned Streamline SDK on first build, verifies its GitHub SHA-256 digest,
and extracts it under the Gradle user cache. The version lives in `gradle.properties`;
`STREAMLINE_SDK` remains an optional override for a local SDK copy.

Set `VSDEVCMD` and verify installer-created `VULKAN_SDK`. Values must be absolute paths:

| Variable | Required | Path must point to | File used to validate it |
| --- | --- | --- | --- |
| `VSDEVCMD` | Yes | Visual Studio developer-command batch file | `VsDevCmd.bat` |
| `VULKAN_SDK` | Yes | Vulkan SDK root | `Include/vulkan/vulkan.h` |
| `STREAMLINE_SDK` | No | Extracted Streamline SDK root override | `include/sl.h` |

```powershell
$env:JAVA_HOME = '<JDK 25 root>'
$env:VSDEVCMD = '<Visual Studio root>\Common7\Tools\VsDevCmd.bat'
$env:VULKAN_SDK = '<Vulkan SDK root>'
.\gradlew.bat build
.\gradlew.bat runClient
```

Gradle properties override environment variables when one invocation needs different tools or
local SDK copies:

```powershell
.\gradlew.bat build `
  -Pmc.dlss.vs-dev-cmd='<path to VsDevCmd.bat>' `
  -Pmc.dlss.vulkan-sdk='<Vulkan SDK root>'
```

Do not commit machine paths to `gradle.properties`. For persistent local Gradle properties, put
these keys in `%USERPROFILE%\.gradle\gradle.properties` instead.

## License

MIT — see `LICENSE`. Third-party components included with this software are listed in
`THIRD-PARTY-NOTICES.md`.
