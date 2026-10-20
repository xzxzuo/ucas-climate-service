'use strict';
const $=id=>document.getElementById(id);
const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const roles={ADMIN:'管理员',RESEARCHER:'研究人员',VIEWER:'查看人员'};
const statuses={QUEUED:'等待执行',RUNNING:'执行中',SUCCESS:'已完成',FAILED:'失败',INTERRUPTED:'已中断',STAGED:'待导入',IMPORTED:'已导入'};
const phaseNames={WAITING:'等待调度',STARTING:'准备执行',VALIDATING:'校验数据',IMPORTING:'导入观测',CALCULATING:'计算指标',PUBLISHING:'发布结果',EXPORTING:'生成文件',COMPLETE:'完成',FAILED:'失败',INTERRUPTED:'中断'};
const typeNames={IMPORT:'数据导入',ANALYZE:'指标计算',EXPORT:'结果导出'};
const titles={datasets:'数据集工作台',tasks:'任务中心',exports:'导出记录',audit:'操作记录',users:'账号管理'};
const columnLabels={stationCode:'站点编号',temp10:'温度 P10 (°F)',temp90:'温度 P90 (°F)',prcp95:'降水 P95 (in)',prcp99:'降水 P99 (in)',humidity5:'湿度 P5 (比例)',tempSamples:'温度样本数',prcpSamples:'降水样本数',humiditySamples:'湿度样本数',date:'观测日期',year:'年份',metric:'指标',nonzeroDays:'非零天数',validDays:'有效天数',country:'国家',province:'省 / 州',city:'城市',district:'区县',level:'地区层级',avgValue:'均值',minValue:'最小值',maxValue:'最大值',medianValue:'中位数',validStationCount:'有效站点数',avgDays:'平均天数',minDays:'最少天数',maxDays:'最多天数',medianDays:'中位天数',stationCount:'站点数'};
const state={user:null,csrf:null,page:'datasets',datasets:[],dataset:null,detail:null,tab:'overview',clone:null,query:null,offset:0,params:null};
const date=value=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—';
const num=value=>Number(value||0).toLocaleString('zh-CN');
function pill(value){const style=['SUCCESS','IMPORTED'].includes(value)?'success':value==='RUNNING'?'running':['FAILED','INTERRUPTED'].includes(value)?'failed':'queued';return `<span class="pill ${style}">${escape(statuses[value]||value)}</span>`;}
function notice(message,bad=false){$('notice').textContent=message;$('notice').className=`notice${bad?' bad':''}`;}
async function csrf(){const res=await fetch('/platform/api/csrf',{credentials:'same-origin'});if(!res.ok)throw new Error('无法获取登录令牌');state.csrf=await res.json();}
async function api(path,options={}){
  const method=options.method||'GET',headers={...(options.headers||{})};
  if(!['GET','HEAD'].includes(method)){if(!state.csrf)await csrf();headers[state.csrf.headerName]=state.csrf.token;}
  let body=options.body;if(body!==undefined&&!(body instanceof FormData)&&!(body instanceof URLSearchParams)){body=JSON.stringify(body);headers['Content-Type']='application/json';}
  const res=await fetch('/platform/api'+path,{method,headers,body,credentials:'same-origin'});
  const text=await res.text();let data;try{data=JSON.parse(text);}catch{data=text;}
  if(!res.ok){if(res.status===401&&path!=='/login'){showLogin();}throw new Error((data.message||data.error||`请求失败 ${res.status}`)+((data.requestId)?` [${data.requestId.slice(0,8)}]`:''));}return data;
}
function showLogin(){state.user=null;$('app').classList.add('hidden');$('login-screen').classList.remove('hidden');}
function showApp(){ $('login-screen').classList.add('hidden');$('app').classList.remove('hidden');$('account-name').textContent=state.user.displayName;$('account-role').textContent=roles[state.user.role];$('users-nav').classList.toggle('hidden',state.user.role!=='ADMIN');}
function heading(title,description,actions=''){return `<div class="page-heading"><div><h1>${escape(title)}</h1><p class="muted">${escape(description)}</p></div><div class="heading-actions">${actions}</div></div>`;}
function table(headers,rows){return `<div class="table-scroll"><table><thead><tr>${headers.map(h=>`<th>${escape(h)}</th>`).join('')}</tr></thead><tbody>${rows.length?rows.map(row=>`<tr>${row.map(cell=>`<td>${cell}</td>`).join('')}</tr>`).join(''):`<tr><td colspan="${headers.length}" class="empty">暂无记录</td></tr>`}</tbody></table></div>`;}
function datasetButton(id,label='进入数据集'){return `<button data-open-dataset="${id}">${label} →</button>`;}
async function navigate(page){
  state.page=page;state.dataset=null;state.offset=0;$('notice').classList.add('hidden');$('breadcrumb').textContent=titles[page];
  document.querySelectorAll('[data-page]').forEach(b=>b.classList.toggle('active',b.dataset.page===page));
  try{await ({datasets:renderDatasets,tasks:renderTasks,exports:renderExports,audit:renderAudit,users:renderUsers}[page])();window.scrollTo(0,0);}catch(e){notice(e.message,true);}
}
async function renderDatasets(){
  state.datasets=await api('/datasets');const rows=state.datasets,own=rows.filter(d=>d.owner_id===state.user.id&&!d.is_example),observations=rows.reduce((n,d)=>n+Number(d.raw_rows),0);
  $('content').innerHTML=heading('数据集工作台','从观测数据到可复现的分析结果，在这里管理你的研究。',state.user.role!=='VIEWER'?'<button class="primary" data-create>＋ 新建数据集</button>':'')+
    `<div class="metrics"><div class="metric"><span>可访问数据集</span><strong>${rows.length}</strong><small>包含共享数据与只读示例</small></div><div class="metric"><span>我的研究数据集</span><strong>${own.length}</strong><small>独立存储、独立计算与授权</small></div><div class="metric"><span>可访问观测记录</span><strong>${num(observations)}</strong><small>按数据集计数，复制样本会重复计入</small></div></div>`+
    `<div class="section-heading"><h2>研究数据集</h2><small>${rows.length} 个工作空间</small></div><div class="dataset-grid">${rows.map(d=>`<article class="dataset-card"><div class="card-top"><span class="dataset-icon">▦</span>${d.is_example?'<span class="pill example">真实样本 · 只读</span>':`<span class="pill ${d.can_write?'success':'example'}">${d.can_write?'可编辑':'共享只读'}</span>`}</div><h3>${escape(d.name)}</h3><p>${escape(d.description||'尚未填写数据集说明')}</p><div class="card-data"><div><strong>${num(d.raw_rows)}</strong><small>观测记录</small></div><div><strong>${num(d.station_count)}</strong><small>气象站点</small></div><div><strong>${d.active_result_version?'v'+d.active_result_version:'—'}</strong><small>当前结果</small></div></div><div class="card-footer"><span>${escape(d.owner_name)} · ${d.raw_revision?`数据修订 ${d.raw_revision}`:'等待导入'}</span>${datasetButton(d.id)}</div></article>`).join('')}</div>`+
    (!rows.length?'<div class="panel empty">创建第一个数据集，或让管理员完成示例数据初始化。</div>':'')+
    `<div class="panel explanation"><strong>第一次使用？</strong> 先进入“真实气象样本”，查看已有阈值与统计。研究人员可将示例复制为自己的数据集，再体验上传、重算和导出。当前样本只覆盖三个站点和部分年份，不代表完整全球气象数据。</div>`;
}
async function openDataset(id,tab='overview'){
  state.dataset=Number(id);state.tab=tab;state.offset=0;state.detail=await api(`/datasets/${id}`);const d=state.detail.dataset;
  $('breadcrumb').textContent=d.name;let actions='';
  if(d.is_example&&state.user.role!=='VIEWER')actions+=`<button class="primary" data-clone="${d.id}">复制为我的数据集</button>`;
  if(d.can_write)actions+=`<button class="primary" data-analyze="${d.id}">重新计算指标</button>`;
  $('content').innerHTML=`<button class="text-button back" data-page="datasets">← 返回数据集</button>`+heading(d.name,d.description||'研究数据集',actions)+
    `<div class="version-line"><span class="pill ${d.can_write?'success':'example'}">${d.can_write?'可编辑':'只读'}</span><span>数据修订 ${d.raw_revision}</span><span>当前结果 ${d.active_result_version?'v'+d.active_result_version:'尚未计算'}</span><span>时间范围 ${escape(d.first_date||'—')} 至 ${escape(d.last_date||'—')}</span></div>`+
    `<div class="tabs">${[['overview','数据概况'],['files','文件与任务'],['analysis','结果分析'],['members','共享权限']].map(([key,label])=>`<button data-tab="${key}" class="${key===tab?'active':''}">${label}</button>`).join('')}</div><div id="dataset-content"></div>`;
  if(tab==='overview')await renderOverview();if(tab==='files')await renderFiles();if(tab==='analysis')await renderAnalysis();if(tab==='members')renderMembers();
  window.scrollTo(0,0);
}
async function renderOverview(){const d=state.detail.dataset,runs=state.detail.versions,latest=runs.find(r=>r.result_version===d.active_result_version),stations=await api(`/datasets/${d.id}/stations`);
  $('dataset-content').innerHTML=`${latest&&latest.input_revision<d.raw_revision?'<div class="status-note">观测数据已更新，当前仍展示上一次成功发布的结果。请提交计算任务生成新版本。</div>':''}`+
    `<div class="metrics"><div class="metric"><span>原始观测</span><strong>${num(d.raw_rows)}</strong><small>站点与日期联合去重</small></div><div class="metric"><span>已发布逐日指标</span><strong>${latest?num(latest.analysis_rows):'—'}</strong><small>${latest?'结果版本 v'+latest.result_version:'等待首次计算'}</small></div><div class="metric"><span>缺少基准阈值的分析观测</span><strong>${latest?num(latest.missing_threshold_rows):'—'}</strong><small>不把缺失结果当作零值</small></div></div>`+
    `<div class="panel no-padding">${table(['站点编号','站点名称','国家 / 地区','首条观测','末条观测','观测数量'],stations.map(s=>[escape(s.stationCode),escape(s.name),escape([s.country,s.province,s.city].filter(Boolean).join(' / ')),escape(s.firstDate),escape(s.lastDate),num(s.observations)]))}</div>`+
    `<div class="panel"><h2>计算口径</h2><p class="explanation">基准窗口固定为 1973–1992，按站点分别排除缺失值后计算经验分位阈值；1993 年起计算 7 项幅度指标与 6 类天气标记。年度频次是有效观测中的非零天数，不是全年发生概率。温度为华氏度，降水为英寸，湿度为 0–1 比例。</p><p class="explanation">现有观测的最早与最晚日期不代表中间每天都有数据；样本包含不连续年份。平台保留已发布结果版本，不保存完整原始数据的历史快照。</p></div>`+
    `<div class="panel no-padding">${table(['结果版本','输入修订','公式版本','逐日结果','缺失阈值','发布时间'],runs.map(r=>[`v${r.result_version}`,num(r.input_revision),escape(r.formula_version),num(r.analysis_rows),num(r.missing_threshold_rows),date(r.created_at)]))}</div>`;
}
async function renderFiles(){const id=state.dataset,d=state.detail.dataset,[files,tasks]=await Promise.all([api(`/datasets/${id}/files`),api(`/tasks?datasetId=${id}`)]);
  $('dataset-content').innerHTML=(d.can_write?`<form id="upload-form" class="upload-zone"><div><strong>导入新的观测文件</strong><p>UTF-8 · 标准化 13 列 CSV · 最多 8 MiB · 全文件事务导入</p><a href="/platform/api/template.csv">下载空白字段模板 ↗</a></div><div><input type="file" id="csv-file" name="file" accept=".csv" required aria-label="选择气象 CSV 文件"><button class="primary" type="submit">上传并创建任务</button></div></form>`:'<div class="status-note">此数据集为只读。需要体验导入时，可复制示例或申请编辑权限。</div>')+
    '<p class="file-note">上传成功只代表文件已暂存。后台任务成功后观测才会入库；随后需另行计算指标。相同内容在同一数据集内会去重。</p>'+
    `<div class="panel no-padding">${table(['文件名','大小','状态','已导入记录','上传时间'],files.map(f=>[escape(f.original_name),(f.file_size/1024).toFixed(1)+' KiB',pill(f.status),num(f.imported_rows),date(f.created_at)]))}</div>`+
    `<div class="section-heading"><h2>此数据集的任务</h2><button data-refresh-files>刷新</button></div><div class="panel no-padding">${taskTable(tasks)}</div>`;
}
function taskTable(tasks){return table(['任务','类型','数据集','状态','阶段 / 处理数','创建时间','操作'],tasks.map(t=>[escape(t.id.slice(0,8)),escape(typeNames[t.task_type]),escape(t.dataset_name||state.detail?.dataset.name||t.dataset_id),pill(t.status),escape(phaseNames[t.phase]||t.phase)+' / '+num(t.processed_rows),date(t.created_at),`<button data-task="${t.id}">详情</button>`]));}
async function renderTasks(){const tasks=await api('/tasks');$('content').innerHTML=heading('任务中心','查看后台执行进度与失败原因。当前为单 Worker，任务按接收顺序执行。','<button data-refresh>刷新列表</button>')+
  `<div class="metrics"><div class="metric"><span>最近记录中的待执行任务</span><strong>${tasks.filter(t=>t.status==='QUEUED').length}</strong><small>最多展示最近 50 条记录</small></div><div class="metric"><span>执行中</span><strong>${tasks.filter(t=>t.status==='RUNNING').length}</strong><small>导入、计算、导出串行处理</small></div><div class="metric"><span>失败或中断</span><strong>${tasks.filter(t=>['FAILED','INTERRUPTED'].includes(t.status)).length}</strong><small>可在详情中查看原因并手动重试</small></div></div><div class="panel no-padding">${taskTable(tasks)}</div><p class="data-note">本页每 3 秒刷新；记录只展示当前账号有权限访问的数据集。</p>`;}
