<p align="center">
  <img src="docs/img/logo.png" width="160" alt="P5M">
</p>

<h1 align="center">P5M</h1>

<p align="center">
  PS5 Remote Play in VR, on the Quest 3.<br>
  A big screen floating in your room, with as little lag as I could squeeze out.
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

## What it does differently

chiaki-ng's Android app runs on the Quest as a floating 2D window, because it
was written for phones. P5M throws that away and hands the video straight to
the Horizon OS compositor, which draws it as a real screen in your room.

That's about lag, not looks. The video goes:

```
PS5 → network → MediaCodec → compositor swapchain → your eyes
```

No intermediate texture, no GPU work from the app, nothing copying frames
around. It works because `XR_KHR_android_surface_swapchain` gives you a
swapchain that literally *is* an `android.view.Surface`, so the hardware
decoder writes right into the thing you're looking at.

Other things it does:

- A curved screen you resize, move and bend without leaving the game.
- Passthrough on by default, so your room stays around the screen. You can dim
  it if you want the cinema feel.
- 120 Hz panel for a 60 fps source. It's an exact multiple, so every console
  frame gets exactly two panel frames and nothing stutters from bad math.
- Rec.709 color, forced. Horizon OS defaults to Display P3 and it wrecks reds
  and greens.
- MQSR sharpening through the compositor, plus a CAS shader for the cases where
  MQSR doesn't apply.
- Fake 3D. The app guesses depth from the flat picture and builds a second eye
  out of it. It's a guess and you can tell, but the sense of depth is real.
- Remote Play over PSN, for when you're not home.
- Diagnostics you can read inside the headset, because your Quest is never
  sitting next to your PC when something breaks.

## Install

You need a Quest 3 with developer mode on, and a PS5 (PS4 works too) with
Remote Play enabled.

1. Grab the APK from [Releases](../../releases).
2. Sideload it. SideQuest, or `adb install -r p5m.apk`. You can also just open
   the release page in the Quest browser and install the download.
3. It shows up under **Unknown Sources** in your library.

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

### Pair your controller with the headset, not with the console

This is the thing that trips up almost everyone. A DualSense paired to the PS5 is invisible
to the Quest, so the app gets no input at all and looks broken. The diagnostics
screen will tell you that's what happened.

## What's still broken

Writing this out because a beta that hides it wastes your time:

- **The 3D is guesswork.** Depth comes from things like "the ground is usually
  at the bottom of the frame" and "far away stuff is hazier". Text and HUDs are
  the hard part and get special handling, but 2D games and fixed camera angles
  break the whole idea.
- **3D makes the headset warm.** Frame times stay fine, but it heats up faster
  and the compositor starts dropping frames on a long session.
- **Frame pacing wobbles.** Most ten second windows deliver 590-something of
  600 frames on time. Some drop to half that, in bursts. I don't know why yet.
- **You can't type the PIN in immersive mode.** If your console asks for a
  login PIN, register it once from the 2D panel first.
- **Quest Touch controllers do nothing.** You need a real gamepad.
- **The DualSense touchpad click may not reach the console.** The driver hands
  the touchpad over as a separate device and not as a gamepad button, so the
  app now listens for it there too. If your game still won't open its map,
  the settings panel has it on the PS button, and the log will tell me which
  way your controller sends it. Send it.
- **Bitrate is stuck at 25 Mbps.** No setting for it yet.
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
