# Search app grid 4×2, home widget grid restored

Screenshots from CI (Screenshots #13, commit 2c6b037). Each sheet, left to right:
edit mode, then searches for "c", "ch", "s" and "g".

- **Edit mode:** the free-placement widget canvas is back (adaptive columns × rows,
  not 4×2). The slim pill sits in its own lane above row 1.
- **Search:** app results are capped at 2 rows (4 across folded, 6 unfolded) and
  scroll inside that area when more apps match. One row of matches gives a
  one-row area, so there is never an empty row.

The emulator has few apps, so no query here fills more than 2 rows.
