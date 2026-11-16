'use strict';
const {test,after} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.MARILIANA_KEY = 'test-secret';
process.env.MARILIANA_DATA = fs.mkdtempSync(path.join(os.tmpdir(),'mariliana-test-'));
const {apply,snapshot,db,server} = require('../server');
after(()=>{db.close();fs.rmSync(process.env.MARILIANA_DATA,{recursive:true,force:true})});
const product = {id:'product-1234',name:'Agua',barcode:'1234567890123',quantity:10,minimum:2,unit:'botella'};
const op = (id,type,fields={})=>({id,type,at:'2026-01-02T12:00:00.000Z',actor:'Ana',...fields});
test('inventario compartido, idempotencia, conflictos y borrado independiente',()=>{
 assert.equal(apply(op('operation-create','create',{product})).ok,true);
 const sale=op('operation-sale','adjust',{productId:product.id,delta:-6,reason:'Caja'});
 assert.equal(apply(sale).ok,true);
 assert.equal(apply(sale).ok,true);
 assert.equal(snapshot().products[0].quantity,4, 'reintentar no duplica la salida');
 const conflict=apply(op('operation-conflict','adjust',{productId:product.id,delta:-6}));
 assert.equal(conflict.ok,false);assert.match(conflict.error,/suficientes/);
 assert.equal(snapshot().products[0].quantity,4,'nunca hay stock negativo');
 assert.equal(apply(op('operation-clear','clearHistory',{day:'2026-01-02',start:'2026-01-02T00:00:00.000Z',end:'2026-01-03T00:00:00.000Z'})).ok,true);
 const data=snapshot();assert.equal(data.products[0].quantity,4);assert.equal(data.history.length,1);assert.equal(data.history[0].reason,'Inventario inicial');
 assert.equal(apply(sale).ok,true,'la recepción se conserva incluso si se borró el historial');assert.equal(snapshot().products[0].quantity,4);
});
test('códigos de barra únicos y edición que no cambia el stock',()=>{
 const duplicate=apply(op('operation-other','create',{product:{...product,id:'another-1234',name:'Otro'}}));assert.equal(duplicate.ok,false);
 assert.equal(apply(op('operation-edit','edit',{product:{...product,name:'Agua fría',quantity:999}})).ok,true);
 assert.equal(snapshot().products[0].name,'Agua fría');assert.equal(snapshot().products[0].quantity,4);
});

test('HTTP exige clave y comparte cambios entre clientes', async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 try {
  const base=`http://127.0.0.1:${server.address().port}`;
  assert.equal((await fetch(base+'/api/snapshot')).status,401);
  const headers={'Content-Type':'application/json','X-Mariliana-Key':'test-secret'};
  const a=await fetch(base+'/api/sync',{method:'POST',headers,body:JSON.stringify({operations:[op('http-sale-123','adjust',{productId:product.id,delta:-1})]})});
  assert.equal((await a.json()).results[0].ok,true);
  const b=await fetch(base+'/api/snapshot',{headers});
  assert.equal((await b.json()).products[0].quantity,3);
 } finally { await new Promise(resolve=>server.close(resolve)); }
});
