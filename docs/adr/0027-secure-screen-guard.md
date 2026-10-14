# ADR-0027: Confirm guard on Secure-screen toggle only

- **Status:** Accepted
- **Date:** 2026-10-14
- **Context:** The owner accidentally flipped the Secure-screen toggle while
  exploring the dashboard, blacking out their screen mirror mid-session
  (FLAG_SECURE hides the window; expected behavior, ADR-0007/0010), and asked
  for an "are you sure" guard. A first implementation guarded Secure screen +
  token rotation + all six kill-switches; the owner corrected the scope as
  overreach — only the Secure toggle was requested.
- **Decision:** A reusable `ConfirmDialog` (M3 AlertDialog, test-tagged
  `confirm_dialog(_ok/_cancel)`) guards the Secure-screen toggle in both
  directions, with direction-specific copy (hiding warns of black mirrors;
  showing warns the pairing token/QR becomes mirror-readable and names
  re-enabling). Rotate-token and kill-switch toggles stay direct: rotation is
  already explicitly labeled, and kill-switches are routine session controls
  where per-flip dialogs would cause fatigue. Guard copy is pinned by JVM
  tests (ConfirmTextTest) so safety wording cannot silently regress. Message
  builders are pure top-level functions; dialog state is `remember` (not
  saveable — worst case the dialog dismisses on rotation).
- **Alternatives considered:** (a) Guarding every security toggle — rejected
  by the owner as unasked friction. (b) No guard, relabel only — rejected:
  the incident proved one tap can black out an active mirror session with no
  recourse visible to the viewer.
- **Consequences:** + Accidental Secure flips now need two taps; proven live
  (dialog appeared with correct copy, confirm path flipped the flag,
  verified OFF in a fresh dump). + Zero signature changes to guarded cards'
  public APIs. − A user who dislikes even one dialog cannot opt out (accepted:
  the protected secret is the pairing token).
