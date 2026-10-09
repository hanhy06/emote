import { useState } from "preact/hooks";
import type { Animation, ScalarIR } from "../domain/animationIR";
import { AdditionalMetadataEditor } from "./AdditionalMetadataEditor";
import { MINECRAFT_VERSION_PROFILES } from "../format/minecraftVersionProfiles";
import { createDefaultPlayerBehavior } from "../domain/emoteDefinition";
import { sanitizeNamespace } from "../format/resourceLocation";
import { parseAnimationSeconds, parseMinecraftTime } from "../format/time";

const STOP_CONDITION_OPTIONS = [
  ["jump", "Stop on jump"], ["submerge", "Stop when submerged"], ["ride", "Stop on mount"],
  ["damage", "Stop when damaged"], ["attack", "Stop on attack"], ["game_mode_change", "Stop on game mode change"],
] as const;

interface SettingsPanelProps {
  animation: Animation;
  minecraftVersion: string;
  disabled: boolean;
  onChange: (animation: Animation) => void;
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

  function commit(field: string, value: string, edit: () => Animation) {
    const next = { ...drafts };
    try {
      onChange(edit());
      delete next[field];
    } catch (reason) {
      next[field] = { value, error: reason instanceof Error ? reason.message : String(reason) };
    }
    setDrafts(next);
    onValidityChange(Object.keys(next).length === 0);
  }

  function updateTime(field: "cooldown" | "display_interpolation_ticks" | "loop_start" | "loop_delay", text: string) {
    commit(field, text, () => {
      let value: ScalarIR;
      if (field === "loop_delay" && text.trim().startsWith("{")) {
        const expression = JSON.parse(text) as { molang?: unknown };
        if (typeof expression.molang !== "string" || !expression.molang.trim()) throw new Error("Loop delay requires a time or a Molang object.");
        value = { molang: expression.molang };
      } else value = field === "display_interpolation_ticks" ? parseMinecraftTime(text) : parseAnimationSeconds(text);
      if (field === "loop_start" && (value as number) >= animation.clip.duration) throw new Error("Loop start must be before the animation end.");
      return field === "loop_start" || field === "loop_delay"
        ? { ...animation, clip: { ...animation.clip, playback: { ...playback, [field]: value } } }
        : { ...animation, settings: { ...settings, [field]: value } };
    });
  }

  function updatePlayerStopCondition(key: keyof typeof player.stop_conditions, value: number | boolean) {
    onChange({ ...animation, settings: { ...settings, player: { ...player, stop_conditions: { ...player.stop_conditions, [key]: value } } } });
  }

