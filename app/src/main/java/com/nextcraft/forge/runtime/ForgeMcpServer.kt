package com.nextcraft.forge.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in local Model Context Protocol (MCP) server and ARM64 wrappers provisioned into
 * `/opt/nextcraft/bin/` inside the Ubuntu guest rootfs:
 *
 * 1. `/opt/nextcraft/bin/forge-mcp-server.py`: Zero-dependency stdio MCP server providing
 *    UI/UX design token generation (Compose, Tailwind, SwiftUI, Flutter, Desktop), SVG vector
 *    generation, Mermaid blueprint generation, Android live screen/UI hierarchy inspection
 *    (via `android-shell` / `android-screencap`), and local web preview inspection.
 * 2. `/opt/nextcraft/bin/forge-playwright-mcp`: ARM64 chroot/proot wrapper for `@playwright/mcp`
 *    that automatically injects `--headless --no-sandbox` and resolves system Chromium if installed.
 * 3. `toDshCordisPatchYaml`: Translates `ConnectorStore.mcpServersJson()` into DeepSeek Harness
 *    (`$DSH_HOME/cordis.patch.yml`) `@deepseek-ai/dsh-mcp-client` plugin declarations so all three
 *    coding agents (Claude Code, Antigravity, and DeepSeek) share the same MCP connectors.
 */
internal object ForgeMcpServer {
    const val GUEST_BIN_DIR = "opt/nextcraft/bin"
    const val FORGE_MCP_SCRIPT_NAME = "forge-mcp-server.py"
    const val FORGE_MCP_GUEST_PATH = "/$GUEST_BIN_DIR/$FORGE_MCP_SCRIPT_NAME"
    const val PLAYWRIGHT_WRAPPER_NAME = "forge-playwright-mcp"
    const val PLAYWRIGHT_WRAPPER_GUEST_PATH = "/$GUEST_BIN_DIR/$PLAYWRIGHT_WRAPPER_NAME"

    private val VALID_DSH_SERVER_NAME = Regex("^[A-Za-z0-9_-]{1,32}$")

    val PLAYWRIGHT_WRAPPER_SH: String = """#!/bin/sh
# ARM64 Android Chroot/PRoot wrapper for @playwright/mcp:
# Enforces --headless and --no-sandbox (required inside Android chroot/proot namespaces)
# and binds system Chromium if available on ARM64.
for bin in /usr/bin/chromium /usr/bin/chromium-browser /usr/bin/google-chrome; do
    if [ -x "${'$'}bin" ]; then
        export PLAYWRIGHT_MCP_EXECUTABLE_PATH="${'$'}bin"
        break
    fi
done
exec npx -y @playwright/mcp@latest --headless --no-sandbox "${'$'}@"
"""

