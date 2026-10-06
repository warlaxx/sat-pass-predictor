/**
 * The address of a separation's link preview, rendered at build time by
 * scripts/og-images.mjs (`previewPath` there writes the file; this names it).
 */
export function previewPath(id: string, language: string): string {
  return `/og/separations/${encodeURIComponent(id)}.${language}.png`;
}
