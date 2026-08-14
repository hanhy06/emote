import type { AnimationOutputSettings } from "../domain/conversionDocument";
import { AdditionalMetadataEditor } from "./AdditionalMetadataEditor";

const STOP_CONDITION_OPTIONS = [
  ["jump", "Stop on jump"],
  ["submerge", "Stop when submerged"],
  ["ride", "Stop on mount"],
  ["damage", "Stop when damaged"],
  ["attack", "Stop on attack"],
  ["game_mode_change", "Stop on game mode change"],
] as const;

interface SettingsPanelProps {
  metadata: AnimationOutputSettings;
  minecraftVersion: string;
  disabled: boolean;
  onMetadataChange: (metadata: AnimationOutputSettings) => void;
  onMinecraftVersionChange: (minecraftVersion: string) => void;
}

export function SettingsPanel({ metadata, minecraftVersion, disabled, onMetadataChange, onMinecraftVersionChange }: SettingsPanelProps) {
  function updatePlayerStopCondition(key: keyof AnimationOutputSettings["player"]["stop_conditions"], value: number | boolean) {
    onMetadataChange({
      ...metadata,
      player: { ...metadata.player, stop_conditions: { ...metadata.player.stop_conditions, [key]: value } },
    });
  }

  return (
    <section className="export settings-page">
      <div className="section-heading export-heading">
        <div><span className="step-label">Page 2</span><h2>Metadata, settings &amp; other</h2><p>Edit the JSON-facing metadata and playback behavior.</p></div>
      </div>
      <section className="settings-section" aria-labelledby="metadata-heading">
        <h3 id="metadata-heading">Metadata</h3>
        <div className="fields">
          <label>Namespace<input value={metadata.namespace} disabled={disabled} onChange={(event) => onMetadataChange({ ...metadata, namespace: event.currentTarget.value })} /></label>
          <label>Display name<input value={metadata.displayName} disabled={disabled} onChange={(event) => onMetadataChange({ ...metadata, displayName: event.currentTarget.value })} /></label>
          <label>Description<input value={metadata.description} disabled={disabled} onChange={(event) => onMetadataChange({ ...metadata, description: event.currentTarget.value })} /></label>
        </div>
      </section>
      <AdditionalMetadataEditor value={metadata.additionalMetadata} disabled={disabled} onChange={(additionalMetadata) => onMetadataChange({ ...metadata, additionalMetadata })} />
      <section className="playback-behavior" aria-labelledby="playback-behavior-heading">
        <h3 id="playback-behavior-heading">Settings</h3>
        <p>Minecraft time accepts d, s, t, or bare ticks. Export is normalized to ticks.</p>
        <div className="fields settings-selectors">
          <div className="playback-settings-group">
            <label>Playback mode<select value={metadata.playbackMode} disabled={disabled} onChange={(event) => {
              const playbackMode = event.currentTarget.value as AnimationOutputSettings["playbackMode"];
              onMetadataChange({ ...metadata, playbackMode, loopDelay: playbackMode === "once" ? "0t" : metadata.loopDelay });
            }}>
              <option value="source">Source setting</option><option value="once">Play once</option><option value="loop">Loop</option><option value="server_sync">Server-synchronized loop</option>
            </select></label>
            <label>Loop delay<input value={metadata.loopDelay ?? "0t"} disabled={disabled || metadata.playbackMode === "once"} onChange={(event) => onMetadataChange({ ...metadata, loopDelay: event.currentTarget.value })} /></label>
          </div>
          <label>Cooldown<input value={metadata.cooldown ?? "0t"} disabled={disabled} onChange={(event) => onMetadataChange({ ...metadata, cooldown: event.currentTarget.value })} /></label>
          <label>Movement distance<input type="number" min="0" step="0.05" value={metadata.player.stop_conditions.movement_distance} disabled={disabled} onChange={(event) => updatePlayerStopCondition("movement_distance", Number(event.currentTarget.value))} /></label>
        </div>
        <div className="fields settings-toggles">
          <label className="checkbox"><input type="checkbox" checked={metadata.standalone ?? true} disabled={disabled} onChange={(event) => onMetadataChange({ ...metadata, standalone: event.currentTarget.checked })} />Standalone animation</label>
          <label className="checkbox"><input type="checkbox" checked={metadata.player.hidden} disabled={disabled} onChange={(event) => onMetadataChange({ ...metadata, player: { ...metadata.player, hidden: event.currentTarget.checked } })} />Hide original player</label>
          {STOP_CONDITION_OPTIONS.map(([condition, label]) => <label className="checkbox" key={condition}><input type="checkbox" checked={metadata.player.stop_conditions[condition]} disabled={disabled} onChange={(event) => updatePlayerStopCondition(condition, event.currentTarget.checked)} />{label}</label>)}
        </div>
      </section>
      <section className="playback-behavior"><h3>Other</h3><div className="fields"><label>Minecraft version <small>Used only for generated resource packs.</small><input value={minecraftVersion} disabled={disabled} onChange={(event) => onMinecraftVersionChange(event.currentTarget.value)} /></label></div></section>
    </section>
  );
}
