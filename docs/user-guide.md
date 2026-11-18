# User Guide

## Uncommitted Reviews

An **Uncommitted Changes** review is automatically created when you open a project. It captures all local modifications — edited, added, deleted, renamed, and unversioned files — and refreshes automatically as you work.

<put_example_ss_of_uncommitted_review_here>

- The uncommitted review is a singleton workspace review; it cannot be deleted or saved to a file.
- When the repository HEAD changes (e.g. after committing) or all local changes disappear, the review resets: all comments, seen-file state, and turn snapshots are cleared.

---

## Creating Reviews

You can create formal reviews from committed changes in three ways.

### Single Commit Review

1. Open the VCS Log (`Git` > `Show Log` or use the version control tab).
2. Select a single commit in the log.
3. Click **Create Agentic Review** in the log toolbar or right-click and select it from the context menu.

<put_example_ss_of_single_commit_review_creation_here>

A review is created with the commit message as its title.

### Multi-Commit Review

1. Open the VCS Log and select multiple commits (use Shift/Cmd+Click).
2. Click **Create Agentic Review** from the toolbar or context menu.

The review spans the full net diff from the oldest selected commit's parent to the newest selected commit, regardless of whether the selection is contiguous. Commits are ordered by timestamp.

<put_example_ss_of_multi_commit_review_creation_here>

### Branch Review

1. In the Review toolwindow, click **Create Review** > **Branch Review**.
2. The plugin compares your current branch against `main` (or `master` as fallback).

<put_example_ss_of_branch_review_creation_here>

Branch review is unavailable when you are already on `main` or `master`.

### Alternative: Pick in VCS Log

From the Review toolwindow you can also click **Create Review** > **Pick in VCS Log**. This opens the VCS Log and shows a reminder notification to select commits and run **Create Agentic Review**.

<put_example_ss_of_pick_in_vcs_log_here>

---

## Managing Reviews

Each review appears in the Review toolwindow panel. The **Edit** menu provides review-level management actions.

<put_example_ss_of_review_toolwindow_with_edit_menu_here>

### Rename

Select **Edit** > **Rename** to change a review's title. The uncommitted review cannot be renamed.

### Save to File

Select **Edit** > **Save** to archive the current review as a JSON file. The file is written to `<repo>/.agentic-review/<title>-<id>.json`. The uncommitted review cannot be saved.

<put_example_ss_of_save_review_here>

### Load from File

Select **Edit** > **Load** and pick a previously saved `.json` archive. The plugin validates that the referenced commits are reachable from the current branch and imports the review with a new ID and fresh comment identifiers.

<put_example_ss_of_load_review_here>

### Delete

Select **Edit** > **Delete** to remove a review from the workspace. The uncommitted review cannot be deleted. When the current review is deleted, the panel reverts to the uncommitted review.

---

## Review Comments

Comments help you annotate specific lines or provide general feedback.

### Adding a Comment

- **From the diff editor**: Click the green gutter icon on any line to open an inline comment form. Type your comment and submit.
- **From the review panel**: Use the **Add Review Comment** action to open a dialog where you specify the side (left/right), line number, and comment body.

<put_example_ss_of_adding_comment_here>

### Editing a Comment

Every open comment card has a `...` menu. Select **Edit comment** to switch to inline editing. Save with Cmd+Enter (macOS) / Ctrl+Enter (Windows/Linux), or cancel to discard changes.

<put_example_ss_of_editing_comment_here>

### Resolving a Comment

Select **Resolve comment** from the `...` menu to mark a comment as resolved. Resolved comments are hidden from the default view and the agent prompt.

### Deleting a Comment

Select **Delete comment** from the `...` menu to permanently remove a comment.

---

## Navigating to Files

- **Double-click** any file in the changed files list to open it in the diff editor.
- While viewing a file in the diff editor, press **F4** or use the **Go to File** action from the editor popup to open the underlying source file in a normal editor tab.

<put_example_ss_of_navigation_here>

Deleted files cannot be opened as source files.

---

## Seen / Unseen Tracking

Files you have opened in the diff editor are automatically marked as **seen**.

- Unseen files are displayed with a leading `*` and bold text in the changed files list.
- Seen files appear in normal weight without the prefix.

<put_example_ss_of_seen_unseen_here>

The seen state is keyed to the file's content hash, so if the underlying file changes, it becomes unseen again.

---

## Turn Snapshots

The [`@fatihdogmus/opencode-agentic-review`](https://github.com/fatihdogmus/opencode-plugins/tree/main/packages/agentic-review) OpenCode plugin records each agent turn as a **turn snapshot**. A turn is one run of the agent, from its first model step until it succeeds, fails, or is interrupted. It captures which files the agent changed (through OpenCode's `write`, `edit`, and `patch` tools), when the turn started and ended, and which tool calls it made, including `shell` calls.

Install it in OpenCode V2 with `opencode plugin add @fatihdogmus/opencode-agentic-review`. It needs the IDE's built-in MCP server; see the plugin's README for options.

<put_example_ss_of_turn_dropdown_here>

The **Turn** dropdown appears in the changed files panel when turn snapshots exist for the current uncommitted changes review. Selecting a turn from the dropdown filters the diff view to show only the files that were changed during that specific agent turn. The default option, **Review Changes**, shows the full uncommitted diff.

Turn snapshots only work with the uncommitted changes review — they are not available for reviews created from commits or branches.

- Each turn entry displays the agent name, start time, duration, and number of files changed.
- Turn snapshots are persisted across IDE restarts.
- Turn snapshots are cleared when changes are committed.

---

## MCP Integration & Agent Prompt

The plugin integrates with IntelliJ's built-in MCP Server so that AI agents can read reviews and interact with comments programmatically.

### Available MCP Tools

- `review_list_reviews` — list all reviews in the project
- `review_get_review` — get full review details (supports selectors: `current`, `latest`, `latest-open`, `uncommitted`, `commit:<hash>`)
- `review_list_unresolved_comments` — list open (unresolved) comments for a review
- `review_get_comment_context` — get the code context surrounding a comment
- `review_mark_comment_resolved` — mark a comment as resolved (optionally with agent metadata)
- `review_export` — export a review as JSON or Markdown
- `review_turn_snapshot_begin` / `review_turn_snapshot_end` — delimit agent turns for tracking (called by the opencode plugin)
- `review_list_turn_snapshots` — list saved turn snapshots

<put_example_ss_of_mcp_tools_available_here>

### Copying the Agent Prompt

Click the **Copy Prompt** button on the review page to copy a structured Markdown prompt to your clipboard. The prompt includes review metadata, open comments with line ranges, and file context — ready to be pasted into an AI agent conversation.

<put_example_ss_of_copy_prompt_button_here>

### Typical Workflow

1. Create a review and add comments on the lines you want changed.
2. Copy the agent prompt (or let the MCP client discover the review).
3. The agent reads the review through MCP, implements changes, and marks resolved comments.
4. Review the result in the diff editor and follow up if needed.

<put_example_ss_of_mcp_workflow_here>
