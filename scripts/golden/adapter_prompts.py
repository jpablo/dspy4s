"""Write the golden prompts of the Chat, JSON, XML, and two-step adapters (UPGRADE-3.2.1-to-3.4.0.md, decision D3).

Each case gives a signature, demos, and input values. The script runs ``Adapter.format`` of upstream for each case and
each adapter, and records the messages. The Scala suite ``AdapterPromptParitySuite`` builds the same case from the
data and compares the messages of the port with the messages of upstream, character for character.

The cases stay inside the scope of decision D3: the port matches the text that is fixed, and the text that it can
compute from its own data. An output of a list type with an item type (``list[str]``), a ``Literal``, an ``Optional``,
or a model type is outside the scope: the port shows its own type name and its own JSON schema for such an output. A
bare ``list`` or ``dict`` output is inside the scope.

A case holds the data that the port needs to build it:

- ``dsl``: the string signature of the port (``SignatureDsl.parse``).
- ``instructions``: the instructions, or null for the default instructions.
- ``descriptions``: a description for each named field.
- ``constraints``: the constraints of each named field, as ``[kind, value]`` pairs.
- ``demos`` and ``inputs``: the values, in a typed form (see ``encode``), because a JSON number does not tell an int
  from a float.

Run it with the Python environment of the upstream checkout:

    ../dspy/.venv/bin/python -I scripts/golden/adapter_prompts.py ../dspy
"""

import json
import pathlib
import subprocess
import sys

import dspy



class JsonInTagXMLAdapter(dspy.XMLAdapter):
    """The XML adapter of the port: decision A6 keeps a JSON value inside the tag of a list, dict, or model output.

    Upstream asks for nested XML elements for such an output. The port asks for the value as JSON text, so it uses the
    structure and the reminder that upstream uses for each other output type.
    """

    @staticmethod
    def _uses_nested_xml(annotation):
        return False


ADAPTERS = {"ChatAdapter": dspy.ChatAdapter, "JSONAdapter": dspy.JSONAdapter, "XMLAdapter": JsonInTagXMLAdapter}


def encode(value):
    """The typed form of a value: ``{"t": <type>, "v": <value>}``."""
    if value is None:
        return {"t": "none"}
    if isinstance(value, bool):
        return {"t": "bool", "v": value}
    if isinstance(value, int):
        return {"t": "int", "v": value}
    if isinstance(value, float):
        return {"t": "float", "v": value}
    if isinstance(value, str):
        return {"t": "str", "v": value}
    if isinstance(value, list):
        return {"t": "list", "v": [encode(item) for item in value]}
    if isinstance(value, dict):
        return {"t": "dict", "v": [[key, encode(item)] for key, item in value.items()]}
    if isinstance(value, dspy.History):
        return {"t": "history", "v": [encode(message) for message in value.messages]}
    raise TypeError(f"no typed form for {value!r}")


class ConstraintSignature(dspy.Signature):
    question: str = dspy.InputField(min_length=2)
    n: int = dspy.OutputField(ge=0, le=10)
    ratio: float = dspy.OutputField(gt=0, lt=1)
    words: list = dspy.OutputField(min_length=1, max_length=5)
    step: float = dspy.OutputField(multiple_of=0.5)


class HistorySignature(dspy.Signature):
    history: dspy.History = dspy.InputField()
    question: str = dspy.InputField()
    answer: str = dspy.OutputField()


class ToolCallsSignature(dspy.Signature):
    question: str = dspy.InputField()
    answer: str = dspy.OutputField()
    tool_calls: dspy.ToolCalls = dspy.OutputField()


# The Python form of the typed signature `Signature.derived[PpQuestion, PpAnswer]` of `TypedPromptParitySuite`.
class TypedSignature(dspy.Signature):
    question: str = dspy.InputField()
    answer: str = dspy.OutputField()
    count: int = dspy.OutputField()
    score: float = dspy.OutputField()
    ok: bool = dspy.OutputField()


TYPES_DSL = (
    "question: str, count: int, tags: list[str], flag: bool -> answer: str, score: float, ok: bool, n: int, "
    "items: list, meta: dict"
)

