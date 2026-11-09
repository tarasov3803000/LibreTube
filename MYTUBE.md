# MyTube — семейная сборка LibreTube

Ветка `family` форка LibreTube: своё название (MyTube), значок и `applicationId`
`pro.antontarasov.mytube`, поэтому ставится рядом с обычным LibreTube и не мешает ему.
Наши правки помечены в коде комментарием «наша сборка».

## Установка и обновления через Obtainium

Obtainium — менеджер обновлений, который берёт APK прямо из выпусков GitHub.

1. Поставить Obtainium: https://github.com/ImranR98/Obtainium/releases (файл
   `app-arm64-v8a-release.apk`) или из F-Droid / IzzyOnDroid.
2. В Obtainium: **Add App** → в поле URL вставить
   `https://github.com/tarasov3803000/LibreTube`
3. Ниже, в настройках источника:
   - **Filter APKs by regular expression**: `MyTube-.*\.apk`
   - **Filter release titles by regular expression**: `-mytube\.`
   (остальное не трогать)
4. **Add** → **Install**. Android спросит разрешение «Установка из этого источника» для
   Obtainium — разрешить.
5. Obtainium сам проверяет обновления (по умолчанию раз в 6 часов) и присылает уведомление;
   обновление ставится одной кнопкой, данные приложения сохраняются.

Если MyTube уже поставлен по шнуру (adb) — в Obtainium после шага 3 нажать **Add**, он
распознает установленную версию и дальше будет обновлять её. Подпись та же, переустановка
не нужна.

## Выпуск новой версии

1. В `app/build.gradle.kts` поднять `versionCode` (наш номер выпуска: `72 * 100 + 2`, …;
   после обновления upstream — `<новый код upstream> * 100 + 1`) и `versionName`
   (`<версия upstream>-mytube.<N>`).
2. Закоммитить в `family`, запушить.
3. Тег ровно как `versionName`:
   `git tag -a 32.1-mytube.2 -m "что нового" && git push origin 32.1-mytube.2`
4. GitHub Actions (`.github/workflows/mytube-release.yml`) соберёт APK, подпишет ключом MyTube,
   сверит версию и отпечаток подписи и выложит выпуск с файлом `MyTube-<версия>.apk`.
   Текст выпуска — сообщение тега. Минут шесть.

## Ключ подписи

Хранится вне репозитория (у Антона — папка `MyTube-ключ-подписи`: `mytube-release.jks` и
`keystore.properties` с паролем) и в секретах репозитория: `MYTUBE_KEYSTORE_B64`,
`MYTUBE_KEYSTORE_PASSWORD`, `MYTUBE_KEY_ALIAS`. PKCS12, RSA 4096, срок 100 лет.
Отпечаток SHA-256: `E1:66:B5:5D:01:AA:35:5D:1C:75:58:B0:9D:C3:59:28:15:4A:B1:AC:5F:91:FD:02:D5:5D:8E:97:F2:9B:D6:9B`.

**Потеря ключа = на всех устройствах удалить приложение вместе с данными** и поставить заново:
Android не принимает обновление с другой подписью.

## Обновление из upstream

```
git fetch upstream && git rebase upstream/master
```
Конфликты возможны только в строках с пометкой «наша сборка».
