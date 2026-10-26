"""Control Flow Graph per fungsi: blok dasar, edge, back-edge (DFS), keluaran ASCII dan Graphviz DOT."""

from __future__ import annotations

from typing import Any

from .code import FunctionCode, immediate_target, is_call, is_jump, is_ret, is_unconditional


def build_cfg(code: FunctionCode) -> dict[str, Any]:
    insns = code.instructions
    if not insns:
        return {"function": code.name, "address": code.address, "blocks": [], "edges": [], "back_edges": 0, "ascii": "", "dot": ""}
    end_of_function = insns[-1].address + insns[-1].size
    addresses = {i.address for i in insns}

    def inside(target: int | None) -> bool:
        return target is not None and target in addresses

    # Pemimpin blok: instruksi pertama, target lompatan dalam fungsi, dan instruksi setelah lompatan/ret.
    leaders = {insns[0].address}
    for position, insn in enumerate(insns):
        if is_jump(insn):
            target = immediate_target(code.machine, insn)
            if inside(target):
                leaders.add(target)
        if (is_jump(insn) or is_ret(insn)) and position + 1 < len(insns):
            leaders.add(insns[position + 1].address)

    blocks: list[dict[str, Any]] = []
    current: list[Any] = []
    for insn in insns:
        if insn.address in leaders and current:
            blocks.append(_block(current))
            current = []
        current.append(insn)
    if current:
        blocks.append(_block(current))
    index = {b["start"]: n for n, b in enumerate(blocks)}

    edges: list[dict[str, Any]] = []
    for number, block in enumerate(blocks):
        last = block["_last"]
        fall = block["end"] if block["end"] < end_of_function else None
        if is_ret(last):
            continue
        if is_jump(last):
            target = immediate_target(code.machine, last)
            kind = "jump" if is_unconditional(code.machine, last) else "cond"
            if inside(target):
                edges.append({"from": number, "to": index[target], "kind": kind, "back": False})
            elif target is not None:
                block["external"] = target  # lompatan keluar fungsi (tail call)
            else:
                block["indirect"] = True
            if kind == "cond" and fall is not None and fall in index:
                edges.append({"from": number, "to": index[fall], "kind": "fall", "back": False})
        elif fall is not None and fall in index:
            edges.append({"from": number, "to": index[fall], "kind": "fall", "back": False})

    back = _mark_back_edges(blocks, edges)
    for block in blocks:
        block.pop("_last")
    return {
        "function": code.name,
        "address": code.address,
        "blocks": blocks,
        "edges": edges,
        "back_edges": back,
        "ascii": render_ascii(blocks, edges),
        "dot": render_dot(code.name, blocks, edges),
    }


def _block(insns: list[Any]) -> dict[str, Any]:
    last = insns[-1]
    return {
        "start": insns[0].address,
        "end": last.address + last.size,
        "instructions": len(insns),
        "calls": sum(1 for i in insns if is_call(i)),
        "first": f"{insns[0].mnemonic} {insns[0].op_str}".strip(),
        "last": f"{last.mnemonic} {last.op_str}".strip(),
        "_last": last,
    }


def _mark_back_edges(blocks: list[dict[str, Any]], edges: list[dict[str, Any]]) -> int:
    """Edge ke blok yang masih ada di tumpukan DFS dari blok masuk adalah back-edge (inti dari sebuah loop)."""
    outgoing: dict[int, list[dict[str, Any]]] = {}
    for edge in edges:
        outgoing.setdefault(edge["from"], []).append(edge)
    state: dict[int, int] = {}  # 1 = sedang dikunjungi, 2 = selesai
    count = 0
    stack = [(0, iter(outgoing.get(0, [])))]
    state[0] = 1
    while stack:
        node, children = stack[-1]
        for edge in children:
            target = edge["to"]
            if state.get(target) == 1:
                edge["back"] = True
                count += 1
            elif target not in state:
                state[target] = 1
                stack.append((target, iter(outgoing.get(target, []))))
                break
        else:
            state[node] = 2
            stack.pop()
    return count


def render_ascii(blocks: list[dict[str, Any]], edges: list[dict[str, Any]]) -> str:
    lines = []
    for number, block in enumerate(blocks):
        header = f"B{number} 0x{block['start']:x}-0x{block['end']:x} ({block['instructions']} instr)"
        outs = [
            f"B{e['to']} ({e['kind']}{', loop' if e['back'] else ''})" for e in edges if e["from"] == number
        ]
        suffix = " -> " + ", ".join(outs) if outs else " (keluar)"
        if block.get("external") is not None:
            suffix += f" [lompat ke 0x{block['external']:x}]"
        if block.get("indirect"):
            suffix += " [lompatan tidak langsung]"
        lines.append(header + suffix)
    return "\n".join(lines)


def render_dot(name: str, blocks: list[dict[str, Any]], edges: list[dict[str, Any]]) -> str:
    safe = name.replace('"', "'")
    lines = [f'digraph "{safe}" {{', "  node [shape=box fontname=monospace];"]
    for number, block in enumerate(blocks):
        label = f"B{number}\\n0x{block['start']:x}\\n{block['first']}".replace('"', "'")
        lines.append(f'  b{number} [label="{label}"];')
    for edge in edges:
        style = ' [style=dashed color=red label="loop"]' if edge["back"] else (' [label="%s"]' % edge["kind"])
        lines.append(f"  b{edge['from']} -> b{edge['to']}{style};")
    lines.append("}")
    return "\n".join(lines)
