"""Isi 13 pelajaran dalam 7 tahap. Teks untuk pembelajar dalam Bahasa Indonesia; identifier dalam Bahasa Inggris."""

from __future__ import annotations

from .model import Lesson, Question

HELLO_C = """#include <stdio.h>
int main(void) {
    puts("Halo dari biner pertamamu");
    return 0;
}
"""

LESSONS: tuple[Lesson, ...] = (
    Lesson(
        n=1, stage=1, title="Apa itu biner dan ELF",
        theory=(
            "Program yang kamu jalankan adalah berkas biner: urutan byte yang dimengerti CPU. Di Linux formatnya ELF. "
            "Byte pertama berkas ELF selalu 7f 45 4c 46 (\"\\x7fELF\"). Setelah itu ada penanda 32/64 bit, urutan byte "
            "(endianness), dan arsitektur CPU. Satu biner hanya jalan di arsitektur yang cocok: biner x86_64 tidak "
            "jalan langsung di HP arm64."
        ),
        diagram=(
            "  berkas ELF\n"
            "  +--------------------+\n"
            "  | 7f 45 4c 46 ...    |  <- magic + header\n"
            "  | bagian kode        |\n"
            "  | bagian data        |\n"
            "  +--------------------+"
        ),
        task="Jalankan `rea id <prog>` lalu tulis arsitektur CPU biner ini (contoh jawaban: aarch64 atau x86_64).",
        hint="Baris `arch` pada keluaran `rea id`.",
        explanation="`rea id` membaca header ELF; arsitektur ada di field e_machine, di sini disebut `arch`.",
        source=HELLO_C, dynamic="arch",
        quiz=(
            Question("Byte pertama berkas ELF adalah...", ("00 00 00 00", "7f 45 4c 46", "4d 5a 90 00"), 2),
            Question("Biner x86_64 dijalankan di HP arm64 tanpa emulator. Hasilnya?", ("Jalan normal", "Tidak bisa dijalankan langsung", "Jalan lebih cepat"), 2),
        ),
    ),
    Lesson(
        n=2, stage=1, title="Bit, byte, dan endianness",
        theory=(
            "Satu byte = 8 bit = dua digit heksadesimal. Bilangan yang lebih besar dari satu byte disimpan dengan "
            "urutan tertentu. Little-endian (x86 dan arm64) menyimpan byte paling tidak berarti lebih dulu: "
            "0x12345678 tersimpan sebagai 78 56 34 12. Salah baca urutan ini adalah kesalahan paling umum saat membaca dump memori."
        ),
        diagram=(
            "  nilai 0x12345678\n"
            "  alamat:  +0  +1  +2  +3\n"
            "  little:  78  56  34  12\n"
            "  big:     12  34  56  78"
        ),
        task="Program ini menyimpan konstanta 0x12345678 di variabel global. Tulis byte pertamanya di memori (2 digit hex).",
        hint="`objdump -s -j .data <prog>` menampilkan isi bagian data; cari deretan 78 56 34 12.",
        explanation="Pada little-endian byte terendah (0x78) berada di alamat terendah, jadi byte pertama adalah 78.",
        source=(
            "#include <stdio.h>\n"
            "unsigned int konstanta = 0x12345678;\n"
            "int main(void) {\n"
            "    printf(\"%u\\n\", konstanta);\n"
            "    return 0;\n"
            "}\n"
        ),
        accepted=("78", "0x78"),
        quiz=(
            Question("0x12345678 pada little-endian tersimpan sebagai...", ("12 34 56 78", "78 56 34 12", "21 43 65 87"), 2),
            Question("Berapa bit dalam dua digit heksadesimal?", ("4", "8", "16"), 2),
        ),
    ),
    Lesson(
        n=3, stage=2, title="Header ELF, segmen, dan section",
        theory=(
            "ELF punya dua sudut pandang. Segmen (program header) memberi tahu loader apa yang dimuat ke memori dan "
            "dengan izin apa (baca/tulis/jalan). Section memberi tahu alat analisis arti tiap bagian: .text (kode), "
            ".rodata (konstanta), .data (data), .symtab (simbol). Loader hanya memakai segmen bertipe LOAD."
        ),
        diagram=(
            "  ELF header\n"
            "  program header --> segmen LOAD (dimuat)\n"
            "  .text .rodata .data ...   <- section\n"
            "  section header (untuk alat)"
        ),
        task="Jalankan `rea analyze <prog>` dan hitung segmen bertipe LOAD. Tulis jumlahnya.",
        hint="Lihat daftar segmen di keluaran, atau `readelf -l <prog>`.",
        explanation="Hanya segmen LOAD yang dipetakan ke memori; jumlahnya bergantung pada kompilator, jadi selalu hitung dari biner.",
        source=HELLO_C, dynamic="load_segments",
        quiz=(
            Question("Bagian yang dipakai loader untuk memuat program adalah...", ("Section", "Segmen LOAD", "Simbol"), 2),
            Question("Kode mesin program berada di section...", (".text", ".rodata", ".bss"), 1),
        ),
    ),
    Lesson(
        n=4, stage=2, title="Simbol dan fungsi",
        theory=(
            "Tabel simbol memetakan nama ke alamat. Biner yang tidak di-strip menyimpan nama fungsi, yang sangat "
            "mempercepat analisis. Biner yang di-strip hanya menyisakan alamat, sehingga fungsi harus dikenali "
            "dari pola kodenya. Selalu periksa dulu apakah simbol tersedia sebelum membaca disassembly."
        ),
        diagram=(
            "  nama            alamat\n"
            "  main       ->   0x....\n"
            "  fungsi_x   ->   0x....   (hilang jika di-strip)"
        ),
        task="Program ini punya satu fungsi buatan sendiri selain `main`. Tulis namanya.",
        hint="`rea analyze <prog>` memuat daftar fungsi; abaikan fungsi bawaan sistem seperti _start.",
        explanation="Nama fungsi ada di .symtab. Biner ini tidak di-strip sehingga namanya terlihat.",
        source=(
            "#include <string.h>\n"
            "int secret_check(const char *s) { return s[0] == 'R'; }\n"
            "int main(int argc, char **argv) {\n"
            "    return argc > 1 ? secret_check(argv[1]) : 0;\n"
            "}\n"
        ),
        accepted=("secret_check",),
        quiz=(
            Question("Biner yang di-strip...", ("Tidak punya kode", "Kehilangan nama simbol", "Tidak bisa dijalankan"), 2),
            Question("Tabel simbol memetakan...", ("Nama ke alamat", "Alamat ke warna", "Register ke memori"), 1),
        ),
    ),
    Lesson(
        n=5, stage=3, title="Mencari string",
        theory=(
            "String konstanta disimpan di .rodata dan hampir selalu terbaca apa adanya. Itu sebabnya string adalah "
            "langkah pertama analisis: pesan galat, URL, jalur berkas, dan kata sandi yang ditulis langsung di kode "
            "muncul di sini. `rea analyze --strings --interesting` menyaring yang menarik."
        ),
        diagram=(
            "  .rodata\n"
            "  \"Pemakaian: prog <kunci>\"\n"
            "  \"REA{....}\"       <- yang dibandingkan program\n"
            "  \"benar\" / \"salah\""
        ),
        task="Program membandingkan argumen dengan sebuah kata kunci berformat REA{...}. Temukan kata kunci itu dan tulis lengkap.",
        hint="`rea analyze <prog> --strings --interesting`, atau `strings <prog>`.",
        explanation="Perbandingan strcmp memakai literal di .rodata; string itu terbaca langsung tanpa menjalankan program.",
        source=(
            "#include <stdio.h>\n"
            "#include <string.h>\n"
            "int main(int argc, char **argv) {\n"
            "    if (argc < 2) { puts(\"Pemakaian: prog <kunci>\"); return 2; }\n"
            "    if (strcmp(argv[1], \"REA{string-di-rodata}\") == 0) { puts(\"benar\"); return 0; }\n"
            "    puts(\"salah\");\n"
            "    return 1;\n"
            "}\n"
        ),
        accepted=("REA{string-di-rodata}",),
        quiz=(
            Question("String konstanta C disimpan di...", (".rodata", ".bss", ".symtab"), 1),
            Question("Alat paling cepat untuk melihat teks tertanam adalah...", ("Debugger", "Pencari string", "Kompilator"), 2),
        ),
    ),
    Lesson(
        n=6, stage=3, title="Membaca disassembly",
        theory=(
            "Disassembly menerjemahkan byte kode menjadi instruksi. Pada arm64 nilai kembali fungsi ada di register "
            "w0/x0; pada x86_64 di eax/rax. Fungsi kecil di -O0 mudah dibaca: nilai konstanta dimasukkan ke register "
            "kembali lalu `ret`. Kuncinya adalah mencari instruksi `mov` ke register nilai-kembali sebelum `ret`."
        ),
        diagram=(
            "  arm64:   mov w0, #42     x86_64:  mov eax, 0x2a\n"
            "           ret                      pop rbp / ret"
        ),
        task="Fungsi `magic` mengembalikan sebuah angka. Baca disassembly-nya dan tulis angka itu (desimal).",
        hint="`rea analyze <prog> --disasm magic`. Angka heksadesimal seperti 0x2a perlu diubah ke desimal.",
        explanation="Instruksi mov ke register nilai-kembali sebelum ret menentukan hasil fungsi: 0x2a = 42.",
        source=(
            "int magic(void) { return 42; }\n"
            "int main(void) { return magic() == 42 ? 0 : 1; }\n"
        ),
        accepted=("42", "0x2a"),
        quiz=(
            Question("Nilai kembali fungsi pada arm64 ada di register...", ("w0/x0", "sp", "pc"), 1),
            Question("0x2a dalam desimal adalah...", ("24", "42", "52"), 2),
        ),
    ),
    Lesson(
        n=7, stage=3, title="Control flow graph",
        theory=(
            "CFG memecah fungsi menjadi blok dasar: urutan instruksi tanpa percabangan di tengah. Tiap percabangan "
            "(if, loop) menambah edge antar blok. Dari bentuk graf kamu bisa membaca struktur program tanpa membaca "
            "tiap instruksi: berapa kondisi, apakah ada perulangan (edge mundur)."
        ),
        diagram=(
            "   [masuk]\n"
            "    /   \\\n"
            " [x<0] [x>=0]\n"
            "    \\   /\n"
            "   [keluar]"
        ),
        task="Jalankan CFG untuk fungsi `check` dan tulis jumlah blok dasarnya.",
        hint="`rea analyze <prog> --cfg check`; hitung blok pada keluaran (atau lihat field blocks pada --json).",
        explanation="Jumlah blok bergantung pada kompilator dan arsitektur, jadi dihitung langsung dari biner kamu.",
        source=(
            "int check(int x) {\n"
            "    if (x < 0) return -1;\n"
            "    if (x == 0) return 0;\n"
            "    return 1;\n"
            "}\n"
            "int main(int argc, char **argv) { (void)argv; return check(argc); }\n"
        ),
        dynamic="check_blocks",
        quiz=(
            Question("Blok dasar adalah...", ("Satu fungsi utuh", "Instruksi berurutan tanpa percabangan di tengah", "Satu baris C"), 2),
            Question("Perulangan terlihat di CFG sebagai...", ("Edge mundur", "Blok kosong", "Simbol hilang"), 1),
        ),
    ),
    Lesson(
        n=8, stage=4, title="Menjejak syscall",
        theory=(
            "Program berbicara dengan sistem operasi lewat syscall: open, read, write, connect, dan seterusnya. "
            "Menjejak syscall (strace) menunjukkan perilaku nyata tanpa membaca kode: berkas apa yang dibuka, "
            "ke mana terhubung. Ini analisis dinamis: program benar-benar dijalankan, jadi hanya lakukan pada program yang kamu percayai."
        ),
        diagram=(
            "  program  --syscall-->  kernel\n"
            "  openat(\"/tmp/...\")      <- terlihat di strace\n"
            "  write(1, \"...\")"
        ),
        task="Program mencoba membuka satu berkas. Tulis jalur berkas itu.",
        hint="`rea run <prog> --mode strace --trace file` (butuh strace). Tanpa strace, jalur itu juga terbaca di string.",
        explanation="Syscall openat menerima jalur sebagai argumen pertama; jalurnya terlihat di jejak maupun di string biner.",
        source=(
            "#include <stdio.h>\n"
            "int main(void) {\n"
            "    FILE *f = fopen(\"/tmp/rea-rahasia.txt\", \"r\");\n"
            "    if (!f) { puts(\"tidak ada\"); return 1; }\n"
            "    fclose(f);\n"
            "    return 0;\n"
            "}\n"
        ),
        requires=("strace",), accepted=("/tmp/rea-rahasia.txt",),
        quiz=(
            Question("Program meminta layanan kernel lewat...", ("Syscall", "Kompilasi", "Linker"), 1),
            Question("Analisis dinamis berarti...", ("Membaca kode tanpa menjalankan", "Menjalankan program saat dianalisis", "Mengubah ukuran berkas"), 2),
        ),
    ),
    Lesson(
        n=9, stage=4, title="Breakpoint dan register",
        theory=(
            "Debugger menghentikan program di titik yang kamu pilih dan menunjukkan register serta memori. Pada arm64 "
            "argumen fungsi berurutan di x0, x1, x2, ...; pada x86_64 di rdi, rsi, rdx, ... Dengan breakpoint di awal "
            "fungsi, register menunjukkan argumen yang sebenarnya diterima."
        ),
        diagram=(
            "  add(7, 35)\n"
            "  arm64:   x0=7  x1=35\n"
            "  x86_64:  rdi=7 rsi=35"
        ),
        task="Pasang breakpoint di fungsi `add`. Tulis nilai argumen KEDUA yang diterima.",
        hint="`rea run <prog> --mode gdb --break add` (arm64: x1, x86_64: rsi). Alternatif tanpa ptrace: `--mode emu`.",
        explanation="Argumen kedua ada di register kedua konvensi panggilan: x1 pada arm64, rsi pada x86_64.",
        source=(
            "int add(int a, int b) { return a + b; }\n"
            "int main(void) { return add(7, 35) == 42 ? 0 : 1; }\n"
        ),
        requires=("gdb",), accepted=("35", "0x23"),
        quiz=(
            Question("Argumen pertama fungsi pada arm64 ada di...", ("x0", "x30", "sp"), 1),
            Question("Breakpoint di awal fungsi menunjukkan...", ("Argumen yang diterima", "Ukuran berkas", "Nama pengguna"), 1),
        ),
    ),
    Lesson(
        n=10, stage=5, title="Obfuskasi XOR dan emulasi",
        theory=(
            "Penulis program sering menyembunyikan string dengan XOR sederhana: tiap byte di-XOR dengan satu kunci, "
            "dan program mendekripsinya saat jalan. XOR dua kali dengan kunci yang sama mengembalikan data asli. Kamu "
            "bisa mendekripsi tanpa menjalankan program utuh: emulasikan hanya fungsi dekripsinya (`rea emu`)."
        ),
        diagram=(
            "  tersimpan: 28 3f 3b ... (acak)\n"
            "     XOR 0x5a (tiap byte)\n"
            "  hasil:     \"teks asli\""
        ),
        task="Dekripsi `blob` dengan fungsi `decode` dan kunci 0x5a. Tulis teks aslinya.",
        hint=(
            "Ambil byte blob dengan `objdump -s -j .data <prog>`, lalu `rea emu <prog> --func decode --reg ... --mem ... --dump ...`. "
            "Jangan menjalankan programnya; latih emulasi."
        ),
        explanation="decode meng-XOR tiap byte dengan kunci; mengemulasikan fungsi itu pada byte blob menghasilkan teks asli.",
        source=(
            "#include <stdio.h>\n"
            "static unsigned char blob[] = { {BLOB} };\n"
            "void decode(unsigned char *buf, int n, unsigned char key) {\n"
            "    for (int i = 0; i < n; i++) buf[i] ^= key;\n"
            "}\n"
            "int main(void) {\n"
            "    decode(blob, (int)sizeof blob, 0x5a);\n"
            "    puts((char *)blob);\n"
            "    return 0;\n"
            "}\n"
        ),
        requires=("unicorn",), accepted=("rea-rahasia",),
        quiz=(
            Question("XOR dua kali dengan kunci yang sama menghasilkan...", ("Data asli", "Nol", "Kunci"), 1),
            Question("Mengemulasikan satu fungsi berguna karena...", ("Tidak perlu menjalankan program utuh", "Membuat program lebih cepat", "Menghapus simbol"), 1),
        ),
    ),
    Lesson(
        n=11, stage=5, title="Entropi dan program terkemas",
        theory=(
            "Entropi mengukur keacakan data (0 sampai 8 bit per byte). Teks biasa dan kode normal entropinya rendah "
            "(sekitar 3 sampai 6). Data terkompresi atau terenkripsi mendekati 8. Berkas ELF dengan entropi tinggi "
            "hampir pasti dikemas (packed) atau dienkripsi, sehingga isinya baru terbaca setelah dibuka."
        ),
        diagram=(
            "  teks      ########............  rendah\n"
            "  terkemas  ####################  ~8 bit/byte"
        ),
        task="Ada dua berkas: data.bin dan teks.txt. Periksa dengan `rea id`. Tulis nama berkas yang entropinya mencurigakan (packed).",
        hint="`rea id <berkas>` menampilkan entropi dan peringatan. Folder latihan ada di keluaran `rea learn start 11`.",
        explanation="Berkas acak mendekati 8 bit/byte dan ditandai packed; teks yang berulang entropinya rendah.",
        files="entropy_files", dynamic="packed_file",
        quiz=(
            Question("Entropi maksimum per byte adalah...", ("1", "8", "256"), 2),
            Question("Entropi tinggi pada ELF menandakan...", ("Kode ditulis rapi", "Kemungkinan dikemas atau dienkripsi", "Program kecil"), 2),
        ),
    ),
    Lesson(
        n=12, stage=6, title="Aplikasi Android (APK/DEX)",
        theory=(
            "APK adalah berkas ZIP: AndroidManifest.xml (biner), satu atau lebih classes*.dex (kode Dalvik), "
            "resource, dan pustaka native per arsitektur. Manifest memuat nama paket, izin, dan komponen. Temuan awal "
            "yang penting: android:debuggable, izin berbahaya, dan komponen yang diekspor tanpa perlindungan."
        ),
        diagram=(
            "  app.apk (ZIP)\n"
            "  |- AndroidManifest.xml   paket, izin, komponen\n"
            "  |- classes.dex           kode\n"
            "  |- lib/arm64-v8a/*.so    pustaka native\n"
            "  `- META-INF/             tanda tangan"
        ),
        task="Tulis perintah REA (dua kata) untuk meringkas manifest, izin, dan tanda tangan sebuah APK.",
        hint="Lihat `rea --help`.",
        explanation="`rea apk <berkas.apk>` membaca manifest biner, izin, komponen, DEX, pustaka native, dan tanda tangan.",
        accepted=("rea apk",),
        quiz=(
            Question("APK pada dasarnya adalah berkas...", ("ZIP", "ELF", "PNG"), 1),
            Question("Kode aplikasi Android ada di...", ("classes.dex", "manifest saja", "META-INF"), 1),
        ),
    ),
    Lesson(
        n=13, stage=7, title="Otomasi dengan keluaran JSON",
        theory=(
            "Semua perintah REA punya `--json`: satu objek JSON di baris terakhir keluaran, dengan field schema, "
            "command, ok, data, warnings, dan error. Itu memudahkan skrip dan aplikasi membaca hasil tanpa mengurai "
            "teks. Selalu periksa `ok` dan `error.code` sebelum memakai `data`."
        ),
        diagram=(
            "  rea id prog --json\n"
            "  {\"schema\":1,\"command\":\"id\",\"ok\":true,\n"
            "   \"data\":{...,\"bits\":64},\"error\":null}"
        ),
        task="Jalankan `rea id <prog> --json` dan tulis nilai field `bits` dari data.",
        hint="Baris terakhir keluaran adalah JSON; `python3 -m json.tool` merapikannya.",
        explanation="Field data.bits memuat lebar kata biner (32 atau 64).",
        source=HELLO_C, dynamic="bits",
        quiz=(
            Question("Objek JSON hasil perintah REA ada di...", ("Baris terakhir stdout", "Berkas log", "Baris pertama stderr"), 1),
            Question("Sebelum memakai data, periksa field...", ("ok", "color", "size_hint"), 1),
        ),
    ),
)

BY_NUMBER = {lesson.n: lesson for lesson in LESSONS}
