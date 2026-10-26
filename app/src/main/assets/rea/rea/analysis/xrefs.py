"""Cross-reference: siapa memanggil siapa (call graph) dan fungsi mana yang memakai sebuah string."""

from __future__ import annotations

import bisect
from typing import Any

from ..parsers.elf import open_elf
from .code import FunctionCode, decode, defined_functions, immediate_target, is_call
from .disasm import _capstone

MAX_FUNCTIONS = 500
_PLT_LAYOUT = {  # (skip bytes di awal .plt, ukuran satu stub) bila tidak ada .plt.sec
    "x86_64": (16, 16),
    "386": (16, 16),
    "aarch64": (32, 16),
    "arm": (20, 12),
}


def plt_names(elf) -> dict[int, str]:
    """Alamat stub PLT ke nama impor: stub ke-i melayani relokasi ke-i di .rela.plt (urutan slot)."""
    rela = elf.get_section_by_name(".rela.plt") or elf.get_section_by_name(".rel.plt")
    if rela is None or not hasattr(rela, "iter_relocations"):
        return {}
    symbols = elf.get_section(rela["sh_link"])
    if symbols is None or not hasattr(symbols, "get_symbol"):
        return {}
    names = [symbols.get_symbol(reloc["r_info_sym"]).name for reloc in rela.iter_relocations()]
    machine = str(elf.header["e_machine"]).replace("EM_", "").lower()
    secure = elf.get_section_by_name(".plt.sec")
    if secure is not None:
        base, skip, stride = secure["sh_addr"], 0, 16
    else:
        plt = elf.get_section_by_name(".plt")
        if plt is None or machine not in _PLT_LAYOUT:
            return {}
        skip, stride = _PLT_LAYOUT[machine]
        base = plt["sh_addr"]
    return {base + skip + index * stride: name for index, name in enumerate(names) if name}


def offset_to_address(elf, offset: int) -> int | None:
    """Alamat virtual dari offset berkas lewat segmen PT_LOAD; None bila tidak dimuat ke memori."""
    for segment in elf.iter_segments():
        if segment["p_type"] == "PT_LOAD" and segment["p_offset"] <= offset < segment["p_offset"] + segment["p_filesz"]:
            return segment["p_vaddr"] + offset - segment["p_offset"]
    return None


def _referenced_addresses(machine: str, code: FunctionCode) -> list[tuple[int, int]]:
    """(alamat instruksi, alamat data yang dirujuk) untuk x86 (RIP-relatif/absolut) dan ARM64 (adrp+add)."""
    cs = _capstone()
    refs: list[tuple[int, int]] = []
    if machine in ("x86_64", "386"):
        for insn in code.instructions:
            if is_call(insn):
                continue
            for op in insn.operands:
                if op.type == cs.x86.X86_OP_MEM:
                    if op.mem.base == cs.x86.X86_REG_RIP:
                        refs.append((insn.address, insn.address + insn.size + op.mem.disp))
                    elif op.mem.base == 0 and op.mem.index == 0 and op.mem.disp:
                        refs.append((insn.address, op.mem.disp & 0xFFFFFFFFFFFFFFFF))
                elif op.type == cs.x86.X86_OP_IMM and op.imm > 0x1000:
                    refs.append((insn.address, op.imm))
    elif machine == "aarch64":
        pages: dict[int, int] = {}
        for insn in code.instructions:
            ops = insn.operands
            if insn.mnemonic == "adrp" and len(ops) == 2:
                pages[ops[0].reg] = ops[1].imm
            elif insn.mnemonic == "adr" and len(ops) == 2:
                refs.append((insn.address, ops[1].imm))
            elif insn.mnemonic == "add" and len(ops) == 3 and ops[1].type == cs.arm64.ARM64_OP_REG and ops[2].type == cs.arm64.ARM64_OP_IMM:
                if ops[1].reg in pages:
                    refs.append((insn.address, pages[ops[1].reg] + ops[2].imm))
                    pages[ops[0].reg] = pages[ops[1].reg] + ops[2].imm
            elif ops and ops[-1].type == cs.arm64.ARM64_OP_MEM and ops[-1].mem.base in pages:
                refs.append((insn.address, pages[ops[-1].mem.base] + ops[-1].mem.disp))
    return refs


def analyze_xrefs(path: str, strings: list[dict[str, Any]]) -> dict[str, Any]:
    """Call graph dan rujukan string. ``strings`` = hasil ``strings.extract`` (offset dan teks)."""
    warnings: list[str] = []
    with open(path, "rb") as handle:
        elf = open_elf(handle)
        machine = str(elf.header["e_machine"]).replace("EM_", "").lower()
        functions = defined_functions(elf, MAX_FUNCTIONS)
        by_address = {f["addr"]: f["name"] for f in functions}
        plt = plt_names(elf)
        names = {**by_address, **plt}
        decoded = [decode(elf, handle, function=f) for f in functions]
        string_rows = []
        for item in strings:
            address = offset_to_address(elf, item["offset"])
            if address is not None:
                size = len(item["text"]) * (2 if item["encoding"] == "utf16le" else 1)
                string_rows.append((address, address + size, item))
        string_rows.sort(key=lambda row: row[0])
        starts = [row[0] for row in string_rows]
        can_string_refs = machine in ("x86_64", "386", "aarch64")
        if not can_string_refs:
            warnings.append(f"Rujukan string belum didukung untuk {machine}; hanya call graph.")
        calls: list[dict[str, Any]] = []
        indirect = 0
        found: dict[int, dict[str, Any]] = {}
        for code in decoded:
            for insn in code.instructions:
                if not is_call(insn):
                    continue
                target = immediate_target(machine, insn)
                if target is None:
                    indirect += 1
                    continue
                calls.append({"caller": code.name, "site": insn.address, "target": target, "callee": names.get(target)})
            if not can_string_refs:
                continue
            for site, data_address in _referenced_addresses(machine, code):
                position = bisect.bisect_right(starts, data_address) - 1
                if position < 0:
                    continue
                start, end, item = string_rows[position]
                if start <= data_address < max(end, start + 1):
                    row = found.setdefault(start, {"addr": start, "offset": item["offset"], "text": item["text"], "refs": []})
                    ref = {"function": code.name, "site": site}
                    if ref not in row["refs"]:
                        row["refs"].append(ref)
    graph: dict[str, list[str]] = {}
    for call in calls:
        callee = call["callee"] or f"0x{call['target']:x}"
        graph.setdefault(call["caller"], [])
        if callee not in graph[call["caller"]]:
            graph[call["caller"]].append(callee)
    return {
        "functions_scanned": len(decoded),
        "calls": calls,
        "call_graph": graph,
        "indirect_calls": indirect,
        "strings": sorted(found.values(), key=lambda row: row["addr"]),
        "warnings": warnings,
    }
