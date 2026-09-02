# Superseded V0/V1 acceptance fixtures

These three historical fixtures launch MainActivity and require automatic writes or automatic fuzzy lookup. Those behaviors are intentionally replaced by the V2 user-confirmed draft flow and exact identity lookup. They are archived for provenance, not counted as passing V2 tests and not executed against user data.

Their V2 replacements are InventoryUiTest (A–D, explicit confirmation and selected-unit deletion), InventoryRepositoryTest (read-only drafts, identity/units/no-op/migration), and NluDraftTest (editable raw/structured input, cancellation and no model writes). Pure legacy parser/fuzzy/speech tests remain runnable and are not removed.
