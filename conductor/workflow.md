# Conductor Workflow Rules

## TDD Strictness
Level: Standard (Red -> Green -> Refactor for critical logic, synchronized unit tests on UI and presentation components).

## Commit Strategy
Task-based verification checkpoints with clean git tracking.

## Verification Checkpoints
- Compilation and unit test suite passing (`testDebugUnitTest`).
- All test tags and semantic node assertions verified.
- Architectural layer discipline preserved (`presentation/` remains isolated from framework leaks).