VALUES_DSL = (
    "text, items, single, empty, nums: list[int], floats: list[float], obj: dict, flag: bool, ratio: float, "
    "tenth: float, tiny: float, huge: float, count: int, missing -> answer"
)


def case(case_id, signature, *, dsl=None, instructions=None, descriptions=None, constraints=None, demos=(), inputs):
    """One case. ``signature`` is a string signature, or a class when the port DSL is in ``dsl``."""
    if isinstance(signature, str):
        python_signature = dspy.Signature(signature, instructions) if instructions else dspy.Signature(signature)
        dsl = signature
    else:
        python_signature = signature
    for name, text in (descriptions or {}).items():
        python_signature = python_signature.with_updated_fields(name, desc=text)
    return {
        "id": case_id,
        "python": python_signature,
        "dsl": dsl,
        "instructions": instructions,
        "descriptions": descriptions or {},
        "constraints": constraints or {},
        "demos": list(demos),
        "inputs": inputs,
    }


CASES = [
    case("basic", "question -> answer", inputs={"question": "What is 2+2?"}),
    case(
        "types",
        TYPES_DSL,
        inputs={"question": "Q?", "count": 3, "tags": ["a", "b"], "flag": True},
    ),
    case(
        "instructions",
        "question, context -> answer",
        instructions="Answer the question.\nUse the context.",
        descriptions={"question": "the question", "answer": "a short answer"},
        inputs={"question": "Who?", "context": "Ana wrote it."},
    ),
    case(
        "demos",
        "question -> answer, n: int, score: float, ok: bool, tags: list",
        demos=[
            {"question": "d1", "answer": "a1", "n": 1, "score": 0.5, "ok": True, "tags": ["x", 1.0, None]},
            {"question": "d2", "answer": "a2"},
            {"answer": "a3", "n": 3},
            {"question": "d4", "n": None, "answer": "a4", "score": 2.0, "ok": False},
            {"question": "d5", "answer": "x < y & z", "n": 5, "score": 1e-05, "ok": True, "tags": []},
        ],
        inputs={"question": "Q?"},
    ),
    case(
        "types_scalar",
        "question: str, count: int, flag: bool -> answer: str, score: float, ok: bool, n: int, items: list, meta: dict",
        inputs={"question": "Q?", "count": 3, "flag": True},
    ),
    case(
        "demos_scalar",
        "question -> answer, n: int, score: float, ok: bool",
        demos=[
            {"question": "d1", "answer": "a1", "n": 1, "score": 0.5, "ok": True},
            {"question": "d2", "answer": "a2"},
            {"answer": "a3", "n": 3},
            {"question": "d4", "n": None, "answer": "a4", "score": 2.0, "ok": False},
            {"question": "d5", "answer": "x < y & z", "n": 5, "score": 1e-05, "ok": True},
        ],
        inputs={"question": "Q?"},
    ),
    case(
        "values",
        VALUES_DSL,
        inputs={
            "text": "café\nline two",
            "items": ["x", "y\nz"],
            "single": ["only"],
            "empty": [],
            "nums": [1, 2],
            "floats": [1.0, 0.5, 1e16],
            "obj": {"a": 1, "b": [True, None], "c": "é", "d": 2.0, "e": "line\nbreak"},
            "flag": False,
            "ratio": 3.0,
            "tenth": 0.1,
            "tiny": 1e-05,
            "huge": 1e16,
            "count": 3,
            "missing": None,
        },
    ),
    case(
        "constraints",
        ConstraintSignature,
        dsl="question -> n: int, ratio: float, words: list, step: float",
        constraints={
            "question": [["min_length", 2]],
            "n": [["ge", 0], ["le", 10]],
            "ratio": [["gt", 0], ["lt", 1]],
            "words": [["min_length", 1], ["max_length", 5]],
            "step": [["multiple_of", 0.5]],
        },
        inputs={"question": "Q?"},
    ),
    case(
        "history",
        HistorySignature,
        dsl="history: history, question -> answer",
        demos=[{"question": "d1", "answer": "a1"}],
        inputs={
            "history": dspy.History(
                messages=[{"question": "What is 1+1?", "answer": "2"}, {"question": "What is 2+2?", "answer": "4"}]
            ),
            "question": "What is 3+3?",
        },
    ),
    case(
        "tool_calls",
        ToolCallsSignature,
        dsl="question -> answer, tool_calls: tool_calls",
        inputs={"question": "Find cats."},
    ),
]