    val FORGE_MCP_SERVER_PY: String = """#!/usr/bin/env python3
import html.parser
import ipaddress
import json
import os
import socket
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

SERVER_NAME = "nextcraft-forge-mcp"
SERVER_VERSION = "1.0.0"
SUPPORTED_PROTOCOLS = {"2024-11-05", "2025-03-26", "2025-06-18", "2026-07-28"}

TOOLS = [
    {
        "name": "design_generate_svg",
        "description": "Generate clean, scalable SVG icons, app logos, badges, or UI vector illustrations and optionally write to a file.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "kind": {
                    "type": "string",
                    "description": "SVG preset kind: icon, logo, badge, card, or illustration",
                },
                "title": {
                    "type": "string",
                    "description": "Label or brand title rendered in the SVG",
                },
                "primaryColor": {
                    "type": "string",
                    "description": "Primary hex color (e.g. #10B981)",
                },
                "secondaryColor": {
                    "type": "string",
                    "description": "Secondary accent hex color (e.g. #0EA5E9)",
                },
                "size": {
                    "type": "integer",
                    "description": "Canvas width/height in px (default 512)",
                },
                "outputPath": {
                    "type": "string",
                    "description": "Optional file path inside /workspace to save the .svg file",
                },
            },
            "required": ["kind", "title"],
        },
    },
    {
        "name": "design_theme_tokens",
        "description": "Generate a complete, accessible UI/UX design token palette and theme code for Android (Jetpack Compose), Web (Tailwind/CSS), iOS (SwiftUI), Flutter, or Desktop.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "platform": {
                    "type": "string",
                    "description": "Target stack: compose, tailwind, swiftui, flutter, or desktop",
                },
                "primaryHex": {
                    "type": "string",
                    "description": "Primary brand hex color (e.g. #10B981)",
                },
                "style": {
                    "type": "string",
                    "description": "Visual style: modern-dark, glassmorphism, minimal-light, cyberpunk, or emerald-craft",
                },
            },
            "required": ["platform"],
        },
    },
    {
        "name": "design_mermaid_blueprint",
        "description": "Generate and validate a Mermaid architecture, ER database, sequence, or state diagram and optionally save it to a markdown/mermaid file.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "diagramType": {
                    "type": "string",
                    "description": "Mermaid diagram type: flowchart, sequence, er, state, or class",
                },
                "title": {
                    "type": "string",
                    "description": "Blueprint title",
                },
                "items": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "List of nodes, steps, or relationships (e.g. 'Client -> API Gateway', 'API Gateway -> PostgreSQL')",
                },
                "outputPath": {
                    "type": "string",
                    "description": "Optional path inside /workspace to write the diagram",
                },
            },
            "required": ["diagramType", "title", "items"],
        },
    },
    {
        "name": "android_ui_inspect",
        "description": "Capture a live screenshot and/or dump the interactive Android UI hierarchy (bounds, text, resource-id, clickable nodes) using NextCraft Android host tools.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "screenshotPath": {
                    "type": "string",
                    "description": "Optional path inside /workspace to save PNG screenshot",
                },
                "includeUiHierarchy": {
                    "type": "boolean",
                    "description": "Whether to dump and parse the live uiautomator XML node tree (default true)",
                },
            },
        },
    },
    {
        "name": "web_preview_inspect",
        "description": "Inspect a running local or remote web application URL, checking HTTP status, title, viewport responsiveness, scripts, stylesheets, and headings.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "Target HTTP/HTTPS URL (e.g. http://127.0.0.1:3000 or http://127.0.0.1:5173)",
                },
            },
            "required": ["url"],
        },
    },
]


def _clean_hex(value, fallback):
    if not isinstance(value, str):
        return fallback
    v = value.strip()
    if re.match(r"^#[0-9A-Fa-f]{6}$", v):
        return v.upper()
    return fallback


def _safe_workspace_path(path_str):
    if not path_str or not isinstance(path_str, str):
        return None
    abs_path = os.path.abspath(path_str)
    if abs_path.startswith("/workspace/") or abs_path.startswith("/tmp/"):
        return abs_path
    return None


def _xml_escape(text):
    return (
        str(text or "")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def handle_design_generate_svg(args):
    kind = str(args.get("kind") or "logo").strip().lower()
    title = _xml_escape(args.get("title") or "NextCraft")
    primary = _clean_hex(args.get("primaryColor"), "#10B981")
    secondary = _clean_hex(args.get("secondaryColor"), "#0EA5E9")
    size = max(64, min(2048, int(args.get("size") or 512)))
    half = size // 2
    radius = max(16, size // 5)

    if kind == "badge":
        w, h = max(200, len(title) * 14 + 64), 48
        svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" fill="none">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="{w}" y2="{h}" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="{primary}"/>
      <stop offset="100%" stop-color="{secondary}"/>
    </linearGradient>
  </defs>
  <rect width="{w}" height="{h}" rx="24" fill="url(#bg)"/>
  <circle cx="24" cy="24" r="6" fill="#FFFFFF"/>
  <text x="42" y="29" fill="#FFFFFF" font-family="system-ui, -apple-system, sans-serif" font-size="16" font-weight="700">{title}</text>
</svg>'''
    else:
        initials = _xml_escape("".join([w[0] for w in str(args.get("title") or "NC").split()[:2]]).upper() or "NC")
        svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 {size} {size}" fill="none">
  <defs>
    <linearGradient id="grad" x1="0" y1="0" x2="{size}" y2="{size}" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="{primary}"/>
      <stop offset="100%" stop-color="{secondary}"/>
    </linearGradient>
  </defs>
  <rect width="{size}" height="{size}" rx="{radius}" fill="#0B0F17"/>
  <rect x="{size // 12}" y="{size // 12}" width="{size - (size // 6)}" height="{size - (size // 6)}" rx="{radius - 8}" fill="url(#grad)" fill-opacity="0.18" stroke="url(#grad)" stroke-width="4"/>
  <circle cx="{half}" cy="{half - (size // 10)}" r="{size // 5}" fill="url(#grad)"/>
  <text x="{half}" y="{half - (size // 10) + (size // 18)}" text-anchor="middle" fill="#FFFFFF" font-family="system-ui, -apple-system, sans-serif" font-size="{size // 7}" font-weight="800">{initials}</text>
  <text x="{half}" y="{size - (size // 6)}" text-anchor="middle" fill="#F8FAFC" font-family="system-ui, -apple-system, sans-serif" font-size="{max(14, size // 16)}" font-weight="600">{title}</text>
</svg>'''

    saved_note = ""
    out_path = _safe_workspace_path(args.get("outputPath"))
    if out_path:
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(svg + "\n")
        saved_note = f"Saved SVG to `{out_path}`.\n\n"
    return f"{saved_note}```xml\n{svg}\n```"


def handle_design_theme_tokens(args):
    platform = str(args.get("platform") or "tailwind").strip().lower()
    primary = _clean_hex(args.get("primaryHex"), "#10B981")
    style = str(args.get("style") or "modern-dark").strip().lower()
    is_light = "light" in style
    bg = "#F8FAFC" if is_light else "#090D16"
    surface = "#FFFFFF" if is_light else "#111827"
    text_main = "#0F172A" if is_light else "#F8FAFC"
    text_muted = "#64748B" if is_light else "#94A3B8"
    border = "#E2E8F0" if is_light else "#1E293B"

    if platform == "compose":
        p_hex = primary.lstrip("#")
        bg_hex = bg.lstrip("#")
        surf_hex = surface.lstrip("#")
        txt_hex = text_main.lstrip("#")
        scheme_fn = "lightColorScheme" if is_light else "darkColorScheme"
        return "\n".join([
            f"### Jetpack Compose Material 3 Design Tokens ({style})",
            "```kotlin",
            f"val BrandPrimary = Color(0xFF{p_hex})",
            f"val BrandBackground = Color(0xFF{bg_hex})",
            f"val BrandSurface = Color(0xFF{surf_hex})",
            f"val BrandOnSurface = Color(0xFF{txt_hex})",
            "",
            f"val AppColorScheme = {scheme_fn}(",
            "    primary = BrandPrimary,",
            "    background = BrandBackground,",
            "    surface = BrandSurface,",
            "    onSurface = BrandOnSurface,",
            ")",
            "```",
        ])
    if platform == "swiftui":
        return "\n".join([
            f"### SwiftUI Design Tokens ({style})",
            "```swift",
            "import SwiftUI",
            "",
            "enum DesignTokens {",
            f'    static let primary = Color(hex: "{primary}")',
            f'    static let background = Color(hex: "{bg}")',
            f'    static let surface = Color(hex: "{surface}")',
            f'    static let textPrimary = Color(hex: "{text_main}")',
            f'    static let textSecondary = Color(hex: "{text_muted}")',
            "    static let cornerRadius: CGFloat = 16",
            "}",
            "```",
        ])
    if platform == "flutter":
        p_hex = primary.lstrip("#")
        bg_hex = bg.lstrip("#")
        surf_hex = surface.lstrip("#")
        bright = "light" if is_light else "dark"
        return "\n".join([
            f"### Flutter ThemeData Tokens ({style})",
            "```dart",
            "import 'package:flutter/material.dart';",
            "",
            "final ThemeData appTheme = ThemeData(",
            "  useMaterial3: true,",
            f"  brightness: Brightness.{bright},",
            f"  colorSchemeSeed: const Color(0xFF{p_hex}),",
            f"  scaffoldBackgroundColor: const Color(0xFF{bg_hex}),",
            f"  cardColor: const Color(0xFF{surf_hex}),",
            ");",
            "```",
        ])
    return "\n".join([
        f"### Web / Desktop CSS Design Tokens ({style})",
        "```css",
        ":root {",
        f"  --color-primary: {primary};",
        f"  --color-bg: {bg};",
        f"  --color-surface: {surface};",
        f"  --color-text: {text_main};",
        f"  --color-text-muted: {text_muted};",
        f"  --color-border: {border};",
        "  --radius-card: 16px;",
        "  --radius-pill: 9999px;",
        "}",
        "```",
    ])


def handle_design_mermaid_blueprint(args):
    dtype = str(args.get("diagramType") or "flowchart").strip().lower()
    title = str(args.get("title") or "System Architecture").strip()
    items = args.get("items") or []
    lines = []
    if dtype == "sequence":
        lines.append("sequenceDiagram")
        for idx, item in enumerate(items):
            s = str(item).strip()
            if "->" in s:
                left, right = [p.strip() for p in s.split("->", 1)]
                lines.append(f"    {left}->>{right}: Step {idx + 1}")
            else:
                lines.append(f"    Note over System: {s}")
    elif dtype == "state":
        lines.append("stateDiagram-v2")
        for item in items:
            s = str(item).strip()
            if "->" in s:
                left, right = [p.strip() for p in s.split("->", 1)]
                lines.append(f"    {left} --> {right}")
            else:
                lines.append(f"    [*] --> {s}")
    else:
        lines.append("flowchart TD")
        for idx, item in enumerate(items):
            s = str(item).strip()
            if "->" in s:
                left, right = [p.strip() for p in s.split("->", 1)]
                n1 = f"N{idx}A"
                n2 = f"N{idx}B"
                l1 = left.replace('"', "'")
                l2 = right.replace('"', "'")
                lines.append(f'    {n1}["{l1}"] --> {n2}["{l2}"]')
            else:
                clean = s.replace('"', "'")
                lines.append(f'    Step{idx}["{clean}"]')

    mermaid_block = "\n".join(lines)
    doc = f"## {title}\n\n```mermaid\n{mermaid_block}\n```\n"
    out_path = _safe_workspace_path(args.get("outputPath"))
    if out_path:
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(doc)
        return f"Saved blueprint to `{out_path}`.\n\n{doc}"
    return doc


def handle_android_ui_inspect(args):
    shell_bin = shutil.which("android-shell") or "/opt/nextcraft/bin/android-shell"
    screencap_bin = shutil.which("android-screencap") or "/opt/nextcraft/bin/android-screencap"
    if not os.path.isfile(shell_bin):
        return (
            "Android System Access (`android-shell`) is currently disabled. "
            "Enable Settings -> Android system access in NextCraft Forge to inspect live phone UI."
        )
    parts = []
    shot_path = _safe_workspace_path(args.get("screenshotPath"))
    if shot_path and os.path.isfile(screencap_bin):
        res = subprocess.run([screencap_bin, shot_path], capture_output=True, text=True, timeout=15)
        if res.returncode == 0 and os.path.isfile(shot_path):
            parts.append(f"Screenshot saved to `{shot_path}` ({os.path.getsize(shot_path)} bytes).")
        else:
            parts.append(f"Screenshot failed: {(res.stderr or res.stdout).strip()}")

    if args.get("includeUiHierarchy", True):
        cmd = "uiautomator dump /sdcard/forge_ui_dump.xml >/dev/null 2>&1 && cat /sdcard/forge_ui_dump.xml && rm -f /sdcard/forge_ui_dump.xml"
        res = subprocess.run([shell_bin, cmd], capture_output=True, text=True, timeout=20)
        xml_text = res.stdout.strip()
        if "<hierarchy" in xml_text:
            xml_body = xml_text[xml_text.find("<hierarchy"):]
            try:
                root = ET.fromstring(xml_body)
                nodes = []
                for elem in root.iter("node"):
                    text = (elem.attrib.get("text") or "").strip()
                    desc = (elem.attrib.get("content-desc") or "").strip()
                    res_id = (elem.attrib.get("resource-id") or "").strip()
                    clickable = elem.attrib.get("clickable") == "true"
                    bounds = elem.attrib.get("bounds") or ""
                    if text or desc or clickable:
                        label = text or desc or res_id or elem.attrib.get("class", "View")
                        nodes.append(f"- `{label}` (id=`{res_id}`, clickable={clickable}, bounds={bounds})")
                        if len(nodes) >= 60:
                            break
                parts.append(f"### Active Android UI Elements ({len(nodes)} shown)\n" + "\n".join(nodes))
            except Exception as exc:
                parts.append(f"Failed to parse UI XML: {exc}")
        else:
            parts.append("Could not dump UI hierarchy (screen may be locked or busy).")
    return "\n\n".join(parts) if parts else "No inspection action requested."


class _PreviewHTMLParser(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.in_title = False
        self.title = ""
        self.viewport = ""
        self.headings = []
        self.scripts = 0
        self.styles = 0
        self._curr_heading = None

    def handle_starttag(self, tag, attrs):
        attr_map = dict(attrs)
        if tag == "title":
            self.in_title = True
        elif tag == "meta" and attr_map.get("name", "").lower() == "viewport":
            self.viewport = attr_map.get("content", "")
        elif tag in ("h1", "h2", "h3"):
            self._curr_heading = []
        elif tag == "script":
            self.scripts += 1
        elif tag == "link" and "stylesheet" in attr_map.get("rel", "").lower():
            self.styles += 1

    def handle_endtag(self, tag):
        if tag == "title":
            self.in_title = False
        elif tag in ("h1", "h2", "h3") and self._curr_heading is not None:
            text = "".join(self._curr_heading).strip()
            if text:
                self.headings.append(f"{tag.upper()}: {text}")
            self._curr_heading = None

    def handle_data(self, data):
        if self.in_title:
            self.title += data
        if self._curr_heading is not None:
            self._curr_heading.append(data)


def _preview_url_allowed(url):
    # http/https only; loopback is allowed (the preview is local by design) but no other private, link-local,
    # multicast or reserved range, so the tool cannot be pointed at the LAN or cloud metadata endpoints.
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        return False
    if parts.scheme not in ("http", "https") or not parts.hostname:
        return False
    host = parts.hostname
    if host in ("localhost", "127.0.0.1", "::1"):
        return True
    try:
        infos = socket.getaddrinfo(host, parts.port or (443 if parts.scheme == "https" else 80))
    except (socket.gaierror, OSError):
        return False
    if not infos:
        return False
    for info in infos:
        try:
            ip = ipaddress.ip_address(info[4][0])
        except ValueError:
            return False
        if ip.is_loopback:
            continue
        if ip.is_private or ip.is_link_local or ip.is_multicast or ip.is_reserved or ip.is_unspecified:
            return False
    return True


def handle_web_preview_inspect(args):
    url = str(args.get("url") or "").strip()
    if not (url.startswith("http://") or url.startswith("https://")):
        return "Invalid URL: must start with http:// or https://"
    if not _preview_url_allowed(url):
        return "Invalid URL: only http(s) to localhost or public hosts is allowed"
    req = urllib.request.Request(url, headers={"User-Agent": "NextCraft-Forge-Inspector/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            status = resp.status
            ctype = resp.headers.get("Content-Type", "")
            raw = resp.read(256 * 1024).decode("utf-8", errors="replace")
    except Exception as exc:
        return f"Failed to connect to `{url}`: {exc}"

    parser = _PreviewHTMLParser()
    try:
        parser.feed(raw)
    except Exception:
        pass

    return (
        f"### Web Preview Report: `{url}`\n"
        f"- **HTTP Status**: `{status}`\n"
        f"- **Content-Type**: `{ctype}`\n"
        f"- **Page Title**: `{parser.title.strip() or '(none)'}`\n"
        f"- **Viewport Meta**: `{parser.viewport or '(missing - add responsive viewport meta!)'}`\n"
        f"- **Stylesheets / Scripts**: `{parser.styles}` stylesheets, `{parser.scripts}` scripts\n"
        f"- **Headings**: {', '.join(parser.headings[:10]) if parser.headings else '(none)'}"
    )


def dispatch_tool(name, arguments):
    if name == "design_generate_svg":
        return handle_design_generate_svg(arguments)
    if name == "design_theme_tokens":
        return handle_design_theme_tokens(arguments)
    if name == "design_mermaid_blueprint":
        return handle_design_mermaid_blueprint(arguments)
    if name == "android_ui_inspect":
        return handle_android_ui_inspect(arguments)
    if name == "web_preview_inspect":
        return handle_web_preview_inspect(arguments)
    raise ValueError(f"Unknown tool: {name}")


def handle_rpc(msg):
    method = msg.get("method")
    msg_id = msg.get("id")
    params = msg.get("params") or {}

    if method == "initialize":
        req_proto = str(params.get("protocolVersion") or "2024-11-05")
        proto = req_proto if req_proto in SUPPORTED_PROTOCOLS else "2024-11-05"
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {
                "protocolVersion": proto,
                "capabilities": {
                    "tools": {"listChanged": False},
                    "resources": {"subscribe": False, "listChanged": False},
                },
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                "instructions": "NextCraft Forge Built-in Design, SVG, Mermaid, Android UI & Web Preview MCP Server.",
            },
        }
    if method in ("notifications/initialized", "initialized", "notifications/cancelled"):
        return None
    if method == "ping":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"tools": TOOLS}}
    if method == "resources/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"resources": []}}
    if method == "resources/templates/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"resourceTemplates": []}}
    if method == "prompts/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"prompts": []}}
    if method == "tools/call":
        t_name = params.get("name")
        t_args = params.get("arguments") or {}
        try:
            output = dispatch_tool(t_name, t_args)
            return {
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {
                    "content": [{"type": "text", "text": str(output)}],
                    "isError": False,
                },
            }
        except Exception as exc:
            return {
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {
                    "content": [{"type": "text", "text": f"Tool execution error: {exc}"}],
                    "isError": True,
                },
            }
    if msg_id is not None:
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "error": {"code": -32601, "message": f"Method not found: {method}"},
        }
    return None


def main():
    stdin = sys.stdin.buffer
    stdout = sys.stdout.buffer
    while True:
        line = stdin.readline()
        if not line:
            break
        stripped = line.strip()
        if not stripped:
            continue
        use_content_length = False
        if stripped.lower().startswith(b"content-length:"):
            use_content_length = True
            length = int(stripped.split(b":", 1)[1].strip())
            while True:
                hdr = stdin.readline()
                if not hdr or hdr in (b"\r\n", b"\n"):
                    break
            body = stdin.read(length)
        else:
            body = stripped
        try:
            payload = json.loads(body.decode("utf-8"))
        except Exception:
            continue
        resp = handle_rpc(payload)
        if resp is not None:
            encoded = json.dumps(resp, separators=(",", ":")).encode("utf-8")
            if use_content_length:
                stdout.write(f"Content-Length: {len(encoded)}\r\n\r\n".encode("ascii") + encoded)
            else:
                stdout.write(encoded + b"\n")
            stdout.flush()


if __name__ == "__main__":
    main()
"""

