# Hami Patches

Morphe patches for the Hami Video Android app (`com.cht.ottPlayer`).

## Patches

| Patch | Type | What it does |
|---|---|---|
| Skip profile selection on launch | Bytecode | Skips the member/profile selection page on launch when a profile was already chosen (remembers the last selected profile). Without this, the app asks which member to use on every launch when the account has more than one profile. |

Compatible with Hami Video **7.12.1017** (versionCode `1017`) only.
Fingerprints are pinned to that version; an app update will require re-verification.

## Build

Trigger the `Release` workflow (Actions → Release → Run workflow) with a
version number. It builds `patches/build/libs/patches-<version>.mpp` and
publishes it as a GitHub Release asset.

## How it works

On launch, after login, `MainActivity$2$9.onNext(List)` checks the profile
list (`App.p`) from the server — if there is more than one profile it
unconditionally opens `UserProfileSelectActivity` (the "切換成員" page),
ignoring the profile the user already chose. The chosen profile is saved
server-side (`SET_DEVICE_USER_PROFILEID`) and returned in the `Login`
object as `userProfileId`, but the startup decision never checks it.

This patch inserts a check before the profile-count test: it reads the
stored `userProfileId` (via `AccountManager.c` → `Login.getUserProfileId`),
and if it is non-empty and still present in the current profile list,
it jumps straight to the main-screen setup (`buildViews` / `getMainMenu`)
without opening the selection page. If there is no stored profile, or it
is no longer in the list, the original behaviour (show the page) is kept.

## Limitations (untested)

This patch was written from verified decompiled bytecode (Hami Video
7.12.1017), but it has **not** been built or installed on a device yet.
Build and test it yourself before relying on it. Whether the server
returns the last-selected profile in the `Login` object on every launch
is assumed from the API design (it is the only stored source of the
selection); if it turns out to be empty or stale, the patch safely falls
back to showing the selection page.
