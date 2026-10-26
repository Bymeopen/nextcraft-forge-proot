"""Parser ELF di atas pyelftools: header, section, segment, simbol, dependensi, relokasi."""

from __future__ import annotations

from typing import Any

from ..result import E_BAD_FORMAT, E_MISSING_DEP, ReaError

MAX_ITEMS = 500
_SHF = ((1, "W"), (2, "A"), (4, "X"))
_PF = ((4, "R"), (2, "W"), (1, "X"))


def _elftools():
    try:
        from elftools.common.exceptions import ELFError
        from elftools.elf.dynamic import DynamicSection
        from elftools.elf.elffile import ELFFile
        from elftools.elf.relocation import RelocationSection
        from elftools.elf.segments import InterpSegment
    except ImportError as error:
        raise ReaError(E_MISSING_DEP, "pyelftools belum terpasang.", "Pasang: apt install python3-pyelftools") from error
    return ELFFile, ELFError, DynamicSection, RelocationSection, InterpSegment


def _flags(value: int, table: tuple[tuple[int, str], ...]) -> str:
    return "".join(letter for bit, letter in table if value & bit)


def _clip(items: list[Any]) -> tuple[list[Any], bool]:
    return items[:MAX_ITEMS], len(items) > MAX_ITEMS


def open_elf(handle):
    """Membuka ELFFile dari berkas yang sudah terbuka; galat format menjadi ReaError."""
    ELFFile, ELFError, *_ = _elftools()
    try:
        return ELFFile(handle)
    except ELFError as error:
        raise ReaError(E_BAD_FORMAT, f"Bukan ELF yang valid: {error}", "Gunakan `rea id` untuk memeriksa tipe berkas.") from error


def function_symbols(elf) -> list[dict[str, Any]]:
    """Fungsi dari .symtab dan .dynsym (yang terdefinisi saja), tanpa duplikat nama+alamat."""
    seen: set[tuple[str, int]] = set()
    found: list[dict[str, Any]] = []
    for name in (".symtab", ".dynsym"):
        table = elf.get_section_by_name(name)
        if table is None or not hasattr(table, "iter_symbols"):
            continue
        for symbol in table.iter_symbols():
            info = symbol["st_info"]
            if info["type"] not in ("STT_FUNC", "STT_GNU_IFUNC") or symbol["st_value"] == 0 or not symbol.name:
                continue
            key = (symbol.name, symbol["st_value"])
            if key in seen:
                continue
            seen.add(key)
            found.append(
                {
                    "name": symbol.name,
                    "addr": symbol["st_value"],
                    "size": symbol["st_size"],
                    "bind": info["bind"].replace("STB_", "").lower(),
                    "table": name,
                }
            )
    return sorted(found, key=lambda s: s["addr"])


def summarize(path: str) -> dict[str, Any]:
    """Ringkasan ELF untuk ``rea analyze``: daftar dipotong ke MAX_ITEMS dan diberi tanda ``truncated``."""
    ELFFile, _, DynamicSection, RelocationSection, InterpSegment = _elftools()
    with open(path, "rb") as handle:
        elf = open_elf(handle)
        header = elf.header
        sections = [
            {
                "name": section.name,
                "type": str(section["sh_type"]).replace("SHT_", ""),
                "addr": section["sh_addr"],
                "offset": section["sh_offset"],
                "size": section["sh_size"],
                "flags": _flags(section["sh_flags"], _SHF),
            }
            for section in elf.iter_sections()
        ]
        segments = [
            {
                "type": str(segment["p_type"]).replace("PT_", ""),
                "flags": _flags(segment["p_flags"], _PF),
                "vaddr": segment["p_vaddr"],
                "offset": segment["p_offset"],
                "filesz": segment["p_filesz"],
                "memsz": segment["p_memsz"],
            }
            for segment in elf.iter_segments()
        ]
        needed: list[str] = []
        interpreter = None
        relocations = 0
        undefined: list[str] = []
        for segment in elf.iter_segments():
            if isinstance(segment, InterpSegment):
                interpreter = segment.get_interp_name()
        for section in elf.iter_sections():
            if isinstance(section, DynamicSection):
                needed = [tag.needed for tag in section.iter_tags() if tag.entry.d_tag == "DT_NEEDED"]
            elif isinstance(section, RelocationSection):
                relocations += section.num_relocations()
        dynsym = elf.get_section_by_name(".dynsym")
        if dynsym is not None and hasattr(dynsym, "iter_symbols"):
            undefined = [s.name for s in dynsym.iter_symbols() if s["st_shndx"] == "SHN_UNDEF" and s.name]
        functions = function_symbols(elf)
        stripped = elf.get_section_by_name(".symtab") is None
        sections_out, sections_cut = _clip(sections)
        functions_out, functions_cut = _clip(functions)
        imports_out, imports_cut = _clip(undefined)
        return {
            "class": elf.elfclass,
            "endian": "little" if elf.little_endian else "big",
            "type": str(header["e_type"]).replace("ET_", "").lower(),
            "machine": str(header["e_machine"]).replace("EM_", "").lower(),
            "entry": header["e_entry"],
            "interpreter": interpreter,
            "needed": needed,
            "relocations": relocations,
            "stripped": stripped,
            "sections": sections_out,
            "segments": segments,
            "functions": functions_out,
            "imports": imports_out,
            "truncated": sections_cut or functions_cut or imports_cut,
        }
