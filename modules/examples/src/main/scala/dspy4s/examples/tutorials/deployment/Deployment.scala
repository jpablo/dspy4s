/**
 * Tutorial: Deploying your DSPy program
 *
 * Source:   docs/docs/tutorials/deployment/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/deployment/index.md
 * Status:   scaffold (4 python snippets — TODO translate)
 */
package dspy4s.examples.tutorials.deployment

object Deployment {

  // ── Snippet 1 (lines 7–12) ────────────────────
  // | import dspy
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // | dspy_program = dspy.ChainOfThought("question -> answer")
  // TODO translate snippet 1

  // ── Snippet 2 (lines 25–57) ────────────────────
  // | from fastapi import FastAPI, HTTPException
  // | from pydantic import BaseModel
  // |
  // | import dspy
  // |
  // | app = FastAPI(
  // |     title="DSPy Program API",
  // |     description="A simple API serving a DSPy Chain of Thought program",
  // |     version="1.0.0"
  // | )
  // |
  // | # Define request model for better documentation and validation
  // | class Question(BaseModel):
  // |     text: str
  // |
  // | # Configure your language model and 'asyncify' your DSPy program.
  // | lm = dspy.LM("openai/gpt-4o-mini")
  // | dspy.configure(lm=lm, async_max_workers=4) # default is 8
  // | dspy_program = dspy.ChainOfThought("question -> answer")
  // | dspy_program = dspy.asyncify(dspy_program)
  // |
  // | @app.post("/predict")
  // | async def predict(question: Question):
  // |     try:
  // |         result = await dspy_program(question=question.text)
  // |         return {
  // |             "status": "success",
  // |             "data": result.toDict()
  // |         }
  // |     except Exception as e:
  // |         raise HTTPException(status_code=500, detail=str(e))
  // TODO translate snippet 2

  // ── Snippet 3 (lines 110–118) ────────────────────
  // | import requests
  // |
  // | response = requests.post(
  // |     "http://127.0.0.1:8000/predict",
  // |     json={"text": "What is the capital of France?"}
  // | )
  // | print(response.json())
  // TODO translate snippet 3

  // ── Snippet 4 (lines 158–185) ────────────────────
  // | import dspy
  // | import mlflow
  // |
  // | mlflow.set_tracking_uri("http://127.0.0.1:5000/")
  // | mlflow.set_experiment("deploy_dspy_program")
  // |
  // | lm = dspy.LM("openai/gpt-4o-mini")
  // | dspy.configure(lm=lm)
  // |
  // | class MyProgram(dspy.Module):
  // |     def __init__(self):
  // |         super().__init__()
  // |         self.cot = dspy.ChainOfThought("question -> answer")
  // |
  // |     def forward(self, messages):
  // |         return self.cot(question=messages[0]["content"])
  // |
  // | dspy_program = MyProgram()
  // |
  // | with mlflow.start_run():
  // |     mlflow.dspy.log_model(
  // |         dspy_program,
  // |         "dspy_program",
  // |         input_example={"messages": [{"role": "user", "content": "What is LLM agent?"}]},
  // |         task="llm/v1/chat",
  // |     )
  // TODO translate snippet 4
}
