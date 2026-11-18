<p align="center">
  <img src="docs/img/logo.png" width="160" alt="P5M">
</p>

<h1 align="center">P5M</h1>

<p align="center">
  PS5 Remote Play in VR, on the Quest 3.<br>
  A big screen floating in your room, with as little lag as I could squeeze out,<br>
  and 30 fps games shown at 120 Hz.
</p>

<p align="center">
  <a href="#install">Install</a> ·
  <a href="#what-it-does-differently">What it does differently</a> ·
  <a href="#whats-still-broken">What's still broken</a> ·
  <a href="#when-something-goes-wrong">When something goes wrong</a>
</p>

---

> **Heads up: this is a beta.** I play on it for hours at a time, and it still
> does weird things. There's a list further down of what I know is wrong. Read
> it before you install, so you know what you're getting.

## It's built on chiaki-ng

Everything that talks to the console is
[**chiaki-ng**](https://github.com/streetpea/chiaki-ng), and before that
[Chiaki](https://git.sr.ht/~thestr4ng3r/chiaki) by Florian Märkl. Finding the
PS5, registering with it, the session, the crypto, audio, video decoding. I
didn't rewrite any of that and I wouldn't want to. `libchiaki` is a git
submodule here, used as it comes, and the few things I had to change live in
`patches/` and get applied when you build.

The VR half is mine. Without their work this app doesn't exist.

A fair amount of it was written with an AI assistant, working alongside me:
research into the OpenXR extensions, a lot of the code, and most of the
digging through logs. Every decision, every test on real hardware and
everything that shipped went through me. Saying so because you deserve to know
how the thing you are installing was made.

## What it does differently

chiaki-ng's Android app runs on the Quest as a floating 2D window, because it
was written for phones. P5M throws that away and hands the video straight to
the Horizon OS compositor, which draws it as a real screen in your room.

That's about lag, not looks. In direct mode the video goes:

```
PS5 → network → MediaCodec → compositor swapchain → your eyes
```

No intermediate texture, no GPU work from the app, nothing copying frames
around. It works because `XR_KHR_android_surface_swapchain` gives you a
swapchain that literally *is* an `android.view.Surface`, so the hardware
decoder writes right into the thing you're looking at. Frame generation, 3D and
HDR tone mapping need the GPU, so they take a short detour through a shader,
and only when you turn them on.

### Frame generation, for the 30 fps games

A lot of the big games run at 30 fps in their best looking mode, and GTA 6 is
one of them. P5M can show those at 120 Hz. The PS5 always streams at 60 and
fills the gaps with repeated frames; P5M tells the real frames from the repeats,
learns the game's rhythm (30, 40, or whatever it drops to in a busy scene) and
predicts the frames in between with the Quest's own frame extrapolation. It only
predicts the parts of the picture that are moving, so the HUD and the still
parts of the scene stay sharp, and it resets on a scene cut instead of smearing
it. In immersive mode, set **Advanced > Video path** to **shader + frames**.

### Everything else

- A curved screen you resize, move and bend without leaving the game.
- Passthrough on by default, so your room stays around the screen. You can dim
  it if you want the cinema feel.
- 120 Hz panel by default, with one decoded frame per panel pass, so nothing
  stutters from bad math. 180 Hz is there too, under Advanced.
- 3D from any game. A small depth model runs on the headset, reads the flat
  picture and builds a second eye from it. There's a pop-out mode that brings
  things in front of the screen.
- 10-bit HDR with a proper tone mapper, and Rec.709 color for SDR. Horizon OS
  defaults to Display P3 and it wrecks reds and greens.
- MQSR sharpening through the compositor, plus a CAS shader for the cases where
  MQSR doesn't apply.
- Bitrate up to 100 Mbps. Auto bitrate can lower it after a session with packet
  loss and raise it again after clean ones, or you can switch it off and keep
  the lowest lag.
- The DualSense behaves like it does on the PS5: adaptive triggers, the
  lightbar colour the game picks, your player LEDs, gyro, touchpad, and the
  Quest mic for voice chat with echo cancellation. Vibration has several modes,
  and also works on 8BitDo and Xbox pads.
- Wake your PS5 from rest with one tap, and Remote Play over PSN for when
  you're not home.
- New versions show up inside the app, and install with one tap.
- Diagnostics you can read inside the headset, because your Quest is never
  sitting next to your PC when something breaks.

## Install

You need a Quest 3 with developer mode on, and a PS5 (PS4 works too) with
Remote Play enabled.

No PC needed, it all happens in the headset:

1. Open the [Releases](../../releases) page in the Quest browser and download
   the APK.
2. Open the **Files** app on the Quest and go to **Downloads**.
3. Tap the three dots next to the APK and choose **Install**.
4. P5M shows up in your library under **Unknown Sources**.

After that, new versions show up inside the app and install with one tap.
Coming from 0.2.0? Uninstall it once first; later versions install over it.

Prefer a PC? SideQuest works, or `adb install -r p5m.apk`.

### Press L3 + R3 + R1 while you play

That's the settings panel, in the middle of a game. Screen size, distance,
height, curvature, sharpness, passthrough, 3D strength, all of it, and the same
chord closes it again. There's no other way in, so if you skip this line you'll
be stuck with whatever the defaults gave you. The app shows a reminder at the
start of every session too.

R1 is in there because plenty of games use both stick clicks together and the
panel would pop open mid-fight. If that combination gets in your way, **Settings
chord** in the launcher switches it to plain L3 + R3, or to holding both sticks
for a second.

To **end the session**, open that panel and hold Circle for a second. Closing
the app from the system menu works too, but it kills the process without
telling the console, and then your PS5 spends a while thinking Remote Play is
still in use.

### Pair your controller with the headset, not with the console

This is the thing that trips up almost everyone. A DualSense paired to the PS5 is invisible
to the Quest, so the app gets no input at all and looks broken. The diagnostics
screen will tell you that's what happened.

## What's still broken

Writing this out because a beta that hides it wastes your time:

- **The 3D is still an estimate.** The depth model is much better than the old
  rules of thumb, but it can't know what's behind things. 2D games and fixed
  camera angles still get odd depth.
- **3D costs latency and heat.** With 3D on, the picture reaches your eyes
  about 15 ms later, and the headset warms up faster. With 3D and frame
  generation together, the depth refreshes less often to keep things smooth.
- **Frame generation guesses.** Fast, unpredictable motion can show a little
  wobble at the edges of moving things. If it bothers you in a game, turn it
  off; the game still runs at 30 like it would on a TV.
- **Quest Touch controllers do nothing.** You need a real gamepad.
- **The touchpad click is only tested on the DualSense.** The driver hands the
  touchpad over as a separate mouse device rather than a gamepad button, and
  that is where the app now listens. Other pads may route it somewhere else. If
  your game won't open its map, the settings panel has the click on the PS
  button, and the log will say how your controller sends it.
- **Haptic rumble mode buzzes** at moments the game never asked for. Classic
  mode is the default and it's the better one right now.

## When something goes wrong

Open the app and hit **Report a problem**. It packs up what happened, puts it
on the clipboard, and opens a pre-filled issue in the Quest browser. Write a
line about what you were doing and send it. Nothing leaves the headset until
you press that button.

If the browser is being difficult, the same screen can serve the whole diary
over your local network: hit **Serve on network** and open the address it
prints on your phone. **Copy summary** and **Send as file** are still there for
anyone who prefers them.

The log strips credentials, account IDs, console names, emails and public IP
addresses before it writes them, so pasting it in public is safe. Local network
addresses stay in, because you can't debug a network problem without them.
Still worth a quick look before you post it.

## Your data

It all stays on your headset. The app talks to your console, and to Sony's
servers if you use remote connection (signing in, punching through NAT). That's
it. No telemetry, no account of mine, nothing phones home.

Your PSN token and the console registration key sit in the app's private
storage on the device, same as the official Remote Play app does it.

## Building it

```sh
git clone --recursive https://github.com/beecrepaldi-afk/P5M
cd P5M
./gradlew assembleDebug
```

The chiaki-ng submodule never gets edited in place. `app/build.gradle` applies
everything in `patches/` before it compiles. `tools/conferir.py` and
`tools/compilar_nativo.py` catch the things the compiler won't.

[`docs/COMO-FUNCIONA.md`](docs/COMO-FUNCIONA.md) is the long technical writeup
(Portuguese), and [`docs/O-QUE-E-NOSSO.md`](docs/O-QUE-E-NOSSO.md) goes file by
file through what came from chiaki-ng and what didn't.

## License

**AGPL-3.0-only**, inherited from chiaki-ng. See [`LICENSE`](LICENSE) and
[`NOTICE.md`](NOTICE.md). Use it, change it, share it. Anyone you give it to
gets the source and the same rights you did.

Not affiliated with Sony Interactive Entertainment or Meta Platforms.
PlayStation, PS4, PS5, DualSense and DualShock are trademarks of Sony
Interactive Entertainment Inc. Meta, Quest and Horizon OS are trademarks of
Meta Platforms, Inc.

## Supporting it

The app is free and stays free, the license makes sure of that. This is nights
and weekends work, though, and there's a long list of things still to fix. If
you get some use out of it and want to keep it moving:

**<https://patreon.com/gblandro>**

There's a link inside the app too, under Support.
