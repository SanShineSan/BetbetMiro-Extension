# BetbetMiro Extension

<div align="center">

### CloudStream provider repository maintained by @sad25kag

Anime • Donghua • Drama • Movie • Multi-Source • Age-Restricted Providers

<img src="https://img.shields.io/github/stars/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=yellow" />
<img src="https://img.shields.io/github/forks/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=blue" />
<img src="https://img.shields.io/github/license/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=green" />
<img src="https://img.shields.io/github/last-commit/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=red" />

<p>
  <strong>Language:</strong>
  Bahasa Indonesia |
  <a href="README_EN.md">English</a>
</p>

</div>

---

## Tentang Repository

**BetbetMiro Extension** adalah repository ekstensi CloudStream yang berisi kumpulan provider dari berbagai sumber. Repository ini berfokus pada pemeliharaan provider, perbaikan parser, pembaruan domain, validasi kategori, serta stabilitas playback.

Prioritas pengembangan repository ini:

1. Provider tetap aktif dan dapat digunakan.
2. Build tetap bersih dan kompatibel dengan CloudStream.
3. `repo.json` dan `plugins.json` tetap valid.
4. GitHub Actions berjalan sukses.
5. Dokumentasi jelas dan mudah dipahami.
6. Perubahan kosmetik dilakukan hanya bila diperlukan.

---

## Status Provider

Daftar provider bersifat dinamis dan dikelola secara berkala. Provider dapat ditambahkan, diperbarui, dinonaktifkan, atau dihapus sewaktu-waktu berdasarkan kondisi sumber, stabilitas, dan kebutuhan pemeliharaan.

Provider dalam repository ini bergantung pada sumber pihak ketiga. Perubahan domain, struktur halaman, proteksi akses, endpoint player, atau host video dapat memengaruhi fungsi provider tanpa pemberitahuan sebelumnya.

---

## Kategori Konten

Repository ini dapat memuat provider untuk beberapa kategori berikut:

- Anime
- Donghua
- Drama Asia
- Film Indonesia
- Film Barat dan Asia
- Multi-source provider
- Provider terbatas usia sesuai aturan wilayah masing-masing

Ketersediaan kategori mengikuti provider yang sedang aktif dan dapat berubah sewaktu-waktu.

---

## Instalasi Repository

### One-click Install

Buka link berikut dari perangkat Android yang sudah terpasang CloudStream:

```text
cloudstreamrepo://raw.githubusercontent.com/sad25kag/BetbetMiro-Extension/master/repo.json
```

### Instalasi Manual

1. Buka CloudStream.
2. Masuk ke **Settings**.
3. Buka menu **Extensions**.
4. Pilih **Add Repository**.
5. Masukkan URL berikut:

```text
https://raw.githubusercontent.com/sad25kag/BetbetMiro-Extension/master/repo.json
```

6. Simpan repository.
7. Install provider yang ingin digunakan.

---

## Build From Source

Clone repository:

```bash
git clone https://github.com/sad25kag/BetbetMiro-Extension.git
cd BetbetMiro-Extension
```

Build semua provider:

```bash
./gradlew make
```

Output build akan tersedia di:

```text
/builds
```

---

## Dokumentasi

Dokumentasi lengkap tersedia di:

- [`docs/README.md`](docs/README.md) — index dokumentasi maintainer dan contributor.

Panduan penting:

- [`docs/WORKFLOW_GUIDE.md`](docs/WORKFLOW_GUIDE.md) — alur kerja issue, evidence, patch, build, metadata, Actions, dan final status.
- [`docs/EVIDENCE_COLLECTION_GUIDE.md`](docs/EVIDENCE_COLLECTION_GUIDE.md) — standar pengumpulan bukti sebelum patch provider.
- [`docs/RUNTIME_TESTING_GUIDE.md`](docs/RUNTIME_TESTING_GUIDE.md) — standar validasi runtime di aplikasi CloudStream.
- [`docs/PROVIDER_MAINTENANCE.md`](docs/PROVIDER_MAINTENANCE.md) — standar pemeliharaan provider.
- [`docs/BUILD_GUIDE.md`](docs/BUILD_GUIDE.md) — panduan build lokal.
- [`docs/FAQ.md`](docs/FAQ.md) — pertanyaan umum.

---

## Standar Pemeliharaan Provider

Setiap perubahan provider harus menjaga stabilitas repository dan meminimalkan risiko regresi. Standar dasar yang digunakan:

