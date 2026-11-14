# mc-dlss

DLSS for Minecraft Java Edition powered by NVIDIA [Streamline](https://github.com/NVIDIA-RTX/Streamline). 

Works on Minecraft 26.3 with Fabric Loader 0.20.1 or newer and the Vulkan backend.
Uses NVIDIA Streamline SDK 2.14.16.

Made with compatibility in mind. mc-dlss is expected to work with any modification that doesn't
fully replace minecraft's game renderer (sodium, immediately fast, entity culling, shader loaders 
that don't yet exist, etc.). 

All settings are configured in-game via **Options → Video Settings → DLSS Settings…** (or through Sodium's Video Settings if installed).

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

External renderers and shader mods can drive DLSS Ray Reconstruction (`kFeatureDLSS_RR`) through the public `me.snowmii.dlss.api` package.

### Lifecycle & Frame Hook

1. Register a listener on `BeforeDlssEvaluation.EVENT` (`net.fabricmc.fabric.api.event.Event<Runnable>`). It fires at the end of the world phase (`WorldPhase.end`), after world geometry finishes and immediately before DLSS evaluates the frame.
2. When `RayReconstruction.enabled` is `true` and the frame has a valid camera sample, `mc-dlss` invokes `RayReconstruction.guides.get()`.
   - When guides are supplied, `mc-dlss` runs `kFeatureDLSS_RR` against `noisyColor` (linear HDR) and tone-maps the RR `R16G16B16A16_SFLOAT` output into the `R8G8B8A8_UNORM` destination.
   - If `RayReconstruction.enabled` is `false`, `guides` returns `null`, `camera` is `null`, or RR preparation/tagging fails, `mc-dlss` falls back to Super Resolution on the engine's scene color and depth without latching vanilla fallback. Switching between SR and RR automatically resets DLSS history.
   - While `RayReconstruction.enabled` is `true`, the Halton jitter sequence uses at least 32 phases and optimal render dimensions are queried via `slDLSSDGetOptimalSettings`.

### `RayReconstruction` Controls

- `enabled: Boolean` (`false` by default) — Master toggle (also exposed in **DLSS Settings…** and persisted in `config/mc-dlss/config.json`).
- `guides: Supplier<RayReconstructionGuides?>?` (`null` by default) — Per-frame guide provider polled on the render thread during `evaluateFrame`.
- `modelPreset: RRModelPreset` (`DEFAULT`, `D`, `E`, `F`) — RR model preset (`sl::DLSSDPreset`), applied on the next armed prepare.
- `referenceMode: Boolean` (`false` by default) — Requests native-resolution vanilla routing without modifying user settings.
- `exposure: Float` (`1.0f` by default) — Linear exposure scale (`> 0`) passed to `sl::DLSSDOptions.exposureScale` and the HDR→SDR tone-map pass.
- `tonemapOperator: Int` (`0` by default) — Compute tone-mapping operator applied to the RR HDR output before sRGB conversion and 8-bit dither:
  - `0`: Highlight-compressed ACES (default)
  - `1`: Reinhard-Jodie
  - `2`: Extended Reinhard (white point `3.5`)
  - `3`: Uncharted 2 / Hable
  - `4`: Heji / Hable ALU
  - `5`: Khronos PBR Neutral
  - `6`: AgX
  - `7`: Linear clamp
- `requestHistoryReset()` — Requests a one-shot DLSS history reset on the next evaluated frame.

### `RayReconstructionGuides` & Image Contract

`ImageBinding(view, image, format)` carries raw Vulkan `VkImageView`, `VkImage`, and `VkFormat` handles. Kotlin helpers `imageBindingOf(GpuTextureView?)` and `rayReconstructionGuidesOf(...)` are available in `me.snowmii.dlss.api` (note: `biasCurrentColor` is currently set on `RayReconstructionGuides` directly).

| Field | Required | Extent | Format / Encoding | Notes |
| --- | --- | --- | --- | --- |
| `noisyColor` | Yes | Render | Linear HDR color | Tagged as `ScalingInputColor`. |
| `diffuseAlbedo` | Yes | Render | Color | Tagged as `Albedo`. |
| `specularAlbedo` | Yes | Render | Color | Tagged as `SpecularAlbedo`. |
| `normalRoughness` | Yes | Render | Packed normal + roughness | Tagged as `NormalRoughness` (`ePacked`). |
| `specularHitDistance` | No | Render | Color | Tagged as `SpecularHitDistance` when `specularMotion` is omitted. |
| `resetHistory` | No | — | `Boolean` | Per-frame history reset (`false` by default). |
| `depth` | No | Render | Reversed-Z (`1.0` near, `0.0` far) | Replaces scene depth for RR camera motion and `Depth` tag (e.g., virtual mirror depth); `VK_FORMAT_R32_SFLOAT` uses color aspect, depth formats use depth aspect. DLSS-G still uses physical scene depth. |
| `specularMotion` | No | Render | `R16G16_SFLOAT` or `R32G32_SFLOAT` | Reflection motion in the same NDC units as the main motion field (`mvecScale = (0.5, 0.5)`). Takes precedence over `specularHitDistance`. |
| `reflection` | No | Output | Linear HDR color | Fresnel-weighted reflection layer sampled and added after RR and before tone mapping (only on frames that evaluated with RR). |
| `biasCurrentColor` | No | Render | `R8_UNORM`, `R16_SFLOAT`, or `R8G8B8A8_UNORM` | Binary R history-rejection hint (`0` retains history, `1` biases current color), tagged as `BiasCurrentColorHint` and `DisocclusionMask` (not `ResponsivityMask`). `null` clears the hint without a whole-frame reset; SDK support is model-dependent. |

**Ownership & Layout Invariants**

- Every image must be a single-level, single-layer 2D Vulkan image resting in `VK_IMAGE_LAYOUT_GENERAL`. `mc-dlss` transitions inputs to `SHADER_READ_ONLY_OPTIMAL` for evaluation/tone mapping and restores them to `VK_IMAGE_LAYOUT_GENERAL` on the same command buffer.
- The producer owns image contents, dimensions, and lifetime through present (`eValidUntilPresent`).
- `mc-dlss` supplies `worldToCameraView` and `cameraViewToWorld` (row-major `sl::float4x4`), camera motion vectors, and Halton jitter automatically from the active camera sample.

## License

MIT — see `LICENSE`. Third-party components included with this software are listed in
`THIRD-PARTY-NOTICES.md`.