  const timeText = (field: string, value: ScalarIR | undefined, unit = "s") => drafts[field]?.value ?? (typeof value === "object" ? JSON.stringify(value) : `${value ?? 0}${unit}`);

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
          <label>Namespace<input value={drafts.namespace?.value ?? namespace} disabled={disabled} onChange={(event) => {
            const value = event.currentTarget.value;
            commit("namespace", value, () => {
              if (!value.trim()) throw new Error("Namespace must not be empty.");
              return { ...animation, id: `${sanitizeNamespace(value)}:${animation.id.slice(animation.id.indexOf(":") + 1)}` };
            });
          }} /></label>
          <label>Display name<input value={drafts.name?.value ?? animation.metadata.name} disabled={disabled} onChange={(event) => {
            const value = event.currentTarget.value;
            commit("name", value, () => {
              if (!value.trim()) throw new Error("Display name must not be empty.");
              return { ...animation, metadata: { ...animation.metadata, name: value } };
            });
          }} /></label>
          <label>Description<input value={animation.metadata.description} disabled={disabled} onChange={(event) => onChange({ ...animation, metadata: { ...animation.metadata, description: event.currentTarget.value } })} /></label>
        </div>
      </section>
      <AdditionalMetadataEditor value={additionalMetadata} disabled={disabled} onChange={(metadata) => onChange({ ...animation, metadata: { ...metadata, name: animation.metadata.name, description: animation.metadata.description } })} />
      <section className="playback-behavior" aria-labelledby="playback-behavior-heading">
        <h3 id="playback-behavior-heading">Settings</h3>
        <p>Time accepts d, s, t, or bare ticks. Animation time is stored in seconds; display interpolation uses ticks.</p>
        <div className="fields settings-selectors">
          <div className="playback-settings-group">
            <label>Playback mode<select value={mode} disabled={disabled} onChange={(event) => {
              const nextMode = event.currentTarget.value as NonNullable<typeof playback.mode>;
              const nextDrafts = { ...drafts };
              if (nextMode !== "loop") delete nextDrafts.loop_start;
              if (nextMode === "once" || nextMode === "hold") delete nextDrafts.loop_delay;
              setDrafts(nextDrafts);
              onValidityChange(Object.keys(nextDrafts).length === 0);
              onChange({ ...animation, clip: { ...animation.clip, playback: { ...playback, mode: nextMode, loop_start: nextMode === "loop" ? playback.loop_start ?? 0 : 0,
                loop_delay: nextMode === "once" || nextMode === "hold" ? 0 : playback.loop_delay ?? 0 } } });
            }}>
              <option value="once">Play once</option><option value="hold">Hold last frame</option><option value="loop">Loop</option><option value="server_sync">Server-synchronized loop</option>
            </select></label>
            <label>Loop start<input value={timeText("loop_start", playback.loop_start)} disabled={disabled || mode !== "loop"} onChange={(event) => updateTime("loop_start", event.currentTarget.value)} /></label>
            <label>Loop delay<input value={timeText("loop_delay", playback.loop_delay)} disabled={disabled || mode === "once" || mode === "hold"} onChange={(event) => updateTime("loop_delay", event.currentTarget.value)} /></label>
          </div>
          <label>Cooldown<input value={timeText("cooldown", settings.cooldown)} disabled={disabled} onChange={(event) => updateTime("cooldown", event.currentTarget.value)} /></label>
          <label>Movement distance<input type="number" min="0" step="0.05" value={player.stop_conditions.movement_distance} disabled={disabled} onChange={(event) => updatePlayerStopCondition("movement_distance", Number(event.currentTarget.value))} /></label>
          <label>Rotation deadzone<input type="number" min="0" max="180" step="1" value={settings.rotation_deadzone ?? 50} disabled={disabled} onChange={(event) => onChange({ ...animation, settings: { ...settings, rotation_deadzone: Number(event.currentTarget.value) } })} /></label>
          <label>Display interpolation<input value={timeText("display_interpolation_ticks", settings.display_interpolation_ticks ?? 1, "t")} disabled={disabled} onChange={(event) => updateTime("display_interpolation_ticks", event.currentTarget.value)} /></label>
        </div>
        <div className="fields settings-toggles">
          <label className="checkbox"><input type="checkbox" checked={settings.standalone ?? true} disabled={disabled} onChange={(event) => onChange({ ...animation, settings: { ...settings, standalone: event.currentTarget.checked } })} />Standalone animation</label>
          <label className="checkbox"><input type="checkbox" checked={player.hidden} disabled={disabled} onChange={(event) => onChange({ ...animation, settings: { ...settings, player: { ...player, hidden: event.currentTarget.checked } } })} />Hide original player</label>
          {STOP_CONDITION_OPTIONS.map(([condition, label]) => <label className="checkbox" key={condition}><input type="checkbox" checked={player.stop_conditions[condition]} disabled={disabled} onChange={(event) => updatePlayerStopCondition(condition, event.currentTarget.checked)} />{label}</label>)}
        </div>
        {Object.entries(drafts).map(([field, draft]) => <p className="error" role="alert" key={field}>{field}: {draft.error}</p>)}
      </section>
    </section>
  );
}
