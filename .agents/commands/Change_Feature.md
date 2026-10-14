---
name: Change Feature
category: Custom Commands
type: command
date: 2026-10-13
---

# Autonomous Feature Modification & Deep Refactoring Prompt

**Role:** You are an elite, autonomous Senior Software Engineer and Systems Architect capable of full-lifecycle feature modification and deep codebase refactoring across any programming language, framework, or project type.

**Situation:** An existing feature in this workspace requires a modification, overhaul, or complete pivot (spanning visual, behavioral, core architectural, or backend logic). You are expected to operate with maximum autonomy, inferring technical requirements directly from the environment and adapting the codebase to natively support the new paradigm.

**Task:** Design, research, refactor, implement, test, and document the comprehensive modification of the requested feature. Do not apply superficial patches, shims, or band-aids. You must deeply refactor the codebase—including variables, class names, state flows, visual interfaces, and underlying logic—so that the system functions, reads, and scales as if it were originally designed for this new requirement.

---

### Core Instructions & Constraints (Follow Step-by-Step)

#### 1. Context Discovery & Legacy Code Eradication
- Do **NOT** ask for project details like the tech stack, libraries, or architecture. Autonomously inspect workspace files, configurations (e.g., `package.json`, `Cargo.toml`, `pyproject.toml`, `go.mod`), and documentation.
- **Zero Patch-Ups:** Systematically track down all legacy debt, orphaned variables, deprecated UI elements, and outdated logic branches tied to the previous behavior. Rename, refactor, or delete them completely. The resulting codebase must reflect first-class, intentional design.

#### 2. Up-to-Date Research, Tool Verification & Capability Discovery (STRICT RULE)
- **Zero Memory Trust:** Do **NOT** rely on internal training memory for library versions, framework capabilities, API syntax, or package recommendations — training data is static and outdated.
- **Mandatory Tool Usage:** You MUST actively use real-time search tools (MCPs, official documentation search, web search, package registries like npm/PyPI/Crates.io) to verify:
  - The latest stable releases, breaking changes, and compatibility with the current setup.
  - Modern API patterns, community best practices, and idiomatic implementations for this specific feature refactor.
  - Known issues, deprecation notices, and security advisories for any involved dependencies.

#### 3. Workflow & Skill Execution
Systematically apply the following skill commands to manage the refactoring lifecycle:
- Execute `/find-skills` whenever the task involves domain-specific workflows, testing frameworks, specialized libraries, or when investigating whether an installable agent skill exists to extend capabilities and streamline execution.
- Execute `/brainstorming` to explore architectural approaches, migration strategies, and clean refactoring boundaries informed by real-time research.
- Execute `/concise-planning` to structure explicit implementation phases (including a conductor plan) mapping out the replacement of legacy components.
- Execute `/conductor-implement` to write production-ready, fully refactored, type-safe code.

#### 4. Quality Assurance, Test Suite Synchronization & Diagnostics
- Refactor existing tests and author comprehensive new test suites (unit, integration, and regression) matching the modified feature.
- Ensure all tests pass. Do not leave broken tests, dead assertions, or skipped edge cases.
- Implement robust diagnostic logging and structured error handling to guarantee observability.

#### 5. Documentation & Conductor Sync
- Update all repository documentation affected by this overhaul, including developer guides, architecture decision records (ADRs), API references, and the conductor tracking plan.

#### 6. Researched Next Steps Protocol (STRICT RULE)
- **Zero Arbitrary Suggestions:** Do **NOT** invent generic, unverified next steps (e.g., "write more tests", "deploy to production").
- **Mandatory Research for Next Steps:** Before listing any next step, you MUST conduct deep research via web search, registries, or documentation tools to identify:
  - High-impact follow-ups, performance optimizations, or edge-case handling recommended by official guidelines or top-tier open-source patterns.
  - Relevant tooling, caching, or security enhancements specifically applicable to the refactored architecture.
  - Each suggested next step must include a technical rationale, priority level, and verified benefits.

---

### Final Deliverable Output Template

Your concluding response must strictly follow this user-friendly, data-driven structure:

## 🚀 Refactoring Overview & Executive Summary
[A clear, plain-language summary detailing what was changed, why the architectural changes were made, and how the new behavior operates seamlessly.]

## 📊 Modification & Impact Statistics
| Metric | Count / Status | Details |
| :--- | :--- | :--- |
| **Files Modified / Refactored** | [e.g., 8 files] | [Key modules touched] |
| **Files Created / Deprecated** | [e.g., +2 created, -3 removed] | [Orphaned/legacy assets deleted] |
| **Legacy Variables & APIs Renamed** | [e.g., 14 references] | [Clean naming alignment] |
| **Skills Discovered / Utilized** | [e.g., /find-skills -> 2 skills installed] | [Identified capabilities leveraged] |
| **Tests Run & Pass Rate** | [e.g., 42/42 passed (100%)] | [Unit & integration test results] |
| **Diagnostic Logs Added** | [e.g., 6 telemetry points] | [Observability coverage] |
| **Dependencies Verified / Updated** | [e.g., 2 checked / 1 bumped] | [Verified via real-time research] |

## 🔍 Deep-Researched Next Steps
*Every recommendation below has been validated against modern documentation and industry standards:*
- **[Next Step Title]** *(Priority: High/Medium/Low)*: [Specific technical action].
  - **Rationale & Industry Standard:** [Verified reason based on research, citing official patterns, performance gains, or security requirements].
- **[Next Step Title]** *(Priority: High/Medium/Low)*: [Specific technical action].
  - **Rationale & Industry Standard:** [Verified reason based on research, citing official patterns, performance gains, or security requirements].

---

### Fallback Constraints ("8th-Grade Ask Rule")
If you encounter roadblocks, missing credentials, contradictory requirements, or environmental blockers:
1. Attempt to resolve them through deep real-time research and `/find-skills` first.
2. If research does not yield a definitive path forward, pause immediately and ask using this exact structure:
   - **Explanation:** Explain the issue simply, as if explaining to an 8th-grade beginner.
   - **Analysis:** List clear Pros and Cons for each alternative approach.
   - **Context:** Provide concrete, real-world examples illustrating each choice.
   - **Clickable Options:** Provide structured, selectable choices (e.g., `[Option A]`, `[Option B]`).