import json
import pathlib
import tempfile
import unittest

import bench_tools as tools


def entry(name: str, time: float, allocation: float, size: str = "10") -> dict:
    return {
        "benchmark": name,
        "params": {"size": size},
        "primaryMetric": {"score": time, "scoreUnit": "ns/op"},
        "secondaryMetrics": {"gc.alloc.rate.norm": {"score": allocation}},
    }


class AffectedTests(unittest.TestCase):
    def test_algebra_selects_only_optic_benchmark(self):
        selected = tools.affected(["modules/algebra/src/main/scala/dspy4s/algebra/Optic.scala"])
        self.assertIn("OpticBench", selected)
        self.assertNotIn("DeepProgramBench", selected)

    def test_build_and_unknown_paths_select_all_benchmarks(self):
        self.assertEqual(tools.affected(["build.sbt"]), "FULL")
        self.assertEqual(tools.affected(["new-module/File.scala"]), "FULL")

    def test_documentation_only_change_selects_nothing(self):
        self.assertEqual(tools.affected(["docs/design.md", "README.md"]), "")


class ComparisonTests(unittest.TestCase):
    def load(self, entries: list[dict]) -> dict:
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "result.json"
            path.write_text(json.dumps(entries))
            return tools.load_results(path)

    def test_comparison_pairs_parameterized_results(self):
        name = "dspy4s.bench.DeepProgramBench.execute"
        base = self.load([entry(name, 100.0, 40.0)])
        head = self.load([entry(name, 125.0, 32.0)])
        row = tools.compare(base, head)["rows"][0]
        self.assertEqual(row["time_change_pct"], 25.0)
        self.assertEqual(row["bytes_change_pct"], -20.0)

    def test_markdown_reports_timing_and_allocation(self):
        name = "dspy4s.bench.OpticBench.existentialOpticModify"
        report = tools.compare(self.load([entry(name, 10.0, 8.0)]), self.load([entry(name, 11.0, 16.0)]))
        rendered = tools.markdown(report)
        self.assertIn("OpticBench.existentialOpticModify", rendered)
        self.assertIn("+10.0%", rendered)
        self.assertIn("+100.0%", rendered)


if __name__ == "__main__":
    unittest.main()
