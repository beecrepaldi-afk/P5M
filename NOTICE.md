# Notice

P5M is built on **chiaki-ng**, which is itself built on **Chiaki**.

- chiaki-ng: <https://github.com/streetpea/chiaki-ng>
- Chiaki: <https://git.sr.ht/~thestr4ng3r/chiaki>, by Florian Märkl

The entire Remote Play protocol is theirs. Discovery, registration, the
session, the crypto, Takion, the audio and video pipeline. P5M uses it through
`libchiaki` as a git submodule, unmodified. Anything this project needed to
change lives in `patches/` and gets applied at build time, so the submodule
itself stays clean.

What's new here is the OpenXR layer, the shaders, the launcher, the diagnostics
and the tooling. `docs/O-QUE-E-NOSSO.md` goes through it file by file if you
want the exact line.

chiaki-ng is licensed under the GNU Affero General Public License version 3, so
this is too. `LICENSE` has the full text. You get the same freedoms it gives:
use it, study it, change it, pass it on. Anyone you pass it to gets the source
and the same rights.

## Not affiliated with anyone

This project has nothing to do with Sony Interactive Entertainment or Meta
Platforms, and neither company endorses it. PlayStation, PS4, PS5, DualSense
and DualShock are trademarks of Sony Interactive Entertainment Inc. Meta, Quest
and Horizon OS are trademarks of Meta Platforms, Inc. They're named here only
to say what this software works with.
