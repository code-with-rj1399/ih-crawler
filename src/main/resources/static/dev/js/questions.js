import {deleteAllQuestions,getQuestions} from './api.js';
import {esc,formatQuestionType,questionTypeList,markdownToHtml} from './utils.js';
let questionRows=[];

export async function loadQuestions(){const rows=await getQuestions();questionRows=rows;renderQuestions(rows)}

export async function deleteAllQuestionsAction(){if(!confirm('Delete all questions from DynamoDB? This cannot be undone.'))return;try{await deleteAllQuestions();window.setStatus('All questions deleted');await loadQuestions()}catch{window.setStatus('Failed to delete questions')}}

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

function renderQuestions(rows){
  const element=document.getElementById('questions');
  const validRows=rows.filter(row=>row.experienceTitle&&String(row.experienceTitle).trim());
  if(!validRows.length){element.innerHTML='<div class="empty">No interview experiences with extracted questions have been stored yet.</div>';return}
  const groups=new Map();
  validRows.forEach((row,index)=>{const key=row.originalPostUrl||row.experienceTitle||row.id||index;if(!groups.has(key))groups.set(key,{meta:row,questions:[]});groups.get(key).questions.push({...row,_index:questionRows.indexOf(row)})});
  let html='<div class="experience-list">';
  for(const group of groups.values()){
    const x=group.meta,title=x.experienceTitle,summary=x.experienceSummary||'',posted=x.experiencePostedAt||x.postDate||'—',author=x.experienceAuthor||'anonymous',source=x.sourcePlatform||'—',company=x.company||'—',id='experience-'+Math.random().toString(36).slice(2);
    html+='<div class="experience-card"><button class="experience-header" data-experience-id="'+id+'"><span class="expand-icon">▶</span><span class="experience-main"><strong>'+esc(title)+'</strong><span class="experience-meta">'+esc(company)+' · '+esc(source)+' · '+esc(author)+' · '+esc(posted)+'</span></span><span class="badge">'+group.questions.length+' question'+(group.questions.length===1?'':'s')+'</span></button><div id="'+id+'" class="experience-questions"><div class="experience-summary"><div class="details-label">Summary</div><div class="summary-markdown">'+(summary?markdownToHtml(summary):'No summary available.')+'</div></div><div class="experience-source">'+(x.originalPostUrl?'<a href="'+esc(x.originalPostUrl)+'" target="_blank" rel="noopener">Open interview experience ↗</a>':'')+'</div><table><thead><tr><th>Question</th><th>Type</th><th>Topics</th><th>Confidence</th><th>Granularity</th><th>Details</th><th>Links</th></tr></thead><tbody>';
    for(const q of group.questions)html+='<tr><td class="question">'+esc(q.questionText||'—')+'</td><td class="question-types">'+renderQuestionTypes(q.questionType)+'</td><td>'+(q.topics||[]).map(topic=>'<span class="pill">'+esc(topic)+'</span>').join('')+'</td><td><span class="badge">'+(q.confidence==null?'—':Number(q.confidence).toFixed(2))+'</span></td><td><span class="badge">'+(q.questionGranularity==null?'—':Number(q.questionGranularity).toFixed(2))+'</span></td><td><button class="secondary" data-question-details="'+q._index+'">Details</button></td><td>'+(q.problemUrl?'<a href="'+esc(q.problemUrl)+'" target="_blank" rel="noopener">Problem ↗</a>':'—')+'</td></tr>';
    html+='</tbody></table></div></div>';
  }
  html+='</div>';element.innerHTML=html;
  element.querySelectorAll('[data-experience-id]').forEach(button=>button.addEventListener('click',()=>toggleExperience(button.dataset.experienceId,button)));
  element.querySelectorAll('[data-question-details]').forEach(button=>button.addEventListener('click',()=>showDetails(Number(button.dataset.questionDetails))));
}

function toggleExperience(id,button){const panel=document.getElementById(id),open=panel.classList.toggle('open');button.classList.toggle('open',open)}
