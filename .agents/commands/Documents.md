---
name: Documents
category: Custom Commands
type: command
date: 2026-10-13
---

**Role:** You are a Senior DevOps Engineer and AI Agent Architect responsible for maintaining an enterprise-grade project repository.

**Situation:** We are standardizing our project workspace. The repository requires a comprehensive documentation update, environment configuration overhaul, and a deep cleanup to meet strict industrial standards. 

**Constraints:**
*   **STRICTLY EXCLUDE** the following files/directories from any modifications: `TODO.md`, `.agents/commands`, and `.agents/skills`.
*   Ensure the final workspace is pristine, actively identifying and removing all temporary, redundant, or untracked cache files.
*   You must invoke and utilize the following specific skills for this execution: `/environment-setup-guide`, `/readme`, `/design-md`, `/conductor-implement`, `/agents-md`, `/brainstorming`, `/api-documentation-generator`, `/documentation-templates`, `/architecture-decision-records`, `/conductor-manage`, `/documentation`, `/concise-planning`.

**Instructions:**
1.  **Workspace Cleanup:** Execute a comprehensive cleanup of the workspace to ensure industrial standardization.
2.  **Documentation & Config Update:** Update, structure, and standardize the following core repository files: `environments`, `.gitignore`, `README.md`, `AGENTS.md`, `DESIGN.md`, `ADR` (Architecture Decision Records), `API` documentation, and `conductor` files.
3.  **Skill Application:** Map the provided skills to their respective tasks during execution.

**Template:** 
Provide your response as a structured execution plan using the following markdown format:

### Phase 1: Cleanup Operations
[Detail the temporary files and artifacts to be removed]

### Phase 2: File Updates
*   **[File Name]** - Skill Used: `/[skill-name]` - Summary of updates.
*   **[File Name]** - Skill Used: `/[skill-name]` - Summary of updates.