async function renderExports(){const rows=await api('/exports');$('content').innerHTML=heading('导出记录','文件固定使用提交任务时选择的结果版本；下载时再次检查数据集权限。','<button data-refresh>刷新列表</button>')+
  `<div class="panel no-padding">${table(['数据集','结果版本','行数','大小','生成时间','到期时间','操作'],rows.map(r=>[escape(r.dataset_name),'v'+r.result_version,num(r.row_count),(r.file_size/1024).toFixed(1)+' KiB',date(r.created_at),date(r.expires_at),`<a class="button" href="/platform/api/exports/${r.id}/download">下载 CSV</a>`]))}</div><div class="panel explanation">最多导出 20,000 行。文件默认保留下载资格 7 天，过期后需重新提交任务；本地文件暂不自动清理。导出中包含数据集 ID 与结果版本。</div>`;}
async function renderAudit(){const rows=await api('/audit');$('content').innerHTML=heading('操作记录','记录数据集创建、共享、任务提交、文件下载和账号管理，不记录密码与完整文件内容。')+
  `<div class="panel no-padding">${table(['时间','操作者','操作','数据集','目标'],rows.map(r=>[date(r.created_at),escape(r.actor_name),escape(r.action),r.dataset_id||'平台',escape(r.target_id)]))}</div>`;}
