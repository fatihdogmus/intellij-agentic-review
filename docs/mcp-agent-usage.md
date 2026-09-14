# MCP Agent Usage

Current build ships MCP review access through JetBrains bundled MCP Server.

## Available Now

- Create review for uncommitted changes.
- Create review from commit hash.
- Persist comments in workspace state.
- Copy agent prompt with Markdown payload.
- MCP tools:
  - `review_list_reviews`
  - `review_get_review`
  - `review_list_unresolved_comments`
  - `review_get_comment_context`
  - `review_mark_comment_addressed`
  - `review_mark_comment_resolved` (returns disabled by default)
  - `review_reply_to_comment`
  - `review_export`

## Agent Workflow

1. Create review in `Review` tool window.
2. Add comments.
3. Agent calls review MCP tools against current project.
4. Agent implements requested changes.
5. Agent marks implemented comments `RESOLVED` through MCP.
6. Human reviews the result in UI if desired.

## MCP Status

JetBrains bundled MCP Server exists in IntelliJ IDEA 2025.2+.

This plugin now registers custom review tools through `com.intellij.mcpServer.mcpToolset`.

Current behavior:

- Review read tools are available directly to MCP clients.
- `review_mark_comment_resolved` updates stored comment state and optional agent metadata. Its `message` parameter now appears in the comment's thread as a `RESOLUTION` reply rather than in `agentMetadata.message` (which is left `null`).
- `review_reply_to_comment` posts a reply to an existing comment without changing the comment's status, so an agent can ask a clarifying question or push back on a comment instead of being forced to resolve it in order to say anything.
  - Parameters: `commentId` (required), `body` (required), `agentName` (optional, defaults to the MCP client name when available, otherwise `"agent"`), `runId` (optional).
  - Fails with `Reply body must not be blank` on an empty or whitespace-only body, and `Comment not found: <id>` on an unknown comment id.
  - Returns `{ok, commentId, replyId, replyCount}`.
- Comment payloads returned by `review_get_review`, `review_list_unresolved_comments`, and `review_get_comment_context` now carry a `replies` array projected from the comment's thread: it returns stored replies when any exist, otherwise it synthesises a single legacy entry from a non-blank `agentMetadata.message` (id `legacy-<commentId>`). That synthesised entry corresponds to no stored reply and is not addressable by id. The array is also non-monotonic: once any real reply is stored on the comment, the synthesised legacy entry disappears from all later payloads for good. `replyCount` returned by `review_reply_to_comment` counts stored replies only (`comment.replies.size`), not this projected array, so the two can diverge on comments that still carry legacy-only metadata.
- Review export supports `markdown` and `json`.
