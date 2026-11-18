import type { ConversionAnimation } from "../domain/conversionDocument";

interface ExportPanelProps {
  assignmentSummary?: string;
  animations: ConversionAnimation[];
  error: string;
  disabled: boolean;
  onDownloadAnimation: (index: number) => void;
  onDownloadAllAnimations: () => void;
  onDownloadSequence: () => void;
}

export function ExportPanel({
  assignmentSummary,
  animations,
  error,
  disabled,
  onDownloadAnimation,
  onDownloadAllAnimations,
  onDownloadSequence,
}: ExportPanelProps) {
  const hasAnimations = animations.length > 0;
  return (
    <section className="export">
      <div className="section-heading export-heading">
        <div>
          <span className="step-label">Page 2</span>
          <h2>Output</h2>
          <p>{hasAnimations ? "Download one animation or download every JSON file in sequence." : "Download the sequence as JSON."}</p>
        </div>
        {assignmentSummary && <span className="summary-badge">{assignmentSummary}</span>}
      </div>
      {error && <p className="error" role="alert"><strong>Error</strong> {error}</p>}
      <div className="bundle-actions">
        {hasAnimations && <button type="button" disabled={disabled} onClick={onDownloadAllAnimations}>Download all JSON</button>}
        <button className="primary-button" type="button" disabled={disabled} onClick={onDownloadSequence}>{hasAnimations ? "Download sequence files" : "Download sequence JSON"}</button>
      </div>
      {hasAnimations && <>
        <h3>Animations</h3>
        <ul className="download-list">
          {animations.map((animation, index) => (
            <li key={`${animation.id}:${index}`}>
              <span><strong>{animation.metadata.name}</strong><small>{animation.id}</small></span>
              <div className="download-actions">
                <button className="primary-button" type="button" disabled={disabled} onClick={() => onDownloadAnimation(index)}>Download JSON</button>
              </div>
            </li>
          ))}
        </ul>
      </>}
    </section>
  );
}
