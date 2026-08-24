interface DownloadItem {
  label: string;
  detail: string;
  exportable: boolean;
  reason?: string;
}

interface ExportPanelProps {
  assignmentSummary: string;
  animations: DownloadItem[];
  hasResources: boolean;
  error: string;
  disabled: boolean;
  onDownloadAnimation: (index: number) => void;
  onDownloadAllAnimations: () => void;
  onDownloadSequence: () => void;
  onDownloadResources: () => void;
}

export function ExportPanel({
  assignmentSummary,
  animations,
  hasResources,
  error,
  disabled,
  onDownloadAnimation,
  onDownloadAllAnimations,
  onDownloadSequence,
  onDownloadResources,
}: ExportPanelProps) {
  const bundleDisabled = disabled || animations.some((animation) => !animation.exportable);

  return (
    <section className="export">
      <div className="section-heading export-heading">
        <div>
          <span className="step-label">Page 3</span>
          <h2>Output</h2>
          <p>Download one animation or download every JSON file in sequence.</p>
        </div>
        <span className="summary-badge">{assignmentSummary}</span>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      {animations.length > 1 && <div className="bundle-actions">
        <button type="button" disabled={bundleDisabled} onClick={onDownloadAllAnimations}>Download all JSON</button>
        <button className="primary-button" type="button" disabled={bundleDisabled} onClick={onDownloadSequence}>Download sequence files</button>
      </div>}
      {hasResources && <div className="export-row">
        <span><strong>Resource files</strong><small>Flat files named with their resource-pack locations.</small></span>
        <button type="button" disabled={disabled} onClick={onDownloadResources}>Download resources ZIP</button>
      </div>}
      <h3>Animations</h3>
      <ul className="download-list">
        {animations.map((animation, index) => (
          <li key={`${animation.detail}:${index}`}>
            <span><strong>{animation.label}</strong><small>{animation.exportable ? animation.detail : `${animation.detail} · Export unavailable`}</small>{!animation.exportable && animation.reason && <small>{animation.reason}</small>}</span>
            <div className="download-actions">
              <button className="primary-button" type="button" disabled={disabled || !animation.exportable} onClick={() => onDownloadAnimation(index)}>Download JSON</button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
