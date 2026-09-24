export const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const estimateTokens=text=>text&&text.trim()?Math.ceil(text.length/4):0;
export const formatQuestionType=type=>({SYSTEM_DESIGN:'System Design',CODING:'Coding',DATABASE:'Database',LLD:'LLD',CLOUD:'Cloud',SECURITY:'Security',DEVOPS:'DevOps',AI_ML:'AI/ML',DATA_ENGINEERING:'Data Engineering',DISTRIBUTED_SYSTEMS:'Distributed Systems',NETWORKING:'Networking',OPERATING_SYSTEMS:'Operating Systems',PROGRAMMING_LANGUAGE:'Programming Language',WEB_FRONTEND:'Web Frontend',MOBILE:'Mobile',TESTING:'Testing',TECHNICAL_CONCEPT:'Technical Concept'}[type]||type||'—');

const inlineMarkdown=value=>value
  .replace(/`([^`]+)`/g,'<code>$1</code>')
  .replace(/\*\*([^*]+)\*\*/g,'<strong>$1</strong>')
  .replace(/\*([^*]+)\*/g,'<em>$1</em>')
  .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,'<a href="$2" target="_blank" rel="noopener">$1</a>');

export const markdownToHtml=markdown=>{
  if(markdown==null||String(markdown).trim()==='')return '';
  const normalized=String(markdown).replace(/\\n/g,'\n').replace(/\r\n/g,'\n');
  const lines=normalized.split('\n');
  const html=[];let paragraph=[];let listType=null;let listItems=[];
  const flushParagraph=()=>{if(!paragraph.length)return;html.push('<p>'+inlineMarkdown(esc(paragraph.join(' ')))+'</p>');paragraph=[]};
  const flushList=()=>{if(!listItems.length)return;html.push('<'+listType+'>'+listItems.map(item=>'<li>'+inlineMarkdown(esc(item))+'</li>').join('')+'</'+listType+'>');listItems=[];listType=null};
  for(const rawLine of lines){
    const line=rawLine.trim();
    if(!line){flushParagraph();flushList();continue;}
    const heading=line.match(/^(#{1,3})\s+(.+)$/);
    if(heading){flushParagraph();flushList();const level=heading[1].length;html.push('<h'+level+'>'+inlineMarkdown(esc(heading[2]))+'</h'+level+'>');continue;}
    const bullet=line.match(/^[-*]\s+(.+)$/);
    const ordered=line.match(/^\d+[.)]\s+(.+)$/);
    if(bullet||ordered){flushParagraph();const type=bullet?'ul':'ol';if(listType&&listType!==type)flushList();listType=type;listItems.push((bullet||ordered)[1]);continue;}
    flushList();paragraph.push(line);
  }
  flushParagraph();flushList();
  return html.join('');
};
