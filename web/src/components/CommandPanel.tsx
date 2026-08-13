import type { ImportedAnimation, ImportedTimelineEvent } from "../import/types";
import { frameCommands } from "./frameCommands";

interface CommandPanelProps {
  animation: ImportedAnimation;
  tick: number | null;
  disabled: boolean;
  onAdd: () => void;
  onChange: (eventIndex: number, commandIndex: number, command: string) => void;
  onRemove: (eventIndex: number, commandIndex: number) => void;
}

export function CommandPanel({ animation, tick, disabled, onAdd, onChange, onRemove }: CommandPanelProps) {
  const commands = tick === null ? [] : frameCommands(animation, tick);

  return (
    <section className="command-editor" aria-labelledby="command-editor-heading">
      <div className="command-editor-heading">
        <div>
          <h3 id="command-editor-heading">Frame commands</h3>
          <p>Commands run with their imported source and origin when playback reaches this frame.</p>
        </div>
        <span className="command-frame">{tick === null ? "Create pose" : `Tick ${tick}`}</span>
      </div>

      <div className="command-editor-body">
        {tick === null ? (
          <p className="command-empty">Select a playback frame above to review or add commands.</p>
        ) : (
          <>
          {commands.length === 0 ? (
            <p className="command-empty">No commands run at this frame.</p>
          ) : (
            <ul className="command-list">
              {commands.map(({ eventIndex, commandIndex, command, event }) => {
                const invalid = !command.trim() || command.startsWith("/");
                return (
                  <li key={`${eventIndex}:${commandIndex}`}>
                    <label>
                      <span className="command-label-heading">
                        <span>Command</span>
                        <small>{describeEvent(event)}</small>
                      </span>
                      <input
                        value={command}
                        disabled={disabled}
                        aria-invalid={invalid}
                        placeholder="say Hello"
                        onInput={(inputEvent) => onChange(eventIndex, commandIndex, inputEvent.currentTarget.value)}
                      />
                      {invalid && <small className="command-validation">Enter a command without a leading slash.</small>}
                    </label>
                    <button type="button" disabled={disabled} aria-label={`Remove command ${commandIndex + 1}`} onClick={() => onRemove(eventIndex, commandIndex)}>Remove</button>
                  </li>
                );
              })}
            </ul>
          )}
          <button className="command-add" type="button" disabled={disabled} onClick={onAdd}>Add command</button>
          </>
        )}
      </div>
    </section>
  );
}

function describeEvent(event: ImportedTimelineEvent): string {
  const source = event.source.type === "node" ? `node ${event.source.node}` : event.source.type;
  const origin = event.origin.type === "node" ? `node ${event.origin.node}` : "animation root";
  return `Runs as ${source} · Origin: ${origin}`;
}