async function renderUsers(){const users=await api('/admin/users');$('content').innerHTML=heading('账号管理','仅管理员可创建账号和启用 / 停用账号。停用后已有会话也无法继续访问业务数据。','<button class="primary" data-add-user>＋ 创建账号</button>')+
  `<div class="panel no-padding">${table(['用户名','显示名称','角色','状态','创建时间','操作'],users.map(u=>[escape(u.username),escape(u.display_name),escape(roles[u.role]),u.enabled?'<span class="pill success">启用</span>':'<span class="pill failed">停用</span>',date(u.created_at),u.id===state.user.id?'当前账号':`<button data-user-enable="${u.id}" data-enabled="${!u.enabled}">${u.enabled?'停用':'启用'}</button>`]))}</div>`;}
function renderMembers(){const d=state.detail.dataset;$('dataset-content').innerHTML=(d.can_manage?`<form id="share-form" class="panel share-form"><label>被授权用户名<input name="username" required placeholder="例如 researcher02"></label><label>权限<select name="permission"><option value="READ">只读访问</option><option value="WRITE">编辑与计算</option><option value="REMOVE">移除授权</option></select></label><button class="primary" type="submit">更新授权</button></form>`:'<div class="status-note">只有数据集所有者或管理员可以调整共享权限。</div>')+
  `<div class="panel no-padding">${table(['用户名','显示名称','数据集权限'],state.detail.members.map(m=>[escape(m.username),escape(m.display_name),m.permission==='WRITE'?'编辑与计算':'只读']))}</div><div class="panel explanation">数据集所有者和管理员具有管理权限；查看人员账号不能获得写权限。只读示例对所有已登录账号开放。任务执行时会重新检查提交者的账号状态和数据集权限。</div>`;}
