# MarkItDown for Android

Convert documents to Markdown on your Android device — **offline, private, no server needed**.

Built on [MarkItDown](https://github.com/microsoft/markitdown) by Microsoft, packaged as a standalone Android app.

## Download

**[Latest Release](../../releases/latest)** — download the `prod` APK for your phone.

| Build | For | Size |
|-------|-----|------|
| `markitdown-android-prod-debug.apk` | Real phones (arm64) | ~40 MB |
| `markitdown-android-dev-debug.apk` | Emulators (arm64 + x86_64) | ~59 MB |

> On Android, you'll need to allow "Install unknown apps" for your browser or file manager.

## What it does

Pick a document → get clean Markdown. Everything runs on your device — no files leave your phone.

**Supported formats:**

| Format | Extensions |
|--------|------------|
| PDF | `.pdf` |
| Word | `.docx` |
| PowerPoint | `.pptx` |
| Excel | `.xlsx` |
| EPUB | `.epub` |
| ZIP | `.zip` (converts contents recursively) |
| Outlook | `.msg` |
| HTML | `.html`, `.htm` |
| RSS / Atom | `.xml`, `.rss`, `.atom` |
| Text | `.txt`, `.md`, `.markdown` |
| CSV | `.csv` |
| JSON | `.json`, `.jsonl` |
| Jupyter | `.ipynb` |

**Not supported** (require network or external tools): images, audio, video, YouTube URLs, legacy `.xls`.

## How to use

1. Open the app
2. Tap **Pick a file**
3. Browse to your document (tip: pick from "Downloads", not "Recents")
4. The Markdown appears — tap **Share** to send it somewhere

## Why Markdown?

Markdown is plain text with minimal formatting — perfect for feeding into LLMs, note-taking apps, or text editors. It preserves structure (headings, lists, tables) without the bloat of Word or PDF.

## Privacy

- **No network permission** — the app literally cannot send data anywhere
- **No tracking, no analytics**
- **No cloud services** — everything runs on-device

---

## For developers

### Building from source

**Prerequisites:**
- JDK 17 or 21 (Java 25 is too new for Gradle 8.9)
- Python 3.12 (must match `chaquopy.version`)
- Android SDK (Platform 35, Build-Tools 35.0.0)

**Quick start (Linux):**

```sh
# Install JDK 21
curl -fL -o /tmp/jdk21.tar.gz https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse
mkdir -p ~/tools/jdk21 && tar -xzf /tmp/jdk21.tar.gz -C ~/tools/jdk21 --strip-components=1
export JAVA_HOME=~/tools/jdk21

# Install Python 3.12
curl -LsSf https://astral.sh/uv/install.sh | sh
$HOME/.local/bin/uv python install 3.12
export PATH=$HOME/.local/bin:$PATH

# Install Android SDK
curl -fL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
mkdir -p ~/android-sdk/cmdline-tools && unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools
mv /tmp/cmdtools/cmdline-tools ~/android-sdk/cmdline-tools/latest
yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
~/android-sdk/cmdline-tools/latest/bin/sdkmanager \
    "platform-tools" "platforms;android-35" "build-tools;35.0.0"
export ANDROID_HOME=~/android-sdk

# Write local.properties
echo "sdk.dir=$HOME/android-sdk" > local.properties

# Build
./gradlew :app:assembleProdDebug
```

**Or open in Android Studio** — it handles JDK, SDK, and Python setup automatically.

### Project structure

```
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/com/markitdown/android/
│       │   ├── MarkItDownApp.kt      # Starts embedded Python
│       │   └── MainActivity.kt       # File picker + conversion
│       ├── python/
│       │   ├── markitdown/           # Patched MarkItDown source (MIT)
│       │   ├── markitdown_android.py # Bridge module
│       │   └── offline_converters.py # Lightweight .xlsx converter
│       └── res/                      # UI resources
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

### How it works

The app uses [Chaquopy](https://chaquo.com/chaquopy/) to embed Python 3.12 directly in the Android APK. MarkItDown (a Python library) runs inside this embedded interpreter, converting documents to Markdown without any server or network.

**Key architectural decisions:**

- **Bundled source** — `pip install markitdown` can't be used because it depends on `magika` (needs `onnxruntime`, no Android wheel). The source is copied into `app/src/main/python/markitdown/` with two patches.
- **Patches:**
  1. `_markitdown.py` — makes `magika` optional (falls back to extension-based detection)
  2. `converters/_pdf_converter.py` — makes `pdfplumber` optional (falls back to `pdfminer.six`)
- **Custom XLSX converter** — `offline_converters.py` replaces the pandas-based one with a lightweight `openpyxl` version (~30 MB smaller APK)
- **Offline blocklist** — image, audio, YouTube, Wikipedia, and Bing converters are disabled

### Updating from upstream

The bundled MarkItDown source (v0.1.7) can be updated from [upstream](https://github.com/microsoft/markitdown):

```sh
# Clone upstream into the gitignored reference directory
git clone https://github.com/microsoft/markitdown.git markitdown

# Copy the new source
cp -r markitdown/packages/markitdown/src/markitdown app/src/main/python/markitdown

# Re-apply the two patches (search for 'magika' and 'pdfplumber')
```

### CI/CD

GitHub Actions builds both flavors on every tag push. To create a release:

```sh
git tag v0.1.0
git push origin v0.1.0
```

Both APKs are automatically attached to the GitHub Release.

### Troubleshooting

| Error | Fix |
|-------|-----|
| `Current thread does not hold the state lock` | `org.gradle.parallel=true` races with KGP on Gradle 8.9. It's disabled in `gradle.properties` — don't re-enable. |
| `No matching distribution found` | The package has no Android wheel. Check `app/build.gradle.kts` pip block. |
| Python version mismatch | `buildPython` must match `chaquopy.version` (3.12). |
| File open fails from Recents | The app now shows a dialog asking you to pick again from a real location (e.g. "Downloads"); Recents entries can be stale. |

## License

MIT. The bundled MarkItDown source retains its original copyright headers.
