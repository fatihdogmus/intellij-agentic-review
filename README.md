# Agentic Review

[![JetBrains plugins](https://img.shields.io/jetbrains/plugin/v/31653.svg)](https://plugins.jetbrains.com/plugin/31653-agentic-review)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/31653.svg)](https://plugins.jetbrains.com/plugin/31653-agentic-review)

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/31653-agentic-review" id="install-plugin"></a>
  <script src="https://plugins.jetbrains.com/assets/scripts/mp-widget.js"></script>
  <script>MarketplaceWidget.setupMarketplaceWidget('install', 31653, '#install-plugin');</script>
</p>

Agentic Review is an IntelliJ IDEA plugin for doing lightweight code review inside your local workspace.

Instead of pushing a branch and waiting for a remote PR, you can open a review for:
- current uncommitted changes
- one commit
- multiple selected commits
- the current branch compared against `main` or `master`

The plugin keeps review comments locally, lets you revisit them later, and can export the open review state to coding agents through a built-in MCP toolset.

## Purpose

Agentic Review is built for workflows like these:
- review your own work before commit or before push
- collect review comments while exploring a branch locally
- hand actionable review comments to an AI coding agent
- keep a lightweight, persistent review artifact without opening a hosted PR

The plugin is intentionally local-first. Review state lives in the workspace, not on a server.

## Screenshots

Add screenshots here.

Suggested sections:
- Review page overview
- Native VCS Log commit-picking flow
- Inline comment UI in diff view
- Saved review import/export flow

## Main Workflow

1. Open `Tools -> Open Agentic Review` or use `Alt+Shift+P` / `Cmd+Shift+P`.
2. Start from one of these review sources:
 - uncommitted changes
 - `Create Agentic Review -> Pick in VCS Log`
 - `Create Agentic Review -> Branch Review`
3. Browse changed files and diff them in the review page.
4. Add inline comments on changed lines.
5. Mark work as addressed or resolved as review progresses.
6. Copy the agent prompt or use MCP tools to hand open comments to an agent.
7. Optionally save the review as a local archive and load it later.

## Architecture

### UI model

The plugin no longer uses a traditional persistent toolwindow UI.

- The left stripe `Review` toolwindow is only a launcher.
- The real review experience opens as a custom editor tab.
- The editor tab is backed by `ReviewPageVirtualFile`, `ReviewPageEditorProvider`, and `ReviewPageFileEditor`.

This keeps the review page closer to normal IntelliJ navigation and avoids coupling the UI to a resizable toolwindow panel.

### Core state

`ReviewManagerService` is the central project service.

It owns:
- current review selection
- current file selection
- uncommitted review lifecycle
- review creation for commits, commit ranges, and branch comparisons
- save/load archive flow
- prompt export
- comment status updates

### Diff and comments

Changed files are loaded from Git diff state and rendered in IntelliJ diff viewers.

Comments are:
- anchored to file and line context
- stored in workspace state
- shown inline in the diff editor
- filtered in UI so only `OPEN` comments appear in active file review surfaces

### MCP integration

The plugin registers MCP tools through JetBrains' bundled MCP server plugin: `com.intellij.mcpServer`.

This exposes review data to local coding agents without a separate external server process.

## Functionality

### Review sources

#### Uncommitted review

- A singleton uncommitted review is created automatically per project.
- It is the default review opened by the plugin.
- It is never deleted like a normal saved review.
- If the working tree becomes empty, the review remains but shows an empty-state message.
- If `HEAD` changes, stale comments on the uncommitted review are cleared even if the worktree is still dirty.

#### Commit review

- `Create Review -> Pick in VCS Log` opens the native IntelliJ VCS Log.
- In VCS Log, use `Create Agentic Review` from the context menu or toolbar.
- A single selected commit creates a single-commit review.

#### Multi-commit review

- Selecting multiple commits in VCS Log creates one combined review.
- Commits are ordered by commit timestamp.
- The review diff is computed from the first parent of the oldest selected commit to the newest selected commit.

This means non-contiguous commit selections produce the full net range diff, not isolated per-commit diffs.

#### Branch review

- `Branch Review` compares the current branch against local `main`, falling back to local `master`.
- The review is unavailable when already on `main` or `master`.

### Review page

The review page includes:
- review selector
- changed files list
- IntelliJ diff viewer
- create/edit review actions
- prompt export action

The changed files panel supports:
- single click to select and show diff
- double click to open the real file
- delete shortcut to delete the selected file through IntelliJ's native delete flow

The diff viewer supports:
- inline comment creation
- inline comment display
- `F4` to open the underlying file
- diff header titles that include file path context

### Comments and statuses

Supported comment statuses:
- `OPEN`
- `RESOLVED`

Current UI behavior:
- active review surfaces show resolved comments as a collapsed row, expandable to the full body and reply thread
- review lists still show open/resolved counts
- agents or humans can mark comments `RESOLVED`; only humans can reopen a resolved comment

### Save and load

- Reviews can be saved as JSON archives under `.agentic-review/` inside the repository root.
- Saved archives include review metadata and comments.
- Loading validates structure and commit reachability on the current branch.
- Loaded reviews get fresh review and comment IDs.

The save/load flow is intentionally local and file-based. It does not keep a live link between a saved archive and the in-memory review.

### Prompt export

`Copy Prompt` exports a Markdown review summary for agent use.

The exported prompt is intentionally compact:
- only open comments are included
- comment payload avoids extra diff noise
- internal anchoring details like diff side are not exposed

### MCP tools

Current MCP tools include:
- `review_list_reviews`
- `review_get_review`
- `review_list_unresolved_comments`
- `review_get_comment_context`
- `review_mark_comment_resolved`
- `review_export`

Important MCP behavior:
- tool responses are JSON strings
- `review_mark_comment_resolved` can attach optional agent metadata about the implemented change

## Development

### Requirements

- Java toolchain 21
- IntelliJ Platform Gradle Plugin setup from `build.gradle.kts`

### Common commands

Compile:

```bash
./gradlew compileKotlin
```

Run tests:

```bash
./gradlew check
```

Build plugin ZIP:

```bash
./gradlew buildPlugin
```

Verify plugin against IntelliJ:

```bash
./gradlew verifyPlugin
```

Run sandbox IDE:

```bash
./gradlew runIde
```

Run one test class:

```bash
./gradlew test --tests "dev.agentreview.intellij.ReviewManagerServiceTest"
```

### Notes for contributors

- `README.md` should describe user-facing behavior, but code and Gradle config are the real source of truth.
- The plugin currently targets `intellijIdea("2026.1.1")` from `build.gradle.kts`.
- MCP is enabled by default in `runIde` via `-Dlocal.review.enable.mcp.by.default=true`.
- Saved review artifacts in `.agentic-review/` are local workspace data and should not be committed.

## License

This project is licensed under the MIT License. See [`LICENSE`](LICENSE).
