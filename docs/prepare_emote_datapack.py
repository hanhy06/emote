from __future__ import annotations

import argparse
import copy
import json
import math
import shutil
import re
import tempfile
import zipfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path


PACK_META_FILE_NAME = "emote-datapack.json"
CREATE_FUNCTION_PATTERNS = (
	"data/*/function/_/create.mcfunction",
	"data/*/functions/_/create.mcfunction",
)
ITEM_DISPLAY_PATTERN_TEMPLATE = r'\{id:"minecraft:item_display",item:\{(.*?)\},.*?Tags:\[[^\]]*?"{namespace}_(\d+)"[^\]]*?\]\}'
TRANSFORMATION_PATTERN = re.compile(r"transformation:\[(.*?)\]")
CLUSTER_TOLERANCE = 0.05
ANCHOR_OFFSET = 0.5


@dataclass(frozen=True)
class PlayerHeadPart:
	part_index: int
	start_index: int
	end_index: int
	item_display_text: str
	x: float
	y: float
	z: float
	scale_x: float
	scale_y: float
	scale_z: float
	anchor_x: float
	anchor_y: float
	anchor_z: float
	local_x_axis_x: float
	local_x_axis_y: float
	local_x_axis_z: float
	local_y_axis_x: float
	local_y_axis_y: float
	local_y_axis_z: float


@dataclass(frozen=True)
class BodyFrame:
	anchor_x: float
	anchor_y: float
	anchor_z: float
	local_x_axis_x: float
	local_x_axis_y: float
	local_x_axis_z: float
	local_y_axis_x: float
	local_y_axis_y: float
	local_y_axis_z: float

	def lateral_offset(self, player_head_part: PlayerHeadPart) -> float:
		offset_x = player_head_part.anchor_x - self.anchor_x
		offset_y = player_head_part.anchor_y - self.anchor_y
		offset_z = player_head_part.anchor_z - self.anchor_z
		return dot_vector(offset_x, offset_y, offset_z, self.local_x_axis_x, self.local_x_axis_y, self.local_x_axis_z)

	def vertical_offset(self, player_head_part: PlayerHeadPart) -> float:
		offset_x = player_head_part.anchor_x - self.anchor_x
		offset_y = player_head_part.anchor_y - self.anchor_y
		offset_z = player_head_part.anchor_z - self.anchor_z
		return dot_vector(offset_x, offset_y, offset_z, self.local_y_axis_x, self.local_y_axis_y, self.local_y_axis_z)


@dataclass(frozen=True)
class EmoteMetadata:
	name: str
	description: str
	command_name: str
	default_animation: str


def main() -> int:
	parser = build_argument_parser()
	args = parser.parse_args()
	input_paths = read_input_paths(args.input_paths)

	exit_code = 0
	for input_path in input_paths:
		try:
			output_path = process_input_path(input_path.resolve(), args.defaults, args.swap_left_right)
		except SystemExit as exception:
			message = str(exception)
			if message:
				print(f"[skip] {input_path}: {message}")
			exit_code = 1
			continue

		print(f"[ok] {input_path} -> {output_path}")

	return exit_code


def build_argument_parser() -> argparse.ArgumentParser:
	parser = argparse.ArgumentParser(
		description=(
			"Drop a BD Engine datapack zip or folder onto this script, and it will create "
			"a sibling emote.<name>.zip with emote:* skin markers and emote-datapack.json."
		)
	)
	parser.add_argument("--defaults", action="store_true", help="Use all metadata defaults without prompting")
	parser.add_argument("--swap-left-right", action="store_true", help="Swap inferred left/right skin markers")
	parser.add_argument("input_paths", nargs="*", type=Path, help="One or more datapack .zip files or folders")
	return parser


def read_input_paths(input_paths: list[Path]) -> list[Path]:
	if input_paths:
		return input_paths

	print("[input] No datapack path was provided.")
	try:
		value = input("  datapack path: ").strip().strip('"')
	except EOFError:
		raise SystemExit("No datapack path was provided.") from None
	if not value:
		raise SystemExit("No datapack path was provided.")

	return [Path(value)]


