import {deleteAllQuestions,getExperiences,getExperienceQuestions} from './api.js';
import {esc,formatQuestionType,questionTypeList,markdownToHtml} from './utils.js';
let experienceRows=[];
let questionRows=[];

export async function loadQuestions(){
  experienceRows=await getExperiences();
  questionRows=[];
  renderExperiences(experienceRows);
}

export async function deleteAllQuestionsAction(){if(!confirm('Delete all questions from DynamoDB? This cannot be undone.'))return;try{await deleteAllQuestions();window.setStatus('All questions deleted');await loadQuestions()}catch(error){window.setStatus('Failed to delete questions: '+error.message)}}

export function showDetails(index){
  const x=questionRows[index]||{};
  document.getElementById('detailsQuestion').textContent=x.questionText||'Question';
  document.getElementById('detailsDescription').innerHTML=x.questionDescription?markdownToHtml(x.questionDescription):'<p>No description available.</p>';
  document.getElementById('detailsModal').classList.add('open');
}

export function closeDetails(){document.getElementById('detailsModal').classList.remove('open')}

function renderQuestionTypes(type){
  const types=questionTypeList(type);
  if(!types.length)return '—';
  return types.map(item=>'<span class="badge kind">'+esc(formatQuestionType(item))+'</span>').join(' ');
}

function renderExperiences(rows){
  const element=document.getElementById('questions');
  if(!rows.length){element.innerHTML='<div class="empty">No interview experiences have been stored yet.</div>';return}
  let html='<div class="experience-list">';
  rows.forEach((x,index)=>{
    const id='experience-'+x.id;
    const posted=x.postedAt||'—';
    const author=x.author||'anonymous';
    const source=x.sourcePlatform||'—';
    const company=x.company||'—';
    const count=x.questionCount==null?'—':x.questionCount;
    html+='<div class="experience-card"><button class="experience-header" data-experience-id="'+esc(String(x.id))+'"><span class="expand-icon">▶</span><span class="experience-main"><strong>'+esc(x.title||'Interview experience')+'</strong><span class="experience-meta">'+esc(company)+' · '+esc(source)+' · '+esc(author)+' · '+esc(posted)+'</span></span><span class="badge">'+esc(String(count))+' question'+(count===1?'':'s')+'</span></button><div id="'+id+'" class="experience-questions"><div class="experience-loading">Click to load questions.</div></div></div>';
  });
  html+='</div>';
  element.innerHTML=html;
  element.querySelectorAll('[data-experience-id]').forEach(button=>button.addEventListener('click',()=>toggleExperience(button.dataset.experienceId,button)));
}

async function toggleExperience(experienceId,button){
  const panel=document.getElementById('experience-'+experienceId);
  const open=panel.classList.toggle('open');
  button.classList.toggle('open',open);
  if(!open||panel.dataset.loaded==='true')return;
  panel.innerHTML='<div class="experience-loading">Loading questions...</div>';
  try{
    const questions=await getExperienceQuestions(experienceId);
    questionRows.push(...questions);
    panel.dataset.loaded='true';
    renderExperienceQuestions(panel,questions);
  }catch(error){
    panel.innerHTML='<div class="empty">Failed to load questions: '+esc(error.message)+'</div>';
  }
}

function renderExperienceQuestions(panel,questions){
  const experience=experienceRows.find(x=>String(x.id)===panel.id.replace('experience-',''))||{};
  const summary=experience.summary||'';
  const sourceLink=experience.originalPostUrl?'<a href="'+esc(experience.originalPostUrl)+'" target="_blank" rel="noopener">Open interview experience ↗</a>':'';
  let html='<div class="experience-summary"><div class="details-label">Summary</div><div class="summary-markdown">'+(summary?markdownToHtml(summary):'No summary available.')+'</div></div><div class="experience-source">'+sourceLink+'</div>';
  if(!questions.length){panel.innerHTML=html+'<div class="empty">No questions were extracted for this experience.</div>';return}
  html+='<table><thead><tr><th>Question</th><th>Type</th><th>Confidence</th><th>Granularity</th><th>Details</th><th>Links</th></tr></thead><tbody>';
  for(const q of questions){
    const globalIndex=questionRows.indexOf(q);
    html+='<tr><td class="question">'+esc(q.questionText||'—')+'</td><td class="question-types">'+renderQuestionTypes(q.questionType)+'</td><td><span class="badge">'+(q.confidence==null?'—':Number(q.confidence).toFixed(2))+'</span></td><td><span class="badge">'+(q.questionGranularity==null?'—':Number(q.questionGranularity).toFixed(2))+'</span></td><td><button class="secondary" data-question-details="'+globalIndex+'">Details</button></td><td>'+(q.problemUrl?'<a href="'+esc(q.problemUrl)+'" target="_blank" rel="noopener">Problem ↗</a>':'—')+'</td></tr>';
  }
  html+='</tbody></table>';
  panel.innerHTML=html;
  panel.querySelectorAll('[data-question-details]').forEach(button=>button.addEventListener('click',()=>showDetails(Number(button.dataset.questionDetails))));
}
