# mc-dlss

NVIDIA DLSS (Super Resolution and Frame Generation) for Minecraft Java 26.2 on the Vulkan
backend, as a Fabric mod. Windows with an RTX GPU only.

The repository builds two Gradle subprojects:

- `:streamline` — a Java-only Streamline/DLSS binding for the JVM. Owns every line of C++, the
  FFM bridge, and all NVIDIA vocabulary; has no Minecraft on its compile classpath. Ships nested
  inside the mod jar as the Fabric library mod `streamline-api`.
- `:mc-dlss` (the root project) — the Kotlin-plus-mixins Fabric mod that implements the shipped
  DLSS features through that binding.

## Development setup

Builds and dev-client runs require Windows. Install these first:

1. [JDK 25](https://adoptium.net/temurin/releases/?version=25). Set `JAVA_HOME` to its root.
2. [Visual Studio 2022 Build Tools](https://visualstudio.microsoft.com/downloads/). In Visual
   Studio Installer, select **Desktop development with C++** and its Windows SDK component.
3. [LunarG Vulkan SDK 1.4.357.0](https://vulkan.lunarg.com/sdk/home#windows). Install the SDK,
   including `glslc`; its installer normally sets `VULKAN_SDK`.

No system-wide Visual Studio IDE is required. Build Tools supplies `cl.exe`, linker, libraries,
and `VsDevCmd.bat` used by Gradle.

Gradle downloads the pinned Streamline SDK on first build, verifies its GitHub SHA-256 digest,
and extracts it under the Gradle user cache. The version lives in `gradle.properties`; `STREAMLINE_SDK`
remains an optional override for a local SDK copy.

Set `VSDEVCMD` and verify installer-created `VULKAN_SDK`. Values must be absolute paths:

| Variable | Required | Path must point to | File used to validate it |
| --- | --- | --- | --- |
| `VSDEVCMD` | Yes | Visual Studio developer-command batch file | `VsDevCmd.bat` |
| `VULKAN_SDK` | Yes | Vulkan SDK root | `Include/vulkan/vulkan.h` |
| `STREAMLINE_SDK` | No | Extracted Streamline SDK root override | `include/sl.h` |

Example PowerShell session; replace placeholders with local paths:

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

## Build

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

`docs/agents/` holds working conventions, `docs/` domain notes, and `.rolling/` execution records
of each effort.

## License

MIT — see `LICENSE`. Third-party components included with this software are
listed in `THIRD-PARTY-NOTICES.md`.
