const getJson = async url => {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
};

const postJson = async (url, body) => {
  const response = await fetch(url, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.message || `HTTP ${response.status}`);
  return data;
};

const pretty = value => JSON.stringify(value, null, 2);

function showOutput(id, value, error = false) {
  const element = document.getElementById(id);
  element.textContent = error ? `ERROR\n${value}` : pretty(value);
  element.classList.toggle('output-error', error);
}

async function load() {
  const prompts = await getJson('/dev/api/prompts');
  document.getElementById('step1Prompt').value = prompts.experiencePrompt || '';
  document.getElementById('step2Prompt').value = prompts.questionMetadataPrompt || '';
}

async function runStep1() {
  const button = document.getElementById('runStep1');
  button.disabled = true;
  button.textContent = 'Running...';
  try {
    const result = await postJson('/dev/api/prompt-test/step1', {
      prompt: document.getElementById('step1Prompt').value,
      pageTitle: document.getElementById('pageTitle').value,
      pageContent: document.getElementById('pageContent').value
    });
    showOutput('step1Output', result);
  } catch (error) {
    showOutput('step1Output', error.message, true);
  } finally {
    button.disabled = false;
    button.textContent = '▶ Run Step 1';
  }
}

async function runStep2() {
  const button = document.getElementById('runStep2');
  button.disabled = true;
  button.textContent = 'Running...';
  try {
    const questionsJson = document.getElementById('questionsJson').value;
    JSON.parse(questionsJson);
    const result = await postJson('/dev/api/prompt-test/step2', {
      prompt: document.getElementById('step2Prompt').value,
      questionsJson
    });
    showOutput('step2Output', result);
  } catch (error) {
    showOutput('step2Output', error.message, true);
  } finally {
    button.disabled = false;
    button.textContent = '▶ Run Step 2';
  }
}

document.getElementById('runStep1').addEventListener('click', runStep1);
document.getElementById('runStep2').addEventListener('click', runStep2);

load().catch(error => {
  showOutput('step1Output', error.message, true);
  showOutput('step2Output', error.message, true);
});
