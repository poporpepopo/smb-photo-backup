# SMB Photo Backup

Android の写真と動画を、自宅ルーターの USB ストレージ（SMB 共有）へ自動でバックアップするアプリ。

- Kotlin / Jetpack Compose / minSdk 29 / targetSdk 37
- SMB：smbj（SMB2/3）、NTLM の MD4 は BouncyCastle を直接使う
- 設定：DataStore。SMB のパスワードは Android Keystore の AES-256-GCM 鍵で暗号化して保存

## ビルド

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

実機での確認手順と、ルーターなしでの試し方は [docs/device-testing.md](docs/device-testing.md)。

## 構成

| パス | 役割 |
|---|---|
| `settings/SettingsRepository.kt` | 設定の読み書き。パスワードだけは別扱いで、平文の設定オブジェクトに載せない |
| `settings/PasswordCipher.kt` | Keystore 鍵での暗号化・復号。鍵が消えていたら null を返し、再入力を促す |
| `smb/SmbGateway.kt` | 1回の処理ごとに接続→認証→共有を開く |
| `smb/HostProbe.kt` | 445番ポートへの短いタイムアウトでの接続。「家にいるか」の判定にも使う |
| `smb/SmbFailure.kt` | smbj の例外を、利用者が対処できる粒度に分類 |
| `smb/RemotePath.kt` | 共有内のパスは `/` 区切りで持ち、smbj に渡す直前だけ `\` に変える |
| `smb/ShareStat.kt` | 1往復で種類とサイズを取る存在確認。サーバーごとに違う「無い」の返し方をまとめて扱う |
| `media/MediaStoreSource.kt` | 写真・動画の一覧と、GPS を削られていない元データの読み出し（読み取り専用） |
| `media/MediaPermissions.kt` | 全件・限定（Android 14+）・なし の判定と、要求する権限の一覧 |
| `backup/Destination.kt` | 保存先 `<フォルダ>/年/月`、ファイル名の無害化、`名前 (n).拡張子` の連番 |
| `backup/NameResolver.kt` | 同名があれば中身を比べ、同じなら送信済み、違えば次の連番 |
| `backup/Uploader.kt` | 一時ファイルに書く → サーバー上のサイズを確認 → 上書き禁止でリネーム |
| `ui/` | ホーム画面（権限・件数・1件送信のテスト）と設定画面 |

バックアップと機種変更時の転送はどちらも無効にしています。暗号化したパスワードは
この端末の Keystore 鍵でしか復号できず、移しても使えないためです。
