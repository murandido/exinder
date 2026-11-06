# Google Photos albums: one-time setup

Albums whose destination is **Google Photos** are uploaded with the [Google Photos Library API](https://developers.google.com/photos/library/guides/overview). Google only lets an app call it when the app is registered in a Google Cloud project, so every build of exinder needs its own (free) registration. It takes about 15 minutes, once.

Albums on the phone don't need any of this.

## What the app can and can't do in your Google Photos

exinder asks for the `photoslibrary.appendonly` scope only. With it, the app can:

- create albums;
- upload photos and videos into the albums it created.

It **can't** see, change or delete anything else in your library, including your existing albums and photos. Albums created by exinder show up in Google Photos like any other album, and you can edit or delete them there.

## 1. Find your signing certificate's SHA-1

Google identifies the app by its package name (`app.exinder`) plus the SHA-1 of the certificate it is signed with. Builds from this repository are signed with your local debug key:

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android -keypass android | grep SHA1
```

Or, from the project folder, `./gradlew signingReport` (look at the `debug` variant).

> A different computer has a different debug key. If you build on another machine (or on CI), register that SHA-1 too, or copy your `~/.android/debug.keystore` over.

## 2. Create the Google Cloud project

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create a project (any name, e.g. `exinder`).
2. **APIs & Services → Library**: search for **Photos Library API** and enable it.
3. **APIs & Services → OAuth consent screen** (also called *Google Auth Platform*):
   - User type: **External**.
   - App name: `exinder`; support and developer email: yours.
   - Scopes: you can skip this page; the app asks for its scope at sign-in.
   - Test users: **add your own Google account**. While the app is in *Testing*, only test users can sign in, which is exactly what a personal build needs. No Google verification is required.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type: **Android**.
   - Package name: `app.exinder`.
   - SHA-1 certificate fingerprint: the value from step 1.

There is no client ID or secret to copy into the app: Android matches the app to this registration by package name and certificate.

## 3. Use it

1. In exinder, open a folder, then **menu → review kept**.
2. Swipe a photo up (or tap the album button) and create an album with the destination **Google Photos**.
3. Open **menu → albums**, pick the album and tap **send**. Choose whether the originals stay on the phone or go to the trash once uploaded.
4. The first time, Android shows a Google sign-in and consent screen. After that, uploads don't ask again.

## Troubleshooting

| What you see | Likely cause |
| :-- | :-- |
| "couldn't sign in to Google Photos" right away | The OAuth client doesn't match: wrong package name, or wrong SHA-1 (built on another machine?). |
| Sign-in screen says "Access blocked" / `access_denied` | Your account isn't in the consent screen's **test users** (step 2.3). |
| Sign-in works, but every file fails with "Photos Library API has not been used in project … or it is disabled" | Step 2.2 was skipped: enable the **Photos Library API** in the same project. It can take a few minutes to take effect. |
| Some files show a red "failed" mark | The upload or the album step failed (network, a file Google Photos doesn't accept). The message after a send says what Google answered. Tap **send** again to retry just those. |

## Privacy

Network access is used only for these uploads, and only when you tap **send** on a Google Photos album. Files are streamed straight from the phone to Google; there is no other server in between. Sign-in and the access token are handled by Google Play services; exinder never sees your password.
