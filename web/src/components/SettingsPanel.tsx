import type { ComponentChildren } from "preact";
import { useState } from "preact/hooks";
import type { ClipIR, TimeValueIR } from "../domain/animationIR";
import type { ConversionAnimation, ConversionEntry } from "../domain/conversionDocument";
import { AdditionalMetadataEditor } from "./AdditionalMetadataEditor";
import { SkinOptions } from "./SkinOptions";
import { createDefaultPlayerBehavior } from "../domain/emoteDefinition";
import { isResourceLocation } from "../format/resourceLocation";
import { parseMinecraftTime } from "../format/time";

const STOP_CONDITION_OPTIONS = [
  ["jump", "Stop on jump"], ["submerge", "Stop when submerged"], ["ride", "Stop on mount"],
  ["damage", "Stop when damaged"], ["attack", "Stop on attack"], ["game_mode_change", "Stop on game mode change"],
] as const;

interface SettingsPanelProps {
  entry: ConversionEntry;
  onChange: (entry: ConversionEntry) => void;
  category: string;
  onCategoryChange: (category: string) => void;
  frameControls: ComponentChildren;
  eventsValid: boolean;
  children: ComponentChildren;
  disabled: boolean;
  onValidityChange: (valid: boolean) => void;
}

export function SettingsPanel({ entry, onChange, disabled, onValidityChange, frameControls, eventsValid, children, category, onCategoryChange }: SettingsPanelProps) {
  const [drafts, setDrafts] = useState<Record<string, { value: string; error: string }>>({});
  const animation = entry.type === "animation" ? entry : undefined;
  const settings = entry.settings ?? {};
  const playback = animation?.clip.playback ?? {};
  const player = settings.player ?? createDefaultPlayerBehavior();
  const cooldown = settings.cooldown;
  const mode = playback.mode ?? "once";
  const separator = entry.id.indexOf(":");
  const namespace = entry.id.slice(0, separator);
  const path = entry.id.slice(separator + 1);
  const { name, description, ...additionalMetadata } = entry.metadata;

  function updateSettings(patch: NonNullable<ConversionAnimation["settings"]>) {
    const next = { ...entry };
    next.settings = { ...settings, ...patch };
    onChange(next);
  }

  function updatePlayback(playback: ClipIR["playback"], playbackMode?: ConversionAnimation["playbackMode"]) {
    if (!animation) return;
    onChange({ ...animation, playbackMode: playbackMode ?? animation.playbackMode, clip: { ...animation.clip, playback } });
  }

  function commit(field: string, value: string, edit: () => void) {
    const next = { ...drafts };
    try {
      edit();
      delete next[field];
    } catch (reason) {
      next[field] = { value, error: reason instanceof Error ? reason.message : String(reason) };
    }
    setDrafts(next);
    onValidityChange(Object.values(next).every((draft) => !draft.error));
  }

  function editDraft(field: string, value: string) {
    setDrafts({ ...drafts, [field]: { value, error: drafts[field]?.error ?? "" } });
  }

  function updateTime(field: "cooldown" | "loop_start" | "loop_delay", text: string) {
    commit(field, text, () => {
      if (field === "cooldown") {
        parseMinecraftTime(text);
        updateSettings({ cooldown: text });
      } else {
        let time: TimeValueIR = text;
        if (field === "loop_delay" && text.trim().startsWith("{")) time = JSON.parse(text) as TimeValueIR;
        else parseMinecraftTime(text);
        updatePlayback({ ...playback, [field]: time });
      }
    });
  }

  const timeText = (field: string, value: TimeValueIR | undefined) => drafts[field]?.value ?? (typeof value === "object" ? JSON.stringify(value) : value ?? "0s");

  const errors = Object.entries(drafts).filter(([, draft]) => draft.error);
  const optionsErrors = errors.filter(([key]) => key.startsWith("metadata:"));
  const settingsErrors = errors.filter(([key]) => !key.startsWith("metadata:"));
  return (
    <section className="settings-panel" aria-label="Emote settings">
      <div className="settings-toolbar">
        <div className="settings-tabs" role="tablist" aria-label="Settings categories" onKeyDown={event => {
          if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
          const tabs = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')];
          const current = tabs.findIndex(tab => tab === document.activeElement);
          const next = event.key === "Home" ? 0 : event.key === "End" ? tabs.length - 1
            : (current + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length;
          event.preventDefault();
          tabs[next].click();
          tabs[next].focus();
        }}>
          {[
            { id: "settings", label: "Settings", invalid: settingsErrors.length > 0 },
            { id: "options", label: "Options", invalid: optionsErrors.length > 0 },
            { id: "events", label: animation ? "Events" : "Callbacks", invalid: !eventsValid },
          ].map(tab => <button key={tab.id} id={`settings-tab-${tab.id}`} type="button" role="tab"
            aria-selected={category === tab.id} aria-controls={`settings-panel-${tab.id}`}
            tabIndex={category === tab.id ? 0 : -1} className={tab.invalid ? "error" : ""}
            onClick={() => onCategoryChange(tab.id)}>{tab.label}{tab.invalid && <span className="tab-error" aria-label="Contains errors">!</span>}</button>)}
        </div>
        {frameControls}
      </div>
      {(errors.length > 0 || !eventsValid) && <p className="settings-errors error" role="status">
        {[
          ...(settingsErrors.length ? ["Check settings."] : []),
          ...(optionsErrors.length ? ["Check options."] : []),
          ...(!eventsValid ? [animation ? "Fix the event JSON before changing frames or exporting." : "Fix the callback JSON before exporting."] : []),
        ].join(" ")}
      </p>}
      <div id="settings-category">
        <section id="settings-panel-settings" role="tabpanel" aria-labelledby="settings-tab-settings" hidden={category !== "settings"}>
          <div className="fields">
            <label>Namespace<input value={drafts.namespace?.value ?? namespace} disabled={disabled} onInput={(event) => editDraft("namespace", event.currentTarget.value)} onBlur={(event) => {
              const value = event.currentTarget.value;
              commit("namespace", value, () => {
                const id = `${value}:${path}`;
                if (!isResourceLocation(id)) throw new Error("must be a valid resource namespace");
                onChange({ ...entry, id });
              });
            }} /></label>
            <label>Display name<input value={drafts.name?.value ?? name} disabled={disabled} onInput={(event) => editDraft("name", event.currentTarget.value)} onBlur={(event) => {
              const value = event.currentTarget.value;
              commit("name", value, () => {
                if (!value.trim()) throw new Error("must not be blank");
                onChange({ ...entry, metadata: { ...entry.metadata, name: value } });
              });
            }} /></label>
            <label>Description<input value={drafts.description?.value ?? description} disabled={disabled} onInput={(event) => editDraft("description", event.currentTarget.value)} onBlur={(event) => {
              const value = event.currentTarget.value;
              commit("description", value, () => onChange({ ...entry, metadata: { ...entry.metadata, description: value } }));
            }} /></label>
            {animation && <>
              <label>Playback mode<select value={animation.playbackMode} disabled={disabled} onChange={(event) => {
                const value = event.currentTarget.value;
                const nextDrafts = { ...drafts };
                if (value !== "loop") delete nextDrafts.loop_start;
                if (value === "source" || value === "once" || value === "hold") delete nextDrafts.loop_delay;
                setDrafts(nextDrafts);
                onValidityChange(Object.values(nextDrafts).every((draft) => !draft.error));
                if (value === "source") {
                  updatePlayback(structuredClone(animation.sourcePlayback), "source");
                  return;
                }
                const nextMode = value as NonNullable<typeof playback.mode>;
                updatePlayback({ ...playback, mode: nextMode, loop_start: nextMode === "loop" ? playback.loop_start ?? "0t" : "0t",
                  loop_delay: nextMode === "once" || nextMode === "hold" ? "0t" : playback.loop_delay ?? "0t" }, nextMode);
              }}>
                <option value="source">Source setting</option>
                <option value="once">Play once</option><option value="hold">Hold last frame</option><option value="loop">Loop</option><option value="server_sync">Server-synchronized loop</option>
              </select></label>
              <label>Loop start<input value={timeText("loop_start", playback.loop_start)} disabled={disabled || mode !== "loop"} onInput={(event) => editDraft("loop_start", event.currentTarget.value)} onBlur={(event) => updateTime("loop_start", event.currentTarget.value)} /></label>
              <label>Loop delay<input value={timeText("loop_delay", playback.loop_delay)} disabled={disabled || mode === "once" || mode === "hold"} onInput={(event) => editDraft("loop_delay", event.currentTarget.value)} onBlur={(event) => updateTime("loop_delay", event.currentTarget.value)} /></label>
            </>}
            <label>Cooldown<input value={timeText("cooldown", cooldown)} disabled={disabled} onInput={(event) => editDraft("cooldown", event.currentTarget.value)} onBlur={(event) => updateTime("cooldown", event.currentTarget.value)} /></label>
            <label>Movement distance<input type="number" min="0" step="any" value={drafts.movement_distance?.value ?? player.stop_conditions.movement_distance} disabled={disabled} onInput={(event) => editDraft("movement_distance", event.currentTarget.value)} onBlur={(event) => {
              const text = event.currentTarget.value;
              commit("movement_distance", text, () => {
                const value = Number(text);
                if (!text.trim() || !Number.isFinite(value) || value < 0) throw new Error("must be a non-negative distance");
                updateSettings({ player: { ...player, stop_conditions: { ...player.stop_conditions, movement_distance: value } } });
              });
            }} /></label>
            {animation && <label>Rotation deadzone<input type="number" min="0" max="180" step="1" value={drafts.rotation_deadzone?.value ?? animation.settings?.rotation_deadzone ?? 50} disabled={disabled} onInput={(event) => editDraft("rotation_deadzone", event.currentTarget.value)} onBlur={(event) => {
              const text = event.currentTarget.value;
              commit("rotation_deadzone", text, () => updateSettings({ rotation_deadzone: Number(text) }));
            }} /></label>}
          </div>
          <p className="field-hint">Time accepts d, s, t, or bare ticks.</p>
          {settingsErrors.map(([field, draft]) => <p className="error" role="alert" key={field}>{field}: {draft.error}</p>)}
        </section>
        <section id="settings-panel-options" role="tabpanel" aria-labelledby="settings-tab-options" hidden={category !== "options"}>
          <div className="fields settings-toggles">
            {animation && <label className="checkbox"><input type="checkbox" checked={animation.settings?.standalone ?? true} disabled={disabled} onChange={(event) => updateSettings({ standalone: event.currentTarget.checked })} />Standalone animation</label>}
            <label className="checkbox"><input type="checkbox" checked={player.hidden} disabled={disabled} onChange={(event) => updateSettings({ player: { ...player, hidden: event.currentTarget.checked } })} />Hide original player</label>
            {STOP_CONDITION_OPTIONS.map(([condition, label]) => <label className="checkbox" key={condition}><input type="checkbox" checked={player.stop_conditions[condition]} disabled={disabled} onChange={(event) => updateSettings({ player: { ...player, stop_conditions: { ...player.stop_conditions, [condition]: event.currentTarget.checked } } })} />{label}</label>)}
          </div>
          <AdditionalMetadataEditor value={additionalMetadata} disabled={disabled} onFieldError={(key, error) => {
            const next = { ...drafts };
            const field = `metadata:${key}`;
            if (error) next[field] = { value: "", error };
            else delete next[field];
            setDrafts(next);
            onValidityChange(Object.values(next).every(draft => !draft.error));
          }} onChange={(additionalMetadata) => onChange({ ...entry, metadata: { ...additionalMetadata, name, description } })} />
          {animation && <SkinOptions skin={animation.skin} disabled={disabled} onChange={(skin) => onChange({ ...animation, skin })} />}
        </section>
        <section id="settings-panel-events" role="tabpanel" aria-labelledby="settings-tab-events" hidden={category !== "events"}>{children}</section>
      </div>
    </section>
  );
}
