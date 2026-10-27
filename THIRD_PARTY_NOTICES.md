# Third-Party Notices & Conceptual Acknowledgements

## 1. Licensing of NextCraft Forge (`com.nextcraft.forge`)
**NextCraft Forge** as a combined work is distributed under the **GNU General Public License v3.0 only** (`LICENSE`, SPDX: `GPL-3.0-only`), because it links the GPLv3 Termux terminal emulator libraries listed below.

Starting with the **NextCraft Forge v2.0 Clean-Room Sovereign Architecture**, the core Android host application (`com.nextcraft.forge.*`), Dual-Engine supervisor (`Root Chroot` + `Non-Root PRoot`), native JNI spawner (`libforgespawn.so`), loopback protocol gateways, Compose UI system, Hermes-Lite v2 cognitive memory, and Forge MCP / Reference Index suite are authored and maintained as a sovereign codebase for NextCraft Forge.

| Component | License | Notice Location |
| :--- | :--- | :--- |
| **NextCraft Forge (combined work)** | `GPL-3.0-only` | `LICENSE` |
| **Termux `terminal-emulator` & `terminal-view` (`v0.118.0`)** | `GPL-3.0-only` (Apache-2.0 portions by Jack Palevich) | Section 2 below |
| **Mobile Harness (Conceptual & Historical Inspiration + MIT Notice)** | `MIT` | Section 3 below |
| **XERJ (`xerj-org/xerj` — Conceptual Inspiration for Reference Indexing)** | Conceptual Inspiration | Section 4 below |

---

## 2. Termux Terminal Libraries (`GPL-3.0-only`)
- **Component:** `com.github.termux.termux-app:terminal-emulator` and `com.github.termux.termux-app:terminal-view`, version `v0.118.0` (https://github.com/termux/termux-app), integrated for interactive PTY sessions (`ForgeTerminalClients.kt`, `TerminalScreen.kt`).
- **License:** The `termux/termux-app` repository is released under **GNU GPLv3 only** (https://www.gnu.org/licenses/gpl-3.0.html), with portions derived from [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator) by Jack Palevich under the Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
- **Distribution Requirement:** Any binary or source distribution of NextCraft Forge must remain under **GPL-3.0-only** accompanied by complete corresponding source code and this notice. See `docs/TERMINAL.md`.
- **Copyright:** The Termux contributors; Jack Palevich for the Android-Terminal-Emulator portions.

---

## 3. Mobile Harness (`techjarves/Mobile-Harness` — Conceptual & Historical Inspiration)
- **Acknowledgement:** Early prototypes of NextCraft Forge were inspired by and adapted concepts from **Mobile Harness** (https://github.com/techjarves/Mobile-Harness) regarding on-device Linux container packaging and mobile CLI agent workflows. In **NextCraft Forge v2.0**, the application architecture, Dual-Engine supervisor (`Ubuntu 24.04.5 LTS` Root Chroot + Non-Root PRoot), loopback gateways, domain models, and Compose UI were re-engineered in a clean-room sovereign rewrite. We retain the upstream MIT copyright and permission notice below in full respect of the project's historical lineage and MIT license compatibility with GPLv3:

```text
MIT License

Copyright (c) 2026 Mobile Harness Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 4. XERJ (`xerj-org/xerj` — Conceptual Inspiration)
- **Acknowledgement:** The **Forge Reference Index (`forge_reference_index.py`)** and its 5 MCP tools (`forge_reference_autoindex`, `forge_reference_search`, `forge_reference_def`, `forge_reference_batch_search`, `forge_reference_gain`) were written 100% from scratch in Python 3 using standard-library `sqlite3` (FTS5 BM25) and custom regex symbol extractors. We credit **`xerj-org/xerj`** purely as conceptual inspiration for equipping coding agents with local BM25 passage search, symbol definition lookup, and token-efficiency telemetry. No source code, schema, or assets from `xerj` are used or distributed in NextCraft Forge.
