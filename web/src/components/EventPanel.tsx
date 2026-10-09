import { useState } from "preact/hooks";
import { parseCallbacks } from "../format/emote";
import { requireAnimationEvents } from "../format/animation";
import type { EmoteCallback } from "../domain/emoteDefinition";
import type { TimelineEventIR } from "../domain/animationIR";
import type { ConversionAnimationEvents } from "../domain/conversionDocument";

interface EventPanelProps {
  events: ConversionAnimationEvents;
  callbacks?: EmoteCallback[];
  tick: number | null;
  duration: number;
  disabled: boolean;
  onLifecycleChange: (events: Pick<ConversionAnimationEvents, "start" | "loop" | "stop"> & { callbacks: EmoteCallback[] }) => void;
  onTimelineChange: (events: TimelineEventIR[]) => void;
  onValidityChange: (valid: boolean) => void;
}

export function EventPanel({ events, callbacks, tick, duration, disabled, onLifecycleChange, onTimelineChange, onValidityChange }: EventPanelProps) {
  const [error, setError] = useState("");
  const [scope, setScope] = useState<"lifecycle" | "timeline">("lifecycle");
  const initialValue = scope === "lifecycle"
    ? JSON.stringify({ callbacks: callbacks ?? [], start: events.start, loop: events.loop, stop: events.stop }, null, 2)
    : JSON.stringify(events.timeline, null, 2);

  function handleInput(value: string) {
    try {
      const parsed: unknown = JSON.parse(value);
      if (scope === "timeline") onTimelineChange(requireAnimationEvents({ timeline: parsed }, duration).timeline);
      else {
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("Lifecycle events must be an object.");
        const { callbacks: rawCallbacks, ...rawEvents } = parsed as Record<string, unknown>;
        if ("timeline" in rawEvents) throw new Error("Use the Timeline tab to edit timeline events.");
        const { start, loop, stop } = requireAnimationEvents(rawEvents, duration);
        onLifecycleChange({ callbacks: parseCallbacks(rawCallbacks), start, loop, stop });
      }
      setError("");
      onValidityChange(true);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Invalid JSON.");
      onValidityChange(false);
    }
  }

  return (
    <section className="event-editor" aria-labelledby="event-editor-heading">
      <div className="event-editor-heading">
        <div>
          <h3 id="event-editor-heading">Events</h3>
          <p>{scope === "lifecycle"
            ? "Edit callbacks and start, loop, and stop events as JSON. Changes are saved when the JSON is valid."
            : "Edit the full timeline as a JSON array. Each event has its own time in seconds."}</p>
        </div>
        <span className="event-scope">{tick === null ? "Create pose" : `${tick / 20}s`}</span>
      </div>
      <div className="event-editor-body">
        <div className="bundle-actions">
          <button type="button" disabled={disabled || !!error} onClick={() => setScope("lifecycle")} aria-pressed={scope === "lifecycle"}>Lifecycle</button>
          <button type="button" disabled={disabled || !!error} onClick={() => setScope("timeline")} aria-pressed={scope === "timeline"}>Timeline</button>
        </div>
        <textarea key={scope} className="event-json" defaultValue={initialValue} disabled={disabled} spellcheck={false}
          aria-label={`${scope === "lifecycle" ? "Lifecycle" : "Timeline"} events JSON`} aria-invalid={error ? true : undefined}
          onInput={(event) => handleInput(event.currentTarget.value)} />
        {error && <p className="event-json-error" role="alert">{error} Fix the JSON or press Ctrl+Z before leaving this event scope.</p>}
      </div>
    </section>
  );
}
