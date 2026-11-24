# HardcoreSMP (Paper 1.21.11, Java 21)

## Build jadi .jar
Opsi A (tanpa install apa pun):
1. Upload folder ini ke repo GitHub (private boleh).
2. Tab **Actions** -> workflow **Build** -> tunggu hijau.
3. Download artifact **HardcoreSMP** -> isinya `HardcoreSMP.jar`.

Opsi B (lokal): JDK 21 + Maven, jalankan `mvn package` -> `target/HardcoreSMP.jar`.
Taruh di folder `plugins/` server Paper.

## Pakai
- `/hcs` atau `/hcs gui` : buka GUI setting (semua pengaturan, termasuk editor recipe 3x3)
- `/hcs reload` : baca ulang config.yml dari disk
- `/hcs mace list|remove <player>|clear`
- `/hcs event list` | `/hcs event start <pvp|totem|heavycore|mobdrops> [menit]` | `/hcs event stop <id|all>`
Permission: `hardcoresmp.admin` (default op), `hardcoresmp.bypass`

## Kontrol GUI
- Angka: klik kiri +, klik kanan -, Shift = x10
- ON/OFF: klik
- Teks / material / entity: klik, lalu ketik di chat (`cancel` untuk batal).
  Untuk material bisa juga klik sambil memegang item di cursor.
- Daftar (EZ Craft, Mob Drops, Item Limits): tombol hijau "Tambah" di bawah.
