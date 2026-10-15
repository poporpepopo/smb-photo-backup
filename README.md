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

実機での確認手順は [docs/device-testing.md](docs/device-testing.md)。

## 構成

| パス | 役割 |
|---|---|
| `settings/SettingsRepository.kt` | 設定の読み書き。パスワードだけは別扱いで、平文の設定オブジェクトに載せない |
| `settings/PasswordCipher.kt` | Keystore 鍵での暗号化・復号。鍵が消えていたら null を返し、再入力を促す |
| `smb/SmbGateway.kt` | 1回の処理ごとに接続→認証→共有を開く |
| `smb/HostProbe.kt` | 445番ポートへの短いタイムアウトでの接続。「家にいるか」の判定にも使う |
| `smb/SmbFailure.kt` | smbj の例外を、利用者が対処できる粒度に分類 |
| `smb/RemotePath.kt` | 共有内のパスは `/` 区切りで持ち、smbj に渡す直前だけ `\` に変える |
| `ui/` | 設定画面 |

バックアップと機種変更時の転送はどちらも無効にしています。暗号化したパスワードは
この端末の Keystore 鍵でしか復号できず、移しても使えないためです。
