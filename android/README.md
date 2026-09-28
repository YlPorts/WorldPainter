# WorldPainter Android / Minecraft Bedrock

This directory contains the Android port work. It is intentionally isolated from the original desktop modules so the upstream WorldPainter build remains intact while Android-specific UI and Bedrock storage are developed.

## Current functional slice

The Android app currently implements an end-to-end Bedrock workflow:

- native Android editor (no Swing compatibility layer)
- 128 x 128 editable terrain project
- raise, lower and smooth height brushes
- grass, sand and stone surface painting
- water painting and sea-level filling
- pinch zoom and two-finger pan
- undo / redo history
- persistent local project save
- native Rust Bedrock chunk encoder
- Bedrock LevelDB output using raw-deflate compression
- little-endian \`level.dat\`
- \`.mcworld\` packaging on Android
- intent to open the exported world in Minecraft for Android

The first build intentionally targets \`arm64-v8a\` and Android API 26+; the CI compile target is Android 16 / API 36.

## Build

The GitHub Actions workflow \`.github/workflows/android-bedrock.yml\` builds the Rust native library first and then packages it into the APK. The resulting debug APK is uploaded as a workflow artifact.

For a local build, install Android SDK 36, Android NDK 27.2.12479018, Rust, and \`cargo-ndk\`, then run:

\`\`\`bash
cd android/native
cargo ndk -t arm64-v8a -P 26 -o ../app/src/main/jniLibs build --release
cd ..
gradle :app:assembleDebug
\`\`\`

## Architecture

- \`app/\`: Android editor and \`.mcworld\` packaging.
- \`native/\`: JNI Bedrock exporter. It writes chunk records directly in the Bedrock LevelDB layout and does not convert Java Anvil worlds.

The desktop WorldPainter source remains available in the parent repository for progressively porting terrain algorithms, brushes and layer semantics into the Android editor.
