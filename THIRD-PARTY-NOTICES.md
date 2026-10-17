# Third-party software

Swath's own code is MIT licensed (see `LICENSE`). It depends on the following. Check each project's current terms before distributing a build.

| Component | Used for | Licence |
| --- | --- | --- |
| ARCore SDK (`com.google.ar:core`) | Motion tracking and wall-plane detection | Apache 2.0 for the SDK; the ARCore service (Google Play Services for AR) is Google's and is installed separately on the phone |
| ML Kit Text Recognition (`com.google.mlkit:text-recognition`) | On-device handwriting and print recognition, bundled Latin model | ML Kit terms of service; runs fully on device |
| Jetpack Compose, AndroidX, Room | App UI and local database | Apache 2.0 |
| Kotlin and kotlinx.coroutines | Language and concurrency | Apache 2.0 |
| JUnit 5, kotlin-test | Tests only | EPL 2.0 / Apache 2.0 |

Swath makes no network calls. ARCore may ask Google Play Services for AR to install or update on first launch, which is done by the system, not by Swath.