- Version provider wajib dinaikkan di `build.gradle.kts` setiap ada perubahan kode.
- `search()` harus mengembalikan hasil yang relevan.
- `getMainPage()` harus sesuai kategori aktif dari sumber.
- `load()` harus memuat detail konten dengan data episode/movie yang valid.
- `loadLinks()` harus menghasilkan callback video yang valid, bukan sekadar return `true`.
- Parser harus mengikuti struktur sumber aktif berdasarkan bukti terbaru.
- Extractor harus dibatasi agar tidak memicu request berlebihan, hang, atau OutOfMemory.
- File provider lain tidak diubah kecuali memang terdampak langsung.
- Perubahan fallback, host, kategori, atau selector harus punya alasan teknis yang jelas.

---

## Workflow Perbaikan Provider

Saat provider bermasalah, investigasi dilakukan dengan alur berikut:

1. Verifikasi domain aktif.
2. Cek struktur homepage dan kategori.
3. Cek hasil search.
4. Cek halaman detail dan data episode/movie.
5. Cek iframe/player/API yang digunakan sumber.
6. Cek extractor dan host video aktif.
7. Patch akar masalah secara spesifik.
8. Bump version provider.
9. Jalankan validasi build/test bila environment tersedia.

Jenis masalah yang sering terjadi:

- Domain sumber berubah.
- Struktur HTML berubah.
- Endpoint search/kategori berubah.
- Player iframe berubah.
- Host video berubah.
- Token, referer, origin, atau cookie berubah.
- Extractor tidak lagi cocok dengan response aktif.
- Resolver terlalu luas dan memicu timeout, hang, atau OutOfMemory.

---

## Kompatibilitas CloudStream

CloudStream dan API ekstensi dapat berubah dari waktu ke waktu. Perubahan pada runtime CloudStream dapat menyebabkan provider yang sebelumnya berjalan normal menjadi gagal build, gagal load, atau gagal playback.

Contoh masalah kompatibilitas runtime:

```text
No virtual method parseJson(...)
in class com.lagradost.cloudstream3.utils.AppUtils
```

Error seperti itu biasanya menunjukkan perubahan API/runtime CloudStream, bukan selalu berarti sumber website sedang mati. Provider terkait perlu diperbarui agar sesuai dengan API CloudStream yang digunakan.

---

## Melaporkan Masalah

Saat membuat issue, sertakan informasi berikut agar investigasi lebih cepat:

- Nama provider.
- URL halaman yang bermasalah.
- Screenshot error.
- Log CloudStream atau provider test.
- Langkah reproduksi.
- Informasi apakah error terjadi di CloudStream stable atau prerelease.

Laporan yang lengkap membantu menentukan apakah masalah berasal dari domain, parser, kategori, detail page, extractor, host video, atau runtime CloudStream.

---

## Pull Request

Pull Request diterima untuk perbaikan yang jelas dan terarah, seperti:

- Perbaikan provider.
- Pembaruan domain.
- Pembaruan kategori.
- Perbaikan search/load/loadLinks.
- Perbaikan extractor.
- Optimasi performa resolver.
- Penambahan provider baru.

Checklist sebelum membuat PR:

- [ ] Version provider sudah dinaikkan.
- [ ] Build berhasil dijalankan bila environment tersedia.
- [ ] Search sudah dicek.
- [ ] Homepage/kategori sudah dicek.
- [ ] Load detail sudah dicek.
- [ ] Playback/loadLinks sudah dicek.
- [ ] File yang tidak terkait tidak diubah.

---

## Disclaimer

Repository ini tidak menyimpan, meng-host, atau mendistribusikan konten video apa pun.

Semua konten berasal dari sumber pihak ketiga yang tersedia di internet. Repository ini hanya menyediakan parser dan integrasi provider untuk CloudStream.

Pemilik repository tidak berafiliasi dengan CloudStream maupun sumber pihak ketiga yang digunakan oleh provider.

---

## Peringatan Konten Terbatas Usia

Sebagian provider dalam repository ini dapat mengakses sumber yang dibatasi usia atau wilayah.

Dengan menggunakan repository ini, pengguna menyatakan bahwa:

- Telah memenuhi batas usia yang berlaku di wilayah masing-masing.
- Bertanggung jawab penuh atas penggunaan repository dan provider di dalamnya.
- Memahami hukum, aturan, dan risiko penggunaan sumber di wilayah masing-masing.

---

## Credits

Terima kasih kepada:

- CloudStream
- Developer provider dan extractor
- Komunitas open source
- Tester dan pelapor bug
- Kontributor BetbetMiro Extension

---

<div align="center">

### BetbetMiro Extension

Maintained with parser fixes, extractor patches, source validation, and countless Gradle rebuilds.

</div>
