/**
 * Hands a file built in the browser to the browser as a download.
 *
 * Everything the page exports - the calendar, the track - is built from the response
 * already on screen: no request, no quota, and the file says exactly what the page says.
 */
export function saveText(content: string, type: string, fileName: string): void {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

/** "ISS (ZARYA)" -> "iss-zarya": something a downloads folder can hold several of. */
export function slug(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}