def process_input_path(input_path: Path, use_defaults: bool, swap_left_right: bool) -> Path:
	validate_input_path(input_path)
	output_path = create_output_path(input_path)

	with tempfile.TemporaryDirectory(prefix="emote-datapack-") as temp_dir_name:
		temp_dir = Path(temp_dir_name)
		work_dir = prepare_work_dir(input_path, temp_dir)

		pack_root = find_pack_root(work_dir)
		create_function_paths = find_create_function_paths(pack_root)
		if not create_function_paths:
			raise SystemExit("No compatible create.mcfunction file was found.")

		namespaces: list[str] = []
		updated_files = 0
		for create_function_path in create_function_paths:
			namespace = create_function_path.parents[2].name
			namespaces.append(namespace)

			original_text = create_function_path.read_text(encoding="utf-8")
			updated_text, replaced_count = update_create_function(original_text, namespace, swap_left_right)
			if replaced_count == 0:
				continue

			create_function_path.write_text(updated_text, encoding="utf-8", newline="\n")
			updated_files += 1

		if updated_files == 0:
			raise SystemExit("No player_head parts were found in create.mcfunction.")

		meta = prompt_emote_metadata(pack_root, input_path, namespaces, use_defaults)
		write_emote_datapack_meta(pack_root, meta)
		write_zip(pack_root, output_path)

	return output_path


def validate_input_path(input_path: Path) -> None:
	if not input_path.exists():
		raise SystemExit("The input path does not exist.")
	if input_path.is_file() and input_path.suffix.lower() != ".zip":
		raise SystemExit("The input file must be a .zip.")
	if not input_path.is_file() and not input_path.is_dir():
		raise SystemExit("The input path must be a .zip file or folder.")


def create_output_path(input_path: Path) -> Path:
	stem = input_path.stem if input_path.is_file() else input_path.name
	if stem.endswith(".emote"):
		stem = stem[:-6]
	return input_path.with_name(f"emote.{stem}.zip")


def prepare_work_dir(input_path: Path, temp_dir: Path) -> Path:
	work_dir = temp_dir / "work"
	work_dir.mkdir(parents=True, exist_ok=True)
	if input_path.is_dir():
		copy_dir = work_dir / input_path.name
		shutil.copytree(input_path, copy_dir)
		return copy_dir

	extract_dir = work_dir / "extract"
	extract_dir.mkdir(parents=True, exist_ok=True)
	with zipfile.ZipFile(input_path) as input_zip_file:
		input_zip_file.extractall(extract_dir)
	return extract_dir


def find_pack_root(extract_dir: Path) -> Path:
	pack_meta_paths = sorted(
		extract_dir.rglob("pack.mcmeta"),
		key=lambda path: (len(path.parts), str(path).lower()),
	)
	if not pack_meta_paths:
		raise SystemExit("pack.mcmeta was not found inside the input path.")

	return pack_meta_paths[0].parent


def find_create_function_paths(pack_root: Path) -> list[Path]:
	create_function_paths: list[Path] = []
	for pattern in CREATE_FUNCTION_PATTERNS:
		create_function_paths.extend(sorted(pack_root.glob(pattern)))
	return create_function_paths


def update_create_function(create_function_text: str, namespace: str, swap_left_right: bool) -> tuple[str, int]:
	pattern_text = ITEM_DISPLAY_PATTERN_TEMPLATE.replace("{namespace}", re.escape(namespace))
	pattern = re.compile(pattern_text, re.DOTALL)
	matches = [match for match in pattern.finditer(create_function_text) if 'id:"minecraft:player_head"' in match.group(1)]
	if not matches:
		return create_function_text, 0

	player_head_parts = [parse_player_head_part(match) for match in matches]
	part_names = infer_part_names(player_head_parts)
	if swap_left_right:
		part_names = swap_left_right_part_names(part_names)

	updated_chunks: list[str] = []
	last_index = 0
	for match, player_head_part in zip(matches, player_head_parts, strict=True):
		updated_chunks.append(create_function_text[last_index:match.start()])
		marker_name = part_names[player_head_part.part_index]
		updated_chunks.append(inject_profile_name(match.group(0), marker_name))
		last_index = match.end()

	updated_chunks.append(create_function_text[last_index:])
	return "".join(updated_chunks), len(player_head_parts)


