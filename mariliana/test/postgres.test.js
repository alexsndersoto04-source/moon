'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {Pool} = require('pg');
const {createPostgresStore} = require('../postgres-store');
const url=process.env.TEST_DATABASE_URL;
test('PostgreSQL: escrituras concurrentes, reintentos y borrado de historial', {skip:!url}, async()=>{
 if(new URL(url).pathname!=='/mariliana_test') throw Error('La prueba necesita una base desechable llamada mariliana_test.');
 const admin=new Pool({connectionString:url});
 const store=await createPostgresStore(url);
 try {
  await admin.query('TRUNCATE TABLE history, receipts, products');
  const op=(id,type,rest={})=>({id,type,at:'2026-01-02T12:00:00.000Z',actor:'Ana',...rest});
  const product={id:'producto-1234',name:'Refresco',barcode:'123456',quantity:7,minimum:1};
  assert.equal((await store.apply(op('create-product-123','create',{product}))).ok,true);
  const repeated=op('same-sale-123','adjust',{productId:product.id,delta:-1});
  const retries=await Promise.all(Array.from({length:12},()=>store.apply(repeated)));
  assert(retries.every(r=>r.ok));assert.equal((await store.snapshot()).products[0].quantity,6);
  const sales=await Promise.all(Array.from({length:10},(_,i)=>store.apply(op(`unique-sale-${i}`,'adjust',{productId:product.id,delta:-1}))));
  assert.equal(sales.filter(r=>r.ok).length,6);assert.equal(sales.filter(r=>!r.ok).length,4);
  let snap=await store.snapshot();assert.equal(snap.products[0].quantity,0);assert.equal(snap.history.filter(h=>h.delta<0).length,7);
  assert.equal((await store.apply(op('clear-day-123','clearHistory',{day:'2026-01-02',start:'2026-01-02T00:00:00.000Z',end:'2026-01-03T00:00:00.000Z'}))).ok,true);
  snap=await store.snapshot();assert.equal(snap.products[0].quantity,0);assert.equal(snap.history.length,1);
  assert.equal((await store.apply(repeated)).ok,true);assert.equal((await store.snapshot()).products[0].quantity,0);
  assert.equal((await store.apply(op('duplicate-product-123','create',{product:{...product,id:'producto-2222'}}))).ok,false);
  assert.equal((await store.apply(op('edit-product-123','edit',{product:{...product,name:'Refresco frío',quantity:100}}))).ok,true);
  snap=await store.snapshot();assert.equal(snap.products[0].quantity,0);assert.equal(snap.products[0].name,'Refresco frío');
 } finally { await store.close(); await admin.end(); }
});
