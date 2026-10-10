# Jarvis

A personal voice assistant that lives entirely on an Android phone. Say "Jarvis", ask anything, and he answers in a British voice. He checks your schedule, reads your calls, and researches on the web.

- **Brain:** Claude, through your Claude Pro plan (Claude Code running in Termux)
- **Wake word:** openWakeWord's "Hey Jarvis" model, running on the phone (free, no key)
- **Ears and voice:** Android's built-in speech recognition and text-to-speech
- **Schedule:** your Google Calendar's private iCal link
- **Calls:** your phone's call log

Nothing here costs money beyond Claude Pro.

## Setup (about 20 minutes, all on the phone)

### 1. Install Termux (the brain's home)
Download Termux from F-Droid: https://f-droid.org/packages/com.termux/ (tap "Download APK" and install it).
Don't use the Play Store version, which is outdated.

### 2. Install the brain
Open Termux and paste:

```
apt update && apt full-upgrade -y -o Dpkg::Options::=--force-confnew && apt install -y curl && curl -fsSL https://raw.githubusercontent.com/farhan-aflhh/jarvis/main/brain/setup.sh | bash
```

Wait about 10 minutes. If it asks a question, press Enter.

### 3. Log in to Claude (one time)
In Termux, type `jarvis-login`. Pick the **Claude account / subscription** login, not Console. Long-press the link to open it, approve, and paste the code back into Termux. When the chat prompt appears, type `/exit`.

### 4. Start the brain
In Termux, type `jarvis`. It shows a **brain code**. Leave Termux running in the background.

### 5. Install the Jarvis app
Download it from https://github.com/farhan-aflhh/jarvis/releases/latest/download/jarvis.apk and install it (allow installs from your browser if asked).

Open it. Under Settings, type the brain code and tap Save. Then:
- Tap **LET JARVIS RUN IN BACKGROUND** and allow it.
- Tap **ACTIVATE** and allow microphone, call log and notifications.

Say **"Hey Jarvis"**, wait for the chime, and talk.

### Optional: your calendar
On the phone's browser, open calendar.google.com, switch to "Desktop site", then Settings → your calendar → Integrate calendar → copy **Secret address in iCal format**. Paste it into the app's settings. Treat it like a password.

## Keeping him alive
- Termux and Jarvis both need battery optimization turned off (Settings → Apps → each app → Battery → Unrestricted).
- After restarting the phone, open Termux and type `jarvis` again.
- To update the brain later, type `jarvis-setup` in Termux.

## If call log permission is greyed out
Settings → Apps → Jarvis → ⋮ (top right) → Allow restricted settings, then grant it again.

## Credits
The wake word uses the pre-trained models from [openWakeWord](https://github.com/dscripka/openWakeWord) by David Scripka (models licensed CC BY-NC-SA 4.0, for non-commercial use).
