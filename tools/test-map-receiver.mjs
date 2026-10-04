import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdir,readFile,writeFile,readdir} from 'node:fs/promises';
import {resolve,dirname,join} from 'node:path';
import {fileURLToPath} from 'node:url';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const receiver=join(root,'tools','receive-maps.ps1');
assert.deepEqual(await readFile(receiver),await readFile(join(root,'app','src','main','assets','receive-maps.ps1')));
const digest=b=>createHash('sha256').update(b).digest('hex');
const pageSource=await readFile(join(root,'app','src','main','java','com','corefilter','farmer','maps','MapTransferPage.java'),'utf8');
const shaCode=pageSource.slice(pageSource.indexOf('function sha256('),pageSource.indexOf('async function refresh('));
const browserDigest=Function(shaCode+'; return sha256;')();
for(const data of [Buffer.alloc(0),Buffer.from('abc'),Buffer.alloc(63,255),Buffer.alloc(64,42),Buffer.alloc(10001,7)])assert.equal(browserDigest(data),digest(data));
console.log('PASS 5 browser checksum vectors, including the plain HTTP fallback.');
const token='a'.repeat(43);
// Generated empty ZIP; no supplied gameplay images or recordings are read.
const zip=Buffer.from('504b05060000000000000000000000000000000000000000','hex');
for(const mode of ['valid','corrupt','truncated','already-saved','collision','bad-metadata']) {
  const folder=join(root,'analysis','receiver-tests',`${mode}-${Date.now()}`);await mkdir(folder,{recursive:true});
  const id='run-receiver-test',target=join(folder,`${id}.zip`),hash=digest(zip);
  const original=Buffer.from('a different existing file');
  if(mode==='already-saved')await writeFile(target,zip);
  if(mode==='collision')await writeFile(target,original);
  let downloads=0,acks=0,receiptAfterSave=false;
  const server=createServer(async(req,res)=>{
    if(req.url===`/${token}/api/maps`){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({maps:[{id:mode==='bad-metadata'?'../escape':id,sha256:hash,bytes:zip.length}]}));return;}
    if(req.url===`/${token}/download/${id}`){downloads++;res.end(mode==='corrupt'?Buffer.alloc(zip.length,42):mode==='truncated'?zip.subarray(0,zip.length-2):zip);return;}
    if(req.url===`/${token}/api/ack`&&req.method==='POST'){
      let body='';for await(const b of req)body+=b;
      const receipt=JSON.parse(body);assert.equal(req.headers.authorization,`Bearer ${token}`);
      assert.equal(receipt.sha256,hash);assert.equal(receipt.id,id);
      receiptAfterSave=digest(await readFile(target))===hash;acks++;
      res.setHeader('Content-Type','application/json');res.end(JSON.stringify({deleted:true,id}));return;
    }
    res.writeHead(404);res.end();
  });
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const base=`http://127.0.0.1:${server.address().port}/${token}/`;
  const child=spawn('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',receiver,'-BaseUrl',base,'-OutputDirectory',folder,'-NoPause'],{windowsHide:true,stdio:['ignore','pipe','pipe']});
  let output='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);
  const timeout=setTimeout(()=>child.kill(),30000);
  const status=await new Promise((r,j)=>{child.on('error',j);child.on('exit',r);});clearTimeout(timeout);
  await new Promise(r=>server.close(r));
  const successful=mode==='valid'||mode==='already-saved';
  assert.equal(status,successful?0:1,`${mode}: ${output}`);assert.equal(acks,successful?1:0,mode);
  if(successful){assert.equal(receiptAfterSave,true);assert.deepEqual(await readFile(target),zip);}
  if(mode==='already-saved'||mode==='collision'||mode==='bad-metadata')assert.equal(downloads,0,mode);
  if(mode==='collision')assert.deepEqual(await readFile(target),original);
  assert.equal((await readdir(folder)).filter(x=>x.includes('.partial')).length,0,mode);
  console.log(`PASS ${mode}: ${successful?'saved bytes verified before receipt':'no receipt; phone copy retained'}`);
}
console.log('6 receiver integration scenarios passed.');