def swap_left_right_part_names(part_names: dict[int, str]) -> dict[int, str]:
	swapped_names = {
		"emote:left_arm": "emote:right_arm",
		"emote:right_arm": "emote:left_arm",
		"emote:left_leg": "emote:right_leg",
		"emote:right_leg": "emote:left_leg",
	}
	return {
		part_index: swapped_names.get(part_name, part_name)
		for part_index, part_name in part_names.items()
	}


def parse_player_head_part(match: re.Match[str]) -> PlayerHeadPart:
	item_display_text = match.group(0)
	values = read_transformation_values(item_display_text)

	return PlayerHeadPart(
		part_index=int(match.group(2)),
		start_index=match.start(),
		end_index=match.end(),
		item_display_text=item_display_text,
		x=values[3],
		y=values[7],
		z=values[11],
		scale_x=read_axis_scale(values, 0, 4, 8),
		scale_y=read_axis_scale(values, 1, 5, 9),
		scale_z=read_axis_scale(values, 2, 6, 10),
		anchor_x=values[3] + values[1] * ANCHOR_OFFSET,
		anchor_y=values[7] + values[5] * ANCHOR_OFFSET,
		anchor_z=values[11] + values[9] * ANCHOR_OFFSET,
		local_x_axis_x=values[0],
		local_x_axis_y=values[4],
		local_x_axis_z=values[8],
		local_y_axis_x=values[1],
		local_y_axis_y=values[5],
		local_y_axis_z=values[9],
	)


def read_transformation_values(item_display_text: str) -> list[float]:
	transformation_match = TRANSFORMATION_PATTERN.search(item_display_text)
	if transformation_match is None:
		return [
			1.0, 0.0, 0.0, 0.0,
			0.0, 1.0, 0.0, 0.0,
			0.0, 0.0, 1.0, 0.0,
			0.0, 0.0, 0.0, 1.0,
		]

	values = [parse_matrix_number(value) for value in transformation_match.group(1).split(",")]
	if len(values) != 16:
		raise SystemExit("A player_head transformation did not contain 16 matrix values.")
	return values


def parse_matrix_number(value: str) -> float:
	normalized_value = value.strip()
	if normalized_value.endswith(("f", "d")):
		normalized_value = normalized_value[:-1]
	return float(normalized_value)


def read_axis_scale(values: list[float], first_index: int, second_index: int, third_index: int) -> float:
	first_value = values[first_index]
	second_value = values[second_index]
	third_value = values[third_index]
	return math.sqrt(first_value * first_value + second_value * second_value + third_value * third_value)


def infer_part_names(player_head_parts: list[PlayerHeadPart]) -> dict[int, str]:
	if not player_head_parts:
		raise SystemExit("No player_head parts were found.")

	assignments: dict[int, str] = {}
	head_part = select_head_part(player_head_parts)
	assignments[head_part.part_index] = "emote:head"

	remaining_parts = [part for part in player_head_parts if part.part_index != head_part.part_index]
	if not remaining_parts:
		return assignments

	body_parts = select_body_parts(remaining_parts)
	if not body_parts:
		return assignments

	for part in body_parts:
		assignments[part.part_index] = "emote:body"

	body_frame = create_body_frame(body_parts)
	side_parts = [part for part in remaining_parts if part.part_index not in assignments]
	assign_side_parts(
		[part for part in side_parts if body_frame.lateral_offset(part) >= 0.0],
		body_frame,
		"emote:left_arm",
		"emote:left_leg",
		assignments,
	)
	assign_side_parts(
		[part for part in side_parts if body_frame.lateral_offset(part) < 0.0],
		body_frame,
		"emote:right_arm",
		"emote:right_leg",
		assignments,
	)

	for part in player_head_parts:
		assignments.setdefault(part.part_index, "emote:body")

	return assignments


