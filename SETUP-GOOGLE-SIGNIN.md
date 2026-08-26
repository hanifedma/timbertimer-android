# Turning on Google sign-in for the Android app

The app **works right now with no setup** — sessions, records and both to-do
lists are saved on the device. This page is only for the account sheet: signing
in with Google *inside the app*, instead of through a browser tab.

Everything below happens once, in the Google Cloud console, and takes about five
minutes.

---

## Why this can't be skipped

The web app only needs the public keys already in `src/supabase-config.js`.
Android is different: **Google issues a sign-in token only to an app it has been
told about** — identified by its package name *and* the fingerprint of the
certificate the APK was signed with.

There is no way to register an Android app from code. It has to be done in the
console, by you, as the owner of the project. This is the same step you already
did for Ponder in the Firebase console; it is just in a different console here,
because TimberTimer's account lives in Supabase rather than Firebase.

Until it is done, the in-app sheet cannot work — the app offers the browser tab
instead, which signs you into the same account.

---

## 1. Open the right project

The app asks Google for a token whose audience is this client id, from
`SupabaseConfig.kt`:

```
423591952914-13cjnfhqqjs2aq1o6flbdj1dtusruo5g.apps.googleusercontent.com
```

The `423591952914` on the front is the **project number**. Open
<https://console.cloud.google.com/apis/credentials> and make sure the project
picker at the top is on that project — not Ponder's. The Android client has to
live in the *same* project as the web client above, or Google will refuse a
token minted for one to be used by the other.

## 2. Create the Android OAuth client

**+ Create credentials** → **OAuth client ID** → Application type **Android**.

- **Name** — anything; `TimberTimer Android (debug)` is fine.
- **Package name** — exactly:

  ```
  com.example.timbertimer
  ```

- **SHA-1 certificate fingerprint** — the debug keystore on this machine:

  ```
  F4:86:F1:B0:7C:AA:79:82:D0:64:87:5F:FA:56:4F:21:5E:3C:6E:95
  ```

**Create**. There is nothing to download — unlike Firebase, no config file is
generated. The registration itself is the whole of it.

## 3. Add the release key too

A build signed with a different key is a different app as far as Google is
concerned, so the release APK needs its own entry. Repeat step 2 with the same
package name and this fingerprint:

```
3B:76:27:EB:6B:47:CB:57:7B:06:F9:86:8E:22:6B:A2:B8:E6:ED:4B
```

> Building on another computer later? That machine has its own debug keystore.
> Get its fingerprint with `./gradlew signingReport` and add it as one more
> Android client on the same package name.

## 4. Check Supabase will accept the token

**Supabase dashboard → Authentication → Sign In / Providers → Google.**

The web client id from step 1 must be in **Authorized Client IDs** (a
comma-separated list). This is what lets Supabase trust a token the app got
straight from Google, with no browser in between. If sign-in gets as far as
"Google signed you in, but the server would not accept it", this is the field
that is wrong.

## 5. Try it

Give it a minute — new OAuth clients take a short while to propagate — then:

```bash
./gradlew installDebug
```

Settings → **Continue with Google**. The sheet should appear over the app and
sign you straight in.

---

## Troubleshooting

| What you see | What it means |
|---|---|
| "This build isn't registered with Google yet" | Steps 2–3. The package name or the SHA-1 doesn't match this build. Confirm the fingerprint with `./gradlew signingReport` and check you registered it in project `423591952914`, not Ponder's. |
| "Google's account sheet didn't open" | A transient refusal — usually no connection. The browser button still works. |
| "Google signed you in, but the server would not accept it" | Step 4: the client id isn't on Supabase's Authorized Client IDs list. |
| "No Google account on this phone" | Add one in Android Settings. An emulator without Play Services can't do Google sign-in at all. |
| Sheet appears, you pick an account, nothing happens | This was a bug, now fixed: Play Services reports a refusal as a *cancellation*, and the app used to treat it as you dismissing the sheet. It now says what happened. If you still see it, capture it with `adb logcat -s GoogleSignIn`. |

### Reading the real error

Every refusal is logged with its Play Services status code, which is the fastest
way to tell these apart:

```bash
adb logcat -s GoogleSignIn:* TimberAuth:*
```

`[16] Account reauth failed.` and `[28444] Developer console is not set up
correctly.` both mean step 2 or 3 — despite the first one's wording, which
sounds like a problem with the Google account and usually isn't.
