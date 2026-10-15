# ADR-0034: Per-capability consent and signer pinning

- **Status:** Accepted
- **Date:** 2026-10-15
- **Context:** ADR-0032 left invocation consent as a single global `ext_`
  kill-switch: once enabled, every registered capability could execute.
  A malicious or compromised provider app would ride on one coarse toggle,
  and nothing bound a capability record to its author's signing key across
  re-registrations.
- **Decision:** Two-layer consent, both default-deny:
  - Per-capability `approved` flag on the record (default `false` for new
    owners). `callTool` denies unapproved capabilities with an actionable
    `isError` ("not approved … via `capabilities_approve`"); `tools/list`
    still shows them so agents can request approval instead of guessing.
    Consent is set via dashboard Approve/Revoke buttons or
    `capabilities_approve`/`capabilities_revoke` (bearer-authenticated,
    same trust level as the existing register/unregister tools).
    Same-owner re-registration preserves approval (re-announce after reboot
    must not nuke consent).
  - Signer pinning: the first non-empty `signerSha256` is adopted; a later
    registration for the same id with a *different* pin is rejected
    ("signer changed … unregister first") — key rotation requires explicit
    removal, defeating silent record hijack after app reinstall/re-sign.
- **Alternatives considered:** (a) Global switch only — rejected: one
  compromised provider gets everything; per-app consent is the expected
  Android permission UX. (b) Hide unapproved tools from `tools/list` —
  rejected: agents cannot discover what to request; denial-with-reason is
  more operable. (c) Auto-approve same-signer-as-phone apps — rejected:
  implicit trust is invisible; explicit approval is one tap/tool call.
- **Consequences:** + Invocation needs master switch AND per-app consent;
  signer changes fail closed. + No new transports; `capabilities_list`
  carries the `approved` flag for audit. − Two approval surfaces to
  maintain (dashboard + MCP tools); both write the same `setApproved`
  path, covered by unit tests.
