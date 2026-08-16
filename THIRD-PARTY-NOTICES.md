# Third-party notices

`LICENSE` (MIT) covers the source code in this repository. It does not cover the
NVIDIA binary components listed here. Those are licensed by NVIDIA under their
own terms, are not relicensed by this project, and are not redistributable on
MIT terms.

The repository itself contains no NVIDIA binaries — they are staged from a
workstation-local SDK installation at build time. The **produced mod jar does
contain them**, which is what makes this file necessary.

## Components staged into the jar

Staged by `processResources` into `assets/mc-dlss/native/` and
`assets/mc-dlss/native/streamline/`:

| Component | Files | License |
| --- | --- | --- |
| NVIDIA Streamline SDK 2.12.0 | `sl.interposer.dll`, `sl.common.dll`, `sl.dlss.dll`, `sl.dlss_g.dll`, `sl.reflex.dll`, `sl.pcl.dll` | MIT — see `license.txt` in the Streamline SDK |
| NVIDIA DLSS | `nvngx_dlss.dll`, `nvngx_dlssg.dll` | NVIDIA proprietary — see `LICENSE.txt` in the DLSS SDK |
| NVIDIA Reflex (low-latency Vulkan) | `NvLowLatencyVk.dll` | NVIDIA proprietary, distributed with the Streamline SDK |

`mc_dlss.dll` is this project's own native bridge, built from `native/` in this
repository, and is covered by `LICENSE`. It links against the Streamline
interposer import library and the Vulkan loader.

## Streamline (MIT)

> Copyright (c) 2023 NVIDIA CORPORATION. All rights reserved
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED […] — full text in the Streamline SDK's `license.txt`.

Streamline's own `sl_nvperf.h` / `sl_nvperf.dll` fall under the NSight Perf SDK
License instead. This project neither uses nor stages them.

## DLSS (NVIDIA proprietary)

`nvngx_dlss.dll` and `nvngx_dlssg.dll` come from the NVIDIA DLSS SDK and are
governed by that SDK's `LICENSE.txt`, not by this repository's MIT license.
Conditions relevant to anyone redistributing a build of this mod:

- **§2.c** — the SDK must be distributed under terms at least as protective as
  NVIDIA's own.
- **§3.b** — the SDK may not be distributed or sublicensed as a stand-alone
  product; it may only ship incorporated into an application.
- **§3.e** — the SDK may not be used in a manner that would cause it to become
  subject to an open source license, including one making it redistributable at
  no charge. The scope limitation in `LICENSE` exists for this reason: MIT
  applies to this repository's code, never to the NVIDIA binaries alongside it.

This project previously declared CC0-1.0, a public-domain dedication, which is
incompatible with §3.e for exactly that reason. It is MIT with an explicit
carve-out as of 2026-08-17.

Anyone publicly distributing a build should read the DLSS SDK license directly
rather than relying on this summary. Public distribution is out of scope for the
current development contracts.
