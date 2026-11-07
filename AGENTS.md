# AGENTS.md

## Purpose

This file defines the general working principles that AI coding agents must follow when modifying this repository.

The project is still under active design. Some requirements and architectural decisions are intentionally undecided.

The agent must distinguish between:

* decisions that have already been confirmed,
* requirements explicitly given in the current task,
* and decisions that have not yet been made.

Do not treat undecided areas as implicit requirements.

---

## 1. Work Only From Confirmed Decisions

Only implement or modify behavior that is supported by:

1. an explicitly confirmed project decision,
2. existing project code or documentation,
3. or the user's current request.

Do not invent missing product requirements, architecture, domain concepts, data models, APIs, algorithms, or infrastructure.

If something has not been decided yet, leave it undecided unless the current task explicitly requires making that decision.

---

## 2. Do Not Turn Examples Into Requirements

Documentation may contain:

* examples,
* possible future ideas,
* candidate technologies,
* sample package names,
* hypothetical domain concepts,
* or potential implementation approaches.

These are not requirements unless they are explicitly described as confirmed decisions.

Do not implement something merely because it appears as an example in documentation.

---

## 3. Prefer the Smallest Necessary Change

Make only the changes required to complete the requested task.

Avoid unrelated changes such as:

* unnecessary refactoring,
* renaming unrelated files,
* moving files without a clear need,
* reorganizing packages or directories,
* replacing existing implementations,
* modifying unrelated configuration,
* adding abstractions for hypothetical future use.

Do not expand the scope of the task without an explicit reason.

---

## 4. Preserve Existing Decisions

Do not change established project decisions unless the current task explicitly requests the change.

When modifying existing code:

* preserve existing behavior unless it must change,
* preserve established conventions where reasonable,
* and avoid introducing conflicting architectural decisions.

A new implementation should fit the confirmed project direction rather than redefine it.

---

## 5. Do Not Design Undecided Areas Prematurely

Do not create speculative structures for functionality that does not yet exist.

For example, do not pre-create:

* domain packages,
* database tables,
* entities,
* APIs,
* services,
* repositories,
* pipelines,
* algorithms,
* frontend pages,
* components,
* infrastructure services,

simply because they might be useful later.

Create new structures when an actual confirmed requirement requires them.

---

## 6. Avoid Premature Abstraction

Prefer clear and direct implementations over unnecessary abstraction.

Do not introduce patterns, interfaces, factories, strategies, base classes, generic frameworks, or additional architectural layers unless they solve a concrete current problem.

Do not implement future extensibility at the cost of unnecessary complexity in the present codebase.

---

## 7. Do Not Introduce Technology Without Need

Do not add new:

* frameworks,
* libraries,
* dependencies,
* databases,
* infrastructure components,
* build tools,
* external services,

unless they are required by the current task or explicitly approved.

Before adding a dependency, first consider whether the requested functionality can reasonably be implemented using the existing project stack.

---

## 8. Respect Repository Boundaries

Modify only files relevant to the requested task.

Do not modify unrelated project areas as a side effect of completing another task.

For example, a data-processing task should not result in unrelated backend or frontend restructuring unless explicitly required.

---

## 9. Keep Changes Understandable

Prefer code that is:

* simple,
* readable,
* maintainable,
* explicit,
* and easy for another team member to understand.

Avoid clever implementations when a straightforward solution is sufficient.

Comments should explain important reasoning or non-obvious behavior rather than restating the code.

---

## 10. Validate Changes

After making changes, perform the relevant validation when possible.

Examples include:

* running tests,
* running the relevant build,
* checking syntax,
* checking imports,
* verifying configuration,
* or executing the affected functionality.

Do not claim that validation succeeded if it was not actually performed.

If validation cannot be performed, clearly state that limitation.

---

## 11. Do Not Hide Problems

If the existing code contains an issue directly relevant to the requested work, report it clearly.

Do not silently work around errors in a way that hides the underlying problem.

Do not fabricate successful outputs, test results, files, commands, or runtime behavior.

---

## 12. Protect Sensitive Data

Do not commit or expose sensitive information.

Examples include:

* passwords,
* API keys,
* access tokens,
* private credentials,
* personal information,
* sensitive raw datasets.

Do not place secrets directly in source code.

Do not add real sensitive data to test fixtures or examples.

---

## 13. Documentation Must Match Reality

When a task changes confirmed behavior, configuration, setup instructions, or project usage, update related documentation when necessary.

Do not document functionality that has not actually been implemented.

Do not describe undecided future plans as completed or confirmed behavior.

---

## 14. When Requirements Are Ambiguous

First inspect the existing code and project documentation for an established decision.

If no decision exists, do not silently choose a major product or architectural direction.

When possible, continue with the parts of the task that are already well-defined and leave unrelated undecided decisions untouched.

---

## Core Principle

> Implement what has been decided.
> Preserve what has already been decided.
> Do not decide what has not yet been decided.

When several implementations could satisfy a confirmed requirement, prefer the simplest solution that changes the least amount of existing code.
