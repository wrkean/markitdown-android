# MarkItDown for Android

An Android app that converts documents to Markdown **entirely on-device**, using
[MarkItDown](https://github.com/microsoft/markitdown) embedded via
[Chaquopy](https://chaquo.com/chaquopy/). No network, no server, no API keys.

The app **only enables the converters that work in offline mode**. Everything
that needs a network connection, a cloud API, or an external binary is disabled.

## Supported formats

| Format         | Extensions                              | Notes |
|----------------|-----------------------------------------|-------|
| PDF            | `.pdf`                                  | Pure-Python `pdfminer.six` text extraction (no `pdfplumber`/`pypdfium2` on Android) |
| Word           | `.docx`                                 | Via `mammoth` |
| PowerPoint     | `.pptx`                                 | Via `python-pptx` |
| Excel          | `.xlsx`                                 | Lightweight bundled `openpyxl` converter (no pandas/numpy) |
| EPUB           | `.epub`                                 | |
| ZIP            | `.zip`                                  | Recursively converts contents |
| Outlook        | `.msg`                                  | Via `olefile` |
| HTML / XHTML   | `.html`, `.htm`                         | |
| RSS / Atom     | `.xml`, `.rss`, `.atom`                 | Local feed files only |
| Text / Markdown| `.txt`, `.text`, `.md`, `.markdown`     | |
| CSV            | `.csv`                                  | Charset auto-detected |
| JSON           | `.json`, `.jsonl`                       | |
| Jupyter        | `.ipynb`                                | |

### Deliberately disabled (don't work offline)

`ImageConverter` (needs `exiftool` / LLM), `AudioConverter` (needs `ffmpeg` + speech recognition),
URL-based converters (`YouTubeConverter`, `WikipediaConverter`, `BingSerpConverter`), and the
pandas-based `XlsxConverter`/`XlsConverter` (replaced by lightweight `openpyxl` converter; legacy
`.xls` dropped). Azure Document Intelligence / Content Understanding are cloud-only.

If you pick one of these file types, the app shows a clear "unsupported" error.

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| **JDK** | 17 or 21 | Gradle daemon + AGP compilation. Java 25 is too new for Gradle 8.9. |
| **Python** | 3.12 | Chaquopy's `buildPython` must match `chaquopy.version`. |
| **Android SDK** | Platform 35, Build-Tools 35.0.0 | Standard Android build. |

### Installing on Linux (user-local, no sudo)

```sh
# JDK 21 (Temurin)
curl -fL -o /tmp/jdk21.tar.gz https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse
mkdir -p ~/tools/jdk21 && tar -xzf /tmp/jdk21.tar.gz -C ~/tools/jdk21 --strip-components=1
export JAVA_HOME=~/tools/jdk21
export PATH=$JAVA_HOME/bin:$PATH

# Python 3.12 (via uv)
curl -LsSf https://astral.sh/uv/install.sh | sh
$HOME/.local/bin/uv python install 3.12
export PATH=$HOME/.local/bin:$PATH

# Android SDK (cmdline-tools)
curl -fL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
mkdir -p ~/android-sdk/cmdline-tools && unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools
mv /tmp/cmdtools/cmdline-tools ~/android-sdk/cmdline-tools/latest
yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
~/android-sdk/cmdline-tools/latest/bin/sdkmanager \
    "platform-tools" "platforms;android-35" "build-tools;35.0.0"
export ANDROID_HOME=~/android-sdk
```

Then write `local.properties` in the project root:
```
sdk.dir=/home/<you>/android-sdk
```

## Building

```sh
export JAVA_HOME=~/tools/jdk21
export PATH=$JAVA_HOME/bin:$HOME/.local/bin:$PATH
export ANDROID_HOME=~/android-sdk

./gradlew :app:assembleDevDebug    # arm64 + x86_64 (emulators)
./gradlew :app:assembleProdDebug   # arm64-only (real devices, ~40 MB)
```

First build downloads Chaquopy wheels and takes several minutes; subsequent builds
are fast.

## Installing

```sh
# Over Wi-Fi (requires one-time USB `adb tcpip 5555`, or wireless debugging)
$HOME/android-sdk/platform-tools/adb install -r \
    app/build/outputs/apk/prod/debug/app-prod-debug.apk

# Over local HTTP (no adb needed)
cd app/build/outputs/apk/prod/debug
python3 -m http.server 8080
# Then open http://<your-pc-ip>:8080/app-prod-debug.apk on your phone
```

## Project structure

```
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/markitdown/android/
│       │   ├── MarkItDownApp.kt      # Starts embedded Python interpreter
│       │   └── MainActivity.kt       # SAF file picker + conversion
│       ├── python/
│       │   ├── markitdown/           # Bundled MarkItDown source (patched, MIT)
│       │   ├── markitdown_android.py # Bridge: bytes + filename → Markdown
│       │   └── offline_converters.py # Lightweight openpyxl-based .xlsx converter
│       └── res/                      # Layouts, themes, launcher icon
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── README.md
```

## How it works

### Why a bundled copy of the source?

`pip install markitdown` is **not** used because MarkItDown hard-depends on
`magika` (file-type detection), which requires `onnxruntime` — a native library
with **no Android wheel**. The MarkItDown source is copied into
`app/src/main/python/markitdown/` with two minimal patches, and only the wheels
that exist for Android are installed via the `chaquopy { pip { ... } }` block.

### Patches

1. **`_markitdown.py`** — makes `magika` optional. Without it, MarkItDown
   detects file types from the file extension/mimetype instead of content sniffing.
   This is why the app always passes the original filename through.
2. **`converters/_pdf_converter.py`** — makes `pdfplumber` optional. It needs
   `pypdfium2` (also no Android wheel), so PDFs fall back to pure-Python
   `pdfminer.six` text extraction (no table detection).

### Additional app-level changes

3. **`offline_converters.py`** — lightweight `openpyxl`-based `.xlsx` converter
   that replaces MarkItDown's pandas-based one, avoiding ~30 MB of numpy/pandas.
   Legacy `.xls` is dropped.
4. **`markitdown_android.py`** — the bridge un-registers the offline-unsupported
   converters (`ImageConverter`, `AudioConverter`, `YouTubeConverter`,
   `WikipediaConverter`, `BingSerpConverter`) and registers the custom `.xlsx`
   converter.

### The data flow

1. `MainActivity` opens the Android system file picker, filtered to supported MIME types.
2. The file bytes + name are passed to `markitdown_android.convert_bytes`.
3. Chaquopy converts the Java `byte[]`/`String` to Python `bytes`/`str`.
4. MarkItDown runs locally and returns the Markdown text.
5. The text is shown in a scrollable, selectable view and can be shared via the **Share** button.

No `INTERNET` permission is declared — the app cannot leak data off-device.

## Updating from upstream MarkItDown

The bundled source in `app/src/main/python/markitdown/` was copied from the
[upstream MarkItDown repository](https://github.com/microsoft/markitdown) at
version 0.1.7. To update:

1. Clone or fetch the upstream MarkItDown source into `markitdown/` at the repo
   root (it is gitignored).
2. Copy the new version:
   ```sh
   cp -r markitdown/packages/markitdown/src/markitdown app/src/main/python/markitdown
   ```
3. Re-apply the two patches (see "Patches" above). The diffs are small and
   self-contained — search for `magika` and `pdfplumber` in the two files.
4. Re-run the build and verify all 12 offline formats still convert.

## Troubleshooting

- **`Current thread does not hold the state lock for root project`** (during
  `compile*Kotlin`) — caused by `org.gradle.parallel=true` racing with the Kotlin
  Gradle Plugin on Gradle 8.9. It is intentionally **not** set in this project's
  `gradle.properties`; don't re-enable it.
- **`No matching distribution found for ...`** — the package has no Android wheel.
  Check the `pip` block in `app/build.gradle.kts`. Don't add native-only packages
  (e.g. `onnxruntime`, `pypdfium2`, `ffmpeg-python`).
- **`Could not find a version that satisfies the requirement`** — same cause; see
  the [Chaquopy FAQ](https://chaquo.com/chaquopy/doc/current/faq.html#pip-errors).
- **Python version mismatch** — Chaquopy requires `buildPython` to match
  `chaquopy.defaultConfig.version` (3.12).
- **File open fails from Recents** — some Android versions return broken
  `content://` URIs from the system picker's Recents tab. Pick from
  "Downloads"/browse instead, or update to a build with the MediaStore fallback.

## License

MIT. The bundled MarkItDown source retains its original copyright headers.
