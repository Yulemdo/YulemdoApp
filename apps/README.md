# Yulemdo ilovalari

- `desktop/` — Windows (exe), Electron. Tab paneli, manzil qatori, menyu; sayt `https://yulemdo.uz` dan ochiladi.
- `android/` — Android Studio loyihasi (GeckoView) shu papkaga qo'yiladi.

Fayl olish: GitHub → Actions → "Build Windows (exe)" yoki "Build Android (apk)" → Run workflow →
tugagach sahifa pastidagi Artifacts ro'yxatidan yuklab oling.
Release uchun: `git tag v1.0.0 && git push origin v1.0.0` — fayllar Releases bo'limiga tushadi.

Kompyuterda sinash: `cd apps/desktop && npm install && npm start`
