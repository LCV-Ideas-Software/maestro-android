/**
 * Analysis-only JavaScript marker for GitHub Code Quality.
 *
 * This module is deliberately not imported by Pages, an Android application,
 * or any production runtime. Its sole purpose is to keep one supported
 * language in this pre-implementation repository so native Code Quality has a
 * deterministic target until real application source exists.
 */
export const CODE_QUALITY_PROBE = Object.freeze({
  repository: "LCV-Ideas-Software/maestro-android",
  purpose: "GitHub Code Quality language detection",
});
