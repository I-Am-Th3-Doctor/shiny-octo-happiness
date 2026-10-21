# Scrolling tile text (marquee)

Screenshots from CI (Screenshots #19, commit 1226fbc).

- `marquee-folded.png`, `marquee-unfolded.png`: the weather line during the 2 s hold at the
  start (right edge faded), then while it scrolls or holds at the end (left edge faded).
  Font scale 1.0 and 1.3.
- `animations-off-fallback.png`: CI runs with animations off, so these tiles show the
  fallback: text that doesn't fit ends in "…" and doesn't move.

Loop: hold 2 s → scroll at 32 dp/s → hold 2 s → jump back. Text that fits never moves.
