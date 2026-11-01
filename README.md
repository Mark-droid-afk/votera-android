# VotEra (Android + Browser)

Client apps for **VotEra**, a research prototype of a digital student election platform with an Automated Candidate Credibility Checker. Made for the Fundamentals of Research course (BSIT, Polytechnic University of the Philippines, Quezon City Campus).

> Research prototype. All names, accounts and votes are fictional. Handled in line with R.A. 10173 (Data Privacy Act of 2012). Not for real elections.

Server code is in the separate **Votera-Backend** repository (FastAPI + Postgres). Both clients below use that same server, so accounts, votes and scores are shared.

## Two ways to use it

| | Link | Notes |
|---|---|---|
| **Android app (APK)** | Shared by the researchers | Main version, evaluated in the study. Full administrator tools |
| **Browser copy** | https://mark-droid-afk.github.io/votera-android/ | For respondents who cannot install the APK. Works in any phone or computer browser |

The browser copy is only an easier way to reach the same prototype. It has the student functions and the main administrator screens. Photo and document upload, candidate editing, voter import, the full vote audit, the CSV report and the election reset are Android only.

## Features

**Students**
- Sign in with student number, password and a one-time code
- Candidate profiles with manifesto, verified documents and a credibility score breakdown
- Compare candidates side by side, or "Match me" with your own weights
- Vote once per position, review the ballot first, get a SHA-256 Transaction ID receipt
- Live results, announcements, feedback form, light and dark mode

**Administrators**
- Open, close or schedule voting
- Add and edit candidates, upload photos and proof documents
- Enter verified scores; manifesto originality is computed by TF-IDF and cosine similarity
- Side-by-side similarity view (flags of 0.70 or higher are for review only)
- Voter list, password reset, active sessions, audit log
- Pilot tracker against the sample allocation, turnout by program, feedback summary
- Self-test of the Chapter 3 methods (bcrypt, JWT, RBAC, HOTP, SHA-256, tally, Quick Sort, TF-IDF)

## Test accounts

| Role | ID | Password |
|---|---|---|
| Administrator | `ADMIN-001` | `admin123` |
| Students | `2022-90001-CM-0` to `2022-90500-CM-0` | `student123` |

The one-time code is shown on screen (simulated delivery instead of SMS). The server may need up to a minute to wake on the first sign-in.

## Repository layout

| Path | What it is |
|---|---|
| `app/` | Native Android app (Kotlin, Jetpack Compose, Material 3) |
| `docs/index.html` | Browser copy, a single file served by GitHub Pages |
| `.github/workflows/` | GitHub Actions that build the APK |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | Gradle project files |

## Build the APK

The APK is built automatically by GitHub Actions.

1. Push your changes to `main`.
2. Open the **Actions** tab, then the latest run.
3. Download the artifact (a `.zip`), unzip it, and install the `.apk` on an Android phone.

To build locally you need Android Studio (or JDK 17 and the Android SDK) and then run `./gradlew assembleDebug`. The APK appears in `app/build/outputs/apk/debug/`.

The server address is set in `Api.kt` (`BASE_URL`). To test against another server, change it there.

## Browser copy

`docs/index.html` is plain HTML, CSS and JavaScript with no build step.

- Hosted with GitHub Pages: Settings, Pages, branch `main`, folder `/docs`.
- To point it at another server for testing, add `?api=http://127.0.0.1:8000` to the address.
- The backend must allow cross-origin requests (already enabled in `main.py`).

## Authors

Mark Jade D. Gurrobat and research team, BSIT, PUP Quezon City.
