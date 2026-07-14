import argparse
import tempfile
import unittest
from pathlib import Path

from docs.emote import EmoteTarget, PlayerHeadPart, create_emote_metadata, infer_part_names, split_animation_namespaces


class MultiAnimationDatapackTest(unittest.TestCase):
    def test_splits_animation_directories_into_independent_namespaces(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            pack_root = Path(temp_dir)
            namespace_path = pack_root / "data" / "inv"
            function_path = namespace_path / "function"
            for animation_name in ("default", "inventory_closing", "inventory_opening"):
                animation_path = function_path / "a" / animation_name
                animation_path.mkdir(parents=True)
                (animation_path / "play_anim_loop.mcfunction").write_text(
                    f"function inv:k/{animation_name}/start\n",
                    encoding="utf-8",
                )
            create_path = function_path / "_" / "create.mcfunction"
            create_path.parent.mkdir(parents=True)
            create_path.write_text('tag @s add inv_root\n', encoding="utf-8")

            targets = split_animation_namespaces(
                pack_root,
                ["inv"],
                "a/default/play_anim_loop",
            )

            self.assertEqual(
                [
                    EmoteTarget("inv_1", "a/default/play_anim_loop"),
                    EmoteTarget("inv_2", "a/inventory_closing/play_anim_loop"),
                    EmoteTarget("inv_3", "a/inventory_opening/play_anim_loop"),
                ],
                targets,
            )
            self.assertFalse(namespace_path.exists())
            for target in targets:
                target_create_path = pack_root / "data" / target.namespace / "function" / "_" / "create.mcfunction"
                self.assertIn(f"{target.namespace}_root", target_create_path.read_text(encoding="utf-8"))
                animation_names = [path.name for path in (target_create_path.parents[1] / "a").iterdir()]
                self.assertEqual([target.entrypoint.split("/")[1]], animation_names)

    def test_multiple_targets_use_generated_namespaces_as_default_names(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            metadata = create_emote_metadata(
                Path(temp_dir),
                Path("inv.zip"),
                [
                    EmoteTarget("inv_1", "a/default/play_anim_loop"),
                    EmoteTarget("inv_2", "a/inventory_closing/play_anim_loop"),
                ],
                create_args(),
            )

        self.assertEqual(["inv_1", "inv_2"], [meta.name for meta in metadata.values()])
        self.assertEqual(["inv_1", "inv_2"], [meta.command_name for meta in metadata.values()])
        self.assertEqual(
            ["a/default/play_anim_loop", "a/inventory_closing/play_anim_loop"],
            [meta.entrypoint for meta in metadata.values()],
        )


class InferPartNamesTest(unittest.TestCase):
    def test_dance_pose_keeps_raised_leg_segments_with_their_limb(self) -> None:
        parts = [
            create_part(0, (0.156, 2.396, 0.167), (1.0, 1.0, 1.0)),
            create_part(1, (0.083, 1.661, 0.025), (1.0, 0.5, 0.5)),
            create_part(2, (0.083, 1.661, 0.025), (1.0, 1.0, 0.5)),
            create_part(3, (-0.087, 0.502, -0.184), (0.5, 0.25, 0.5)),
            create_part(4, (-0.087, 0.502, -0.184), (0.5, 0.5, 0.5)),
            create_part(5, (-0.045, 0.906, 0.026), (0.5, 0.5, 0.5)),
            create_part(6, (-0.058, 0.545, -0.077), (0.5, 0.25, 0.5)),
            create_part(7, (0.228, 0.517, -0.134), (0.5, 0.25, 0.5)),
            create_part(8, (0.228, 0.517, -0.134), (0.5, 0.5, 0.5)),
            create_part(9, (0.135, 0.887, 0.116), (0.5, 0.5, 0.5)),
            create_part(10, (0.189, 0.542, -0.019), (0.5, 0.25, 0.5)),
            create_part(11, (0.440, 1.183, 0.282), (0.5, 0.25, 0.5)),
            create_part(12, (0.440, 1.183, 0.282), (0.5, 0.5, 0.5)),
            create_part(13, (0.257, 1.579, 0.079), (0.5, 0.5, 0.5)),
            create_part(14, (0.438, 1.251, 0.104), (0.5, 0.25, 0.5)),
            create_part(15, (-0.368, 1.183, 0.081), (0.5, 0.25, 0.5)),
            create_part(16, (-0.368, 1.183, 0.081), (0.5, 0.5, 0.5)),
            create_part(17, (-0.080, 1.551, 0.017), (0.5, 0.5, 0.5)),
            create_part(18, (-0.299, 1.256, -0.061), (0.5, 0.25, 0.5)),
        ]

        assignments = infer_part_names(parts)

        self.assertEqual("emote:head", assignments[0])
        self.assertTrue(all(assignments[index] == "emote:left_leg" for index in range(3, 7)))
        self.assertTrue(all(assignments[index] == "emote:right_leg" for index in range(7, 11)))
        self.assertTrue(all(assignments[index] == "emote:right_arm" for index in range(11, 15)))
        self.assertTrue(all(assignments[index] == "emote:left_arm" for index in range(15, 19)))

    def test_non_humanoid_player_heads_are_not_assigned_skin_markers(self) -> None:
        parts = [
            create_part(0, (0.5, 2.34, 0.5), (0.937, 0.937, 0.937)),
            create_part(1, (0.5, 1.64, 0.5), (0.937, 0.469, 0.469)),
            create_part(2, (0.5, 1.64, 0.5), (0.937, 0.937, 0.469)),
            create_part(3, (0.38, 0.94, 0.5), (0.469, 0.469, 0.469)),
            create_part(4, (0.38, 0.94, 0.5), (0.469, 0.937, 0.469)),
            create_part(5, (0.62, 0.94, 0.5), (0.469, 0.469, 0.469)),
            create_part(6, (0.62, 0.94, 0.5), (0.469, 0.937, 0.469)),
            create_part(7, (0.92, 1.36, 0.84), (0.469, 0.469, 0.469)),
            create_part(8, (0.92, 1.36, 0.84), (0.469, 0.937, 0.469)),
            create_part(9, (0.08, 1.36, 0.84), (0.469, 0.469, 0.469)),
            create_part(10, (0.08, 1.36, 0.84), (0.469, 0.937, 0.469)),
            create_part(11, (0.14, 2.08, -0.24), (1.0, 1.0, 0.001)),
            create_part(20, (0.63, 1.13, -0.02), (1.0, 0.125, 0.5)),
            create_part(21, (0.26, 1.13, -0.02), (0.5, 0.125, 0.5)),
        ]

        assignments = infer_part_names(parts)

        self.assertEqual(set(range(11)), set(assignments))


def create_part(
    part_index: int,
    anchor: tuple[float, float, float],
    scale: tuple[float, float, float],
) -> PlayerHeadPart:
    return PlayerHeadPart(
        part_index=part_index,
        start_index=0,
        end_index=0,
        item_display_text="",
        x=anchor[0],
        y=anchor[1] - 0.5,
        z=anchor[2],
        scale_x=scale[0],
        scale_y=scale[1],
        scale_z=scale[2],
        anchor_x=anchor[0],
        anchor_y=anchor[1],
        anchor_z=anchor[2],
        local_x_axis_x=1.0,
        local_x_axis_y=0.0,
        local_x_axis_z=0.0,
        local_y_axis_x=0.0,
        local_y_axis_y=1.0,
        local_y_axis_z=0.0,
    )


def create_args() -> argparse.Namespace:
    return argparse.Namespace(
        name=None,
        description=None,
        command_name=None,
        entrypoint="a/default/play_anim_loop",
        hide_player=True,
    )


if __name__ == "__main__":
    unittest.main()
