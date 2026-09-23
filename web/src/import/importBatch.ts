import type { ConversionDocument } from "../domain/conversionDocument";
import { createConversionDocument } from "../domain/conversionDocument";
import { combineConversionDocuments } from "../domain/conversionBatch";
import type { ImportedSequence } from "../domain/emoteDefinition";
import { ConversionError, conversionErrorMessage, type ConversionIssue } from "../foundation/diagnostics";
import { isImportedSequence, type ImportAdapterLoader } from "./adapter";
import { detectAdapter, importDetected } from "./adapterRegistry";

export interface ImportFile {
  name: string;
  arrayBuffer(): Promise<ArrayBuffer>;
}

export async function importFileBatch(files: readonly ImportFile[], adapters: readonly ImportAdapterLoader[]): Promise<ConversionDocument> {
  const results = await Promise.allSettled(files.map(async (file) => {
    const input = { name: file.name, bytes: new Uint8Array(await file.arrayBuffer()) };
    const detected = await detectAdapter(adapters, input);
    const source = await importDetected(detected, input);
    return isImportedSequence(source)
      ? { sequence: source }
      : { document: createConversionDocument(source, detected.adapter.label) };
  }));

  const documents: ConversionDocument[] = [];
  const sequences: ImportedSequence[] = [];
  const failures: ConversionIssue[] = [];
  results.forEach((result, index) => {
    if (result.status === "rejected") {
      failures.push({
        severity: "warning",
        code: "file_import_failed",
        message: `${files[index].name}: ${conversionErrorMessage(result.reason, "Could not import this file.")}`,
        sourcePath: files[index].name,
      });
      return;
    }
    if ("document" in result.value && result.value.document) documents.push(result.value.document);
    else if ("sequence" in result.value && result.value.sequence) sequences.push(result.value.sequence);
  });

  if (documents.length === 0 && failures.length > 0) {
    throw new ConversionError("file_import_failed", failures.map((failure) => failure.message).join(" "));
  }
  const document = combineConversionDocuments(documents, sequences);
  return failures.length === 0 ? document : { ...document, diagnostics: [...document.diagnostics, ...failures] };
}
