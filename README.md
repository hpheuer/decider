# Decider

A yes/no oracle for Android, inspired by the [Global Consciousness Project](https://grokipedia.com/page/Global_Consciousness_Project) (GCP).

## How it works
1. Type a yes/no question.
2. Choose the number of random bits to sample: 1G / 10G / 50G / 100G.
3. The app draws that many bits from `SecureRandom` and counts the ones.
   - ones > N/2  ->  YES (thumbs up)
   - ones < N/2  ->  NO  (thumbs down)
   - exact tie   ->  one extra bit decides
4. The z-score shows how far the result deviates from pure chance
   (|z| > 1.96 would be "significant" at the 5% level).

Bits are drawn in 1 MiB chunks and counted 8 bytes at a time via
`Long.bitCount`, with a progress bar and elapsed time shown while the
calculation runs on a background coroutine. Large sample sizes can take
a while — tap **Stop any time** during a run to abort early and still
see the result for the bits sampled so far.

## Build
1. Open the folder in Android Studio (Koala or newer).
2. Let Gradle sync (internet required the first time).
3. Run on a device/emulator with Android 8.0 (API 26) or higher.

Or from the command line:
```
./gradlew assembleDebug
```

## Stack
Kotlin, Jetpack Compose, Material 3.
