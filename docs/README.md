# Emote Datapack Prep

`prepare_emote_datapack.py` takes a BD Engine datapack `.zip` and creates a new `.emote.zip` next to it.

## What It Does

- Adds `name:"emote:*"` markers to `minecraft:player_head` parts in `_create.mcfunction`
- Creates `emote-datapack.json` if it does not already exist
- Writes the result as `original-name.emote.zip`

## How To Use

### Drag And Drop

Drop a datapack `.zip` file onto `prepare_emote_datapack.py`.

Example:

- `project.zip` -> `project.emote.zip`

### Command Line

```powershell
python docs\prepare_emote_datapack.py path\to\project.zip
```

You can pass more than one zip file:

```powershell
python docs\prepare_emote_datapack.py first.zip second.zip
```

## Supported Layout

The zip must contain:

- `pack.mcmeta`
- `data/<namespace>/function/_/create.mcfunction`

or:

- `data/<namespace>/functions/_/create.mcfunction`

## Notes

- This is designed for the current BD Engine humanoid export shape used in this project.
- Output files overwrite an existing file with the same `.emote.zip` name.
- Existing `emote-datapack.json` files are kept as-is.
