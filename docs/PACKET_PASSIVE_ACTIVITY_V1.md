# HackerGuardian 2.0 — Passive packet activity research (v1)

This remains inside movement-development PR #17. The purpose is to explain packet-model timing in **ordinary gameplay**, not create a new anti-cheat detector or ask players to repeat a guided course.

## Staff view

`/hg detection packets <player> activity`

This is a compact four-line view (including heading) from the live player's per-session read-only counters. No additional tracking command is needed: accumulation occurs whenever the existing opt-in packet reachability research is running. The course and all earlier packet views remain available unchanged.

## What is actually observed

- **Client XZ pairs:** horizontal displacement between successive successfully decoded incoming POSITION / POSITION_LOOK packets. Bins: position nearly unchanged (<= 0.015 block), moving with server-observed walking state, moving with server-observed sprinting state, or server-observed sprint-state flip. These are packet claims, not authenticated keypresses, physics ticks, or absolute proof of motion.
- **SERVER intervals without received position:** two consecutive Bukkit server-tick position samples with no incoming decoded client-position packet in between. They are classified by the difference in server-sampled XZ position, not packet loss: `quiet` (<= 0.01 block), `move` (> 0.01 block), or `unknown` when sampled ticks/epochs/worlds are not contiguous.
- **Hint gaps:** adjacent valid incoming client-position packets whose most recently observed *server scheduler tick hints* differ by more than 1. The gap is attributed to quiet/moving/unknown server-sampled intervals since the preceding client-position claim. This does **not** tell us how many packets were sent or lost.
- **Model gap rejections:** original reachability `TICK_GAP` outcomes. Their attribution can be unknown even when a hint gap was not directly observable (e.g., another model gate previously interrupted its claim chain). Original model counts and eligibility are unchanged.
- **No-position streak:** max and currently-open sequence lengths measured in consecutive sampled **server intervals**, not elapsed client ticks.

The summary's Q/M/? values are exclusive counts of gap *events*, not tick durations. The no-position quiet/move/unknown counters are exclusive counts of sampled server intervals. Keep those two denominators separate.

## What this can and cannot establish

If a long span without position packets contains quiet server samples, this is **consistent with** a stationary player/client reporting position infrequently. It is *not proof* of inactivity, not a networking diagnosis, and not grounds to relax the model. Conversely, server motion during a gap suggests packets and Bukkit-sampled player motion were not one-to-one aligned. We cannot conclude cheating, lag, client simulation-rate changes or packet loss from these counters alone.

The 835-length timing cluster in earlier manual research remains an unexplained historical observation. These counters are prospective; they cannot retroactively classify that old cluster.

All input across ProtocolLib packet callbacks is either decoded, immutable packet state or previously copied server state. The Bukkit tick sampler feeds the new observer once per sampled server tick under the existing serialized player session lock. Player/world API references never enter the packet callback. Corrections (teleports, velocity, respawn, world change and related epochs) fence packet-pair comparisons, while lifetime totals remain.

Only constant-size primitive state is retained. There is no persistent player movement log, no new dataset, and **no links to flags, enforcement, risk scores, punishments, ML labels or alerts**.

## QA

Pure unit tests cover quiet idle-looking gaps, moving server samples during missing-position intervals, walk/sprint switches, skipped sampler ticks, correction fences, invalid position packets, read-only snapshots, and unchanged original packet-model outcomes. Check the CI matrix for supported Paper versions and the general test/build job.

## Next stage

Use natural playtime to assess when the existing reachability model loses eligibility, then validate the ordinary-surface physics envelope separately before considering a non-enforcing shadow detector. Do not derive detection thresholds solely from this exploratory provenance.
