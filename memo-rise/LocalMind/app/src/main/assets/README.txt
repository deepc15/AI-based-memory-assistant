Place your Llama 3.2 model file (e.g. `llama3.2.task` or `llama3.2.bin`) in this `assets/` directory before building the APK.

When the app installs and runs, `ModelAssetsManager` will automatically extract and copy the model file into app internal storage (`context.filesDir/llama3.2.task`) and activate 🟢 Smart Mode out-of-the-box!
