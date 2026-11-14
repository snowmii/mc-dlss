# mc-dlss

DLSS for Minecraft Java Edition powered by NVIDIA [Streamline](https://github.com/NVIDIA-RTX/Streamline). 

Works on Minecraft 26.3 with Fabric Loader 0.20.1 or newer and the Vulkan backend.
Uses NVIDIA Streamline SDK 2.14.16.

Made with compatibility in mind. mc-dlss is expected to work with any modification that doesn't
fully replace minecraft's game renderer (sodium, immediately fast, entity culling, shader loaders 
that don't yet exist, etc.). 

All settings are configured in-game via **Options → Video Settings → DLSS Settings…** (or through Sodium's Video Settings if installed).

## What's new in 2.0.0

- Updated for Minecraft 26.3.
- Added DLSS 4.5 Ray Reconstruction, selectable model presets (`D`, `E`, `F`), and HDR tone mapping for compatible rendering mods.
- Streamlined the **DLSS Settings…** menu and debug readout, and removed keybinds.

## Build from source

Windows only. Install these first:

1. [Visual Studio 2022 Build Tools](https://visualstudio.microsoft.com/downloads/). In Visual
   Studio Installer, select **Desktop development with C++** and its Windows SDK component.
2. [LunarG Vulkan SDK 1.4.357.0](https://vulkan.lunarg.com/sdk/home#windows). Install the SDK,
   including `glslc`; its installer normally sets `VULKAN_SDK`.

Set `VSDEVCMD` to `VsDevCmd.bat` which should be under `<build tool location>/Common7/Tools/`
and `VULKAN_SDK` to Vulkan SDK install location.

And run:
```powershell
.\gradlew.bat build
```

## Ray Reconstruction API

External renderers drive DLSS Ray Reconstruction (`kFeatureDLSS_RR`) through `me.snowmii.dlss.api`.

- Register on `BeforeDlssEvaluation.EVENT` (runs at `WorldPhase.end` before DLSS evaluation).
- Set `RayReconstruction.enabled = true` and assign `RayReconstruction.guides` (`Supplier<RayReconstructionGuides?>`). If disabled or returning `null`, `mc-dlss` falls back to Super Resolution.
- RR queries `slDLSSDGetOptimalSettings` and extends Halton jitter to at least 32 phases.

Controls on `RayReconstruction`:

- `modelPreset`: `DEFAULT`, `D`, `E`, `F` (applied on next prepare).
- `referenceMode`: runs native resolution without touching user settings.
- `exposure`: linear scale applied before RR and tone mapping.
- `tonemapOperator`: `0` ACES (default), `1` Reinhard-Jodie, `2` Extended Reinhard, `3` Uncharted 2, `4` Heji, `5` Khronos PBR Neutral, `6` AgX, `7` linear clamp.
- `requestHistoryReset()`: one-shot history reset on the next frame.

`RayReconstructionGuides` binds raw handles via `ImageBinding(view, image, format)` (helpers: `imageBindingOf`, `rayReconstructionGuidesOf`; set `biasCurrentColor` directly on the data class):

| Field | Extent | Format / Notes |
| --- | --- | --- |
| `noisyColor` | Render | Required. Linear HDR `ScalingInputColor`. |
| `diffuseAlbedo` | Render | Required. Diffuse albedo. |
| `specularAlbedo` | Render | Required. Specular albedo. |
| `normalRoughness` | Render | Required. Packed normal + roughness. |
| `specularHitDistance` | Render | Optional. Ignored if `specularMotion` is set. |
| `resetHistory` | — | Optional. One-frame history reset. |
| `depth` | Render | Optional. Reversed-Z (`1.0` near, `0.0` far) override for RR camera motion & `Depth`. |
| `specularMotion` | Render | Optional. `R16G16_SFLOAT` / `R32G32_SFLOAT` reflection NDC motion. |
| `reflection` | Output | Optional. Linear HDR layer composited before tone mapping. |
| `biasCurrentColor` | Render | Optional. `R8_UNORM` / `R16_SFLOAT` / `R8G8B8A8_UNORM` binary history-rejection hint. |

Invariants:

- Images must be single-level, single-layer 2D textures resting in `VK_IMAGE_LAYOUT_GENERAL` and remain valid through present (`eValidUntilPresent`). `mc-dlss` restores `VK_IMAGE_LAYOUT_GENERAL` on the same command buffer.
- `mc-dlss` supplies camera matrices, camera motion vectors, and Halton jitter automatically.

## License

MIT — see `LICENSE`. Third-party components included with this software are listed in
`THIRD-PARTY-NOTICES.md`.
