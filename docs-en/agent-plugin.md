# File Agent Plugin Design

## Scope

An Agent here is a plugin that can observe a directory, produce a file-operation plan, and execute that plan. It does not require a chat UI or a language model. Rule-based organization, bulk rename, duplicate inspection, natural-language commands, and remote models can all share this boundary.

The Agent should only query files, produce a previewable structured plan, and submit that plan to the host transaction/history service. It must not mutate files directly through `java.io.File`, because direct mutations would bypass the history shared by the UI and other plugins.

## Host capabilities

Continue using optional `PluginHost` provider interfaces under `plugin-api.file`, without adding required members to `PluginHost`:

- `query`: bounded metadata/content search by directory, name, extension, size, modification time, and recursion depth.
- `preview`: resolve declarative `move`, `copy`, `rename`, `trash`, `mkdir`, and `writeText` commands without changing disk state.
- `commit`: execute one approved plan on the host file worker and create one history node.
- `history`: inspect nodes and branches and call `undo`, `redo(childId)`, or `checkout(nodeId)`.

Plans should contain declarative commands and preconditions, not executable closures. At minimum, require that sources still exist and destinations are still absent. A plan that became stale after preview must fail instead of silently selecting a different numbered destination.

## Branching history

```text
root
└─ 1 Organize screenshots
   ├─ 2 Archive by month
   └─ 3 Archive by project  ← current
```

Recording after an undo creates a sibling branch without deleting the old future. Checkout finds the lowest common ancestor, undoes to it, and then redoes down the requested branch. The cursor advances after each successful filesystem step, so failure leaves it at the state actually reached.

The current implementation provides a session-scoped `FileHistoryController`; create, delete, copy, move, and rename now record bidirectional actions. “Redo” selects the newest child by default. A later history or Agent UI can select older branches by node ID.

## Persistence and safety

The current `.ane-filemanager-trash` is cleared at app startup, so history remains session-only. Cross-process history would also need immutable payload storage for deleted, overwritten, and pre-edit content; persisted nodes and cursor; payload reference counting and quotas; and file identity/preconditions for detecting external changes.

Queries and mutations must stay within host-granted roots after canonical/symlink resolution. Destructive and overwrite operations must be explicit in the preview. One Agent plan should become one history node rather than one node per file.

## Delivery order

1. Branching bidirectional history and existing UI integration (foundation implemented).
2. Move executor, transaction commit, and history ownership into one shared host service (completed for UI actions, text writes, and plugin output commits).
3. Expose optional query, preview, commit, and history providers in `plugin-api.file`.
4. Build a model-free rule organizer plugin to validate the boundary.
5. Add natural-language or model planning later; the model emits plans and never mutates paths directly.
