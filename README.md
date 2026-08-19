# mc-dlss

DLSS for Minecraft Java Edition powered by NVIDIA [Streamline](https://github.com/NVIDIA-RTX/Streamline).

![Stress-pass framerate with DLSS off vs on](assets/comparison.png)

Made with compatibility in mind. mc-dlss is expected to work with any modification that doesn't
fully replace minecraft's game renderer (sodium, immediately fast, entity culling, shader loaders 
that don't yet exist, etc.). 

Comes with a few keybinds you can use to toggle through things quickly:<br>
F6: Toggles DLSS on/off<br>
F7: Cycle through Super Resolution modes (DLAA → Quality → Balanced → Performance → Ultra Performance)<br>
F8: Cycle through Super Resolution presets (K / L / M)<br>
F9: Toggles built-in stress test on/off<br>
F10: Toggles Frame Generation on/off<br>
F12: Cycle through Frame Generation multiplier (2x ~ up to 6x if supported)

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

## AI usage disclosure
This project has fully embraced the exponential and is very vibe-coded,
so pls don't trash on me for the code quality or the amount of docs out there in the code.
And use it at your own risk.


## License

MIT — see `LICENSE`. Third-party components included with this software are listed in
`THIRD-PARTY-NOTICES.md`.