    /** Provisions the built-in MCP server and Playwright ARM64 wrapper inside [rootfs]. */
    fun apply(rootfs: File, writeExecutable: (File, String) -> Unit) {
        if (!rootfs.isDirectory) return
        val binDir = File(rootfs, GUEST_BIN_DIR)
        writeExecutable(File(binDir, FORGE_MCP_SCRIPT_NAME), FORGE_MCP_SERVER_PY)
        writeExecutable(File(binDir, PLAYWRIGHT_WRAPPER_NAME), PLAYWRIGHT_WRAPPER_SH)
    }

    /**
     * Converts `ConnectorStore.mcpServersJson()` into DeepSeek Harness's `$DSH_HOME/cordis.patch.yml`
     * format using `@deepseek-ai/dsh-mcp-client` with `failOnStartupError: false`.
     */
    fun toDshCordisPatchYaml(mcpServers: JSONObject?): String {
        if (mcpServers == null || mcpServers.length() == 0) return "[]\n"
        val keys = mcpServers.keys().asSequence().toList().sorted()
        val entries = ArrayList<String>()
        for (rawKey in keys) {
            val server = mcpServers.optJSONObject(rawKey) ?: continue
            val serverName = normalizeDshServerName(rawKey)
            val type = server.optString("type")
            val block = StringBuilder()
            block.appendLine("    - id: ${yamlSingleQuote(serverName)}")
            block.appendLine("      name: '@deepseek-ai/dsh-mcp-client'")
            block.appendLine("      config:")
            block.appendLine("        serverName: ${yamlSingleQuote(serverName)}")
            when (type) {
                "http" -> {
                    val url = server.optString("url").takeIf(String::isNotBlank) ?: continue
                    block.appendLine("        transport: streamable-http")
                    block.appendLine("        url: ${yamlSingleQuote(url)}")
                    val headers = server.optJSONObject("headers")
                    if (headers != null && headers.length() > 0) {
                        block.appendLine("        headers:")
                        headers.keys().asSequence().toList().sorted().forEach { hKey ->
                            block.appendLine("          ${yamlSingleQuote(hKey)}: ${yamlSingleQuote(headers.optString(hKey))}")
                        }
                    }
                    block.appendLine("        failOnStartupError: false")
                    entries += block.toString().trimEnd()
                }
                "stdio" -> {
                    val cmd = server.optString("command").takeIf(String::isNotBlank) ?: continue
                    block.appendLine("        transport: stdio")
                    block.appendLine("        command: ${yamlSingleQuote(cmd)}")
                    val args = server.optJSONArray("args") ?: JSONArray()
                    if (args.length() > 0) {
                        block.appendLine("        args:")
                        for (i in 0 until args.length()) {
                            block.appendLine("          - ${yamlSingleQuote(args.optString(i))}")
                        }
                    }
                    val env = server.optJSONObject("env")
                    if (env != null && env.length() > 0) {
                        block.appendLine("        env:")
                        env.keys().asSequence().toList().sorted().forEach { eKey ->
                            block.appendLine("          ${yamlSingleQuote(eKey)}: ${yamlSingleQuote(env.optString(eKey))}")
                        }
                    }
                    block.appendLine("        failOnStartupError: false")
                    entries += block.toString().trimEnd()
                }
            }
        }
        if (entries.isEmpty()) return "[]\n"
        return buildString {
            appendLine("- insert:")
            entries.forEach { appendLine(it) }
        }
    }

    internal fun normalizeDshServerName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(32)
        return if (cleaned.isNotEmpty() && VALID_DSH_SERVER_NAME.matches(cleaned)) cleaned else "forge_mcp"
    }

    private fun yamlSingleQuote(value: String): String = "'${value.replace("'", "''")}'"
}
