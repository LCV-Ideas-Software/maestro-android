/**
 * Analysis-only JavaScript marker for GitHub Code Quality.
 *
 * This module is deliberately not imported by Pages, an Android application,
 * or any production runtime. Its sole purpose is to keep one language that
 * native Code Quality's rule-based analysis supports: this repository's source
 * is Kotlin, which that analysis does not cover yet. It is removed once Code
 * Quality covers Kotlin (MAEANDR-20).
 */
export const CODE_QUALITY_PROBE = Object.freeze({
  repository: "LCV-Ideas-Software/maestro-android",
  purpose: "GitHub Code Quality language detection",
});
