import {getPrompts,updateExperiencePrompt,updateQuestionMetadataPrompt,resetPrompts,getConfig} from './api.js';
import {estimateTokens} from './utils.js';

function setTokenCount(id,countId){const n=estimateTokens(document.getElementById(id).value);document.getElementById(countId).textContent='Estimated tokens: '+n.toLocaleString()}
function refreshCounts(){setTokenCount('experiencePrompt','experiencePromptTokenCount');setTokenCount('questionMetadataPrompt','questionMetadataPromptTokenCount')}

export async function loadPrompts(){
  try{
    const [prompts,config]=await Promise.all([getPrompts(),getConfig()]);
    document.getElementById('experiencePrompt').value=prompts.experiencePrompt||'';
    document.getElementById('questionMetadataPrompt').value=prompts.questionMetadataPrompt||'';
    document.getElementById('modelName').textContent=config.model||'gpt-5-nano';
    document.getElementById('reasoningName').textContent=config.reasoningEffort||'low';
    refreshCounts();
  }catch(error){document.getElementById('promptStatus').textContent='Failed to load prompts: '+error.message}
}

export async function saveExperiencePromptAction(){
  const prompt=document.getElementById('experiencePrompt').value;
  if(!prompt.trim()){document.getElementById('experiencePromptStatus').textContent='Prompt cannot be empty';return}
  try{const data=await updateExperiencePrompt(prompt);document.getElementById('experiencePrompt').value=data.prompt||prompt;refreshCounts();document.getElementById('experiencePromptStatus').textContent='Saved'}catch(error){document.getElementById('experiencePromptStatus').textContent='Failed: '+error.message}
}

export async function saveQuestionMetadataPromptAction(){
  const prompt=document.getElementById('questionMetadataPrompt').value;
  if(!prompt.trim()){document.getElementById('questionMetadataPromptStatus').textContent='Prompt cannot be empty';return}
  try{const data=await updateQuestionMetadataPrompt(prompt);document.getElementById('questionMetadataPrompt').value=data.prompt||prompt;refreshCounts();document.getElementById('questionMetadataPromptStatus').textContent='Saved'}catch(error){document.getElementById('questionMetadataPromptStatus').textContent='Failed: '+error.message}
}

export async function resetPromptsAction(){
  if(!confirm('Reset both extraction prompts to their bundled defaults?'))return;
  try{const data=await resetPrompts();document.getElementById('experiencePrompt').value=data.experiencePrompt||'';document.getElementById('questionMetadataPrompt').value=data.questionMetadataPrompt||'';refreshCounts();document.getElementById('promptStatus').textContent='Both prompts reset'}catch(error){document.getElementById('promptStatus').textContent='Failed to reset prompts: '+error.message}
}

export function bindPrompt(){
  document.getElementById('experiencePrompt').addEventListener('input',refreshCounts);
  document.getElementById('questionMetadataPrompt').addEventListener('input',refreshCounts);
}
