# 🚀 BetbetMiro Extension

<div align="center">

# 🍿 BetbetMiro Extension

### Anime • Donghua • Drama • Movie • Multi-Source • NSFW

Repository CloudStream yang berfokus pada provider aktif, update cepat, dan perbaikan berkelanjutan.

<img src="https://img.shields.io/github/stars/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=yellow" />
<img src="https://img.shields.io/github/forks/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=blue" />
<img src="https://img.shields.io/github/license/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=green" />
<img src="https://img.shields.io/github/last-commit/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=red" />

</div>

---

## 🎬 Tentang Repository

BetbetMiro Extension adalah repository CloudStream yang menyediakan berbagai provider streaming dari berbagai sumber.

Fokus utama repository ini:

- ⚡ Provider aktif dan terawat
- 🔄 Perbaikan cepat saat source berubah
- 🧩 Dukungan Anime, Donghua, Drama, Movie, dan Multi-Source
- 🎥 Prioritas pada playback yang stabil
- 📱 Kompatibel dengan CloudStream versi terbaru

---

## 📊 Repository Highlights

| Informasi | Status |
|------------|---------|
| Repository Status | 🟢 Aktif |
| Auto Update | 🟢 Didukung |
| CloudStream Compatible | 🟢 Ya |
| Open Source | 🟢 Ya |
| Android Support | 🟢 Ya |
| Continuous Maintenance | 🟢 Aktif |

---

## 🎭 Kategori Konten

### 🎌 Anime

Provider anime subtitle Indonesia dan multi-language.

### 🐉 Donghua

Berbagai source donghua populer.

### 📺 Drama Asia

Drama China, Korea, Jepang, Thailand, dan lainnya.

### 🎬 Movie

Film Indonesia, Barat, Asia, dan multi-source.

### 🌐 Multi-Source

Provider yang menggabungkan berbagai sumber dalam satu ekstensi.

### 🔞 NSFW

Provider khusus konten dewasa untuk pengguna yang memenuhi syarat usia.

---

## 📥 Install Repository

### ✅ One Click Install

Buka dari perangkat Android yang sudah terpasang CloudStream:

```text
cloudstreamrepo://raw.githubusercontent.com/sad25kag/BetbetMiro-Extension/master/repo.json
```

---

### 🧩 Install Manual

1. Buka CloudStream
2. Settings
3. Extensions
4. Add Repository
5. Masukkan URL berikut:

```text
https://raw.githubusercontent.com/sad25kag/BetbetMiro-Extension/master/repo.json
```

6. Install Repository
7. Selesai 🎉

---

## 🛠️ Build From Source

Clone repository:

```bash
git clone https://github.com/sad25kag/BetbetMiro-Extension.git
cd BetbetMiro-Extension
```

Build:

```bash
./gradlew make
```

Output build:

```text
/builds
```

---

## ⚙️ Standar BetbetMiro

Perbaikan provider wajib mengikuti prinsip berikut:

- ✅ Bump version pada `build.gradle.kts`
- ✅ Search harus berfungsi
- ✅ Homepage harus berfungsi
- ✅ Load detail harus berfungsi
- ✅ `loadLinks()` harus menghasilkan link yang valid
- ✅ Kategori mengikuti website sumber
- ✅ Tidak mengubah bagian yang sudah stabil tanpa alasan
- ✅ Menghindari parser crash
- ✅ Menghindari URL kosong dan URL relatif yang tidak ditangani
- ✅ Menghindari membaca response besar dengan `.text`

---

## 🚦 Workflow Perbaikan Provider

Saat provider bermasalah:

1. Verifikasi domain sumber
2. Verifikasi `search()`
3. Verifikasi homepage
4. Verifikasi `load()`
5. Verifikasi `loadLinks()`
6. Verifikasi extractor
7. Verifikasi playback langsung

Masalah yang paling sering terjadi:

- Domain berubah
- Struktur HTML berubah
- Extractor mati
- URL video berubah
- Link embed berubah
- OOM akibat membaca file besar

---

## 🧭 Catatan Kompatibilitas & Investigasi

CloudStream dan source website dapat berubah sewaktu-waktu. Beberapa provider mungkin mengalami error setelah update aplikasi, terutama pada versi prerelease, misalnya:

```text
No virtual method parseJson(...)
in class com.lagradost.cloudstream3.utils.AppUtils
```

Error seperti ini biasanya menandakan perubahan kompatibilitas API/runtime CloudStream, bukan selalu berarti website sumber sedang mati. Provider yang masih memakai parser lama perlu diperbarui agar sesuai dengan runtime CloudStream terbaru.

---

## 📝 Melaporkan Error

Saat membuat issue, sertakan:

- Nama provider
- URL halaman yang bermasalah
- Screenshot error
- Log CloudStream
- Langkah reproduksi

Semakin lengkap informasi yang diberikan, semakin cepat proses investigasi.

---

## 🤝 Pull Request

Pull Request sangat diterima untuk:

- Perbaikan provider
- Penambahan kategori
- Perbaikan extractor
- Perbaikan parser
- Perbaikan performa
- Provider baru

Checklist sebelum PR:

- [ ] Version sudah dinaikkan
- [ ] Build berhasil
- [ ] Search berhasil
- [ ] Load detail berhasil
- [ ] Playback berhasil
- [ ] Tidak merusak provider lain

---

## ⚠️ Disclaimer

Repository ini tidak menyimpan, meng-host, atau mendistribusikan konten video apa pun.

Semua konten berasal dari sumber pihak ketiga yang tersedia secara publik di internet.

Pengembang repository hanya menyediakan parser dan integrasi untuk CloudStream.

---

## 🔞 Peringatan Konten Dewasa

Sebagian ekstensi dalam repository ini dapat mengakses konten dewasa (NSFW).

Dengan menggunakan repository ini, pengguna menyatakan bahwa:

- Berusia minimal 18 tahun atau sesuai hukum setempat.
- Bertanggung jawab penuh atas penggunaan masing-masing.
- Memahami risiko dan aturan yang berlaku di wilayahnya.

---

## ❤️ Credits

Terima kasih kepada:

- CloudStream
- Semua developer provider
- Komunitas Open Source
- Para tester
- Pelapor bug
- Kontributor repository

---

<div align="center">

# 🍿 Happy Streaming

### Built with ☕, parser fixes, extractor patches, and countless Gradle rebuilds.

**BetbetMiro Extension**

</div>