def select_head_part(player_head_parts: list[PlayerHeadPart]) -> PlayerHeadPart:
	return max(
		player_head_parts,
		key=lambda part: (
			part.anchor_y,
			part.y,
			-cube_deviation(part),
			part.scale_x * part.scale_y * part.scale_z,
		),
	)


def cube_deviation(player_head_part: PlayerHeadPart) -> float:
	return (
		abs(player_head_part.scale_x - player_head_part.scale_y)
		+ abs(player_head_part.scale_y - player_head_part.scale_z)
		+ abs(player_head_part.scale_z - player_head_part.scale_x)
	)


def select_body_parts(player_head_parts: list[PlayerHeadPart]) -> list[PlayerHeadPart]:
	body_scale_x = max(part.scale_x for part in player_head_parts)
	return [part for part in player_head_parts if body_scale_x - part.scale_x <= CLUSTER_TOLERANCE]


def create_body_frame(player_head_parts: list[PlayerHeadPart]) -> BodyFrame:
	local_x_axis = normalize_vector(
		average_value(player_head_parts, lambda part: part.local_x_axis_x),
		average_value(player_head_parts, lambda part: part.local_x_axis_y),
		average_value(player_head_parts, lambda part: part.local_x_axis_z),
		1.0,
		0.0,
		0.0,
	)
	local_y_axis = normalize_vector(
		average_value(player_head_parts, lambda part: part.local_y_axis_x),
		average_value(player_head_parts, lambda part: part.local_y_axis_y),
		average_value(player_head_parts, lambda part: part.local_y_axis_z),
		0.0,
		1.0,
		0.0,
	)

	return BodyFrame(
		anchor_x=average_value(player_head_parts, lambda part: part.anchor_x),
		anchor_y=average_value(player_head_parts, lambda part: part.anchor_y),
		anchor_z=average_value(player_head_parts, lambda part: part.anchor_z),
		local_x_axis_x=local_x_axis[0],
		local_x_axis_y=local_x_axis[1],
		local_x_axis_z=local_x_axis[2],
		local_y_axis_x=local_y_axis[0],
		local_y_axis_y=local_y_axis[1],
		local_y_axis_z=local_y_axis[2],
	)


def average_value(player_head_parts: list[PlayerHeadPart], value_getter: Callable[[PlayerHeadPart], float]) -> float:
	return sum(value_getter(part) for part in player_head_parts) / len(player_head_parts)


def normalize_vector(
	x: float,
	y: float,
	z: float,
	fallback_x: float,
	fallback_y: float,
	fallback_z: float,
) -> tuple[float, float, float]:
	length = math.sqrt(x * x + y * y + z * z)
	if length <= 0.0:
		return fallback_x, fallback_y, fallback_z

	return x / length, y / length, z / length


def dot_vector(
	first_x: float,
	first_y: float,
	first_z: float,
	second_x: float,
	second_y: float,
	second_z: float,
) -> float:
	return first_x * second_x + first_y * second_y + first_z * second_z


def assign_side_parts(
	side_parts: list[PlayerHeadPart],
	body_frame: BodyFrame,
	arm_marker_name: str,
	leg_marker_name: str,
	assignments: dict[int, str],
) -> None:
	if not side_parts:
		return

	vertical_clusters = cluster_by_vertical_offset(side_parts, body_frame)
	if len(vertical_clusters) >= 2:
		split_index = find_limb_split_index(vertical_clusters, body_frame)
		for cluster in vertical_clusters[:split_index]:
			for part in cluster:
				assignments[part.part_index] = arm_marker_name
		for cluster in vertical_clusters[split_index:]:
			for part in cluster:
				assignments[part.part_index] = leg_marker_name
		return

	assign_cluster_by_height(side_parts, body_frame, arm_marker_name, leg_marker_name, assignments)


