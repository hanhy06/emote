import type { ExportResult } from "./types";

export function downloadExport(result: ExportResult): void {
  downloadExports([result]);
}

export function downloadExports(results: readonly ExportResult[]): void {
  const urls: string[] = [];
  for (const result of results) {
    const url = URL.createObjectURL(result.blob);
    urls.push(url);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = result.fileName;
    anchor.click();
  }
  setTimeout(() => urls.forEach((url) => URL.revokeObjectURL(url)), 0);
}
