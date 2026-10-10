import { useState } from "preact/hooks";
import type { AnimationEntryIR, TimeValueIR } from "../domain/animationIR";
import type { ConversionAnimation } from "../domain/conversionDocument";
import { AdditionalMetadataEditor } from "./AdditionalMetadataEditor";
import { MINECRAFT_VERSION_PROFILES } from "../format/minecraftVersionProfiles";
import { createDefaultPlayerBehavior } from "../domain/emoteDefinition";
import { parseMinecraftTime } from "../format/time";

const STOP_CONDITION_OPTIONS = [
  ["jump", "Stop on jump"], ["submerge", "Stop when submerged"], ["ride", "Stop on mount"],
  ["damage", "Stop when damaged"], ["attack", "Stop on attack"], ["game_mode_change", "Stop on game mode change"],
] as const;

interface SettingsPanelProps {
  animation: ConversionAnimation;
  minecraftVersion: string;
  disabled: boolean;
  onChange: (animation: AnimationEntryIR) => void;
  onValidityChange: (valid: boolean) => void;
  onMinecraftVersionChange: (minecraftVersion: string) => void;
}

export function SettingsPanel({ animation, minecraftVersion, disabled, onChange, onValidityChange, onMinecraftVersionChange }: SettingsPanelProps) {
  const [drafts, setDrafts] = useState<Record<string, { value: string; error: string }>>({});
  const settings = animation.settings ?? {};
  const playback = animation.clip.playback ?? {};
  const player = settings.player ?? createDefaultPlayerBehavior();
  const mode = playback.mode ?? "once";
  const namespace = animation.id.split(":")[0];
  const additionalMetadata = Object.fromEntries(Object.entries(animation.metadata).filter(([key]) => key !== "name" && key !== "description"));

  function commit(field: string, value: string, edit: () => AnimationEntryIR) {
    const next = { ...drafts };
    try {
      onChange(edit());
      delete next[field];
    } catch (reason) {
      next[field] = { value, error: reason instanceof Error ? reason.message : String(reason) };
    }
    setDrafts(next);
    onValidityChange(Object.values(next).every((draft) => !draft.error));
  }

  function editDraft(field: string, value: string) {
    setDrafts({ ...drafts, [field]: { value, error: "" } });
  }

  function updateTime(field: "cooldown" | "loop_start" | "loop_delay", text: string) {
    commit(field, text, () => {
      if (field === "loop_delay" && text.trim().startsWith("{")) {
        const expression = JSON.parse(text) as TimeValueIR;
        return { ...animation, clip: { ...animation.clip, playback: { ...playback, loop_delay: expression } } };
      }
      parseMinecraftTime(text);
      return field === "cooldown"
        ? { ...animation, settings: { ...settings, cooldown: text } }
        : { ...animation, clip: { ...animation.clip, playback: { ...playback, [field]: text } } };
    });
  }

  function updatePlayerStopCondition(key: keyof typeof player.stop_conditions, value: number | boolean) {
    onChange({ ...animation, settings: { ...settings, player: { ...player, stop_conditions: { ...player.stop_conditions, [key]: value } } } });
  }

  const timeText = (field: string, value: TimeValueIR | undefined) => drafts[field]?.value ?? (typeof value === "object" ? JSON.stringify(value) : value ?? "0s");

  return (
    <section className="export settings-page">
      <div className="section-heading export-heading">
        <div><span className="step-label">Page 2</span><h2>Metadata &amp; settings</h2><p>Edit the JSON-facing metadata and playback behavior.</p></div>
      </div>
      <div className="minecraft-version-field">
        <label>Target Minecraft version
          <select value={minecraftVersion} disabled={disabled} onChange={(event) => onMinecraftVersionChange(event.currentTarget.value)}>
            {Object.keys(MINECRAFT_VERSION_PROFILES).map((version) => <option key={version} value={version}>{version}</option>)}
          </select>
        </label>
      </div>
      <section className="settings-section" aria-labelledby="metadata-heading">
        <h3 id="metadata-heading">Metadata</h3>
        <div className="fields">
          <label>Namespace<input value={drafts.namespace?.value ?? namespace} disabled={disabled} onInput={(event) => editDraft("namespace", event.currentTarget.value)} onBlur={(event) => {
            const value = event.currentTarget.value;
            commit("namespace", value, () => {
              return { ...animation, id: `${value}:${animation.id.slice(animation.id.indexOf(":") + 1)}` };
            });
          }} /></label>
          <label>Display name<input value={drafts.name?.value ?? animation.metadata.name} disabled={disabled} onInput={(event) => editDraft("name", event.currentTarget.value)} onBlur={(event) => {
            const value = event.currentTarget.value;
            commit("name", value, () => {
              return { ...animation, metadata: { ...animation.metadata, name: value } };
            });
          }} /></label>
          <label>Description<input value={drafts.description?.value ?? animation.metadata.description} disabled={disabled} onInput={(event) => editDraft("description", event.currentTarget.value)} onBlur={(event) => {
            const value = event.currentTarget.value;
            commit("description", value, () => ({ ...animation, metadata: { ...animation.metadata, description: value } }));
          }} /></label>
        </div>
      </section>
      <AdditionalMetadataEditor value={additionalMetadata} disabled={disabled} onChange={(metadata) => onChange({ ...animation, metadata: { ...metadata, name: animation.metadata.name, description: animation.metadata.description } })} />
      <section className="playback-behavior" aria-labelledby="playback-behavior-heading">
        <h3 id="playback-behavior-heading">Settings</h3>
        <p>Time accepts d, s, t, or bare ticks. Your time strings are preserved; Minecraft converts them to ticks when loading.</p>
        <div className="fields settings-selectors">
          <div className="playback-settings-group">
            <div>
            <label>Playback mode<select value={mode} disabled={disabled} onChange={(event) => {
              const nextMode = event.currentTarget.value as NonNullable<typeof playback.mode>;
              const nextDrafts = { ...drafts };
              if (nextMode !== "loop") delete nextDrafts.loop_start;
              if (nextMode === "once" || nextMode === "hold") delete nextDrafts.loop_delay;
              setDrafts(nextDrafts);
              onValidityChange(Object.values(nextDrafts).every((draft) => !draft.error));
              onChange({ ...animation, clip: { ...animation.clip, playback: { ...playback, mode: nextMode, loop_start: nextMode === "loop" ? playback.loop_start ?? "0t" : "0t",
                loop_delay: nextMode === "once" || nextMode === "hold" ? "0t" : playback.loop_delay ?? "0t" } } });
            }}>
              <option value="once">Play once</option><option value="hold">Hold last frame</option><option value="loop">Loop</option><option value="server_sync">Server-synchronized loop</option>
            </select></label>
            <button type="button" disabled={disabled} onClick={() => {
              const nextDrafts = { ...drafts };
              delete nextDrafts.loop_start;
              delete nextDrafts.loop_delay;
              setDrafts(nextDrafts);
              onValidityChange(Object.values(nextDrafts).every((draft) => !draft.error));
              onChange({ ...animation, clip: { ...animation.clip, playback: structuredClone(animation.sourcePlayback) } });
            }}>Source setting</button>
            </div>
            <label>Loop start<input value={timeText("loop_start", playback.loop_start)} disabled={disabled || mode !== "loop"} onInput={(event) => editDraft("loop_start", event.currentTarget.value)} onBlur={(event) => updateTime("loop_start", event.currentTarget.value)} /></label>
            <label>Loop delay<input value={timeText("loop_delay", playback.loop_delay)} disabled={disabled || mode === "once" || mode === "hold"} onInput={(event) => editDraft("loop_delay", event.currentTarget.value)} onBlur={(event) => updateTime("loop_delay", event.currentTarget.value)} /></label>
          </div>
          <label>Cooldown<input value={timeText("cooldown", settings.cooldown)} disabled={disabled} onInput={(event) => editDraft("cooldown", event.currentTarget.value)} onBlur={(event) => updateTime("cooldown", event.currentTarget.value)} /></label>
          <label>Movement distance<input type="number" min="0" step="0.05" value={drafts.movement_distance?.value ?? player.stop_conditions.movement_distance} disabled={disabled} onInput={(event) => editDraft("movement_distance", event.currentTarget.value)} onBlur={(event) => {
            const text = event.currentTarget.value;
            commit("movement_distance", text, () => {
              const value = text.trim() ? Number(text) : text as unknown as number;
              return { ...animation, settings: { ...settings, player: { ...player, stop_conditions: { ...player.stop_conditions, movement_distance: value } } } };
            });
          }} /></label>
          <label>Rotation deadzone<input type="number" min="0" max="180" step="1" value={drafts.rotation_deadzone?.value ?? settings.rotation_deadzone ?? 50} disabled={disabled} onInput={(event) => editDraft("rotation_deadzone", event.currentTarget.value)} onBlur={(event) => {
            const text = event.currentTarget.value;
            commit("rotation_deadzone", text, () => {
              const value = Number(text);
              return { ...animation, settings: { ...settings, rotation_deadzone: value } };
            });
          }} /></label>
        </div>
        <div className="fields settings-toggles">
          <label className="checkbox"><input type="checkbox" checked={settings.standalone ?? true} disabled={disabled} onChange={(event) => onChange({ ...animation, settings: { ...settings, standalone: event.currentTarget.checked } })} />Standalone animation</label>
          <label className="checkbox"><input type="checkbox" checked={player.hidden} disabled={disabled} onChange={(event) => onChange({ ...animation, settings: { ...settings, player: { ...player, hidden: event.currentTarget.checked } } })} />Hide original player</label>
          {STOP_CONDITION_OPTIONS.map(([condition, label]) => <label className="checkbox" key={condition}><input type="checkbox" checked={player.stop_conditions[condition]} disabled={disabled} onChange={(event) => updatePlayerStopCondition(condition, event.currentTarget.checked)} />{label}</label>)}
        </div>
        {Object.entries(drafts).filter(([, draft]) => draft.error).map(([field, draft]) => <p className="error" role="alert" key={field}>{field}: {draft.error}</p>)}
      </section>
    </section>
  );
}
