'use strict';
const { Pool } = require('pg');

// One transaction-scoped lock serializes the small store's writes across all instances.
// It also makes retries of the same operation safe, even through Neon poolers.
const LOCK_ID = 748201423;
const clean = (s, max = 120) => String(s ?? '').trim().slice(0, max);
const integer = (v, max = 1000000) => Number.isSafeInteger(v) && v >= 0 && v <= max;
const validDate = s => /^\d{4}-\d{2}-\d{2}$/.test(s);
const operationTime = op => { const time = Date.parse(op.at); return Number.isFinite(time) ? new Date(time).toISOString() : new Date().toISOString(); };
class ValidationError extends Error {}
const invalid = message => { throw new ValidationError(message); };

async function createPostgresStore(connectionString) {
  const pool = new Pool({ connectionString, max: 5, idleTimeoutMillis: 30000, connectionTimeoutMillis: 15000 });
  pool.on('error', () => console.error('Se perdió una conexión inactiva de PostgreSQL.'));
  const init = await pool.connect();
  try {
    await init.query('BEGIN');
    await init.query('SELECT pg_advisory_xact_lock($1)', [LOCK_ID]);
    await init.query(`
      CREATE TABLE IF NOT EXISTS products (id TEXT PRIMARY KEY, name TEXT NOT NULL, barcode TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', material TEXT NOT NULL DEFAULT '', unit TEXT NOT NULL DEFAULT 'unidad', quantity INTEGER NOT NULL CHECK(quantity >= 0), minimum INTEGER NOT NULL DEFAULT 0, expiry TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', createdat TEXT NOT NULL);
      CREATE UNIQUE INDEX IF NOT EXISTS barcode_unique ON products(barcode) WHERE barcode <> '';
      CREATE TABLE IF NOT EXISTS history (id TEXT PRIMARY KEY, productid TEXT NOT NULL, name TEXT NOT NULL, delta INTEGER NOT NULL, reason TEXT NOT NULL, date TEXT NOT NULL, actor TEXT NOT NULL DEFAULT '');
      CREATE INDEX IF NOT EXISTS history_date_idx ON history(date DESC);
      CREATE TABLE IF NOT EXISTS receipts (id TEXT PRIMARY KEY, result TEXT NOT NULL);
    `);
    await init.query('COMMIT');
  } catch (error) {
    await init.query('ROLLBACK').catch(() => {});
    init.release();
    await pool.end().catch(() => {});
    throw error;
  }
  init.release();

  async function snapshot() {
    const client = await pool.connect();
    try {
      await client.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
      const products = await client.query('SELECT id,name,barcode,category,material,unit,quantity,minimum,expiry,notes,createdat AS "createdAt" FROM products ORDER BY lower(name), id');
      const history = await client.query('SELECT id,productid AS "productId",name,delta,reason,date,actor FROM history ORDER BY date DESC, id DESC LIMIT 10000');
      await client.query('COMMIT');
      return { products: products.rows, history: history.rows };
    } catch (error) { await client.query('ROLLBACK').catch(() => {}); throw error; }
    finally { client.release(); }
  }

  async function apply(op) {
    if (!op || typeof op !== 'object' || !/^[\w-]{8,100}$/.test(op.id || '')) return {id:op?.id, ok:false, error:'Operación inválida.'};
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      await client.query('SELECT pg_advisory_xact_lock($1)', [LOCK_ID]);
      const existing = await client.query('SELECT result FROM receipts WHERE id=$1', [op.id]);
      if (existing.rows.length) { await client.query('COMMIT'); return JSON.parse(existing.rows[0].result); }
      await client.query('SAVEPOINT work');
      let result;
      try {
        if (op.type === 'create' || op.type === 'edit') {
          const p = op.product || {};
          const id = clean(p.id, 100), name = clean(p.name), barcode = clean(p.barcode, 100);
          if (!/^[\w-]{8,100}$/.test(id) || !name || !integer(p.minimum) || (p.expiry && !validDate(p.expiry))) invalid('Revisa el nombre, la cantidad mínima o la fecha.');
          const fields = [name, barcode, clean(p.category), clean(p.material), clean(p.unit) || 'unidad', p.minimum, clean(p.expiry, 10), clean(p.notes, 500)];
          if (op.type === 'create') {
            if (!integer(p.quantity)) invalid('Cantidad inicial inválida.');
            await client.query('INSERT INTO products (id,name,barcode,category,material,unit,minimum,expiry,notes,quantity,createdat) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)', [id, ...fields, p.quantity, new Date().toISOString()]);
            if (p.quantity) await client.query('INSERT INTO history VALUES ($1,$2,$3,$4,$5,$6,$7)', [op.id,id,name,p.quantity,'Inventario inicial',operationTime(op),clean(op.actor)]);
          } else {
            const updated = await client.query('UPDATE products SET name=$1,barcode=$2,category=$3,material=$4,unit=$5,minimum=$6,expiry=$7,notes=$8 WHERE id=$9', [...fields,id]);
            if (!updated.rowCount) invalid('El producto ya no existe.');
          }
        } else if (op.type === 'adjust') {
          if (!Number.isSafeInteger(op.delta) || op.delta === 0 || Math.abs(op.delta) > 1000000) invalid('Cantidad inválida.');
          const found = await client.query('SELECT id,name,quantity FROM products WHERE id=$1', [clean(op.productId,100)]);
          if (!found.rowCount) invalid('El producto ya no existe.');
          const p=found.rows[0];
          if (p.quantity + op.delta < 0) invalid(`No hay suficientes unidades de ${p.name}; quedan ${p.quantity}.`);
          if (p.quantity + op.delta > 2147483647) invalid('La cantidad supera el límite permitido.');
          await client.query('UPDATE products SET quantity=quantity+$1 WHERE id=$2', [op.delta,p.id]);
          await client.query('INSERT INTO history VALUES ($1,$2,$3,$4,$5,$6,$7)', [op.id,p.id,p.name,op.delta,clean(op.reason,100)||(op.delta<0?'Salida':'Entrada'),operationTime(op),clean(op.actor)]);
        } else if (op.type === 'clearHistory') {
          if (!validDate(op.day)) invalid('Fecha inválida.');
          const start=Date.parse(op.start),end=Date.parse(op.end);
          if (!Number.isFinite(start)||!Number.isFinite(end)||end<=start||end-start>90000000) invalid('Intervalo de fecha inválido.');
          await client.query('DELETE FROM history WHERE date >= $1 AND date < $2 AND delta < 0', [new Date(start).toISOString(),new Date(end).toISOString()]);
        } else invalid('Operación desconocida.');
        result={id:op.id,ok:true};
      } catch (error) {
        if (!(error instanceof ValidationError) && error.code !== '23505') throw error;
        await client.query('ROLLBACK TO SAVEPOINT work');
        result={id:op.id,ok:false,error:error.code==='23505'?'El producto o código ya existe.':error.message};
      }
      await client.query('INSERT INTO receipts (id,result) VALUES ($1,$2)', [op.id,JSON.stringify(result)]);
      await client.query('COMMIT');
      return result;
    } catch (error) { await client.query('ROLLBACK').catch(() => {}); throw error; }
    finally { client.release(); }
  }
  return { apply, snapshot, close: () => pool.end() };
}
module.exports = { createPostgresStore };
