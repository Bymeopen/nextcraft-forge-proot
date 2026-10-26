"""Klien GDB Remote Serial Protocol minimal (cukup untuk gdbstub QEMU): tanpa dependensi, dengan batas waktu di setiap baca."""

from __future__ import annotations

import socket
import time

from ..result import E_INTERNAL, ReaError

_MAX_PACKET = 4 * 1024 * 1024


def checksum(payload: bytes) -> int:
    return sum(payload) & 0xFF


def encode_packet(payload: str) -> bytes:
    body = payload.encode("latin-1")
    return b"$" + body + b"#" + f"{checksum(body):02x}".encode()


def _expand(body: bytes) -> bytes:
    """Membuka escape ``}`` (xor 0x20) dan run-length ``*`` dari respons."""
    out = bytearray()
    i = 0
    while i < len(body):
        byte = body[i]
        if byte == 0x7D and i + 1 < len(body):  # }
            out.append(body[i + 1] ^ 0x20)
            i += 2
        elif byte == 0x2A and out and i + 1 < len(body):  # *
            out.extend(bytes([out[-1]]) * (body[i + 1] - 29))
            i += 2
        else:
            out.append(byte)
            i += 1
    return bytes(out)


class GdbClient:
    """Satu koneksi ke gdbstub. Setiap permintaan menunggu respons paket tunggal."""

    def __init__(self, sock: socket.socket) -> None:
        self.sock = sock
        self._buffer = b""

    @classmethod
    def connect(cls, host: str, port: int, timeout: float, alive=None) -> "GdbClient":
        """Mencoba menyambung sampai ``timeout``; gdbstub baru membuka port setelah proses QEMU siap."""
        deadline = time.monotonic() + timeout
        last: Exception | None = None
        while time.monotonic() < deadline:
            if alive is not None and not alive():
                break  # proses yang harus membuka port sudah berhenti; menunggu lebih lama sia-sia
            try:
                sock = socket.create_connection((host, port), timeout=1.0)
                return cls(sock)
            except OSError as error:
                last = error
                time.sleep(0.05)
        raise ReaError(E_INTERNAL, f"Tidak bisa tersambung ke gdbstub di {host}:{port}: {last}", "Pastikan qemu-user terpasang dan port tidak dipakai.")

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass

    def _read_byte_chunk(self, deadline: float) -> bytes:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("waktu habis menunggu gdbstub")
        self.sock.settimeout(remaining)
        try:
            chunk = self.sock.recv(65536)
        except socket.timeout as error:
            raise TimeoutError("waktu habis menunggu gdbstub") from error
        if not chunk:
            raise ConnectionError("gdbstub menutup koneksi")
        return chunk

    def receive(self, timeout: float) -> str:
        """Membaca satu paket ``$...#xx``, membalas ``+``, dan mengembalikan isinya."""
        deadline = time.monotonic() + timeout
        while True:
            start = self._buffer.find(b"$")
            if start >= 0:
                end = self._buffer.find(b"#", start)
                if end >= 0 and len(self._buffer) >= end + 3:
                    body = self._buffer[start + 1 : end]
                    given = int(self._buffer[end + 1 : end + 3], 16)
                    self._buffer = self._buffer[end + 3 :]
                    if checksum(body) != given:
                        self.sock.sendall(b"-")
                        continue
                    self.sock.sendall(b"+")
                    return _expand(body).decode("latin-1")
            if len(self._buffer) > _MAX_PACKET:
                raise ReaError(E_INTERNAL, "Paket gdbstub terlalu besar.", "")
            self._buffer += self._read_byte_chunk(deadline)

    def send(self, payload: str, timeout: float = 5.0) -> None:
        """Mengirim paket dan menunggu ``+`` (kirim ulang bila ``-``)."""
        packet = encode_packet(payload)
        deadline = time.monotonic() + timeout
        for _ in range(3):
            self.sock.sendall(packet)
            while True:
                if not self._buffer:
                    self._buffer = self._read_byte_chunk(deadline)
                head, self._buffer = self._buffer[:1], self._buffer[1:]
                if head == b"+":
                    return
                if head == b"-":
                    break
                if head == b"$":  # paket datang sebelum ack: kembalikan ke penyangga
                    self._buffer = head + self._buffer
                    return
        raise ReaError(E_INTERNAL, "gdbstub menolak paket berulang kali.", "")

    def request(self, payload: str, timeout: float = 5.0) -> str:
        self.send(payload, timeout)
        return self.receive(timeout)

    def interrupt(self) -> None:
        self.sock.sendall(b"\x03")


def parse_stop_reply(reply: str) -> dict[str, object]:
    """``T05...`` atau ``S05`` -> sinyal; ``W00``/``X..`` -> proses selesai."""
    if reply[:1] in ("T", "S") and len(reply) >= 3:
        return {"kind": "stopped", "signal": int(reply[1:3], 16)}
    if reply[:1] == "W":
        return {"kind": "exited", "code": int(reply[1:3] or "0", 16)}
    if reply[:1] == "X":
        return {"kind": "killed", "signal": int(reply[1:3] or "0", 16)}
    return {"kind": "unknown", "raw": reply[:80]}


def parse_registers(reply: str, layout: list[tuple[str, int]]) -> dict[str, int]:
    """Memecah respons ``g`` (heksadesimal little-endian) menjadi register bernama; berhenti bila data habis."""
    out: dict[str, int] = {}
    offset = 0
    for name, size in layout:
        chunk = reply[offset : offset + size * 2]
        if len(chunk) < size * 2 or "x" in chunk:
            break
        out[name] = int.from_bytes(bytes.fromhex(chunk), "little")
        offset += size * 2
    return out
