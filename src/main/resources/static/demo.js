const examples = [
  {
    matches: ["review", "content", "used", "human"],
    answer: "AI-generated content should be reviewed by a subject-matter expert before it is used for a customer, employee, or operational decision. The sample guide emphasizes human review for consequential use.",
    sources: ["Responsible AI guide · passage 2"]
  },
  {
    matches: ["source", "pilot", "come from", "citation", "where"],
    answer: "A live system can retrieve relevant passages, rerank them, and return an answer with references to supporting source documents. This page illustrates that flow with a prepared example.",
    sources: ["Knowledge assistant pilot · passage 3", "Knowledge assistant pilot · passage 5"]
  },
  {
    matches: ["not contain", "no answer", "unknown", "unsupported", "don't know"],
    answer: "If the indexed sources do not support an answer, the assistant should say it could not find the information rather than inventing a response.",
    sources: ["Responsible AI guide · passage 4"]
  }
];

const form = document.getElementById("question-form");
const input = document.getElementById("question-input");
const answerPanel = document.getElementById("answer");
const answerText = document.getElementById("answer-text");
const sourceList = document.getElementById("source-list");
const status = document.getElementById("demo-status");

function showAnswer(question) {
  const normalized = question.toLowerCase();
  const example = examples.find(item => item.matches.some(match => normalized.includes(match)));
  if (!example) {
    answerText.textContent = "The sample documents do not contain a prepared answer to that question. In the current live app, an answer is withheld when reranking returns no passages; returned passages can still be irrelevant, so check the source excerpts.";
    sourceList.replaceChildren();
    const item = document.createElement("li");
    item.textContent = "No supporting sample passage found";
    sourceList.append(item);
  } else {
    answerText.textContent = example.answer;
    sourceList.replaceChildren();
    for (const source of example.sources) {
      const item = document.createElement("li");
      item.textContent = source;
      sourceList.append(item);
    }
  }
  answerPanel.hidden = false;
  status.textContent = "Prepared sample response — no AI provider was contacted.";
}

form.addEventListener("submit", event => {
  event.preventDefault();
  const question = input.value.trim();
  if (!question) {
    input.focus();
    return;
  }
  showAnswer(question);
});

document.querySelectorAll("[data-question]").forEach(button => {
  button.addEventListener("click", () => {
    input.value = button.dataset.question;
    showAnswer(input.value);
  });
});
