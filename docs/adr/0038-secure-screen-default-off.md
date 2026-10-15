# ADR-0038: Secure-screen (FLAG_SECURE) default OFF

- **Status:** Accepted
- **Date:** 2026-10-15
- **Context:** Since ADR-0021 the dashboard set `FLAG_SECURE` by default, so
  the app renders black in mirrors/recents (scrcpy) to protect the pairing
  token/QR. The primary operator now drives the phone over a screen mirror
  and needs the dashboard visible there; the per-launch opt-out (Settings →
  Appearance → Secure screen OFF) does not stick across fresh installs, and
  `onCreate` forced the flag before state loaded.
- **Decision:** Default `secureScreen` to OFF (`DashboardUiState`,
  `DashboardNetSnapshot`, `readSecureScreen`, `AppearanceCard` param) and
  set `FLAG_SECURE` in `MainActivity.onCreate` only when the stored toggle
  says so (still reactive via the existing `LaunchedEffect`). The toggle,
  its "token exposed" warning label, and persistence are unchanged — anyone
  who wants the TOFU-material protection re-enables it with one tap.
- **Alternatives considered:** (a) Keep default ON and document the toggle —
  rejected: the operator's steady state is mirrored driving, so secure
  default optimizes for the rarer case. (b) Remove the flag/toggle entirely
  — rejected: unattended/exposed setups still want it; the switch stays.
- **Consequences:** + Mirror-visible by default, matching how the app is
  operated. − Fresh installs expose token/QR to mirrors/recents until the
  user opts in; accepted explicitly (warning label retained). ADR-0021/0027
  stay immutable; this record supersedes their default, not their mechanism.
