# Hami Patches

Morphe patches for the Hami Video Android app (`com.cht.ottPlayer`).

## Patches

| Patch | Type | What it does |
|---|---|---|
| Skip profile selection on launch | Bytecode | Skips the member/profile selection page on launch when a profile was already chosen (remembers the last selected profile). Without this, the app asks which member to use on every launch when the account has more than one profile. |
| Record selected profile locally | Bytecode | Records the profile the user picks into a patch-owned SharedPreferences file (`morphe_hami` / `last_profile_id`), so the skip patch has a value to read on the next launch. |

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

v1.0.0/1.0.1 tried reading that server-supplied value (`AccountManager.c`
→ `Login.getUserProfileId`) at startup. Real-device testing (2026-11-17)
showed the `Login` object has no `userProfileId` at launch, so v1 fell
back to showing the selection page every time. v1.0.2 (Plan B) no longer
relies on the server value:

1. **Record:** every profile choice in the app funnels through
   `UserProfileSelectActivity.setDeviceUserProfileId(Context, String,
   OnNextListener)` — tapping a profile on the selection page, the intro
   dialog, or adding a new user all end up there. The record patch saves
   the chosen profile id into a patch-owned SharedPreferences file
   (`morphe_hami` / `last_profile_id`). The server call itself is
   untouched, so the server learns the choice exactly as before.
2. **Skip:** the startup check now reads that locally stored id instead
   of `Login`. If it is non-empty and still present in the current
   profile list, it jumps straight to the main-screen setup (`buildViews`
   / `getMainMenu`) without opening the selection page. If there is no
   stored profile, or it is no longer in the list, the original behaviour
   (show the page) is kept.

After installing a module with v1.0.2, pick a profile manually once; the
next launch should skip the page. Switching profiles later (manual
「切換成員」) updates the stored value.

## Limitations

This patch was written from verified decompiled bytecode (Hami Video
7.12.1017). v1.0.1 was built and tested on a real phone (module installs,
manual switching works, but the v1 skip did not trigger — see above).
v1.0.2 changes the data source to local storage; it still needs a
real-phone test: install the new module, pick a profile once, then
relaunch and check the page is skipped.
