/**
 * Use and Customize DSPy Cache
 *
 * Source:   docs/docs/tutorials/cache/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/cache/index.md
 * Status:   scaffold (9 python snippets — TODO translate)
 */
package dspy4s.examples.tutorials.cache

object Cache {

  // ── Snippet 1 (lines 21–39) ────────────────────
  // | import dspy
  // | import os
  // | import time
  // |
  // | os.environ["OPENAI_API_KEY"] = "{your_openai_key}"
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"), track_usage=True)
  // |
  // | predict = dspy.Predict("question->answer")
  // |
  // | start = time.time()
  // | result1 = predict(question="Who is the GOAT of basketball?")
  // | print(f"Time elapse: {time.time() - start: 2f}\n\nTotal usage: {result1.get_lm_usage()}")
  // |
  // | start = time.time()
  // | result2 = predict(question="Who is the GOAT of basketball?")
  // | print(f"Time elapse: {time.time() - start: 2f}\n\nTotal usage: {result2.get_lm_usage()}")
  // TODO translate snippet 1

  // ── Snippet 2 (lines 57–76) ────────────────────
  // | import dspy
  // | import os
  // |
  // | os.environ["ANTHROPIC_API_KEY"] = "{your_anthropic_key}"
  // | lm = dspy.LM(
  // |     "anthropic/claude-sonnet-4-5-20250929",
  // |     cache_control_injection_points=[
  // |         {
  // |             "location": "message",
  // |             "role": "system",
  // |         }
  // |     ],
  // | )
  // | dspy.configure(lm=lm)
  // |
  // | # Use with any DSPy module
  // | predict = dspy.Predict("question->answer")
  // | result = predict(question="What is the capital of France?")
  // TODO translate snippet 2

  // ── Snippet 3 (lines 94–99) ────────────────────
  // | dspy.configure_cache(
  // |     enable_disk_cache=False,
  // |     enable_memory_cache=False,
  // | )
  // TODO translate snippet 3

  // ── Snippet 4 (lines 103–110) ────────────────────
  // | dspy.configure_cache(
  // |     enable_disk_cache=True,
  // |     enable_memory_cache=True,
  // |     disk_size_limit_bytes=YOUR_DESIRED_VALUE,
  // |     memory_max_entries=YOUR_DESIRED_VALUE,
  // | )
  // TODO translate snippet 4

  // ── Snippet 5 (lines 120–139) ────────────────────
  // | class CustomCache(dspy.clients.Cache):
  // |     def __init__(self, **kwargs):
  // |         {write your own constructor}
  // |
  // |     def cache_key(self, request: dict[str, Any], ignored_args_for_cache_key: Optional[list[str]] = None) -> str:
  // |         {write your logic of computing cache key}
  // |
  // |     def get(self, request: dict[str, Any], ignored_args_for_cache_key: Optional[list[str]] = None) -> Any:
  // |         {write your cache read logic}
  // |
  // |     def put(
  // |         self,
  // |         request: dict[str, Any],
  // |         value: Any,
  // |         ignored_args_for_cache_key: Optional[list[str]] = None,
  // |         enable_memory_cache: bool = True,
  // |     ) -> None:
  // |         {write your cache write logic}
  // TODO translate snippet 5

  // ── Snippet 6 (lines 145–147) ────────────────────
  // | dspy.cache = CustomCache()
  // TODO translate snippet 6

  // ── Snippet 7 (lines 151–159) ────────────────────
  // | class CustomCache(dspy.clients.Cache):
  // |
  // |     def cache_key(self, request: dict[str, Any], ignored_args_for_cache_key: Optional[list[str]] = None) -> str:
  // |         messages = request.get("messages", [])
  // |         return sha256(orjson.dumps(messages, option=orjson.OPT_SORT_KEYS)).hexdigest()
  // |
  // | dspy.cache = CustomCache(enable_disk_cache=True, enable_memory_cache=True, disk_cache_dir=dspy.clients.DISK_CACHE_DIR)
  // TODO translate snippet 7

  // ── Snippet 8 (lines 163–182) ────────────────────
  // | import dspy
  // | import os
  // | import time
  // |
  // | os.environ["OPENAI_API_KEY"] = "{your_openai_key}"
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // |
  // | predict = dspy.Predict("question->answer")
  // |
  // | start = time.time()
  // | result1 = predict(question="Who is the GOAT of soccer?")
  // | print(f"Time elapse: {time.time() - start: 2f}")
  // |
  // | start = time.time()
  // | with dspy.context(lm=dspy.LM("openai/gpt-4.1-mini")):
  // |     result2 = predict(question="Who is the GOAT of soccer?")
  // | print(f"Time elapse: {time.time() - start: 2f}")
  // TODO translate snippet 8

  // ── Snippet 9 (lines 186–216) ────────────────────
  // | import dspy
  // | import os
  // | import time
  // | from typing import Dict, Any, Optional
  // | import orjson
  // | from hashlib import sha256
  // |
  // | os.environ["OPENAI_API_KEY"] = "{your_openai_key}"
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // |
  // | class CustomCache(dspy.clients.Cache):
  // |
  // |     def cache_key(self, request: dict[str, Any], ignored_args_for_cache_key: Optional[list[str]] = None) -> str:
  // |         messages = request.get("messages", [])
  // |         return sha256(orjson.dumps(messages, option=orjson.OPT_SORT_KEYS)).hexdigest()
  // |
  // | dspy.cache = CustomCache(enable_disk_cache=True, enable_memory_cache=True, disk_cache_dir=dspy.clients.DISK_CACHE_DIR)
  // |
  // | predict = dspy.Predict("question->answer")
  // |
  // | start = time.time()
  // | result1 = predict(question="Who is the GOAT of volleyball?")
  // | print(f"Time elapse: {time.time() - start: 2f}")
  // |
  // | start = time.time()
  // | with dspy.context(lm=dspy.LM("openai/gpt-4.1-mini")):
  // |     result2 = predict(question="Who is the GOAT of volleyball?")
  // | print(f"Time elapse: {time.time() - start: 2f}")
  // TODO translate snippet 9
}
