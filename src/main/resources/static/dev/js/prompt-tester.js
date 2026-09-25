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

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function renderInlineMarkdown(value) {
  let html = escapeHtml(value);
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/__([^_]+)__/g, '<strong>$1</strong>');
  html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
  html = html.replace(/_([^_]+)_/g, '<em>$1</em>');
  return html;
}

function renderMarkdown(markdown) {
  if (!markdown) return '';

  const lines = String(markdown).replace(/\r\n?/g, '\n').split('\n');
  const output = [];
  let paragraph = [];
  let listType = null;
  let listItems = [];

  const flushParagraph = () => {
    if (paragraph.length === 0) return;
    output.push(`<p>${paragraph.map(renderInlineMarkdown).join('<br>')}</p>`);
    paragraph = [];
  };

  const flushList = () => {
    if (!listType) return;
    const tag = listType === 'ol' ? 'ol' : 'ul';
    output.push(`<${tag}>${listItems.map(item => `<li>${renderInlineMarkdown(item)}</li>`).join('')}</${tag}>`);
    listType = null;
    listItems = [];
  };

  for (const line of lines) {
    const trimmed = line.trim();

    if (!trimmed) {
      flushParagraph();
      flushList();
      continue;
    }

    const heading = trimmed.match(/^(#{1,6})\s+(.+)$/);
    if (heading) {
      flushParagraph();
      flushList();
      const level = heading[1].length;
      output.push(`<h${level}>${renderInlineMarkdown(heading[2])}</h${level}>`);
      continue;
    }

    const bullet = trimmed.match(/^[-*+]\s+(.+)$/);
    if (bullet) {
      flushParagraph();
      if (listType !== 'ul') {
        flushList();
        listType = 'ul';
      }
      listItems.push(bullet[1]);
      continue;
    }

    const ordered = trimmed.match(/^\d+\.\s+(.+)$/);
    if (ordered) {
      flushParagraph();
      if (listType !== 'ol') {
        flushList();
        listType = 'ol';
      }
      listItems.push(ordered[1]);
      continue;
    }

    flushList();
    paragraph.push(trimmed);
  }

  flushParagraph();
  flushList();
  return output.join('');
}

function showSummary(value, error = false) {
  const element = document.getElementById('step1Summary');
  element.classList.toggle('output-error', error);
  if (error) {
    element.textContent = `ERROR\n${value}`;
    return;
  }

  const summary = value?.experience?.summary;
  if (!summary) {
    element.textContent = 'No summary returned.';
    return;
  }

  element.innerHTML = renderMarkdown(summary);
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
    showSummary(result);
    showOutput('step1Output', result);
  } catch (error) {
    showSummary(error.message, true);
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
  showSummary(error.message, true);
  showOutput('step1Output', error.message, true);
  showOutput('step2Output', error.message, true);
});