def cluster_by_vertical_offset(player_head_parts: list[PlayerHeadPart], body_frame: BodyFrame) -> list[list[PlayerHeadPart]]:
	clusters: list[list[PlayerHeadPart]] = []
	for player_head_part in sorted(player_head_parts, key=lambda part: (-body_frame.vertical_offset(part), part.part_index)):
		if not clusters:
			clusters.append([player_head_part])
			continue

		cluster_anchor = average_vertical_offset(clusters[-1], body_frame)
		if abs(body_frame.vertical_offset(player_head_part) - cluster_anchor) <= CLUSTER_TOLERANCE:
			clusters[-1].append(player_head_part)
			continue

		clusters.append([player_head_part])

	return clusters


def find_limb_split_index(vertical_clusters: list[list[PlayerHeadPart]], body_frame: BodyFrame) -> int:
	largest_gap = -1.0
	split_index = 1
	for index in range(1, len(vertical_clusters)):
		previous_offset = average_vertical_offset(vertical_clusters[index - 1], body_frame)
		current_offset = average_vertical_offset(vertical_clusters[index], body_frame)
		gap = previous_offset - current_offset
		if gap > largest_gap:
			largest_gap = gap
			split_index = index

	return split_index


def average_vertical_offset(player_head_parts: list[PlayerHeadPart], body_frame: BodyFrame) -> float:
	return sum(body_frame.vertical_offset(part) for part in player_head_parts) / len(player_head_parts)


def assign_cluster_by_height(
	player_head_parts: list[PlayerHeadPart],
	body_frame: BodyFrame,
	arm_marker_name: str,
	leg_marker_name: str,
	assignments: dict[int, str],
) -> None:
	sorted_parts = sorted(player_head_parts, key=lambda part: (-body_frame.vertical_offset(part), part.part_index))
	if len(sorted_parts) == 1:
		assignments[sorted_parts[0].part_index] = arm_marker_name
		return

	arm_count = len(sorted_parts) // 2
	if arm_count == 0:
		arm_count = 1

	for part in sorted_parts[:arm_count]:
		assignments[part.part_index] = arm_marker_name
	for part in sorted_parts[arm_count:]:
		assignments[part.part_index] = leg_marker_name


def inject_profile_name(item_display_text: str, marker_name: str) -> str:
	profile_key = '"minecraft:profile":{'
	profile_index = item_display_text.find(profile_key)
	if profile_index >= 0:
		start_index = profile_index + len(profile_key) - 1
		end_index = find_matching_brace(item_display_text, start_index)
		profile_body = item_display_text[start_index + 1:end_index]
		fields = split_top_level_fields(profile_body)
		filtered_fields = [field for field in fields if not field.lstrip().startswith("name:")]
		new_profile_body = ",".join([f'name:"{marker_name}"', *filtered_fields])
		return item_display_text[:start_index + 1] + new_profile_body + item_display_text[end_index:]

	components_key = "components:{"
	components_index = item_display_text.find(components_key)
	if components_index >= 0:
		start_index = components_index + len(components_key) - 1
		end_index = find_matching_brace(item_display_text, start_index)
		components_body = item_display_text[start_index + 1:end_index]
		new_entry = f'"minecraft:profile":{{name:"{marker_name}"}}'
		new_components_body = new_entry if not components_body.strip() else new_entry + "," + components_body
		return item_display_text[:start_index + 1] + new_components_body + item_display_text[end_index:]

	item_key = "item:{"
	item_index = item_display_text.find(item_key)
	if item_index < 0:
		raise SystemExit("An item_display did not contain item:{}.")

	start_index = item_index + len(item_key) - 1
	end_index = find_matching_brace(item_display_text, start_index)
	item_body = item_display_text[start_index + 1:end_index]
	new_item_body = item_body + f',components:{{"minecraft:profile":{{name:"{marker_name}"}}}}'
	return item_display_text[:start_index + 1] + new_item_body + item_display_text[end_index:]


