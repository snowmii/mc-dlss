# mc-dlss

NVIDIA DLSS (Super Resolution and Frame Generation) for Minecraft Java 26.2 on the Vulkan
backend, as a Fabric mod. Windows with an RTX GPU only.

The repository builds two Gradle subprojects:

- `:streamline` — a Java-only Streamline/DLSS binding for the JVM. Owns every line of C++, the
  FFM bridge, and all NVIDIA vocabulary; has no Minecraft on its compile classpath. Ships nested
  inside the mod jar as the Fabric library mod `streamline-api`.
- `:mc-dlss` (the root project) — the Kotlin-plus-mixins Fabric mod that implements the shipped
  DLSS features through that binding.

## Build

```bash
./gradlew.bat build     # Windows only: the native build shells out to MSVC
./gradlew.bat runClient
```

`docs/agents/` holds the working conventions, `docs/` the domain notes, and `.rolling/` the
execution records of each effort.

## License

MIT — see `LICENSE`. Third-party components and their terms are listed in
`THIRD-PARTY-NOTICES.md`; the NVIDIA Streamline and DLSS runtimes are not covered by it and carry
NVIDIA's own license.
