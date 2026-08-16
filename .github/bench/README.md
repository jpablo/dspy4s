# Benchmark comparison

The benchmark project is outside the root aggregate. Run `sbt benchQuick` for a short local sample or `sbt bench` for
a longer sample.

The pull-request workflow runs the affected JMH classes on the merge base and the pull-request head. It reports timing
and allocation changes. Timing is advisory because shared runners have variable load. The workflow does not enforce a
threshold until the project has enough A/A data to set a useful noise floor.

The Python helper uses only the standard library. Verify it with:

```text
python3 -m unittest discover .github/bench -v
python3 .github/bench/bench_tools.py validate-mapping
```