def split_top_level_fields(value: str) -> list[str]:
	fields: list[str] = []
	current: list[str] = []
	brace_depth = 0
	bracket_depth = 0
	in_string = False
	escaped = False

	for character in value:
		if in_string:
			current.append(character)
			if escaped:
				escaped = False
			elif character == "\\":
				escaped = True
			elif character == '"':
				in_string = False
			continue

		if character == '"':
			in_string = True
			current.append(character)
			continue

		if character == "{":
			brace_depth += 1
			current.append(character)
			continue

		if character == "}":
			brace_depth -= 1
			current.append(character)
			continue

		if character == "[":
			bracket_depth += 1
			current.append(character)
			continue

		if character == "]":
			bracket_depth -= 1
			current.append(character)
			continue

		if character == "," and brace_depth == 0 and bracket_depth == 0:
			field = "".join(current).strip()
			if field:
				fields.append(field)
			current = []
			continue

		current.append(character)

	field = "".join(current).strip()
	if field:
		fields.append(field)
	return fields


def find_matching_brace(value: str, open_index: int) -> int:
	depth = 0
	in_string = False
	escaped = False

	for index in range(open_index, len(value)):
		character = value[index]
		if in_string:
			if escaped:
				escaped = False
			elif character == "\\":
				escaped = True
			elif character == '"':
				in_string = False
			continue

		if character == '"':
			in_string = True
			continue

		if character == "{":
			depth += 1
			continue

		if character == "}":
			depth -= 1
			if depth == 0:
				return index

	raise SystemExit("A closing brace could not be found.")


def prompt_emote_metadata(pack_root: Path, input_path: Path, namespaces: list[str], use_defaults: bool) -> EmoteMetadata:
	existing_meta = load_existing_meta(pack_root)
	default_name = str(existing_meta.get("name") or prettify_name(get_input_stem(input_path)))
	default_description = str(existing_meta.get("description") or f"{default_name} emote.")
	default_command_name = str(
		existing_meta.get("command_name")
		or sanitize_command_name(namespaces[0] if len(namespaces) == 1 else get_input_stem(input_path))
	)
	default_animation = str(existing_meta.get("default_animation") or "default")

	print()
	print(f"[meta] {input_path.name}")
	if use_defaults:
		print("  using defaults")
		print()
		return EmoteMetadata(
			name=default_name,
			description=default_description,
			command_name=default_command_name,
			default_animation=default_animation,
		)

	name = prompt_value("name", default_name)
	description = prompt_value("description", default_description)
	command_name = sanitize_command_name(prompt_value("command_name", default_command_name))
	default_animation = prompt_value("default_animation", default_animation)
	print()

	return EmoteMetadata(
		name=name,
		description=description,
		command_name=command_name,
		default_animation=default_animation,
	)


def load_existing_meta(pack_root: Path) -> dict[str, object]:
	meta_path = pack_root / PACK_META_FILE_NAME
	if not meta_path.exists():
		return {}
	try:
		loaded_meta = json.loads(meta_path.read_text(encoding="utf-8"))
	except json.JSONDecodeError:
		return {}
	if not isinstance(loaded_meta, dict):
		return {}
	return copy.deepcopy(loaded_meta)


def prompt_value(label: str, default_value: str) -> str:
	prompt = f"  {label} [{default_value}]: "
	value = input(prompt).strip()
	return value or default_value


def get_input_stem(input_path: Path) -> str:
	stem = input_path.stem if input_path.is_file() else input_path.name
	if stem.endswith(".emote"):
		stem = stem[:-6]
	return stem


def write_emote_datapack_meta(pack_root: Path, meta: EmoteMetadata) -> None:
	meta_path = pack_root / PACK_META_FILE_NAME
	meta = {
		"name": meta.name,
		"description": meta.description,
		"command_name": meta.command_name,
		"default_animation": meta.default_animation,
	}
	meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def prettify_name(value: str) -> str:
	prettified_value = value.replace("_", " ").replace("-", " ").strip()
	return prettified_value or value


def sanitize_command_name(value: str) -> str:
	command_name = re.sub(r"[^a-z0-9_-]+", "_", value.lower()).strip("_")
	return command_name or "emote"


def write_zip(pack_root: Path, output_path: Path) -> None:
	if output_path.exists():
		output_path.unlink()

	with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_DEFLATED) as output_zip_file:
		for path in sorted(pack_root.rglob("*")):
			if path.is_dir():
				continue
			output_zip_file.write(path, arcname=path.relative_to(pack_root))


if __name__ == "__main__":
	raise SystemExit(main())
