/**
 * Single-project pipeline entry point (backward-compatible wrapper).
 * Delegates to pipeliner() in project mode.
 */
def call(Map overrides = [:]) {
    pipeliner(overrides)
}
