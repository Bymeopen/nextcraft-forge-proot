"""Plugin contoh bawaan: pemeriksaan proteksi ELF (NX, PIE, RELRO, stack canary, FORTIFY), mirip `checksec`.

Contoh yang bisa disalin: satu kelas, dua hook. Kerangka plugin baru: `rea plugins new nama`.
"""

import rea


def _symbol_names(elf):
    names = set()
    for table in (".dynsym", ".symtab"):
        section = elf.get_section_by_name(table)
        if section is not None and hasattr(section, "iter_symbols"):
            names.update(symbol.name for symbol in section.iter_symbols() if symbol.name)
    return names


@rea.plugin("hardening", formats=["ELF"])
class Hardening:
    def on_analyze(self, path, data):
        from elftools.elf.elffile import ELFFile

        with open(path, "rb") as handle:
            elf = ELFFile(handle)
            segments = {str(s["p_type"]): s for s in elf.iter_segments()}
            names = _symbol_names(elf)
            stack = segments.get("PT_GNU_STACK")
            nx = stack is not None and not (stack["p_flags"] & 0x1)  # PF_X
            pie = str(elf.header["e_type"]) == "ET_DYN" and "PT_INTERP" in segments
            bind_now = False
            dynamic = elf.get_section_by_name(".dynamic")
            if dynamic is not None:
                for tag in dynamic.iter_tags():
                    entry = tag.entry
                    if entry.d_tag == "DT_BIND_NOW" or (entry.d_tag == "DT_FLAGS" and entry.d_val & 0x8) or (entry.d_tag == "DT_FLAGS_1" and entry.d_val & 0x1):
                        bind_now = True
            relro = "none" if "PT_GNU_RELRO" not in segments else ("full" if bind_now else "partial")
        canary = "__stack_chk_fail" in names or "__stack_chk_guard" in names
        fortify = any(n.startswith("__") and n.endswith("_chk") and n != "__stack_chk_fail" for n in names)
        advice = []
        if not nx:
            advice.append("Stack bisa dieksekusi (tanpa NX): kompilasi tanpa -z execstack.")
        if not pie:
            advice.append("Bukan PIE: alamat kode tetap, ASLR tidak melindungi kode (-fPIE -pie).")
        if relro != "full":
            advice.append(f"RELRO {relro}: tambahkan -Wl,-z,relro,-z,now.")
        if not canary:
            advice.append("Tanpa stack canary (-fstack-protector-strong).")
        return {"nx": nx, "pie": pie, "relro": relro, "canary": canary, "fortify": fortify, "advice": advice}

    def on_report(self, data, markdown):
        info = (data.get("plugins") or {}).get("hardening", {}).get("on_analyze")
        if not info:
            return None
        rows = [
            f"| NX | {'ya' if info['nx'] else 'tidak'} |",
            f"| PIE | {'ya' if info['pie'] else 'tidak'} |",
            f"| RELRO | {info['relro']} |",
            f"| Stack canary | {'ya' if info['canary'] else 'tidak'} |",
            f"| FORTIFY | {'ya' if info['fortify'] else 'tidak'} |",
        ]
        text = "| Proteksi | Status |\n|---|---|\n" + "\n".join(rows)
        if info["advice"]:
            text += "\n\n" + "\n".join(f"- {line}" for line in info["advice"])
        return text
