# Home card: two rows (4×2)

Screenshots from CI (Screenshots #16, commit 3fddf4b).

- Tile row: one row tall, the same height as the search row. Each tile is a single line:
  weather (icon, temp, condition, high/low), clock (time, date), battery (%, status, signals),
  music (artwork, track · artist, controls) and Quick Actions (four circles; long-press names
  them). The page dots sit inside the tile row.
- Search row: unchanged, directly beneath.
- The widget canvas above gains the freed space (7 rows instead of 6 when folded).

`tiles-*.png` show every tile folded/unfolded and at font scale 1.3; `home-and-edit.png`
shows home, edit mode, a search, and unfolded home.
