import { createConversionDocument, type ConversionDocument, type ImportedConversionDocument } from "../domain/conversionDocument";
import { combineConversionDocuments } from "../domain/conversionBatch";
import { ConversionError, conversionErrorMessage, type ConversionIssue } from "../foundation/diagnostics";
import { detectInputFormat, readInput, INPUT_FORMATS } from "./formats";

export interface ImportFile {
  name: string;
  arrayBuffer(): Promise<ArrayBuffer>;
}

export async function importFileBatch(files: readonly ImportFile[]): Promise<ConversionDocument> {
  const results = await Promise.allSettled(files.map(async (file) => {
    const input = { name: file.name, bytes: new Uint8Array(await file.arrayBuffer()) };
    const format = await detectInputFormat(input);
    try {
      const source = await readInput(format, input);
      return "entries" in source ? source : createConversionDocument(source, INPUT_FORMATS[format].label);
    } catch (reason) {
      throw ConversionError.fromUnknown(
        reason,
        `${format}_import_failed`,
        `Could not import ${input.name} as ${INPUT_FORMATS[format].label}.`,
        input.name,
      );
    }
  }));

  const documents: ImportedConversionDocument[] = [];
  const failures: ConversionIssue[] = [];
  results.forEach((result, index) => {
    if (result.status === "rejected") {
      failures.push({
        severity: "error",
        code: "file_import_failed",
        message: `${files[index].name}: ${conversionErrorMessage(result.reason, "Could not import this file.")}`,
        sourcePath: files[index].name,
      });
      return;
    }
    documents.push(result.value);
  });

  if (documents.length === 0 && failures.length > 0) {
    throw new ConversionError("file_import_failed", failures.map((failure) => failure.message).join(" "));
  }
  const document = combineConversionDocuments(documents);
  return failures.length === 0 ? document : { ...document, diagnostics: [...document.diagnostics, ...failures] };
}