async function renderAnalysis(){const d=state.detail.dataset,stations=await api(`/datasets/${d.id}/stations`),runs=state.detail.versions;
  if(!runs.length){$('dataset-content').innerHTML='<div class="panel empty">还没有发布的结果。先上传数据并等待导入完成，再点击“重新计算指标”。</div>';return;}
  $('dataset-content').innerHTML=`<form id="query-form" class="panel filters"><label>结果类型<select name="kind"><option value="THRESHOLDS">站点基准阈值</option><option value="DAILY">站点逐日指标</option><option value="STATION_ANNUAL">站点年度频次</option><option value="REGION_DAILY">地区逐日统计</option><option value="REGION_ANNUAL">地区年度统计</option></select></label><label>结果版本<select name="version">${runs.map(r=>`<option value="${r.result_version}">v${r.result_version} · 修订 ${r.input_revision}</option>`).join('')}</select></label><label>站点<select name="station">${stations.map(s=>`<option value="${s.stationCode}">${escape(s.name)} · ${s.stationCode}</option>`).join('')}</select></label><label>起始日期<input name="from" type="date" value="1993-01-01"></label><label>结束日期（不含）<input name="to" type="date" value="2024-01-01"></label><label>分析年份<input name="year" type="number" min="1993" max="2100" value="1993"></label><label>地区统计日期<input name="date" type="date" value="1993-01-01"></label><label>地区层级<select name="level"><option>COUNTRY</option><option>PROVINCE</option><option>CITY</option><option>DISTRICT</option></select></label><label>地区指标<select name="metric">${['HTD','LTD','HR','EHR','LH','HTD1','CTD1','RAIN','SNOW','FOG','HAIL','THUNDER','TORNADO'].map(m=>`<option>${m}</option>`).join('')}</select></label><label>国家（可空）<input name="country" maxlength="96" placeholder="例如 中国"></label><div class="form-actions"><button class="primary" type="submit">查询结果</button><button type="button" data-export-query>导出</button></div></form><p class="meta-line">查询字段随结果类型生效。逐日日期区间为 [起始, 结束)，地区逐日仅计算 7 项幅度指标。</p><div id="query-result"></div>`;
  syncQueryFields();await runQuery();
}
function syncQueryFields(){const form=$('query-form');if(!form)return;const kind=form.elements.kind.value,fields={THRESHOLDS:['kind','version'],DAILY:['kind','version','station','from','to'],STATION_ANNUAL:['kind','version','station','year'],REGION_DAILY:['kind','version','date','level','metric','country'],REGION_ANNUAL:['kind','version','year','level','metric','country']}[kind];
  for(const label of form.querySelectorAll('label')){const control=label.querySelector('input,select');const visible=fields.includes(control.name);label.classList.toggle('hidden',!visible);control.disabled=!visible;}
  for(const option of form.elements.metric.options)option.disabled=kind==='REGION_DAILY'&&['RAIN','SNOW','FOG','HAIL','THUNDER','TORNADO'].includes(option.value);
  if(form.elements.metric.selectedOptions[0]?.disabled)form.elements.metric.value='HTD';
}
async function runQuery(){const form=$('query-form');state.params=Object.fromEntries(new FormData(form));const qs=new URLSearchParams({...state.params,limit:'200',offset:String(state.offset)}),data=await api(`/datasets/${state.dataset}/analysis?${qs}`);state.query=data;
  const columns=data.items.length?Object.keys(data.items[0]):[];
  $('query-result').innerHTML=`<div class="panel"><div class="section-heading"><h2>结果 v${data.resultVersion}</h2><small>共 ${num(data.total)} 条 · 当前页 ${data.items.length} 条</small></div><div id="chart" class="chart"></div><p class="chart-label" id="chart-label"></p></div><div class="panel no-padding">${table(columns.map(c=>columnLabels[c]||c),data.items.map(row=>columns.map(c=>row[c]===null?'<span class="muted">缺失</span>':escape(row[c]))))}<div class="pagination"><button data-query-page="prev" ${state.offset===0?'disabled':''}>上一页</button><span>第 ${state.offset/200+1} 页</span><button data-query-page="next" ${state.offset+200>=data.total?'disabled':''}>下一页</button></div></div>`;
  drawChart(data);
}
function drawChart(data){const host=$('chart'),label=$('chart-label'),kind=data.parameters.kind,rows=data.items;
  if(!rows.length){host.innerHTML='<div class="empty">此条件下没有结果。请检查日期、地区和基准样本是否足够。</div>';return;}
  if(kind==='THRESHOLDS'){host.innerHTML='<div class="empty">阈值包含不同单位，请使用下表核对。选择逐日、年度或地区结果查看图形。</div>';label.textContent='TEMP 为 °F，PRCP 为英寸，HUMIDITY 为 0–1 比例。不同量纲不混绘。';return;}
  const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');svg.setAttribute('viewBox','0 0 900 240');svg.setAttribute('role','img');svg.setAttribute('aria-label','当前页气象分析结果图');host.replaceChildren(svg);
  const daily=kind==='DAILY',field=daily?'htd':kind==='STATION_ANNUAL'?'nonzeroDays':kind==='REGION_DAILY'?'medianValue':'medianDays';
  const values=rows.map(r=>r[field]===null?null:Number(r[field])),valid=values.filter(v=>v!==null&&Number.isFinite(v));
  if(!valid.length){host.innerHTML='<div class="empty">当前指标均为缺失值。</div>';return;}
  const low=Math.min(0,...valid),high=Math.max(1,...valid),scale=v=>205-(v-low)/(high-low)*175;
  function element(tag,attrs,text){const node=document.createElementNS(ns,tag);for(const [k,v]of Object.entries(attrs))node.setAttribute(k,String(v));if(text!==undefined)node.textContent=text;svg.append(node);return node;}
  for(let i=0;i<4;i++){const v=low+(high-low)*i/3,y=scale(v);element('line',{x1:55,x2:880,y1:y,y2:y,class:'axis'});element('text',{x:7,y:y+4},v.toFixed(1));}
  if(daily){const times=rows.map(r=>Date.parse(r.date)),min=Math.min(...times),max=Math.max(...times);let path='',previous=null;
    values.forEach((v,i)=>{if(v===null||!Number.isFinite(v)){previous=null;return;}const x=55+(times[i]-min)/(max-min||1)*825,y=scale(v);const continuous=previous!==null&&times[i]-previous<=86400000;path+=(continuous?'L':'M')+x.toFixed(2)+','+y.toFixed(2)+' ';element('circle',{cx:x,cy:y,r:2,fill:'#228c7b'});previous=times[i];});
    element('path',{d:path,class:'series'});element('text',{x:55,y:231},String(rows[0].date));element('text',{x:795,y:231},String(rows.at(-1).date));label.textContent='当前页 HTD（高温幅度，°F 温差）。缺失值和不连续观测日期会断开，不补零，不代表完整连续年份。';
  }else{const shown=rows.slice(0,30),width=825/shown.length;shown.forEach((r,i)=>{if(r[field]===null)return;const value=Number(r[field]),y=scale(value);element('rect',{x:55+i*width+width*.15,y,width:width*.7,height:Math.max(0,205-y),class:'bar'});element('text',{x:55+i*width+width*.2,y:227},String(kind==='STATION_ANNUAL'?r.metric:r.city||r.province||r.country).slice(0,8));});label.textContent=kind==='STATION_ANNUAL'?'各指标非零观测天数，不是全年发生概率；有效天数见下表。':`${data.parameters.metric} 的地区中位数，最多绘制当前页前 30 项；详细值与有效站点数量见表格。`;}
}
async function showTask(id){const task=await api(`/tasks/${id}`);$('task-json').textContent=JSON.stringify(task,null,2);$('task-actions').innerHTML=(['FAILED','INTERRUPTED'].includes(task.status)?`<button class="primary" data-retry="${id}">重试任务</button>`:'')+(task.export?`<a class="button" href="/platform/api/exports/${task.export.id}/download">下载导出文件</a>`:'');$('task-dialog').showModal();}
function openCreate(clone=null){state.clone=clone;$('create-form').reset();$('create-title').textContent=clone?'复制示例数据集':'新建数据集';$('description-label').classList.toggle('hidden',Boolean(clone));$('create-dialog').showModal();}
document.addEventListener('click',async event=>{const b=event.target.closest('button,[data-page]');if(!b)return;
  try{
    if(b.dataset.close){$(b.dataset.close).close();return;}
    if(b.dataset.page){await navigate(b.dataset.page);return;}
    if(b.hasAttribute('data-create')){openCreate();return;}
    if(b.dataset.clone){openCreate(b.dataset.clone);return;}
    if(b.dataset.openDataset){await openDataset(b.dataset.openDataset);return;}
    if(b.dataset.tab){await openDataset(state.dataset,b.dataset.tab);return;}
    if(b.dataset.analyze){if(!confirm('为当前数据集创建新的计算任务？已有成功结果会保留。'))return;const t=await api(`/datasets/${b.dataset.analyze}/analyze`,{method:'POST'});notice('计算任务已提交：'+t.id.slice(0,8));await openDataset(state.dataset,'files');return;}
    if(b.hasAttribute('data-refresh')){await navigate(state.page);return;}
    if(b.hasAttribute('data-refresh-files')){await openDataset(state.dataset,'files');return;}
    if(b.dataset.task){await showTask(b.dataset.task);return;}
    if(b.dataset.retry){const t=await api(`/tasks/${b.dataset.retry}/retry`,{method:'POST'});$('task-dialog').close();notice('重试任务已提交：'+t.id.slice(0,8));if(state.dataset)await openDataset(state.dataset,'files');else await renderTasks();return;}
    if(b.hasAttribute('data-add-user')){$('user-form').reset();$('user-dialog').showModal();return;}
    if(b.dataset.userEnable){await api(`/admin/users/${b.dataset.userEnable}/enabled`,{method:'PATCH',body:{enabled:b.dataset.enabled==='true'}});await renderUsers();return;}
    if(b.dataset.queryPage){state.offset=Math.max(0,state.offset+(b.dataset.queryPage==='next'?200:-200));await runQuery();return;}
    if(b.hasAttribute('data-export-query')){const parameters=Object.fromEntries(new FormData($('query-form'))),t=await api(`/datasets/${state.dataset}/exports`,{method:'POST',body:parameters});notice('导出任务已提交：'+t.id.slice(0,8)+'。完成后可在“导出记录”下载。');}
  }catch(e){notice(e.message,true);}
});
document.addEventListener('submit',async event=>{if(event.target.id==='login-form')return;event.preventDefault();const form=event.target,submit=form.querySelector('[type=submit]');if(submit)submit.disabled=true;
  try{
    if(form.id==='create-form'){const data=Object.fromEntries(new FormData(form)),d=await api(state.clone?`/datasets/${state.clone}/clone`:'/datasets',{method:'POST',body:data});$('create-dialog').close();notice('数据集已创建');await openDataset(d.id);}
    if(form.id==='user-form'){await api('/admin/users',{method:'POST',body:Object.fromEntries(new FormData(form))});form.reset();$('user-dialog').close();await renderUsers();notice('账号已创建，请通过私密渠道向使用者交付初始密码。');}
    if(form.id==='upload-form'){const data=new FormData(form),result=await api(`/datasets/${state.dataset}/files`,{method:'POST',body:data});notice(result.duplicate?'检测到相同内容，未重复创建导入任务。已有失败任务可在详情中重试。':'文件已暂存，后台导入任务已创建。导入成功后请再计算指标。');await openDataset(state.dataset,'files');}
    if(form.id==='share-form'){await api(`/datasets/${state.dataset}/members`,{method:'POST',body:Object.fromEntries(new FormData(form))});await openDataset(state.dataset,'members');notice('数据集授权已更新。');}
    if(form.id==='query-form'){state.offset=0;await runQuery();}
  }catch(e){notice(e.message,true);}finally{if(submit)submit.disabled=false;}
});
document.addEventListener('change',event=>{if(event.target.closest('#query-form')&&event.target.name==='kind')syncQueryFields();});
$('login-form').addEventListener('submit',async event=>{event.preventDefault();const button=event.target.querySelector('button');button.disabled=true;$('login-error').textContent='';try{await csrf();await api('/login',{method:'POST',body:new URLSearchParams({username:$('username').value,password:$('password').value})});$('password').value='';await csrf();state.user=await api('/me');showApp();await navigate('datasets');}catch(e){$('login-error').textContent=e.message;}finally{button.disabled=false;}});
$('logout').addEventListener('click',async()=>{try{await api('/logout',{method:'POST'});state.csrf=null;state.dataset=null;$('content').replaceChildren();showLogin();await csrf();}catch(e){notice(e.message,true);}});
setInterval(()=>{if(state.user&&state.page==='tasks'&&!state.dataset&&!document.querySelector('dialog[open]'))renderTasks().catch(e=>notice(e.message,true));},3000);
(async()=>{try{await csrf();state.user=await api('/me');showApp();await navigate('datasets');}catch{showLogin();}})();
