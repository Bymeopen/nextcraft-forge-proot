# Third-party notices

## Licensing of this project
NextCraft Forge as a whole is distributed under the **GNU General Public License v3.0 only** (`LICENSE`,
SPDX: `GPL-3.0-only`), because it links the GPLv3 Termux terminal libraries listed below.
Parts derived from Mobile Harness stay under their **MIT** licence: MIT is GPL-compatible, so they are combined into the
GPLv3 work, and their copyright and permission notice below must be kept in every copy.

| Component | Licence | Notice kept in |
|---|---|---|
| NextCraft Forge (combined work) | GPL-3.0-only | `LICENSE` |
| Mobile Harness-derived parts | MIT | this file |
| Termux `terminal-emulator` / `terminal-view` | GPL-3.0-only (Apache-2.0 portions by Jack Palevich) | this file |

## Mobile Harness
- **What:** NextCraft Forge started as an adaptation of Mobile Harness (https://github.com/techjarves/Mobile-Harness);
  parts of the app and of the runtime bundle layout derive from it.
- **Licence:** MIT, reproduced below as its licence requires.

```
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

## Termux terminal libraries
- **What:** `com.github.termux.termux-app:terminal-emulator` and `:terminal-view`, version `v0.118.0`
  (https://github.com/termux/termux-app), used for the interactive terminal (`ForgeTerminalClients.kt`,
  `TerminalScreen.kt`).
- **Licence:** the `termux/termux-app` repository is released under GPLv3 only
  (https://www.gnu.org/licenses/gpl-3.0.html). Its `LICENSE.md` makes an exception for code from
  [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator) by Jack Palevich, released under
  the Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0), which it locates in the `terminal-view` and
  `terminal-emulator` libraries. Those libraries have no licence file of their own, so this app treats them as GPLv3.
- **If you distribute this app to other people:** release it under GPLv3 together with its complete source code and this
  notice. Using it yourself or inside one organisation is not distribution. See `docs/TERMINAL.md`.
- **Copyright:** the Termux contributors; Jack Palevich for the Android-Terminal-Emulator portions.