# TwoStepAdapter prints a list or dict value with Python `repr`. The port prints a structured value as JSON, so the
# cases for TwoStepAdapter have scalar values only.
TWO_STEP_CASES = ("basic", "types_scalar", "instructions", "demos_scalar")
TWO_STEP_ONLY = ("types_scalar", "demos_scalar")
TWO_STEP_COMPLETION = "The answer is 4."


def main():
    upstream = pathlib.Path(sys.argv[1])
    commit = subprocess.run(
        ["git", "-C", str(upstream), "rev-parse", "--short=9", "HEAD"], check=True, capture_output=True, text=True
    ).stdout.strip()
    root = pathlib.Path(__file__).resolve().parents[2]

    cases = []
    for entry in CASES:
        if entry["id"] in TWO_STEP_ONLY:
            continue
        for adapter_name, adapter_type in ADAPTERS.items():
            messages = adapter_type().format(entry["python"], demos=entry["demos"], inputs=entry["inputs"])
            for message in messages:
                assert isinstance(message["content"], str), (entry["id"], adapter_name, message)
            # True when the prompt of the port differs from upstream by decision A6.
            nested = adapter_name == "XMLAdapter" and messages != dspy.XMLAdapter().format(
                entry["python"], demos=entry["demos"], inputs=entry["inputs"]
            )
            cases.append(
                {
                    "id": entry["id"],
                    "adapter": adapter_name,
                    "dsl": entry["dsl"],
                    "instructions": entry["instructions"],
                    "descriptions": entry["descriptions"],
                    "constraints": entry["constraints"],
                    "demos": [encode(demo) for demo in entry["demos"]],
                    "inputs": encode(entry["inputs"]),
                    "differs_from_upstream_by_a6": nested,
                    "messages": [{"role": m["role"], "content": m["content"]} for m in messages],
                }
            )

    typed = []
    for adapter_name in ("ChatAdapter", "JSONAdapter"):
        messages = ADAPTERS[adapter_name]().format(TypedSignature, demos=[], inputs={"question": "Who is Ana?"})
        typed.append({"adapter": adapter_name, "messages": [{"role": m["role"], "content": m["content"]} for m in messages]})
    typed_path = root / "modules/programs/src/test/resources/golden/typed-prompts.json"
    typed_path.parent.mkdir(parents=True, exist_ok=True)
    typed_body = {"upstream": commit, "generator": "scripts/golden/adapter_prompts.py", "cases": typed}
    typed_path.write_text(json.dumps(typed_body, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {typed_path} with {len(typed)} cases")

    # TwoStepAdapter: the prompt of the main model, and the prompt of the extraction model for one completion.
    two_step = dspy.TwoStepAdapter(extraction_model=dspy.LM("openai/extraction-model"))
    for entry in CASES:
        if entry["id"] not in TWO_STEP_CASES:
            continue
        messages = two_step.format(entry["python"], demos=entry["demos"], inputs=entry["inputs"])
        extractor = two_step._create_extractor_signature(entry["python"])
        extraction = dspy.ChatAdapter().format(extractor, demos=[], inputs={"text": TWO_STEP_COMPLETION})
        cases.append(
            {
                "id": entry["id"],
                "adapter": "TwoStepAdapter",
                "dsl": entry["dsl"],
                "instructions": entry["instructions"],
                "descriptions": entry["descriptions"],
                "constraints": entry["constraints"],
                "demos": [encode(demo) for demo in entry["demos"]],
                "inputs": encode(entry["inputs"]),
                "differs_from_upstream_by_a6": False,
                "messages": [{"role": m["role"], "content": m["content"]} for m in messages],
                "completion": TWO_STEP_COMPLETION,
                "extraction_messages": [{"role": m["role"], "content": m["content"]} for m in extraction],
            }
        )

    path = root / "modules/adapters/src/test/resources/golden/adapter-prompts.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    body = {"upstream": commit, "generator": "scripts/golden/adapter_prompts.py", "cases": cases}
    path.write_text(json.dumps(body, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {path} with {len(cases)} cases")


if __name__ == "__main__":
    main()
