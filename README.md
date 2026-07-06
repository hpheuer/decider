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

## GCP-style physical entropy

A pseudo-random generator is deterministic once seeded, which would leave
no physical channel for anything to "influence" — contrary to the GCP
premise. Decider therefore works like a miniature GCP egg:

- During a run the **camera** captures frames continuously (covering the
  lens is fine — sensor dark-frame noise is ideal). The per-pixel LSBs
  are XOR-whitened with the alternating mask GCP eggs use, then folded
  into a SHA-256 entropy pool.
- Sampling is **parallelized across CPU cores**, and every 1 MiB chunk
  reseeds its `SecureRandom` with fresh camera entropy
  (`setSeed` supplements internal state, so it can only add randomness).
- If camera permission is denied the app falls back to pure PRNG and
  says so in the result.

While sampling, the running z-score is drawn as a **cumulative deviation
random walk** — the GCP's signature visualization — with the ±1.96
significance band as dashed guides. The result reports the two-sided
**p-value** with a plain-language strength label alongside the z-score.

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
