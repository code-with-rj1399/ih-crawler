import {loadPrompts,saveExperiencePromptAction,saveQuestionMetadataPromptAction,resetPromptsAction,bindPrompt} from './prompt.js';
import {loadSources,addSeed,disableAllSeeds} from './sources.js';
import {loadQuestions,deleteAllQuestionsAction,showDetails,closeDetails} from './questions.js';
import {runCrawl} from './crawl.js';

const setStatus=message=>{document.getElementById('status').textContent=message};
async function reloadDashboard(){await Promise.all([loadSources(),loadQuestions()])}
window.setStatus=setStatus;
window.reloadDashboard=reloadDashboard;
window.addEventListener('load',async()=>{
  bindPrompt();
  document.getElementById('crawl').addEventListener('click',runCrawl);
  document.getElementById('saveExperiencePrompt').addEventListener('click',saveExperiencePromptAction);
  document.getElementById('saveQuestionMetadataPrompt').addEventListener('click',saveQuestionMetadataPromptAction);
  document.getElementById('resetPrompts').addEventListener('click',resetPromptsAction);
  document.getElementById('addSeed').addEventListener('click',addSeed);
  document.getElementById('disableAllSeeds').addEventListener('click',disableAllSeeds);
  document.getElementById('deleteAllQuestions').addEventListener('click',deleteAllQuestionsAction);
  document.getElementById('closeDetails').addEventListener('click',closeDetails);
  document.getElementById('detailsModal').addEventListener('click',event=>{if(event.target.id==='detailsModal')closeDetails()});
  try{await Promise.all([reloadDashboard(),loadPrompts()])}catch(error){setStatus('Failed to load dashboard: '+error.message)}
});
