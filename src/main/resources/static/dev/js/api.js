export async function getJson(url){const response=await fetch(url);if(!response.ok)throw new Error(`HTTP ${response.status}`);return response.json()}
export async function sendJson(url,options={}){const response=await fetch(url,{headers:{'Content-Type':'application/json',...(options.headers||{})},...options});const data=await response.json().catch(()=>({}));if(!response.ok)throw new Error(data.message||`HTTP ${response.status}`);return data}
export const getSources=()=>getJson('/dev/api/sources');
export const getQuestions=()=>getJson('/dev/api/questions');
export const getConfig=()=>getJson('/dev/api/config');
export const getPrompts=()=>getJson('/dev/api/prompts');
export const updateExperiencePrompt=prompt=>sendJson('/dev/api/prompts/experience',{method:'PUT',body:JSON.stringify({prompt})});
export const updateQuestionMetadataPrompt=prompt=>sendJson('/dev/api/prompts/question-metadata',{method:'PUT',body:JSON.stringify({prompt})});
export const resetPrompts=()=>sendJson('/dev/api/prompts/reset',{method:'POST'});
export const addSource=payload=>sendJson('/dev/api/sources',{method:'POST',body:JSON.stringify(payload)});
export const disableAllSources=()=>sendJson('/dev/api/sources/disable-all',{method:'POST'});
export const deleteAllQuestions=()=>sendJson('/dev/api/questions',{method:'DELETE'});
export const setSourceEnabled=(id,enabled)=>sendJson(`/dev/api/sources/${id}/enabled?enabled=${enabled}`,{method:'PATCH'});
export const runCrawl=()=>sendJson('/dev/api/crawl',{method:'POST'});
