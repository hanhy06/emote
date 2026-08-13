import { useEffect, useState } from "preact/hooks";
import { addMetadataEntry, parseMetadataJson, renameMetadataEntry } from "./additionalMetadata";

interface AdditionalMetadataEditorProps {
  value: Record<string, unknown>;
  disabled: boolean;
  onChange: (value: Record<string, unknown>) => void;
}

export function AdditionalMetadataEditor({ value, disabled, onChange }: AdditionalMetadataEditorProps) {
  return (
    <section className="additional-metadata" aria-labelledby="additional-metadata-heading">
      <div className="additional-metadata-heading">
        <h3 id="additional-metadata-heading">Additional metadata</h3>
        <p>Custom fields are preserved as JSON and can be added, renamed, changed, or removed.</p>
      </div>
      {Object.entries(value).length === 0
        ? <p className="empty-metadata">No additional metadata.</p>
        : <div className="metadata-editor-list">
          {Object.entries(value).map(([key, fieldValue]) => (
            <MetadataRow
              key={key}
              fieldKey={key}
              value={fieldValue}
              metadata={value}
              disabled={disabled}
              onChange={onChange}
            />
          ))}
        </div>}
      <button className="metadata-add-button" type="button" disabled={disabled} onClick={() => onChange(addMetadataEntry(value))}>Add field</button>
    </section>
  );
}

interface MetadataRowProps {
  fieldKey: string;
  value: unknown;
  metadata: Record<string, unknown>;
  disabled: boolean;
  onChange: (value: Record<string, unknown>) => void;
}

function MetadataRow({ fieldKey, value, metadata, disabled, onChange }: MetadataRowProps) {
  const [keyDraft, setKeyDraft] = useState(fieldKey);
  const [valueDraft, setValueDraft] = useState(JSON.stringify(value));
  const [error, setError] = useState("");

  useEffect(() => setKeyDraft(fieldKey), [fieldKey]);
  useEffect(() => setValueDraft(JSON.stringify(value)), [value]);

  function commitKey() {
    try {
      onChange(renameMetadataEntry(metadata, fieldKey, keyDraft));
      setError("");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Invalid metadata key.");
    }
  }

  function commitValue() {
    try {
      onChange({ ...metadata, [fieldKey]: parseMetadataJson(valueDraft) });
      setError("");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Invalid metadata value.");
    }
  }

  return (
    <div className="metadata-editor-row">
      <label>Key<input value={keyDraft} disabled={disabled} onInput={(event) => setKeyDraft(event.currentTarget.value)} onBlur={commitKey} /></label>
      <label>JSON value<textarea rows={2} value={valueDraft} disabled={disabled} onInput={(event) => setValueDraft(event.currentTarget.value)} onBlur={commitValue} /></label>
      <button type="button" disabled={disabled} onClick={() => onChange(Object.fromEntries(Object.entries(metadata).filter(([key]) => key !== fieldKey)))}>Remove</button>
      {error && <p className="error" role="alert">{error}</p>}
    </div>
  );
}
