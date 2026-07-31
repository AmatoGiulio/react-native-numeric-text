# iter1_direction

- iOS: `captures/ios_human.mov` onset 255
- Android: `/private/tmp/claude-501/-Users-giulioamato-Documents-GitHub-react-native-numeric-text/355d13ea-786f-4a01-a7a0-7219af550804/scratchpad/iter1_human.webm` onset 199
- window: onset .. onset+230 frames

Verify `pre_*.png` shows the expected starting value and `end_*.png` the
expected settled value BEFORE reading the tables (wrong-preset trap).

```

iOS iter1_direction   frames 255-485   5 columns (left to right, settled digit 180px tall)
 col              ink             edge              ext              cen      up      dn   crisp
   0 0.66 [0.66,1.00] 0.44 [0.44,1.01] 0.87 [0.87,0.92] -0.08 [-0.08,+0.00]   -0.03   -0.02    0.19
   1 1.00 [1.00,1.00] 1.00 [1.00,1.03] 0.86 [0.86,0.86] +0.00 [-0.00,+0.00]   -0.04   -0.03    1.00
   2 0.75 [0.43,1.16] 1.15 [0.65,1.52] 0.88 [0.81,1.20] +0.01 [-0.04,+0.08]   -0.04   +0.04    0.75
   3 1.17 [0.70,1.61] 0.88 [0.45,1.11] 0.89 [0.84,1.20] +0.15 [+0.11,+0.20]   -0.02   +0.04    0.51
   4 0.71 [0.39,0.95] 0.79 [0.52,1.21] 0.86 [0.79,1.17] +0.03 [-0.02,+0.08]   -0.04   +0.03    0.45

Android iter1_direction   frames 199-429   5 columns (left to right, settled digit 156px tall)
 col              ink             edge              ext              cen      up      dn   crisp
   0 0.64 [0.64,1.00] 0.42 [0.42,1.02] 0.88 [0.88,0.92] -0.09 [-0.09,-0.00]   -0.03   -0.03    0.18
   1 1.00 [1.00,1.00] 1.01 [1.01,1.06] 0.87 [0.87,0.87] -0.00 [-0.00,-0.00]   -0.02   -0.05    1.00
   2 0.94 [0.70,1.14] 1.37 [0.96,1.62] 0.95 [0.86,1.44] -0.02 [-0.10,+0.06]   +0.05   +0.17    0.93
   3 1.38 [1.22,1.58] 0.98 [0.70,1.14] 0.93 [0.88,1.44] +0.13 [+0.07,+0.18]   +0.05   +0.18    0.73
   4 0.86 [0.67,1.01] 1.00 [0.73,1.18] 0.92 [0.83,1.41] -0.00 [-0.07,+0.06]   +0.05   +0.18    0.64
```

![grid](grid.png)
