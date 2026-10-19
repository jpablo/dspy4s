"""Write the golden LM calls of ChainOfThought, ReAct, and RLM (UPGRADE-3.2.1-to-3.4.0.md, decision D3).

Each case runs an upstream program with a scripted model (``DummyLM``) and records each call: the messages that the
program sends, and the text that the model gives back. The Scala suite ``ProgramPromptParitySuite`` runs the same
program of the port with a model that gives back the same texts, and compares the messages, character for character.

ReAct shows three things in a form that is outside the boundary rule of D3. The script writes the port form of them,
which is the upstream text with these changes:

- The arguments of a tool are JSON text (``json.dumps``), not the Python text of a dict.
- ``next_tool_name`` has the type name ``str`` and no hint in the output reminder: the port gives the field enum values,
  not a ``Literal`` type. The note of the field is the same.
- ``next_tool_args`` has the type name ``dict``, not ``dict[str, Any]``.

RLM runs with the local interpreter of each side (upstream ``LocalInterpreter``, the port ``LocalPythonInterpreter``).
Upstream adds the sub-agent text of the sandbox ``dspy`` facade to each action prompt. The port has no facade (upgrade
item P8), so the script removes that text: the port form has the execution text of the interpreter only (I7).

Run it with the Python environment of the upstream checkout:

    ../dspy/.venv/bin/python -I scripts/golden/program_prompts.py ../dspy
"""

import json
import pathlib
import subprocess
import sys

import dspy
import dspy.predict.rlm as rlm_module
from dspy.adapters.types.tool import Tool
from dspy.primitives.local_interpreter import LocalInterpreter
from dspy.utils.dummies import DummyLM


def search(query: str) -> str:
    """Search the web for a query."""
    return f"3 results for {query}"


def add(a: int, b: int) -> int:
    return a + b


def tool_text(tool):
    """The port form of ``Tool.__str__``: the arguments as JSON text."""
    desc = f", whose description is <desc>{tool.desc}</desc>.".replace("\n", "  ") if tool.desc else "."
    return f"{tool.name}{desc} It takes arguments {json.dumps(tool.args, ensure_ascii=False)}."


def port_form(text, tool_names):
    literal = "Literal[" + ", ".join(repr(name) for name in tool_names) + "]"
    text = text.replace(f" (must be formatted as a valid Python {literal})", "")
    text = text.replace(f"({literal})", "(str)")
    return text.replace("dict[str, Any]", "dict")


def run(program, answers, inputs, transform=None):
    lm = DummyLM(answers)
    with dspy.context(lm=lm, adapter=dspy.ChatAdapter()):
        prediction = program(**inputs)
    calls = []
    for entry in lm.history:
        messages = [{"role": m["role"], "content": m["content"]} for m in entry["messages"]]
        if transform is not None:
            messages = [{"role": m["role"], "content": transform(m["content"])} for m in messages]
        calls.append({"messages": messages, "output": entry["outputs"][0]})
    return prediction, calls


def main():
    upstream = pathlib.Path(sys.argv[1])
    commit = subprocess.run(
        ["git", "-C", str(upstream), "rev-parse", "--short=9", "HEAD"], check=True, capture_output=True, text=True
    ).stdout.strip()
    root = pathlib.Path(__file__).resolve().parents[2]
    cases = []

    _, calls = run(
        dspy.ChainOfThought("question -> answer"),
        [{"reasoning": "Two plus two is four.", "answer": "4"}],
        {"question": "What is 2+2?"},
    )
    cases.append({"id": "cot_basic", "calls": calls})

    _, calls = run(
        dspy.ChainOfThought(dspy.Signature("question -> answer", "Answer in one word.")),
        [{"reasoning": "It is a color.", "answer": "Blue"}],
        {"question": "What color is the sky?"},
    )
    cases.append({"id": "cot_instructions", "calls": calls})

    original = Tool.__str__
    Tool.__str__ = tool_text
    try:
        react = dspy.ReAct("question -> answer", tools=[search, add], max_iters=5)
    finally:
        Tool.__str__ = original
    prediction, calls = run(
        react,
        [
            {"next_thought": "I should search.", "next_tool_name": "search", "next_tool_args": {"query": "cats"}},
            {"next_thought": "Now add.", "next_tool_name": "add", "next_tool_args": {"a": 2, "b": 3}},
            {"next_thought": "Done.", "next_tool_name": "finish", "next_tool_args": {}},
            {"reasoning": "I found it.", "answer": "5 cats"},
        ],
        {"question": "How many cats?"},
        transform=lambda text: port_form(text, ["search", "add", "finish"]),
    )
    assert prediction.answer == "5 cats", prediction
    cases.append({"id": "react", "calls": calls, "answer": prediction.answer})

    # The port has no sandbox facade (P8), so the RLM prompts have no sub-agent text.
    rlm_module.SUB_AGENT_INSTRUCTIONS = ""
    prediction, calls = run(
        dspy.RLM("context, query -> answer", max_iters=5, interpreter_factory=LocalInterpreter),
        [
            {"reasoning": "Look at the data.", "code": "```python\nprint(len(context))\n```"},
            {"reasoning": "Submit the length.", "code": "```python\nSUBMIT(answer='eleven')\n```"},
        ],
        {"context": "hello world", "query": "How long is the context?"},
    )
    assert prediction.answer == "eleven", prediction
    cases.append({"id": "rlm_submit", "calls": calls, "answer": prediction.answer})

    prediction, calls = run(
        dspy.RLM("context, query -> answer", max_iters=1, interpreter_factory=LocalInterpreter),
        [
            {"reasoning": "Look at the data.", "code": "```python\nprint(context.upper())\n```"},
            {"answer": "HELLO WORLD"},
        ],
        {"context": "hello world", "query": "What is the context in upper case?"},
    )
    assert prediction.answer == "HELLO WORLD", prediction
    cases.append({"id": "rlm_extract", "calls": calls, "answer": prediction.answer})

    path = root / "modules/programs/src/test/resources/golden/program-prompts.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    body = {"upstream": commit, "generator": "scripts/golden/program_prompts.py", "cases": cases}
    path.write_text(json.dumps(body, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {path} with {len(cases)} cases")


if __name__ == "__main__":
    main()
